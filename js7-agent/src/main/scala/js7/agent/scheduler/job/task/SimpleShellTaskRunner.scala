package js7.agent.scheduler.job.task

import javax.inject.{Inject, Singleton}
import js7.agent.configuration.AgentConfiguration
import js7.agent.data.views.TaskOverview
import js7.agent.scheduler.job.ShellReturnValuesProvider
import js7.agent.scheduler.job.task.SimpleShellTaskRunner._
import js7.agent.task.BaseAgentTask
import js7.base.generic.Completed
import js7.base.process.ProcessSignal
import js7.base.time.ScalaTime._
import js7.base.time.Timestamp
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.SetOnce
import js7.common.scalautil.{IOExecutor, Logger}
import js7.data.job.TaskId.newGenerator
import js7.data.job.{ReturnCode, TaskId}
import js7.data.order.{OrderId, Outcome}
import js7.data.value.NamedValues
import js7.taskserver.modules.shell.RichProcessStartSynchronizer
import js7.taskserver.task.process.ShellScriptProcess.startPipedShellScript
import js7.taskserver.task.process.{ProcessConfiguration, RichProcess, StdChannels}
import monix.eval.Task
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success
import scala.util.control.NonFatal

/**
  * @author Joacim Zschimmer
  */
final class SimpleShellTaskRunner(
  conf: TaskConfiguration,
  taskId: TaskId,
  synchronizedStartProcess: RichProcessStartSynchronizer,
  agentConfiguration: AgentConfiguration)
  (implicit iox: IOExecutor, ec: ExecutionContext)
extends TaskRunner
{
  val asBaseAgentTask = new BaseAgentTask {
    def id = taskId
    def jobKey = conf.jobKey
    def pidOption = richProcessOnce.flatMap(_.pidOption)
    def terminated = terminatedPromise.future
    def overview = TaskOverview(jobKey, id, pidOption, startedAt)

    def sendProcessSignal(signal: ProcessSignal) =
      for (o <- richProcessOnce) o.sendProcessSignal(signal)

    override def toString = SimpleShellTaskRunner.this.toString
  }

  private val terminatedPromise = Promise[Completed]()
  private val startedAt = Timestamp.now
  private lazy val returnValuesProvider = new ShellReturnValuesProvider(
    agentConfiguration.temporaryDirectory,
    v1Compatible = conf.v1Compatible)
  private val richProcessOnce = SetOnce[RichProcess]
  private var killedBeforeStart = false

  def terminate: Task[Unit] =
    Task.defer {
      returnValuesProvider.deleteFile()
      richProcessOnce.toOption match {
        case Some(richProcess) =>
          Task.fromFuture(richProcess.terminated)
            .map((_: ReturnCode) => ())
        case None =>
          Task.unit
      }
    }

  def processOrder(orderId: OrderId, env: Map[String, String], stdChannels: StdChannels)
  : Task[Outcome.Completed] =
    for (returnCode <- runProcess(orderId, env, stdChannels)) yield
      conf.toOutcome(fetchReturnValuesThenDeleteFile(), returnCode)

  private def runProcess(orderId: OrderId, env: Map[String, String], stdChannels: StdChannels): Task[ReturnCode] =
    Task.deferFuture(
      for {
        richProcess <- startProcess(env, stdChannels) andThen {
          case Success(richProcess) => logger.info(s"Process '$richProcess' started for $orderId, ${conf.jobKey}: ${conf.commandLine}")
        }
        returnCode <- richProcess.terminated andThen { case tried =>
          logger.info(s"Process '$richProcess' terminated with ${tried getOrElse tried} after ${richProcess.duration.pretty}")
        }
      } yield {
        richProcess.close()
        returnCode
      })

  private def fetchReturnValuesThenDeleteFile(): NamedValues = {
    val result = returnValuesProvider.read() // TODO Catch exceptions
    // TODO When Windows locks the file, try delete it later, asynchronously, and block file in FilePool
    try returnValuesProvider.deleteFile()
    catch { case NonFatal(t) =>
      logger.error(s"Cannot delete file '$returnValuesProvider': ${t.toStringWithCauses}")
      throw t
    }
    result
  }

  private def startProcess(env: Map[String, String], stdChannels: StdChannels): Future[RichProcess] =
    if (killedBeforeStart)
      Future.failed(new RuntimeException(s"$taskId killed before start"))
    else {
      val processConfiguration = ProcessConfiguration(
        stdFileMap = Map.empty,
        encoding = AgentConfiguration.FileEncoding,
        workingDirectory = Some(agentConfiguration.jobWorkingDirectory),
        additionalEnvironment = env + returnValuesProvider.toEnv,
        maybeTaskId = Some(taskId),
        killScriptOption = agentConfiguration.killScript)
      synchronizedStartProcess {
        startPipedShellScript(conf.commandLine, processConfiguration, stdChannels)
      } andThen { case Success(richProcess) =>
        terminatedPromise.completeWith(richProcess.terminated.map(_ => Completed))
        richProcessOnce := richProcess
      }
    }

  def kill(signal: ProcessSignal): Unit =
    richProcessOnce.toOption match {
      case Some(richProcess) =>
        richProcess.sendProcessSignal(signal)
      case None =>
        terminatedPromise.tryFailure(new RuntimeException(s"$taskId killed before start"))
        killedBeforeStart = true
    }

  override def toString = s"SimpleShellTaskRunner($taskId ${conf.jobKey})"
}

object SimpleShellTaskRunner
{
  private val logger = Logger(getClass)

  @Singleton
  final class TaskIdGenerator extends Iterator[TaskId] {
    private val generator = newGenerator()
    def hasNext = generator.hasNext
    def next() = generator.next()
  }

  @Singleton
  final class Factory @Inject()(
    taskIdGenerator: TaskIdGenerator,
    synchronizedStartProcess: RichProcessStartSynchronizer,
    agentConfiguration: AgentConfiguration)
    (implicit iox: IOExecutor, ec: ExecutionContext)
  extends TaskRunner.Factory
  {
    def apply(conf: TaskConfiguration) = {
      val taskId = taskIdGenerator.next()
      new SimpleShellTaskRunner(conf, taskId, synchronizedStartProcess, agentConfiguration)
    }
  }
}
