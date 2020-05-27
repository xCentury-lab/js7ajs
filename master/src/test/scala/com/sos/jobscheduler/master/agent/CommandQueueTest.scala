package com.sos.jobscheduler.master.agent

import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.data.commands.AgentCommand.Batch
import com.sos.jobscheduler.base.crypt.Signed
import com.sos.jobscheduler.base.crypt.silly.{SillySignature, SillySigner}
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.WaitForCondition.waitForCondition
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.filebased.FileBasedSigner
import com.sos.jobscheduler.data.job.ExecutablePath
import com.sos.jobscheduler.data.order.{Order, OrderId}
import com.sos.jobscheduler.data.workflow.instructions.Execute
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.{Workflow, WorkflowPath}
import com.sos.jobscheduler.master.agent.AgentDriver.{Input, Queueable}
import com.sos.jobscheduler.master.agent.CommandQueue.QueuedInputResponse
import com.sos.jobscheduler.master.agent.CommandQueueTest._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers._
import scala.collection.mutable
import scala.language.reflectiveCalls
import com.typesafe.scalalogging.{Logger => ScalaLogger}
import monix.execution.atomic.AtomicInt

/**
  * @author Joacim Zschimmer
  */
final class CommandQueueTest extends AnyFreeSpec
{
  "test" in {
    val commandQueue = new MyCommandQueue(logger, batchSize = 3) {
      val succeeded = mutable.Buffer[Seq[QueuedInputResponse]]()
      val failed = mutable.Buffer[(Vector[Queueable], Problem)]()

      protected def asyncOnBatchSucceeded(queuedInputResponses: Seq[QueuedInputResponse]) =
        succeeded += queuedInputResponses

      protected def asyncOnBatchFailed(inputs: Vector[Queueable], problem: Problem) =
        failed += ((inputs, problem))
    }

    val expected = mutable.Buffer[Seq[QueuedInputResponse]]()

    commandQueue.onCoupled(Set.empty)

    // The first Input is sent alone to the Agent regardless of commandBatchSize.
    val aOrder = toOrder("A")
    commandQueue.enqueue(AgentDriver.Input.AttachOrder(aOrder, TestAgentRefPath, signedWorkflow))
    expected += toQueuedInputResponse(aOrder) :: Nil
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)

    val twoOrders = toOrder("B") :: toOrder("C") :: Nil
    for (o <- twoOrders) commandQueue.enqueue(AgentDriver.Input.AttachOrder(o, TestAgentRefPath, signedWorkflow))
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)

    // After the Agent has processed the Input, the two queued commands are sent as a Batch to the Agent
    commandQueue.handleBatchSucceeded(commandQueue.succeeded.last) shouldEqual List(Input.AttachOrder(aOrder, TestAgentRefPath, signedWorkflow))
    expected += twoOrders map toQueuedInputResponse
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)

    val fiveOrders = toOrder("D") :: toOrder("E") :: toOrder("F") :: toOrder("G") :: toOrder("H") :: Nil
    for (o <- fiveOrders) commandQueue.enqueue(AgentDriver.Input.AttachOrder(o, TestAgentRefPath, signedWorkflow))
    expected += fiveOrders take 1 map toQueuedInputResponse
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)

    // After the Agent has processed the Input, three of the queued commands are sent as a Batch to the Agent
    commandQueue.handleBatchSucceeded(commandQueue.succeeded.last) shouldEqual fiveOrders.take(1).map(o => Input.AttachOrder(o, TestAgentRefPath, signedWorkflow))
    expected += fiveOrders drop 1 take 3 map toQueuedInputResponse
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)

    // Finally, the last queued Input is processed
    commandQueue.handleBatchSucceeded(commandQueue.succeeded.last)
    expected += fiveOrders drop 4 map toQueuedInputResponse
    waitForCondition(99.s, 10.ms) { commandQueue.succeeded == expected }
    assert(commandQueue.succeeded == expected)
    assert(commandQueue.failed.isEmpty)
  }

  "Performany with any orders" in {
    // Run with -DCommandQueueTest=10000000 (10 million) -Xmx3g
    val n = sys.props.get("CommandQueueTest").map(_.toInt) getOrElse 10000
    val orders = for (i <- 1 to n) yield toOrder(i.toString)
    val commandQueue = new MyCommandQueue(logger, batchSize = 100) {
      val succeeded = AtomicInt(0)
      protected def asyncOnBatchSucceeded(queuedInputResponses: Seq[QueuedInputResponse]) = {
        handleBatchSucceeded(queuedInputResponses)
        succeeded += queuedInputResponses.size
      }
      protected def asyncOnBatchFailed(inputs: Vector[Queueable], problem: Problem) = {}
    }
    commandQueue.onCoupled(Set.empty)
    for (order <- orders) {
      commandQueue.enqueue(AgentDriver.Input.AttachOrder(order, TestAgentRefPath, signedWorkflow))
    }
    commandQueue.maySend()
    waitForCondition(9.s, 10.ms) { commandQueue.succeeded.get == n }
    assert(commandQueue.succeeded.get == n)
  }
}

object CommandQueueTest {
  private val logger = Logger(getClass)
  private val TestAgentRefPath = AgentRefPath("/AGENT")
  private val TestWorkflow = Workflow.of(WorkflowPath("/A") ~ "VERSION",
    Execute(WorkflowJob(TestAgentRefPath, ExecutablePath("/EXECUTABLE"))))
  private val fileBasedSigner = new FileBasedSigner(new SillySigner(SillySignature("MY-SILLY-SIGNATURE")), Workflow.jsonEncoder)
  private val signedWorkflow: Signed[Workflow] = fileBasedSigner.toSigned(TestWorkflow)

  private def toQueuedInputResponse(order: Order[Order.IsFreshOrReady]) =
    QueuedInputResponse(AgentDriver.Input.AttachOrder(order, TestAgentRefPath, signedWorkflow), Right(AgentCommand.Response.Accepted))

  private def toOrder(name: String) = Order(OrderId(name), TestWorkflow.id, Order.Fresh.StartImmediately)

  private abstract class MyCommandQueue(logger: ScalaLogger, batchSize: Int)
  extends CommandQueue(logger, batchSize = batchSize)
  {
    protected def commandParallelism = 2

    protected def executeCommand(command: AgentCommand.Batch) =
      Task(Right(Batch.Response(Vector.fill(command.commands.size)(Right(AgentCommand.Response.Accepted)))))
  }
}
