package com.sos.jobscheduler.shared.event.journal.tests

import akka.Done
import akka.actor.ActorRef
import com.sos.jobscheduler.base.utils.ScalaUtils.cast
import com.sos.jobscheduler.shared.event.journal.KeyedJournalingActor
import com.sos.jobscheduler.shared.event.journal.tests.TestAggregateActor._

/**
  * @author Joacim Zschimmer
  */
private[tests] final class TestAggregateActor(protected val key: String, val journalActor: ActorRef)
extends KeyedJournalingActor[TestEvent] {

  private var aggregate: TestAggregate = null

  protected def snapshot = Option(aggregate)

  protected def recoverFromSnapshot(o: Any) = {
    aggregate = cast[TestAggregate](o)
  }

  protected def recoverFromEvent(event: TestEvent) =
    update(event)

  def receive = {
    case command: Command ⇒
      commandToEvent(command) match {
        case event: TestEvent.Removed.type ⇒
          persist(event) { event ⇒  // Test without afterLastPersist because the event stops the actor immediately
            update(event)
            sender() ! Done
          }
        case event ⇒
          persist(event)(update)
          afterLastPersist {   // Different to Removed case, simply to test afterLastPersist. Removed stops the actor.
            sender() ! Done
          }
      }

    case Input.Get ⇒
      assert(aggregate != null)
      afterLastPersist {  // For testing
        sender() ! aggregate
      }
  }

  private def commandToEvent(command: Command): TestEvent =
    command match {
      case Command.Add(string) ⇒ TestEvent.Added(string)
      case Command.Remove ⇒ TestEvent.Removed
      case Command.Append(string) ⇒ TestEvent.Appended(string)
    }

  private def update(event: TestEvent): Unit = {
    event match {
      case event: TestEvent.Added ⇒
        assert(aggregate == null)
        import event._
        aggregate = TestAggregate(key, string, a, b, c, d, e, f, g, h, i, k, l, m, n, o, p, q, r)

      case TestEvent.Removed ⇒
        aggregate = null
        context.stop(self)

      case event: TestEvent ⇒
        aggregate = aggregate.update(event)
    }
  }
}

private[tests] object TestAggregateActor {

  object Input {
    final case object Get
  }

  object Output {
    final case object Ready
  }

  sealed trait Command
  final object Command {
    final case class Add(string: String) extends Command
    final case class Append(string: String) extends Command
    final case object Remove extends Command
  }
}
