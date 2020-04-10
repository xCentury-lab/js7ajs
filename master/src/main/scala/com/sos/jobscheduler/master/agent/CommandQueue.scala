package com.sos.jobscheduler.master.agent

import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.data.commands.AgentCommand.Batch
import com.sos.jobscheduler.base.problem.{Checked, Problem}
import com.sos.jobscheduler.base.utils.Assertions.assertThat
import com.sos.jobscheduler.master.agent.AgentDriver.{Input, Queueable, ReleaseEventsQueueable}
import com.sos.jobscheduler.master.agent.CommandQueue._
import com.typesafe.scalalogging.{Logger => ScalaLogger}
import monix.eval.Task
import monix.execution.Scheduler
import scala.collection.mutable
import scala.util.{Failure, Success}

/**
  * @author Joacim Zschimmer
  */
private[agent] abstract class CommandQueue(logger: ScalaLogger, batchSize: Int)(implicit s: Scheduler)
{
  protected def commandParallelism: Int
  protected def executeCommand(command: AgentCommand.Batch): Task[Checked[command.Response]]
  protected def asyncOnBatchSucceeded(queuedInputResponses: Seq[QueuedInputResponse]): Unit
  protected def asyncOnBatchFailed(inputs: Vector[Queueable], problem: Problem): Unit

  private val executingInputs = mutable.Set[Queueable]()
  private var freshReconnected = true  // After connect, send a single command first. Start queueing first after one successful response.
  private var openRequestCount = 0
  private var isTerminating = false

  private object queue {
    private val queue = mutable.Queue[Queueable]()
    private val detachQueue = mutable.Queue[Queueable]()  // DetachOrder is sent to Agent prior any AttachOrder, to relieve the Agent

    def enqueue(input: Queueable): Unit =
      input match {
        case o: Input.DetachOrder => detachQueue += o
        case o => queue += o
      }

    def dequeueAll(what: Set[Queueable]): Unit = {
      queue.dequeueAll(what)
      detachQueue.dequeueAll(what)
    }

    def size = queue.size + detachQueue.size

    def iterator = detachQueue.iterator ++ queue.iterator
  }

  final def onCoupled() =
    freshReconnected = true

  final def enqueue(input: Queueable): Unit = {
    assertThat(!isTerminating)
    queue.enqueue(input)
    if (queue.size == batchSize || freshReconnected) {
      maySend()
    }
  }

  final def terminate(): Unit = {
    if (executingInputs.nonEmpty) {
      logger.info(s"Waiting for responses to AgentCommands: ${executingInputs.map(_.toShortString).mkString(", ")}")
    }
    isTerminating = true
  }

  final def isTerminated =
    isTerminating && executingInputs.isEmpty

  final def maySend(): Unit =
    if (!isTerminating) {
      lazy val inputs = queue.iterator.filterNot(executingInputs).take(batchSize).toVector
      if (openRequestCount < commandParallelism && (!freshReconnected || openRequestCount == 0) && inputs.nonEmpty) {
        executingInputs ++= inputs
        openRequestCount += 1
        executeCommand(Batch(inputs map inputToAgentCommand))
          .materialize foreach {
            case Success(Right(Batch.Response(responses))) =>
              asyncOnBatchSucceeded(for ((i, r) <- inputs zip responses) yield QueuedInputResponse(i, r))

            case Success(Left(problem)) =>
              asyncOnBatchFailed(inputs, problem)

            case Failure(t) =>
              asyncOnBatchFailed(inputs, Problem.pure(t))
          }
      }
    }

  private def inputToAgentCommand(input: Queueable): AgentCommand =
    input match {
      case Input.AttachOrder(order, agentRefPath, signedWorkflow) =>
        AgentCommand.AttachOrder(order, agentRefPath, signedWorkflow.signedString)
      case Input.DetachOrder(orderId) =>
        AgentCommand.DetachOrder(orderId)
      case Input.CancelOrder(orderId, mode) =>
        AgentCommand.CancelOrder(orderId, mode)
      case ReleaseEventsQueueable(untilEventId) =>
        AgentCommand.ReleaseEvents(untilEventId)
    }

  final def handleBatchSucceeded(responses: Seq[QueuedInputResponse]): Seq[Queueable] = {
    freshReconnected = false
    val inputs = responses.map(_.input).toSet
    queue.dequeueAll(inputs)  // Including rejected commands. The corresponding orders are ignored henceforth.
    onQueuedInputsResponded(inputs)
    responses.flatMap {
      case QueuedInputResponse(input, Right(AgentCommand.Response.Accepted)) =>
        Some(input)
      case QueuedInputResponse(_, Right(o)) =>
        sys.error(s"Unexpected response from Agent: $o")
      case QueuedInputResponse(input, Left(problem)) =>
        // CancelOrder(NotStarted) fails if order has started !!!
        logger.error(s"Agent has rejected the command ${input.toShortString}: $problem")
        // Agent's state does not match master's state ???
        // TODO: But "Agent is shutting down" is okay
        None
    }
  }

  final def handleBatchFailed(inputs: Seq[Queueable]): Unit = {
    // Don't remove from queue. Queued inputs will be processed again
    onQueuedInputsResponded(inputs.toSet)
  }

  private def onQueuedInputsResponded(inputs: Set[Queueable]): Unit = {
    executingInputs --= inputs
    openRequestCount -= 1
    maySend()
  }
}

object CommandQueue
{
  private[agent] final case class QueuedInputResponse(input: Queueable, response: Checked[AgentCommand.Response])
}
