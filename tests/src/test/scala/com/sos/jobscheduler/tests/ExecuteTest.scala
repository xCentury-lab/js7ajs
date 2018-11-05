package com.sos.jobscheduler.tests

import akka.actor.ActorSystem
import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.utils.MapDiff
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.system.OperatingSystem.isWindows
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.event.{EventSeq, KeyedEvent, TearableEventSeq}
import com.sos.jobscheduler.data.job.{ExecutablePath, ReturnCode}
import com.sos.jobscheduler.data.order.OrderEvent.{OrderAdded, OrderDetachable, OrderFinished, OrderMoved, OrderProcessed, OrderProcessingStarted, OrderTransferredToAgent, OrderTransferredToMaster}
import com.sos.jobscheduler.data.order.{FreshOrder, OrderEvent, OrderId, Outcome}
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.data.workflow.parser.WorkflowParser
import com.sos.jobscheduler.data.workflow.position.Position
import com.sos.jobscheduler.master.tests.TestEventCollector
import com.sos.jobscheduler.tests.ExecuteTest._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec
import scala.language.higherKinds

final class ExecuteTest extends FreeSpec {

  "Execute" in {
    autoClosing(new DirectoryProvider(List(TestAgentPath))) { directoryProvider ⇒
      directoryProvider.master.writeJson(TestWorkflow.withoutVersion)
      for (a ← directoryProvider.agents) {
        for (o ← Array("/SCRIPT-0a", "/SCRIPT-0b")) a.writeExecutable(ExecutablePath(o), ":")
        for (o ← Array("/SCRIPT-1", "/SCRIPT-2", "/SCRIPT-3", "/SCRIPT-4"))
          a.writeExecutable(ExecutablePath(o),
            if (isWindows) "@exit %SCHEDULER_PARAM_RETURN_CODE%" else "exit $SCHEDULER_PARAM_RETURN_CODE")
      }
      directoryProvider.run { (master, _) ⇒
        val eventCollector = new TestEventCollector
        eventCollector.start(master.injector.instance[ActorSystem], master.injector.instance[StampedKeyedEventBus])
          val orderId = OrderId("🔺")
          master.addOrderBlocking(FreshOrder(orderId, TestWorkflow.id.path))
          eventCollector.await[OrderFinished](_.key == orderId)
          checkEventSeq(orderId, eventCollector.all[OrderEvent])
      }
    }
  }

  private def checkEventSeq(orderId: OrderId, eventSeq: TearableEventSeq[TraversableOnce, KeyedEvent[OrderEvent]]): Unit = {
    eventSeq match {
      case EventSeq.NonEmpty(stampeds) ⇒
        val events = stampeds.filter(_.value.key == orderId).map(_.value.event).toVector
        assert(events == ExpectedEvents)
      case o ⇒
        fail(s"Unexpected EventSeq received: $o")
    }
  }
}

object ExecuteTest {
  private val TestAgentPath = AgentPath("/AGENT")
  private val script = """
    workflow {
      execute executable="/SCRIPT-0a", agent="AGENT";
      execute executable="/SCRIPT-1", agent="AGENT", arguments={"RETURN_CODE": "1"}, successReturnCodes=[1];
      job aJob;
      job bJob;  // returnCode=2
      if (true) {
        job aJob;
        job bJob;  // returnCode=3
        job cJob;  // returnCode=4
        define job bJob {
          execute executable="/SCRIPT-3", agent="AGENT", arguments={"RETURN_CODE": "3"}, successReturnCodes=[3];
        }
        define job cJob {
          execute executable="/SCRIPT-4", agent="AGENT", arguments={"RETURN_CODE": "4"}, successReturnCodes=[4];
        }
      };
      define job aJob {
        execute executable="/SCRIPT-0b", agent="AGENT";
      }
      define job bJob {
        execute executable="/SCRIPT-2", agent="AGENT", arguments={"RETURN_CODE": "2"}, successReturnCodes=[2];
      }
    }"""
  private val TestWorkflow = WorkflowParser.parse(WorkflowPath("/WORKFLOW") % "(initial)", script).orThrow

  private val ExpectedEvents = Vector(
    OrderAdded(TestWorkflow.id, None),
    OrderTransferredToAgent(TestAgentPath % "(initial)"),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(0))),
    OrderMoved(Position(1)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(1))),
    OrderMoved(Position(2)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(0))),
    OrderMoved(Position(3)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(2))),
    OrderMoved(Position(4, 0, 0)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(0))),
    OrderMoved(Position(4, 0, 1)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(3))),
    OrderMoved(Position(4, 0, 2)),
    OrderProcessingStarted,
    OrderProcessed(MapDiff.empty, Outcome.Succeeded(ReturnCode(4))),
    OrderMoved(Position(5)),
    OrderDetachable,
    OrderTransferredToMaster,
    OrderFinished)
}
