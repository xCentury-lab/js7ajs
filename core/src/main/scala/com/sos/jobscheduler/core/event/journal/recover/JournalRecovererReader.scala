package com.sos.jobscheduler.core.event.journal.recover

import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.time.Timestamp.now
import com.sos.jobscheduler.base.utils.ScalaUtils.RichEither
import com.sos.jobscheduler.common.scalautil.Closers.implicits.RichClosersAutoCloseable
import com.sos.jobscheduler.common.scalautil.{HasCloser, Logger}
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.Stopwatch
import com.sos.jobscheduler.common.utils.ByteUnits.toMB
import com.sos.jobscheduler.core.common.jsonseq.{InputStreamJsonSeqReader, PositionAnd}
import com.sos.jobscheduler.core.event.journal.data.JournalHeaders.{EventsHeader, SnapshotsHeader}
import com.sos.jobscheduler.core.event.journal.data.{JournalHeader, JournalMeta, SnapshotMeta}
import com.sos.jobscheduler.core.event.journal.recover.JournalRecovererReader._
import com.sos.jobscheduler.data.event.{Event, EventId, KeyedEvent, Stamped}
import io.circe.Json
import java.nio.file.{Files, Path}
import scala.annotation.tailrec

/**
  * @author Joacim Zschimmer
  */
private[recover] final class JournalRecovererReader[E <: Event](journalMeta: JournalMeta[E], journalFile: Path) extends HasCloser
{
  import journalMeta.{eventJsonCodec, snapshotJsonCodec}

  private val logger = Logger.withPrefix[JournalRecovererReader[_]](journalFile.getFileName.toString)
  private val stopwatch = new Stopwatch
  private val jsonReader = InputStreamJsonSeqReader.open(journalFile).closeWithCloser
  private var snapshotSeparatorRead = false
  private var eventSeparatorRead = false
  private var _lastReadEventId = EventId.BeforeFirst
  private var _completelyRead = false
  private var snapshotCount = 0
  private var allSnapshotsRecovered = false
  private var eventCount = 0

  logger.info(s"Recovering from journal journalFile '${journalFile.getFileName}' (${toMB(Files.size(journalFile))})")

  JournalHeader.checkHeader(
    readJson() map (_.value) getOrElse sys.error(s"Journal '$journalFile' is empty"),
    journalFile)

  private[journal] def recoverNext(): Option[Recovered[E]] =
    readJson() match {
      case Left(0) ⇒   // End of file
        if (!allSnapshotsRecovered) {
          allSnapshotsRecovered = true
          Some(AllSnapshotsRecovered)
        } else {
          _completelyRead = true
          None
        }

      case Left(1) ⇒
        allSnapshotsRecovered = true
        Some(AllSnapshotsRecovered)

      case Right(positionAndJson) ⇒
        if (!snapshotSeparatorRead)
          sys.error(s"Unexpected JSON value in '$journalFile': $positionAndJson")
        else if (!eventSeparatorRead) {
          snapshotCount += 1
          Some(RecoveredSnapshot(snapshotJsonCodec.decodeJson(positionAndJson.value).orThrow))
        } else {
          eventCount += 1
          val stampedEvent = positionAndJson.value.as[Stamped[Json]].orThrow
          if (stampedEvent.eventId <= _lastReadEventId)
            sys.error(s"Journal is corrupt, EventIds are out of order: ${EventId.toString(stampedEvent.eventId)} follows ${EventId.toString(_lastReadEventId)}")
          _lastReadEventId = stampedEvent.eventId
          Some(RecoveredEvent(stampedEvent.copy(value = stampedEvent.value.as[KeyedEvent[E]].orThrow)))
        }
    }

  @tailrec
  private def readJson(): Either[Int, PositionAnd[Json]] =
    jsonReader.read() match {
      case None ⇒
        Left(0)

      case Some(positionAndJson) ⇒
        val json = positionAndJson.value
        if (json.isObject)
          Right(positionAndJson)
        else if (!snapshotSeparatorRead && json == SnapshotsHeader) {
          snapshotSeparatorRead = true
          val posAndJson = jsonReader.read() getOrElse sys.error("Journal file is truncated")  // Tolerate this ???
          val snapshotMeta = posAndJson.value.as[SnapshotMeta].toChecked.mapProblem(_ wrapProblemWith "Error while reading SnapshotMeta").orThrow
          _lastReadEventId = snapshotMeta.eventId
          readJson()
        } else if (!eventSeparatorRead && json == EventsHeader) {
          eventSeparatorRead = true
          Left(1)
        } else
          sys.error(s"Unexpected JSON value in '$journalFile': $positionAndJson")
    }

  def logStatistics(): Unit = {
    if (stopwatch.duration >= 1.s) {
      logger.debug(stopwatch.itemsPerSecondString(snapshotCount + eventCount, "snapshots+events") + " read")
    }
    if (eventCount > 0) {
      val time = EventId.toDateTimeString(_lastReadEventId)
      val age = (now - EventId.toTimestamp(_lastReadEventId)).withNanos(0).pretty
      logger.info(s"Recovered last EventId is ${_lastReadEventId} of $time $age ago " +
        s"($snapshotCount snapshot elements and $eventCount events read in ${stopwatch.duration.pretty})")
    }
  }

  def isCompletelyRead = _completelyRead

  def lastReadEventId = _lastReadEventId
}

private[recover] object JournalRecovererReader {
  sealed trait Recovered[+E <: Event]
  final case class RecoveredSnapshot private(snapshot: Any) extends Recovered[Nothing]
  final case object AllSnapshotsRecovered extends Recovered[Nothing]
  final case class RecoveredEvent[E <: Event] private[JournalRecovererReader](stamped: Stamped[KeyedEvent[E]]) extends Recovered[E]
}
