package js7.tests

import js7.base.problem.Checked.Ops
import js7.base.problem.Problem
import js7.base.system.OperatingSystem.isWindows
import js7.base.utils.AutoClosing.autoClosing
import js7.common.process.Processes.{ShellFileExtension => sh}
import js7.data.agent.AgentId
import js7.data.event.{EventSeq, KeyedEvent, TearableEventSeq}
import js7.data.job.{RelativePathExecutable, ReturnCode}
import js7.data.order.OrderEvent.{OrderAdded, OrderAttachable, OrderAttached, OrderDetachable, OrderDetached, OrderFailed, OrderFinished, OrderMoved, OrderProcessed, OrderProcessingStarted, OrderStarted, OrderTerminated}
import js7.data.order.{FreshOrder, OrderEvent, OrderId, Outcome}
import js7.data.value.{NumberValue, StringValue}
import js7.data.workflow.position.BranchId.{Else, Then}
import js7.data.workflow.position.Position
import js7.data.workflow.{WorkflowParser, WorkflowPath}
import js7.tests.IfTest._
import js7.tests.testenv.DirectoryProvider
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

final class IfTest extends AnyFreeSpec
{
  "test" in {
    autoClosing(new DirectoryProvider(agentId :: Nil, versionedItems = TestWorkflow :: Nil, testName = Some("IfTest"))) { directoryProvider =>
      for (a <- directoryProvider.agents) a.writeExecutable(RelativePathExecutable(s"TEST$sh"), ":")
      for (a <- directoryProvider.agents) a.writeExecutable(RelativePathExecutable(s"TEST-RC$sh"), jobScript)

      directoryProvider.run { (controller, _) =>
        for (returnCode <- ExpectedEvents.keys) withClue(s"$returnCode: ") {
          val orderId = OrderId("🔺" + returnCode.number)
          controller.addOrderBlocking(newOrder(orderId, returnCode))
          controller.eventWatch.await[OrderTerminated](_.key == orderId)
          checkEventSeq(orderId, controller.eventWatch.all[OrderEvent], returnCode)
        }
      }
    }
  }

  "OrderFailed on Agent is delayed until Order has been moved back to Controller" in {
     val workflowNotation = s"""
       |define workflow {
       |  // Move order to agent
       |  execute executable="TEST$sh", agent="AGENT";
       |  if ($$MISSING == "?") {
       |    execute executable="TEST$sh", agent="AGENT";
       |  }
       |}""".stripMargin
    val workflow = WorkflowParser.parse(WorkflowPath("WORKFLOW") ~ "INITIAL", workflowNotation).orThrow
    val directoryProvider = new DirectoryProvider(agentId :: Nil, versionedItems = workflow :: Nil,
      testName = Some("IfTest"))
    autoClosing(directoryProvider) { directoryProvider =>
      for (a <- directoryProvider.agents) a.writeExecutable(RelativePathExecutable(s"TEST$sh"), ":")
      directoryProvider.run { (controller, _) =>
        val orderId = OrderId("❌")
        controller.addOrderBlocking(FreshOrder(orderId, TestWorkflow.id.path))
        controller.eventWatch.await[OrderTerminated](_.key == orderId)
        val EventSeq.NonEmpty(events) = controller.eventWatch.all[OrderEvent]
        assert(events.map(_.value.event) == Seq(
          OrderAdded(workflow.id),
          OrderAttachable(agentId),
          OrderAttached(agentId),
          OrderStarted,
          OrderProcessingStarted,
          OrderProcessed(Outcome.succeededRC0),
          OrderDetachable,
          OrderDetached,
          OrderFailed(Position(0),Some(Outcome.Disrupted(Problem("No such named value: MISSING"))))))
      }
    }
  }

  private def checkEventSeq(orderId: OrderId, eventSeq: TearableEventSeq[IterableOnce, KeyedEvent[OrderEvent]], returnCode: ReturnCode): Unit =
    eventSeq match {
      case EventSeq.NonEmpty(stampeds) =>
        val events = stampeds.iterator.filter(_.value.key == orderId).map(_.value.event).to(Vector)
        assert(events == ExpectedEvents(returnCode))
      case o =>
        fail(s"Unexpected EventSeq received: $o")
    }
}

object IfTest {
  private val agentId = AgentId("AGENT")

  private val jobScript =
    if (isWindows)
      """@echo off
        |echo JOB-KEY=JOB-RESULT >>%SCHEDULER_RETURN_VALUES%
        |exit %SCHEDULER_PARAM_RETURN_CODE%
        |""".stripMargin
    else
      """echo JOB-KEY=JOB-RESULT >>"$SCHEDULER_RETURN_VALUES"
        |exit $SCHEDULER_PARAM_RETURN_CODE
        |""".stripMargin

  private val workflowNotation = s"""
     |define workflow {
     |  if ($$ARG == "ARG-VALUE") {
     |    if (argument("ARG") == "ARG-VALUE") {
     |      LABEL: job MYJOB;  // 0/then:0/then:0
     |    }
     |  }
     |  if ($$ARG != "X" && variable(key="JOB-KEY", label=LABEL) == "JOB-RESULT" && variable("JOB-KEY", job=MYJOB) == "JOB-RESULT") {
     |    if (variable("returnCode", label=LABEL) == 0) {
     |      execute executable="TEST$sh", agent="AGENT";
     |    } else {
     |      execute executable="TEST$sh", agent="AGENT";
     |    }
     |  }
     |  execute executable="TEST$sh", agent="AGENT";
     |
     |  define job MYJOB {
     |    execute executable="TEST-RC$sh", agent="AGENT", v1Compatible=true, successReturnCodes=[0, 1];
     |  }
     |}""".stripMargin
  private val TestWorkflow = WorkflowParser.parse(WorkflowPath("WORKFLOW") ~ "INITIAL", workflowNotation).orThrow

  private val ExpectedEvents = Map(
    ReturnCode(0) -> Vector(
      OrderAdded(TestWorkflow.id, Map("ARG" -> StringValue("ARG-VALUE"), "RETURN_CODE" -> StringValue("0"))),
      OrderMoved(Position(0) / Then % 0 / Then % 0),
      OrderAttachable(agentId),
      OrderAttached(agentId),
      OrderStarted,
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(Map("JOB-KEY" -> StringValue("JOB-RESULT"), "returnCode" -> NumberValue(0)))),
      OrderMoved(Position(1) / Then % 0 / Then % 0),
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(ReturnCode(0))),
      OrderMoved(Position(2)),
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(ReturnCode(0))),
      OrderMoved(Position(3)),
      OrderDetachable,
      OrderDetached,
      OrderFinished),
    ReturnCode(1) -> Vector(
      OrderAdded(TestWorkflow.id, Map("ARG" -> StringValue("ARG-VALUE"), "RETURN_CODE" -> StringValue("1"))),
      OrderMoved(Position(0) / Then % 0 / Then % 0),
      OrderAttachable(agentId),
      OrderAttached(agentId),
      OrderStarted,
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(Map("JOB-KEY" -> StringValue("JOB-RESULT"), "returnCode" -> NumberValue(1)))),
      OrderMoved(Position(1) / Then % 0 / Else % 0),
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(ReturnCode(0))),
      OrderMoved(Position(2)),
      OrderProcessingStarted,
      OrderProcessed(Outcome.Succeeded(ReturnCode(0))),
      OrderMoved(Position(3)),
      OrderDetachable,
      OrderDetached,
      OrderFinished),
    ReturnCode(2) ->  Vector(
      OrderAdded(TestWorkflow.id, Map("ARG" -> StringValue("ARG-VALUE"), "RETURN_CODE" -> StringValue("2"))),
      OrderMoved(Position(0) / Then % 0 / Then % 0),
      OrderAttachable(agentId),
      OrderAttached(agentId),
      OrderStarted,
      OrderProcessingStarted,
      OrderProcessed(Outcome.Failed(Map("JOB-KEY" -> StringValue("JOB-RESULT"), "returnCode" -> NumberValue(2)))),
      OrderDetachable,
      OrderDetached,
      OrderFailed(Position(0) / Then % 0 / Then % 0)))

  private def newOrder(orderId: OrderId, returnCode: ReturnCode) =
    FreshOrder(orderId, TestWorkflow.id.path, arguments = Map(
      "ARG" -> StringValue("ARG-VALUE"),
      "RETURN_CODE" -> StringValue(returnCode.number.toString)))
}
