package com.sos.jobscheduler.core.event.journal.watch

import com.sos.jobscheduler.base.utils.CloseableIterator
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.core.common.jsonseq.PositionAnd
import com.sos.jobscheduler.core.event.journal.data.JournalMeta
import com.sos.jobscheduler.core.event.journal.files.JournalFiles.JournalMetaOps
import com.sos.jobscheduler.data.event.{Event, EventId}
import com.typesafe.config.Config
import scala.concurrent.ExecutionContext

/**
  * @author Joacim Zschimmer
  */
private[watch] final class CurrentEventReader[E <: Event](
  protected val journalMeta: JournalMeta[E],
  /** Length and after-EventId of initialized and empty journal. */
  flushedLengthAndEventId: PositionAnd[EventId],
  protected val config: Config)
  (implicit
    protected val executionContext: ExecutionContext,
    protected val timerService: TimerService)
extends EventReader[E]
{
  val tornEventId = flushedLengthAndEventId.value
  protected def isHistoric = false
  final val journalFile = journalMeta.file(after = tornEventId)
  protected def tornPosition = flushedLengthAndEventId.position
  protected var flushedLength = flushedLengthAndEventId.position  // Initially, the file contains no events

  private var _lastEventId = flushedLengthAndEventId.value

  private[journal] def onEventsAdded(flushedPositionAndEventId: PositionAnd[EventId], n: Int): Unit = {
    val PositionAnd(flushedPosition, eventId) = flushedPositionAndEventId
    if (flushedPosition < flushedLength)
      throw new IllegalArgumentException(s"CurrentEventReader: Added files position $flushedPosition ${EventId.toString(eventId)} < flushedLength $flushedLength")
    eventIdToPositionIndex.addAfter(eventId = flushedPositionAndEventId.value, position = flushedPositionAndEventId.position, n = n)
    _lastEventId = eventId
    flushedLength = flushedPosition
  }

  protected def reverseEventsAfter(after: EventId) =
    CloseableIterator.empty  // Not implemented

  def lastEventId = _lastEventId

  override def toString = s"CurrentEventReader(${journalFile.getFileName})"
}
