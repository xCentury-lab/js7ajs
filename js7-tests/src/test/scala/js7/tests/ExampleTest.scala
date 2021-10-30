package js7.tests

import js7.base.circeutils.CirceUtils.JsonStringInterpolator
import js7.base.configutils.Configs._
import js7.base.problem.Checked.Ops
import js7.base.thread.MonixBlocking.syntax._
import js7.base.time.ScalaTime._
import js7.base.utils.ScalaUtils.syntax.RichEither
import js7.data.agent.AgentPath
import js7.data.order.OrderEvent._
import js7.data.order.{FreshOrder, OrderEvent, OrderId, Outcome}
import js7.data.workflow.Workflow
import js7.data.workflow.position.Position
import js7.tests.ExampleTest._
import js7.tests.testenv.ControllerAgentForScalaTest
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

final class ExampleTest extends AnyFreeSpec with ControllerAgentForScalaTest
{
  protected val agentPaths = Seq(agentPath)
  protected val items = Seq(workflow)
  override protected def controllerConfig = config"""
    js7.auth.users.TEST-USER.permissions = [ UpdateItem ]
    js7.journal.slow-check-state = on
    """
  override protected def agentConfig = config"""
    js7.job.execution.signed-script-injection-allowed = on
    js7.journal.slow-check-state = on
    """

  "Test" in {
    val eventId = eventWatch.lastAddedEventId
    val orderId = OrderId("🔵")
    controllerApi.addOrder(FreshOrder(orderId, workflow.path, deleteWhenTerminated = true))
      .await(99.s).orThrow
    assert(eventWatch.await[OrderTerminated](_.key == orderId).map(_.value.event) ==
      Seq(OrderFinished))
    eventWatch.await[OrderDeleted](_.key == orderId)
    assert(eventWatch.eventsByKey[OrderEvent](orderId, after = eventId) == Seq(
      OrderAdded(workflow.id, deleteWhenTerminated = true),
      OrderAttachable(agentPath),
      OrderAttached(agentPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderFinished,
      OrderDeleted))
  }
}

object ExampleTest
{
  private val agentPath = AgentPath("AGENT")

  private val workflow = json"""{
    "path": "WORKFLOW",
    "versionId": "INITIAL",
    "instructions": [
      {
        "TYPE": "Execute.Anonymous",
        "job": {
          "agentPath": "AGENT",
          "executable": {
            "TYPE": "InternalExecutable",
            "className": "js7.tests.jobs.EmptyJob"
          }
        }
      }
    ]
  }""".as[Workflow].orThrow
}
