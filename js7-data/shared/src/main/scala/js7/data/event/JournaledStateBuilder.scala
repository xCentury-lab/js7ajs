package js7.data.event

import js7.base.problem.Checked._
import js7.base.problem.Problem
import js7.base.time.ScalaTime._
import js7.base.time.{Stopwatch, Timestamp}
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.SetOnce
import js7.base.utils.StackTraces._
import js7.data.cluster.ClusterState
import JournaledStateBuilder._
import js7.base.time.Stopwatch.{itemsPerSecondString, perSecondStringOnly}
import js7.base.utils.ByteUnits.toKBGB
import js7.data.event.SnapshotMeta.SnapshotEventId
import monix.eval.Task
import scala.concurrent.duration.Deadline.now
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

trait JournaledStateBuilder[S <: JournaledState[S]]
{
  private val since = now
  private var _snapshotCount = 0L
  private var _firstEventId = EventId.BeforeFirst
  private var _eventId = EventId.BeforeFirst
  private var _eventCount = 0L
  private val _journalHeader = SetOnce[JournalHeader]
  private val getStatePromise = Promise[Task[S]]()

  def initializeState(journalHeader: Option[JournalHeader], eventId: EventId, totalEventCount: Long, state: S): Unit = {
    journalHeader foreach { _journalHeader := _ }
    _eventId = eventId
    _eventCount = totalEventCount - journalHeader.fold(0L)(_.totalEventCount)
    onInitializeState(state)
    onStateIsAvailable()
  }

  protected def onInitializeState(state: S): Unit

  protected def onAddSnapshotObject: PartialFunction[Any, Unit]

  protected def onOnAllSnapshotsAdded(): Unit

  protected def onAddEvent: PartialFunction[Stamped[KeyedEvent[Event]], Unit]

  def state: S

  def journalState: JournalState

  def clusterState: ClusterState

  def addSnapshotObject(obj: Any): Unit =
    obj match {
      case journalHeader: JournalHeader =>
        this._journalHeader := journalHeader
        require(_firstEventId == EventId.BeforeFirst && _eventId == EventId.BeforeFirst, "EventId mismatch in snapshot")
        _firstEventId = journalHeader.eventId
        _eventId = journalHeader.eventId

      case SnapshotEventId(eventId) =>
        require(eventId == _firstEventId && eventId == _eventId ||
                _firstEventId == EventId.BeforeFirst && _eventId == EventId.BeforeFirst,
          "EventId mismatch in snapshot")
        _firstEventId = eventId
        _eventId = eventId

      case _ =>
        _snapshotCount += 1
        onAddSnapshotObject.applyOrElse(obj, onSnapshotObjectNoApplicable)
    }

  protected def onSnapshotObjectNoApplicable(obj: Any): Unit =
    throw SnapshotObjectNotApplicableProblem(obj).throwable.appendCurrentStackTrace

  def onAllSnapshotsAdded(): Unit = {
    onOnAllSnapshotsAdded()
    onStateIsAvailable()
  }

  private def onStateIsAvailable(): Unit =
    getStatePromise.success(Task {
      synchronized {
        state
      }
    })

  final def addEvent(stamped: Stamped[KeyedEvent[Event]]) =
    synchronized {  // synchronize with asynchronous execution of synchronizedStateFuture
      if (stamped.eventId <= _eventId) {
        throw new IllegalArgumentException(s"EventId out of order: ${EventId.toString(_eventId)} >= ${stamped.toString.truncateWithEllipsis(100)}")
      }
      try onAddEvent(stamped)
      catch { case NonFatal(t) =>
        throw new RuntimeException(s"Event failed: $stamped", t)
      }
      _eventCount += 1
      if (_firstEventId == EventId.BeforeFirst) {
        _firstEventId = stamped.eventId
      }
      _eventId = stamped.eventId
    }

  def logStatistics(byteCount: Option[Long]): Unit = {
    val elapsed = since.elapsed
    if (elapsed >= 1.s) {
      scribe.debug(
        itemsPerSecondString(elapsed, _snapshotCount + eventCount, "snapshots+events") +
        byteCount.fold("")(byteCount =>
          ", " + perSecondStringOnly(elapsed, byteCount / 1_000_000, "MB", gap = false) +
          " " + toKBGB(byteCount)
        ) + " read")
    }
    if (snapshotCount + eventCount > 0) {
      val age = (Timestamp.now - EventId.toTimestamp(eventId)).withMillis(0).pretty
      scribe.info(s"Recovered last EventId is ${EventId.toString(eventId)}, emitted $age ago " +
        s"($snapshotCount snapshot objects and $eventCount events" +
        (byteCount.fold("")(o => ", " + toKBGB(o))) +
        " read" +
        ((elapsed >= 10.s) ?? s" in ${elapsed.pretty}") +
        ")")
    }
  }

  def synchronizedStateFuture: Future[Task[S]] =
    getStatePromise.future

  /** Journal file's JournalHeader. */
  final def fileJournalHeader = _journalHeader.toOption

  /** Calculated next JournalHeader. */
  final def recoveredJournalHeader: Option[JournalHeader] =
    _journalHeader.map(_.copy(
      eventId = eventId,
      totalEventCount = totalEventCount,
      totalRunningTime = _journalHeader.fold(Duration.Zero) { header =>
        val lastJournalDuration = EventId.toTimestamp(_eventId) - EventId.toTimestamp(_firstEventId)
        header.totalRunningTime + lastJournalDuration roundUpToNext 1.ms
      },
      timestamp = lastEventIdTimestamp))

  final def eventId = _eventId

  protected def updateEventId(o: EventId) = {
    assert(_eventId < o)
    _eventId = o
  }

  final def snapshotCount = _snapshotCount

  final def eventCount = _eventCount

  final def totalEventCount = _journalHeader.fold(0L)(_.totalEventCount) + _eventCount

  private def lastEventIdTimestamp: Timestamp =
    if (eventId == EventId.BeforeFirst) Timestamp.now
    else EventId.toTimestamp(eventId)

}

object JournaledStateBuilder
{
  abstract class Simple[S <: JournaledState[S]](S: JournaledState.Companion[S]) extends JournaledStateBuilder[S]
  {
    private var _state = S.empty

    protected def onInitializeState(state: S) =
      _state = state

    override def addSnapshotObject(obj: Any) = obj match {
      case o: JournalState =>
        _state = _state.withStandards(_state.standards.copy(
          journalState = o))

      case o: ClusterState =>
        _state = _state.withStandards(_state.standards.copy(
          clusterState = o))

      case o => super.addSnapshotObject(o)
    }

    protected def onOnAllSnapshotsAdded() = {}

    protected def onAddEvent = {
      case stamped =>
        _state = _state.applyEvent(stamped.value).orThrow
        updateEventId(stamped.eventId)
    }

    def state = _state withEventId eventId

    protected def updateState(state: S) = _state = state

    def journalState = _state.journalState

    def clusterState = _state.clusterState
  }

  private case class SnapshotObjectNotApplicableProblem(obj: Any) extends Problem.Coded {
    def arguments = Map("object" -> obj.getClass.scalaName)
  }
}
