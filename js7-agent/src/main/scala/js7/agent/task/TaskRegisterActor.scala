package js7.agent.task

import akka.actor.{Actor, ActorSystem, Cancellable, DeadLetterSuppression, PoisonPill, Props, Status}
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import com.typesafe.config.Config
import js7.agent.data.views.TaskRegisterOverview
import js7.agent.data.{AgentTaskId, KillScriptConf}
import js7.agent.task.TaskRegisterActor._
import js7.base.generic.Completed
import js7.base.process.ProcessSignal
import js7.base.process.ProcessSignal.{SIGKILL, SIGTERM}
import js7.base.system.OperatingSystem.isWindows
import js7.base.time.ScalaTime._
import js7.common.scalautil.Logger
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.Deadline
import scala.util.control.NonFatal

/**
  * Delivers information about running tasks and handles the `CrashKillScript`.
  *
  * @author Joacim Zschimmer
  */
final class TaskRegisterActor private(killScriptConf: Option[KillScriptConf]) extends Actor {

  import context.dispatcher

  private val idToTask = mutable.Map[AgentTaskId, BaseAgentTask]()
  private var totalCount = 0
  private val crashKillScriptOption =
    for (conf <- killScriptConf) yield new CrashKillScript(conf.killScript, conf.crashKillScriptFile)
  private var killAllSchedule: Cancellable = null
  private var terminating = false

  override def postStop() = {
    if (killAllSchedule != null) {
      killAllSchedule.cancel()
    }
    for (o <- crashKillScriptOption)
      o.close()
    super.postStop()
  }

  def receive = {
    case o: Input => handleInput(o)
    case o: Command => handleCommand(o)
    case o: Internal => handleInternal(o)
  }

  private def handleInput(input: Input): Unit =
    input match {
      case Input.Add(task, promise) =>
        idToTask += task.id -> task
        totalCount += 1
        for (o <- crashKillScriptOption) o.add(task.id, task.pidOption)
        task.terminated onComplete { _ =>
          self ! Input.Remove(task.id)
        }
        promise.success(Completed)

      case Input.Remove(taskId) =>
        idToTask -= taskId
        for (o <- crashKillScriptOption) o.remove(taskId)
        if (idToTask.isEmpty && terminating) {
          context.stop(self)
        }
    }

  private def handleCommand(command: Command): Unit =
    command match {
      case Command.SendSignalToAllProcesses(signal) =>
        sendSignalToAllProcesses(signal)
        sender() ! Completed

      case _: Command.Terminate if terminating =>
        sender() ! Status.Failure(new IllegalStateException("TaskRegisterActor is already terminating"))

      case cmd: Command.Terminate =>
        terminating = true
        if (cmd.sigterm) {
          trySigtermProcesses()
        }
        killAllSchedule = context.system.scheduler.scheduleOnce(
          delay = cmd.sigkillProcessesDeadline.timeLeftOrZero,
          context.self, Internal.KillAll)
        sender() ! Completed

      case Command.GetOverview =>
        sender() ! TaskRegisterOverview(
          currentTaskCount = idToTask.size,
          totalTaskCount = totalCount)

      case Command.GetTaskOverviews =>
        sender() ! idToTask.values.map(_.overview).toVector

      case Command.GetTaskOverview(taskId) if idToTask contains taskId =>
        sender() ! idToTask(taskId).overview

      case Command.GetTaskOverview(taskId) =>
        sender() ! Status.Failure(new NoSuchElementException(s"Unknown task $taskId"))
    }

  private def handleInternal(internal: TaskRegisterActor.Internal) =
    internal match {
      case Internal.KillAll =>
        sendSignalToAllProcesses(SIGKILL)
    }

  private def trySigtermProcesses() =
    if (isWindows) {
      logger.debug("ShutDown: Under Windows, SIGTERM is ignored")
    } else {
      sendSignalToAllProcesses(SIGTERM)
    }

  private def sendSignalToAllProcesses(signal: ProcessSignal) =
    for (task <- idToTask.values) {
      try task.sendProcessSignal(signal)
      catch { case NonFatal(t) =>
        logger.warn(s"${task.id}: $t")
      }
    }

  override def toString = s"TaskRegisterActor(${idToTask.size} active tasks, $totalCount total)"
}

object TaskRegisterActor {
  private val logger = Logger(getClass)

  def props(killScriptConf: Option[KillScriptConf]) =
    Props { new TaskRegisterActor(killScriptConf) }
      .withDispatcher("js7.job.internal.TaskRegisterActor.mailbox")

  sealed trait Input
  object Input {
    final case class Add(task: BaseAgentTask, response: Promise[Completed]) extends Input
    final case class Remove(taskId: AgentTaskId) extends Input
  }

  sealed trait Command
  object Command {
    final case class SendSignalToAllProcesses(signal: ProcessSignal) extends Command
    final case class Terminate(sigterm: Boolean, sigkillProcessesDeadline: Deadline) extends Command
    final case object GetOverview extends Command
    final case object GetTaskOverviews extends Command
    final case class GetTaskOverview(taskId: AgentTaskId) extends Command
  }

  private sealed trait Internal
  private object Internal {
    final case object KillAll extends Internal with DeadLetterSuppression
  }
}

private[task] final class TaskRegisterActorMailbox(settings: ActorSystem.Settings, config: Config)
extends UnboundedStablePriorityMailbox(
  PriorityGenerator {
    case _: Input.Remove => 0  // Process with priority, to avoid task and process overflow
    case PoisonPill => 1
    case _: Command => 2
    case _: Input.Add => 3
    case _ => 3
  })
