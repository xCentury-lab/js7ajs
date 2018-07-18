package com.sos.jobscheduler.core.event.journal

import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.utils.CloseableIterator
import com.sos.jobscheduler.base.utils.Collections.implicits._
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import com.sos.jobscheduler.common.event.RealEventReader
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.core.common.jsonseq.PositionAnd
import com.sos.jobscheduler.core.event.journal.JournalEventReader._
import com.sos.jobscheduler.core.event.journal.JournalFiles.listJournalFiles
import com.sos.jobscheduler.data.event.{Event, EventId, KeyedEvent, Stamped}
import java.io.IOException
import java.nio.file.Files.delete
import java.nio.file.Path
import monix.eval.Task
import monix.execution.atomic.AtomicLong
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}

/**
  * @author Joacim Zschimmer
  */
final class JournalEventReader[E <: Event](journalMeta: JournalMeta[E])
  (implicit
    protected val executionContext: ExecutionContext,
    protected val timerService: TimerService)
extends AutoCloseable
with RealEventReader[E]
with WriterReaderAdapter
{
  // Read journal file names from directory while constructing
  @volatile
  private var afterEventIdToFile: Map[EventId, HistoricJournalFile] =
    listJournalFiles(journalMeta.fileBase) toKeyedMap (_.afterEventId)
  private val started = Promise[Completed]()
  @volatile
  private var currentJournalEventReaderOption: Option[CurrentJournalEventReader[E]] = None
  private val eventsAcceptedUntil = AtomicLong(EventId.BeforeFirst)

  def close() = {
    afterEventIdToFile.values foreach (_.close())
    currentJournalEventReaderOption foreach (_.close())
  }

  override def whenStarted: Task[this.type] =
    Task.deferFuture(started.future) map (_ ⇒ this)

  private def currentJournalEventReader =
    currentJournalEventReaderOption getOrElse (throw new IllegalStateException(s"$toString: Journal is not yet ready"))

  def onJournalingStarted(file: Path, flushedLengthAndEventId: PositionAnd[EventId]): Unit = {
    // TODO For properly working TakeSnapshot, remember current CurrentJournalEventReader as HistoricJournalEventReader
    // Wenn currentJournalEventReader erneuert wird, dann kann die Meldung des nächsten neuen Events verloren gehen,
    // wenn noch dem alten CurrentJournalEventReader gelauscht wird.
    // Also: Alten CurrentJournalEventReader schließen und Lauscher benachrichtigen.
    // Die bekommen dann einen leeren CloseableIterator und wiederholen den Aufruf, dann mit dem neuen CurrentJournalEventReader.
    // Siehe auch onEventsAdded.
    currentJournalEventReaderOption = Some(
      new CurrentJournalEventReader[E](journalMeta, flushedLengthAndEventId))
    onEventsAdded(eventId = flushedLengthAndEventId.value)  // Notify about historic events
    started.trySuccess(Completed)
  }

  def tornEventId =
    synchronized {
      if (afterEventIdToFile.nonEmpty)
        afterEventIdToFile.keys.min
      else
        currentJournalEventReader.tornEventId
    }

  @tailrec
  def onEventsAcceptedUntil(eventId: EventId): Unit = {
    val old = eventsAcceptedUntil()
    if (eventId < old)
      logger.warn(s"EventsAcceptedUntil with already accepted EventId $eventId < $old ?")  // Not expected to happen. No exception here!
    else
    if (eventId > old) {
      if (!eventsAcceptedUntil.compareAndSet(old, eventId))
        onEventsAcceptedUntil(eventId)  // Try again when concurrently called
      else
        deleteObsoleteArchives()
    }
  }

  private def deleteObsoleteArchives(): Unit = {
    val untilEventId = eventsAcceptedUntil()
    val keepAfter = currentJournalEventReader.tornEventId match {
      case current if untilEventId >= current ⇒ current
      case _ ⇒ journalFileAfter(untilEventId).fold(EventId.BeforeFirst)(_.afterEventId)  // Delete only journal files before the file containing untilEventId
    }
    for ((afterEventId, file) ← afterEventIdToFile if afterEventId < keepAfter) {
      try {
        logger.info(s"Deleting obsolete journal file '$file'")
        delete(file.file)
        afterEventIdToFile -= afterEventId
      } catch {
        case e: IOException ⇒ logger.error(s"Cannot delete file '$file': ${e.toStringWithCauses}")
      }
    }
  }

  private[journal] def onEventsAdded(flushedPositionAndEventId: PositionAnd[EventId]): Unit = {
    currentJournalEventReader.onEventsAdded(flushedPositionAndEventId)
    super.onEventsAdded(eventId = flushedPositionAndEventId.value)
  }

  /**
    * @return `Task(None)` if `after` < `tornEventId`
    *         `Task(Some(Iterator.empty))` if no events are available for now
    */
  def eventsAfter(after: EventId): Option[CloseableIterator[Stamped[KeyedEvent[E]]]] =
    currentJournalEventReader.eventsAfter(after) orElse ( // Torn
      journalFileAfter(after) map { journalFile ⇒
        var last = after
        journalFile.eventReader(journalMeta).eventsAfter(after).map { stamped ⇒
          last = stamped.eventId
          stamped
        } ++
          (if (last == after)
            CloseableIterator.empty
          else  // Continue with next HistoricJournalEventReader or CurrentJournalEventReader
            eventsAfter(last).getOrElse(CloseableIterator.empty/*Should never be torn here because last > after*/))
      }
    )

  protected def reverseEventsAfter(after: EventId) =
    CloseableIterator.empty  // Not implemented

  private def journalFileAfter(after: EventId): Option[HistoricJournalFile] =
    afterEventIdToFile.toVector.sortBy(_._1).reverseIterator find (_._1 <= after) map (_._2)
}

private[journal] object JournalEventReader {
  private val logger = Logger(getClass)
}
