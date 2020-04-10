package com.sos.jobscheduler.common.event

import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.base.utils.CloseableIterator
import com.sos.jobscheduler.common.event.RealEventWatchTest._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.data.event.{Event, EventId, EventRequest, KeyedEvent, Stamped}
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class RealEventWatchTest extends AnyFreeSpec
{
  "tornOlder" in {
    val events = Stamped(1, 1 <-: TestEvent(1)) :: Nil  // Event 1 = 1970-01-01, very old
    val eventWatch = new RealEventWatch {
      protected def scheduler = Scheduler.global
      def fileEventIds = EventId.BeforeFirst :: Nil
      protected def eventsAfter(after: EventId) = Some(CloseableIterator.fromIterator(events.iterator dropWhile (_.eventId <= after)))
      def snapshotObjectsFor(after: EventId) = None
      def observeFile(fileEventId: Option[EventId], position: Option[Long], timeout: FiniteDuration, markEOF: Boolean, onlyLastOfChunk: Boolean) =
        throw new NotImplementedError
      onEventsCommitted(events.last.eventId)
    }
    val a = eventWatch.observe(EventRequest.singleClass[TestEvent](limit = 1)).toListL.runToFuture await 99.s
    assert(a == events)

    // Event from 1970-01-01 is older than 1s
    val observable = eventWatch.observe(EventRequest.singleClass[TestEvent](tornOlder = Some(1.s))).toListL.runToFuture
    intercept[TornException] { observable await 99.s }
    observable.cancel()

    assert(eventWatch.observe(EventRequest.singleClass[TestEvent](limit = 7, after = 1, tornOlder = Some(1.s)))
      .toListL.runToFuture.await(99.s).isEmpty)
  }

  "observe without stack overflow" in {
    val eventWatch = new EndlessEventWatch().strict
    var expectedNext = Stamped(1, 1 <-: TestEvent(1))
    val events = mutable.Buffer[Stamped[KeyedEvent[TestEvent]]]()
    val n = 100000
    eventWatch.observe(EventRequest.singleClass[TestEvent](limit = n, timeout = Some(99.s)), onlyLastOfChunk = false)
      .foreach { stamped =>
        assert(stamped == expectedNext)
        expectedNext = Stamped(stamped.eventId + 1, (stamped.value.key + 1) <-: TestEvent(stamped.value.event.number + 1))
        events += stamped
      }
      .await(99.s)
    assert(expectedNext.eventId == n + 1)
    assert(events == (1L to n).map(toStampedEvent))
  }
}

object RealEventWatchTest {
  private val EventsPerIteration = 3

  private case class TestEvent(number: Long) extends Event {
    type Key = Long
  }

  private def toStampedEvent(i: Long) = Stamped(i, i <-: TestEvent(i))

  private class EndlessEventWatch extends RealEventWatch {
    def fileEventIds = EventId.BeforeFirst :: Nil

    def snapshotObjectsFor(after: EventId) = None

    onEventsCommitted(1)

    def eventsAfter(after: EventId) =
      Some(CloseableIterator.fromIterator(
        Iterator.from(1) take EventsPerIteration map { i =>
          onEventsCommitted(after + i + 1)  // Announce following event
          toStampedEvent(after + i)
        }))

    def observeFile(fileEventId: Option[EventId], position: Option[Long], timeout: FiniteDuration, markEOF: Boolean, onlyLastOfChunk: Boolean) =
      Left(Problem("EndlessEventWatch.observeFile is not implemented"))
  }
}
