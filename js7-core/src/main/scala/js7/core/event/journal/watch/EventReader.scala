package js7.core.event.journal.watch

import com.typesafe.config.Config
import java.nio.file.Path
import js7.base.monixutils.MonixBase.memoryLeakLimitedObservableTailRecM
import js7.base.time.Timestamp
import js7.base.utils.Assertions.assertThat
import js7.base.utils.AutoClosing.closeOnError
import js7.base.utils.CloseableIterator
import js7.base.utils.Collections.implicits.RichIterator
import js7.base.utils.ScalaUtils.syntax._
import js7.common.event.PositionAnd
import js7.common.scalautil.Logger
import js7.common.utils.UntilNoneIterator
import js7.core.common.jsonseq.InputStreamJsonSeqReader
import js7.core.event.journal.data.JournalMeta
import js7.core.event.journal.recover.JournalReader
import js7.core.problems.JsonSeqFileClosedProblem
import js7.data.event.JournalSeparators.EndOfJournalFileMarker
import js7.data.event.{Event, EventId, JournalId, KeyedEvent, Stamped}
import monix.eval.Task
import monix.execution.atomic.AtomicAny
import monix.reactive.Observable
import scala.concurrent.duration.Deadline.now
import scala.concurrent.duration.{Deadline, FiniteDuration}
import scodec.bits.ByteVector

/**
  * @author Joacim Zschimmer
  */
private[watch] trait EventReader
extends AutoCloseable
{
  /** `committedLength` does not grow if `isOwnJournalIndex`. */
  protected def journalMeta: JournalMeta
  protected def expectedJournalId: Option[JournalId]
  protected def isHistoric: Boolean
  protected def journalFile: Path
  protected def tornEventId: EventId
  protected def tornPosition: Long
  protected def isFlushedAfterPosition(position: Long): Boolean
  protected def committedLength: Long
  protected def isEOF(position: Long): Boolean
  protected def whenDataAvailableAfterPosition(position: Long, until: Deadline): Task[Boolean]
  /** Must be constant if `isHistoric`. */
  protected def config: Config

  private lazy val logger = Logger.withPrefix[this.type](journalFile.getFileName.toString)
  protected lazy val journalIndex = new JournalIndex(PositionAnd(tornPosition, tornEventId),
    size = config.getInt("js7.journal.watch.index-size"))
  private lazy val journalIndexFactor = config.getInt("js7.journal.watch.index-factor")
  private lazy val limitTailRecM = config.getInt("js7.monix.tailrecm-limit")
  protected final lazy val iteratorPool = new FileEventIteratorPool(journalMeta, expectedJournalId, journalFile, tornEventId, () => committedLength)
  @volatile
  private var _closeAfterUse = false
  @volatile
  private var _lastUsed = 0L

  final def closeAfterUse(): Unit = {
    logger.debug("closeAfterUse")
    _closeAfterUse = true
    if (!isInUse) close()
  }

  final def close(): Unit =
    iteratorPool.close()

  /**
    * @return None if torn
    */
  final def eventsAfter(after: EventId): Option[CloseableIterator[Stamped[KeyedEvent[Event]]]] = {
    val indexPositionAndEventId = journalIndex.positionAndEventIdAfter(after)
    import indexPositionAndEventId.position
    val iterator = iteratorPool.borrowIterator()
    closeOnError(iterator) {
      if (iterator.position != position &&
        (iterator.position < position || iterator.eventId > after/*No seek if skipToEventAfter works without seek*/))
      {
        logger.trace(s"seek $position (eventId=${indexPositionAndEventId.value}, for $after) ≠ " +
          s"iterator ${iterator.position} (eventId=${iterator.eventId})")
        iterator.seek(indexPositionAndEventId)
      }
      val exists = iterator.skipToEventAfter(journalIndex, after) // May run very long (minutes for gigabyte journals) !!!
      if (!exists) {
        iteratorPool.returnIterator(iterator)
        None
      } else
        Some(new MyIterator(iterator, after))
    }
  }

  private final class MyIterator(iterator_ : FileEventIterator, after: EventId) extends CloseableIterator[Stamped[KeyedEvent[Event]]] {
    private val iteratorAtomic = AtomicAny(iterator_)
    @volatile private var eof = false

    // May be called asynchronously (parallel to hasNext or next), as by Monix guarantee
    def close() =
      for (it <- Option(iteratorAtomic.getAndSet(null))) {
        iteratorPool.returnIterator(it)
        if (_closeAfterUse && !isInUse || iteratorPool.isClosed) {
          logger.debug(s"CloseableIterator.close _closeAfterUse: '${EventReader.this}'")
          EventReader.this.close()
        }
      }

    def hasNext =
      !eof && {  // Avoid exception in iterator in case of automatically closed iterator (closeAtEnd, for testing)
        iteratorAtomic.get() match {
          case null =>
            logger.debug(JsonSeqFileClosedProblem(iteratorName).toString)
            eof = true  // EOF to avoid exception logging (when closed (cancelled) asynchronously before hasNext, but not before `next`).
            false
          case iterator =>
            val has = iterator.hasNext
            eof |= !has
            if (!has && isHistoric) {
              journalIndex.freeze(journalIndexFactor)
            }
            if (!has) {
              close()
            }
            has
        }
      }

    def next() =
      iteratorAtomic.get() match {
        case null => throw new ClosedException(iterator_.journalFile)
        case iterator =>
          _lastUsed = Timestamp.currentTimeMillis
          val stamped = iterator.next()
          assertThat(stamped.eventId >= after, s"${stamped.eventId} ≥ $after")
          if (isHistoric) {
            if (eof/*freezed*/) sys.error(s"FileEventIterator: !hasNext but next() returns a value, eventId=${stamped.eventId} position=${iterator.position}")
            journalIndex.tryAddAfter(stamped.eventId, iterator.position)
          }
          stamped
      }

    private def iteratorName = iterator_.toString
  }

  final def snapshot: Observable[Any] =
    JournalReader.snapshot(journalMeta, expectedJournalId, journalFile)

  /** Observes a journal file lines and length. */
  final def observeFile(position: Long, timeout: FiniteDuration, markEOF: Boolean = false, onlyLastOfChunk: Boolean)
  : Observable[PositionAnd[ByteVector]] =
    Observable.fromResource(InputStreamJsonSeqReader.resource(journalFile))
      .flatMap { jsonSeqReader =>
        val until = now + timeout
        jsonSeqReader.seek(position)

        memoryLeakLimitedObservableTailRecM(position, limit = limitTailRecM)(position =>
          Observable.fromTask(whenDataAvailableAfterPosition(position, until))
            .flatMap {
              case false =>  // Timeout
                Observable.empty
              case true =>  // Data may be available
                var lastPosition = position
                var eof = false
                var iterator = UntilNoneIterator {
                  val maybeLine = jsonSeqReader.readRaw()
                  eof = maybeLine.isEmpty
                  lastPosition = jsonSeqReader.position
                  maybeLine.map(PositionAnd(lastPosition, _))
                }.takeWhileInclusive(_ => isFlushedAfterPosition(lastPosition))
                if (onlyLastOfChunk) {
                  // TODO Optimierung: Bei onlyLastOfChunk interessiert nur die geschriebene Dateilänge.
                  //  Dann brauchen wir die Datei nicht zu lesen, sondern nur die geschriebene Dateilänge zurückzugeben.
                  var last = null.asInstanceOf[PositionAnd[ByteVector]]
                  iterator foreach { last = _ }
                  iterator = Option(last).iterator
                }
                iterator = iterator
                  .tapEach { o =>
                    if (o.value == EndOfJournalFileMarker) sys.error(s"Journal file must not contain a line like $o")
                  } ++
                    (eof && markEOF).thenIterator(PositionAnd(lastPosition, EndOfJournalFileMarker))
                Observable.fromIteratorUnsafe(iterator map Right.apply) ++
                  Observable.fromIterable(
                    !eof ? Left(lastPosition))
              })
      }

  final def lastUsedAt: Long =
    _lastUsed

  final def isInUse = iteratorPool.isLent
}

object EventReader
{
  final class TimeoutException private[EventReader] extends scala.concurrent.TimeoutException
}
