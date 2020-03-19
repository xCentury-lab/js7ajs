package com.sos.jobscheduler.core.event.journal.test

import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.data.event.{EventId, JournaledState, KeyedEvent}
import monix.reactive.Observable

/**
  * @author Joacim Zschimmer
  */
final case class TestState(keyToAggregate: Map[String, TestAggregate])
extends JournaledState[TestState, TestEvent]
{
  def applySnapshot(aggregate: TestAggregate): TestState =
    TestState(keyToAggregate + (aggregate.key -> aggregate))

  def applyEvent(keyedEvent: KeyedEvent[TestEvent]): Checked[TestState] = {
    val KeyedEvent(key, event) = keyedEvent
    event match {
      case event: TestEvent.Added =>
        assert(!keyToAggregate.contains(key))
        import event._
        Right(TestState(keyToAggregate + (key -> TestAggregate(key, string, a, b, c, d, e, f, g, h, i, k, l, m, n, o, p, q, r))))

      case TestEvent.Removed =>
        Right(TestState(keyToAggregate - key))

      case event: TestEvent =>
        Right(TestState(keyToAggregate + (key -> keyToAggregate(key).applyEvent(event))))
    }
  }

  override def withEventId(eventId: EventId): TestState =
    throw new NotImplementedError("TestState.withEventId")  // ???

  override def toSnapshotObservable =
    Observable.fromIterable(keyToAggregate.values)
}
