package com.sos.jobscheduler.agent.scheduler

import akka.pattern.ask
import akka.util.Timeout
import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.data.commands.AgentCommand.{AttachOrder, DetachOrder, GetOrders, RegisterAsMaster}
import com.sos.jobscheduler.agent.scheduler.AgentActorTest._
import com.sos.jobscheduler.agent.scheduler.order.TestAgentActorProvider
import com.sos.jobscheduler.base.utils.ScalazStyle.OptionRichBoolean
import com.sos.jobscheduler.common.scalautil.Closer.withCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.system.OperatingSystem.isWindows
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.Stopwatch
import com.sos.jobscheduler.data.event.EventRequest
import com.sos.jobscheduler.data.order.{Order, OrderEvent, OrderId}
import com.sos.jobscheduler.data.workflow.test.TestSetting._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class AgentActorTest extends FreeSpec {

  private implicit val askTimeout = Timeout(60.seconds)

  for (n ← List(10) ++ (sys.props contains "test.speed" option 1000 /*needs taskLimit=100 !!!*/)) {
    s"AgentActorTest, $n orders" in {
      TestAgentActorProvider.provide { provider ⇒
        import provider.{agentDirectory, eventCollector, executeCommand}
        for (executablePath ← TestExecutablePaths) {
          val file = executablePath.toFile(agentDirectory / "config" / "executables")
          file.writeExecutable(TestScript)
        }
        withCloser { implicit closer ⇒
          (provider.agentActor ? AgentActor.Input.Start).mapTo[AgentActor.Output.Ready.type] await 99.s
          executeCommand(RegisterAsMaster) await 99.s
          val stopwatch = new Stopwatch
          val orderIds = for (i ← 0 until n) yield OrderId(s"TEST-ORDER-$i")
          orderIds map (orderId ⇒ executeCommand(AttachOrder(TestOrder.copy(id = orderId), TestAgentPath % "(initial)", SimpleTestWorkflow))) await 99.s
          for (orderId ← orderIds)
            eventCollector.whenKeyedEvent[OrderEvent.OrderDetachable](EventRequest.singleClass(timeout = 90.seconds), orderId) await 99.s
          info(stopwatch.itemsPerSecondString(n, "Orders"))

          val GetOrders.Response(orders) = executeCommand(GetOrders) await 99.s
          assert(orders.toSet ==
            orderIds.map(orderId ⇒ Order(
              orderId,
              SimpleTestWorkflow.lastWorkflowPosition,
              Order.Ready,
              Some(Order.AttachedTo.Detachable(TestAgentPath % "(initial)")),
              payload = TestOrder.payload.copy(
                variables = TestOrder.payload.variables + ("result" → "TEST-RESULT-B-VALUE"))
            )).toSet)

          (for (orderId ← orderIds) yield executeCommand(DetachOrder(orderId))) await 99.s
          for (orderId ← orderIds)
            eventCollector.whenKeyedEvent[OrderEvent.OrderDetached](EventRequest.singleClass(timeout = 90.seconds), orderId) await 99.s
        }
        executeCommand(AgentCommand.Terminate()) await 99.s
      }
    }
  }
}

object AgentActorTest {
  private val TestScript =
    if (isWindows) """
      |@echo off
      |echo Hej!
      |echo result=TEST-RESULT-%SCHEDULER_PARAM_JOB_B% >>"%SCHEDULER_RETURN_VALUES%"
      |""".stripMargin
    else """
      |echo "Hej!"
      |echo "result=TEST-RESULT-$SCHEDULER_PARAM_JOB_B" >>"$SCHEDULER_RETURN_VALUES"
      |""".stripMargin
}
