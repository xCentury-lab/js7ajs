package com.sos.jobscheduler.common.event.collector

import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.common.event.collector.KeyedEventQueueTest._
import com.sos.jobscheduler.data.event.{EventId, KeyedEvent, NoKeyEvent, Stamped}
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class KeyedEventQueueTest extends FreeSpec {

  private val queue = new KeyedEventQueue(initialOldestEventId = EventId.BeforeFirst, 3)
  private val stampeds = for (i ← 1 to 5) yield Stamped(EventId(i), Timestamp.ofEpochMilli(0), KeyedEvent(AEvent(i)))

  stampeds foreach queue.add

  "event stream is torn - the first event has been lost" in {
    assert(queue.after(EventId.BeforeFirst) == None)
  }

  "hasAfter" in {
    assert(queue.hasAfter(EventId.BeforeFirst))
    assert(queue.hasAfter(EventId(1)))
    assert(queue.hasAfter(EventId(2)))
    assert(queue.hasAfter(EventId(3)))
    assert(queue.hasAfter(EventId(4)))
    assert(!queue.hasAfter(EventId(5)))
  }

  "after" in {
    //assert(queue.events(EventId(1)) == None)
    assert((queue.after(EventId(2)) map { _.toVector }) == Some(stampeds drop 2))
    assert((queue.after(EventId(3)) map { _.toVector }) == Some(stampeds drop 3))
    assert((queue.after(EventId(4)) map { _.toVector }) == Some(stampeds drop 4))
    assert(queue.after(EventId(5)).get.isEmpty)
  }

  "reverseEvents" in {
    assert(queue.reverseEvents(EventId.BeforeFirst).toVector == (stampeds drop 2).reverse)
    assert(queue.reverseEvents(EventId(1)).toVector == (stampeds drop 2).reverse)
    assert(queue.reverseEvents(EventId(2)).toVector == (stampeds drop 2).reverse)
    assert(queue.reverseEvents(EventId(3)).toVector == (stampeds drop 3).reverse)
    assert(queue.reverseEvents(EventId(4)).toVector == (stampeds drop 4).reverse)
    assert(queue.reverseEvents(EventId(5)).isEmpty)
  }
}

object KeyedEventQueueTest {
  private case class AEvent(number: Int) extends NoKeyEvent
}
