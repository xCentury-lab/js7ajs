package com.sos.jobscheduler.agent.scheduler.job.task

import akka.util.ByteString
import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.data.commands.AgentCommand.{StartNonApiTask, StartTask}
import com.sos.jobscheduler.agent.scheduler.job.JobConfiguration
import com.sos.jobscheduler.agent.scheduler.job.task.ModuleInstanceRunner.ModuleStepEnded
import com.sos.jobscheduler.agent.scheduler.job.task.TaskRunner._
import com.sos.jobscheduler.agent.task.{AgentTask, AgentTaskFactory}
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.process.ProcessSignal
import com.sos.jobscheduler.base.process.ProcessSignal.SIGKILL
import com.sos.jobscheduler.base.utils.ScalaUtils.cast
import com.sos.jobscheduler.common.log.LazyScalaLogger.AsLazyScalaLogger
import com.sos.jobscheduler.common.scalautil.SideEffect.ImplicitSideEffect
import com.sos.jobscheduler.common.scalautil.{Logger, SetOnce}
import com.sos.jobscheduler.common.utils.Exceptions.ignoreException
import com.sos.jobscheduler.data.order.Order
import com.sos.jobscheduler.minicom.remoting.ClientRemoting
import com.sos.jobscheduler.minicom.remoting.dialog.ClientDialogConnection
import com.sos.jobscheduler.minicom.remoting.proxy.ProxyIDispatch
import com.sos.jobscheduler.taskserver.task.RemoteModuleInstanceServer
import com.sos.jobscheduler.tunnel.server.TunnelHandle
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Joacim Zschimmer
  */
final class TaskRunner(jobConfiguration: JobConfiguration, newTask: AgentTaskFactory)
  (implicit executionContext: ExecutionContext) {

  private val taskOnce = new SetOnce[AgentTask]
  private val moduleInstanceRunnerOnce = new SetOnce[ModuleInstanceRunner]
  private var _killed = false
  private val taskId = AgentCommand.StartTask.Meta.NoCppJobSchedulerTaskId

  def processOrderAndTerminate(order: Order[Order.InProcess.type]): Future[ModuleStepEnded] = {
    processOrder(order) andThen { case _ ⇒ terminate() }
  }

  def processOrder(order: Order[Order.InProcess.type]): Future[ModuleStepEnded] = {
    if (killed)
      Future.failed(newKilledException())
    else
      for ((moduleInstanceRunner, startOk) ← startedModuleInstanceRunner();
           moduleStepEnded ←
             if (!startOk) throw new IllegalStateException("Task has refused to start (in spooler_init or spooler_open)")
             else if (killed) throw newKilledException()
             else moduleInstanceRunner.processOrder(order))
        yield moduleStepEnded
  }

  private def startedModuleInstanceRunner(): Future[(ModuleInstanceRunner, Boolean)] =
    moduleInstanceRunnerOnce.toOption match {
      case None ⇒ startModuleInstance()
      case Some(o) ⇒ Future.successful((o, true))
    }

  private def startModuleInstance(): Future[(ModuleInstanceRunner, Boolean)] = {
    val task = startTask()
    taskOnce := task
    val remoting = newRemoting(task.tunnel, name = task.id.string)
    for (moduleInstance ← createModuleInstance(remoting);
         moduleInstanceRunner = moduleInstanceRunnerOnce := new ModuleInstanceRunner(jobConfiguration, taskId, moduleInstance);
         startOk ← moduleInstanceRunner.start())
      yield
        (moduleInstanceRunner, startOk)
  }

  private def startTask(): AgentTask = {
    val command = StartNonApiTask(Some(StartTask.Meta(job = jobConfiguration.path.string, taskId)))
    newTask(command, clientIpOption = None) sideEffect {
      _.start()
    }
  }

  def kill(signal: ProcessSignal): Unit = {
    logger.debug(s"Kill $signal $toString")
    for (task ← taskOnce) {
      ignoreException(logger.asLazy.warn) {
        task.sendProcessSignal(signal)
      }
    }
    _killed |= signal == SIGKILL
  }

  private def newRemoting(tunnel: TunnelHandle, name: String): ClientRemoting =
    new ClientRemoting (
      new ClientDialogConnection with ClientDialogConnection.ImplementBlocking {
        protected implicit def executionContext = TaskRunner.this.executionContext
        def sendAndReceive(data: ByteString) =
          tunnel.request(data, timeout = None) map Some.apply
      },
      name = name)

  private def createModuleInstance(remoting: ClientRemoting): Future[ProxyIDispatch] =
    remoting.createInstance(RemoteModuleInstanceServer.clsid, RemoteModuleInstanceServer.iid) map
      cast[ProxyIDispatch]

  def terminate(): Future[Completed] = {
    val whenCompleted =
      moduleInstanceRunnerOnce.toOption match {
        case Some(moduleInstanceRunner) ⇒
          moduleInstanceRunner.terminate()
        case None ⇒
          Future.successful(Completed)
      }
    whenCompleted onComplete { _ ⇒
      for (o ← taskOnce) o.close()
    }
    whenCompleted
  }

  def killed: Boolean =
    _killed

  private def newKilledException() = new IllegalStateException("Task killed")

  override def toString =
    s"TaskRunner(${jobConfiguration.path}" +
      (taskOnce.toOption map { _.toString } getOrElse "") +
      ")"
}

object TaskRunner {
  private val logger = Logger(getClass)
}
