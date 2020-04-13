package com.sos.jobscheduler.core.event.journal.watch

import akka.util.ByteString
import com.sos.jobscheduler.base.problem.Checked.{CheckedOption, Ops}
import com.sos.jobscheduler.base.problem.{Checked, Problem}
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.base.utils.Collections.implicits._
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import com.sos.jobscheduler.base.utils.{CloseableIterator, SetOnce}
import com.sos.jobscheduler.common.event.{PositionAnd, RealEventWatch}
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.core.event.journal.data.JournalMeta
import com.sos.jobscheduler.core.event.journal.files.JournalFiles.listJournalFiles
import com.sos.jobscheduler.core.event.journal.watch.JournalEventWatch._
import com.sos.jobscheduler.data.event.{Event, EventId, JournalId, KeyedEvent, Stamped}
import com.sos.jobscheduler.data.problems.UnknownEventIdProblem
import com.typesafe.config.{Config, ConfigFactory}
import java.io.IOException
import java.nio.file.Files.delete
import java.nio.file.Path
import monix.execution.atomic.AtomicAny
import monix.reactive.Observable
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

/**
  * Watches a complete journal consisting of n `JournalFile`.
  * The last one (with highest after-EventId) is the currently written file while the others are historic.
  * @author Joacim Zschimmer
  */
final class JournalEventWatch(val journalMeta: JournalMeta, config: Config)
extends AutoCloseable
with RealEventWatch
with JournalingObserver
{
  private val keepOpenCount = config.getInt("jobscheduler.journal.watch.keep-open")
  // Read journal file names from directory while constructing
  private val journalId = SetOnce[JournalId]
  @volatile
  private var afterEventIdToHistoric: SortedMap[EventId, HistoricJournalFile] =
    SortedMap.empty[EventId, HistoricJournalFile] ++
      listJournalFiles(journalMeta.fileBase)
      .map(o => new HistoricJournalFile(o.afterEventId, o.file))
      .toKeyedMap(_.afterEventId)
  private val startedPromise = Promise[this.type]()
  @volatile
  private var currentEventReaderOption: Option[CurrentEventReader] = None
  @volatile
  private var nextEventReaderPromise: Option[(EventId, Promise[CurrentEventReader])] = None

  def close() = {
    afterEventIdToHistoric.values foreach (_.close())
    currentEventReaderOption foreach (_.close())
    for (o <- nextEventReaderPromise)
      o._2.tryFailure(new IllegalStateException("JournalEventWatch has been closed") with NoStackTrace)
  }

  override def whenStarted = startedPromise.future

  /*protected[journal]*/ def onJournalingStarted(
    file: Path,
    expectedJournalId: JournalId,
    tornLengthAndEventId: PositionAnd[EventId],
    flushedLengthAndEventId: PositionAnd[EventId]): Unit
  = {
    // Always tornLengthAndEventId == flushedLengthAndEventId ???
    logger.debug(s"onJournalingStarted ${file.getFileName}, torn length=${tornLengthAndEventId.position}, " +
      s"torn eventId=${tornLengthAndEventId.value}, flushed length=${flushedLengthAndEventId.position}, " +
      s"flushed eventId=${flushedLengthAndEventId.value}")
    journalId.toOption match {
      case None => journalId := expectedJournalId
      case Some(o) => require(expectedJournalId == o, s"JournalId $journalId does not match expected $expectedJournalId")
    }
    synchronized {
      val after = flushedLengthAndEventId.value
      if (after < lastEventId) throw new IllegalArgumentException(s"Invalid onJournalingStarted(after=$after), must be ≥ $lastEventId")
      for (current <- currentEventReaderOption) {
        if (file == current.journalFile) sys.error(s"onJournalingStarted: file == current.journalFile == ${file.getFileName}")
        if (current.lastEventId != tornLengthAndEventId.value)
          throw new IllegalArgumentException(s"onJournalingStarted(${tornLengthAndEventId.value}) does not match lastEventId=${current.lastEventId}")
        for (o <- afterEventIdToHistoric.get(current.tornEventId)) {
          o.closeAfterUse()  // In case last journal file had no events (and `after` remains), we exchange it
        }
        afterEventIdToHistoric += current.tornEventId -> new HistoricJournalFile(
          afterEventId = current.tornEventId,
          current.journalFile,
          Some(current)/*Reuse built-up JournalIndex*/)
      }
      val currentEventReader = new CurrentEventReader(journalMeta, Some(expectedJournalId),
        tornLengthAndEventId, flushedLengthAndEventId, config)
      currentEventReaderOption = Some(currentEventReader)
      for ((eventId, promise) <- nextEventReaderPromise if eventId == tornLengthAndEventId.value)
        promise.success(currentEventReader)
    }
    onFileWritten(flushedLengthAndEventId.position)
    onEventsCommitted(flushedLengthAndEventId.value)  // Notify about already written events
    startedPromise.trySuccess(this)
    evictUnusedEventReaders()
  }

  def onJournalingEnded(fileLength: Long) =
    // TODO Delay until no FailedOver event may be written?
    //  This would be after the next journal file has been written with an acknowledged event
    //  - SnapshotTaken is not being acknowledged!
    //  Können wir auf onJournalingEnded verzichten zu Gunsten von onJournalingStarted ?
    for (o <- currentEventReaderOption) {
      logger.debug(s"onJournalingEnded ${o.journalFile.getFileName} fileLength=$fileLength")
      nextEventReaderPromise = Some(o.lastEventId -> Promise[CurrentEventReader]())
      o.onJournalingEnded(fileLength)
    }

  def checkEventId(eventId: EventId): Checked[Unit] =
    eventsAfter(eventId) match {
      case Some(iterator) =>
        iterator.close()
        Checked.unit
      case None =>
        Left(UnknownEventIdProblem(requiredEventId = eventId))
    }

  def releaseEvents(untilEventId: EventId): Unit = {
    logger.debug(s"releaseEvents($untilEventId)")
    val keepFileAfter = currentEventReaderOption match {
      case Some(current) if current.tornEventId <= untilEventId =>
        current.tornEventId
      case _ =>
        historicJournalFileAfter(untilEventId).fold(EventId.BeforeFirst)(_.afterEventId)  // Delete only journal files before the file containing `after`
    }
    for (historic <- afterEventIdToHistoric.values if historic.afterEventId < keepFileAfter) {
      logger.info(s"Deleting obsolete journal file '$historic'")
      historic.close()
      try {
        delete(historic.file)
        afterEventIdToHistoric -= historic.afterEventId
      } catch {
        case e: IOException => logger.warn(s"Cannot delete obsolete journal file '$historic': ${e.toStringWithCauses}")
      }
    }
  }

  def onFileWritten(flushedPosition: Long): Unit =
    for (o <- currentEventReaderOption) {
      o.onFileWritten(flushedPosition)
    }

  def onEventsCommitted(positionAndEventId: PositionAnd[EventId], n: Int): Unit = {
    currentEventReader.onEventsCommitted(positionAndEventId, n = n)
    onEventsCommitted(positionAndEventId.value)
  }

  def snapshotObjectsFor(after: EventId) =
    currentEventReaderOption match {
      case Some(current) if current.tornEventId <= after =>
        Some(current.tornEventId -> current.snapshotObjects)
      case _ =>
        historicJournalFileAfter(after)
          .map { historyJournalFile =>
            logger.debug(s"Reading snapshot from journal file '$historyJournalFile'")
            historyJournalFile.afterEventId -> historyJournalFile.eventReader.snapshotObjects
          }
    }

  /**
    * @return `Task(None)` torn, `after` < `tornEventId`
    *         `Task(Some(Iterator.empty))` if no events are available for now
    */
  def eventsAfter(after: EventId): Option[CloseableIterator[Stamped[KeyedEvent[Event]]]] = {
    val result = currentEventReaderOption match {
      case Some(current) if current.tornEventId <= after =>
        current.eventsAfter(after)
      case _ =>
        historicEventsAfter(after)
    }
    evictUnusedEventReaders()
    result
  }

  override def toString = s"JournalEventWatch(${journalMeta.name})"

  private def historicEventsAfter(after: EventId): Option[CloseableIterator[Stamped[KeyedEvent[Event]]]] =
    historicJournalFileAfter(after) flatMap { historicJournalFile =>
      var last = after
      historicJournalFile.eventReader.eventsAfter(after) map { events =>
        events.tapEach { stamped =>
          last = stamped.eventId
        } ++  // ++ is lazy, so last contains last read eventId
          (if (last == after)  // Nothing read
            CloseableIterator.empty
          else {  // Continue with next HistoricEventReader or CurrentEventReader
            logger.debug(s"Continue with next HistoricEventReader or CurrentEventReader, last=$last after=$after")
            assert(last > after, s"last=$last ≤ after=$after ?")
            eventsAfter(last) getOrElse CloseableIterator.empty  // Should never be torn here because last > after
          })
      }
    }

  /** Close unused HistoricEventReader. **/
  private def evictUnusedEventReaders(): Unit =
    afterEventIdToHistoric.values
      .filter(_.isEvictable)
      .toVector.sortBy(_.lastUsedAt)
      .dropRight(keepOpenCount)
      .foreach(_.evictEventReader())

  private def historicJournalFileAfter(after: EventId): Option[HistoricJournalFile] =
    afterEventIdToHistoric.values.toVector.reverseIterator find (_.afterEventId <= after)

  def fileEventIds: Seq[EventId] =
    synchronized {
      afterEventIdToHistoric.keys.toSeq.sorted ++ currentEventReaderOption.map(_.tornEventId)
    }

  def observeFile(fileEventId: Option[EventId], position: Option[Long], timeout: FiniteDuration, markEOF: Boolean, onlyLastOfChunk: Boolean)
  : Checked[Observable[PositionAnd[ByteString]]] =
    checkedCurrentEventReader  // TODO Implement for historic journal files, too
      .flatMap(current =>
        // Use defaults for manual request of the current journal file stream, just to show something
        observeFile(
          fileEventId = fileEventId getOrElse current.tornEventId,
          position = position getOrElse current.committedLength,
          timeout,
          markEOF = markEOF,
          onlyLastOfChunk = onlyLastOfChunk))

  private def observeFile(fileEventId: EventId, position: Long, timeout: FiniteDuration, markEOF: Boolean, onlyLastOfChunk: Boolean)
  : Checked[Observable[PositionAnd[ByteString]]] =
    (nextEventReaderPromise match {
      case Some((`fileEventId`, promise)) =>
        logger.debug(s"observeFile($fileEventId): waiting for this new journal file")
        Right(Observable.fromFuture(promise.future))
      case _ =>
        currentEventReaderOption
          .filter(_.tornEventId == fileEventId)
          .orElse(afterEventIdToHistoric.get(fileEventId).map(_.eventReader))
          .toRight(Problem(s"Unknown journal file=$fileEventId"))
          .map(Observable.pure)
    }).map(_.flatMap(_
      .observeFile(position, timeout, markEOF = markEOF, onlyLastOfChunk = onlyLastOfChunk)))

  private def lastEventId =
    currentEventReaderOption match {
      case Some(o) => o.lastEventId
      case None if afterEventIdToHistoric.nonEmpty => afterEventIdToHistoric.keys.max
      case None => EventId.BeforeFirst
    }

  private final class HistoricJournalFile(
    val afterEventId: EventId,
    val file: Path,
    initialEventReader: Option[EventReader] = None)
  {
    private val _eventReader = AtomicAny[EventReader](initialEventReader.orNull)

    def closeAfterUse(): Unit =
      for (r <- Option(_eventReader.get)) r.closeAfterUse()

    def close(): Unit =
      for (r <- Option(_eventReader.get)) r.close()

    @tailrec
    def eventReader: EventReader =
      _eventReader.get match {
        case null =>
          val r = new HistoricEventReader(journalMeta,
            Some(journalId.getOrElse(throw JournalIsNotYetReadyProblem(file).throwable)),
            tornEventId = afterEventId, file, config)
          if (_eventReader.compareAndSet(null, r)) {
            logger.debug(s"Using HistoricEventReader(${file.getFileName})")
            r
          } else {
            r.close()
            eventReader
          }
        case r => r
      }

    def evictEventReader(): Unit = {
      val reader = _eventReader.get
      if (reader != null) {
        if (!reader.isInUse && _eventReader.compareAndSet(reader, null)) {  // Race condition, may be become in-use before compareAndSet
          logger.debug(s"Evict HistoricEventReader(${file.getFileName}' lastUsedAt=${Timestamp.ofEpochMilli(reader.lastUsedAt)})")
          reader.closeAfterUse()
        }
      }
    }

    def lastUsedAt: Long =
      _eventReader.get match {
        case null => 0L
        case reader => reader.lastUsedAt
      }

    def isEvictable: Boolean = {
      val reader = _eventReader.get
      reader != null && !reader.isInUse
    }

    override def toString = file.getFileName.toString
  }

  private def currentEventReader = checkedCurrentEventReader.orThrow

  private def checkedCurrentEventReader: Checked[CurrentEventReader] =
    currentEventReaderOption.toChecked(JournalIsNotYetReadyProblem(journalMeta.fileBase))
}

object JournalEventWatch
{
  private val logger = Logger(getClass)

  private case class JournalIsNotYetReadyProblem(file: Path) extends Problem.Coded {
    def arguments = Map("file" -> file.getFileName.toString)
  }

  val TestConfig = ConfigFactory.parseString("""
     |jobscheduler.journal.watch.keep-open = 2
     |jobscheduler.journal.watch.index-size = 100
     |jobscheduler.journal.watch.index-factor = 10
     |jobscheduler.journal.remove-obsolete-files = true
     |jobscheduler.journal.users-allowed-to-release-events = []
    """.stripMargin)
}
