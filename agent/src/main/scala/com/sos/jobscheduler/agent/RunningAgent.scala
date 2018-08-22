package com.sos.jobscheduler.agent

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.Uri
import com.google.common.io.Closer
import com.google.inject.Stage.PRODUCTION
import com.google.inject.{Guice, Injector, Module}
import com.sos.jobscheduler.agent.RunningAgent._
import com.sos.jobscheduler.agent.command.{CommandHandler, CommandMeta}
import com.sos.jobscheduler.agent.configuration.inject.AgentModule
import com.sos.jobscheduler.agent.configuration.{AgentConfiguration, AgentStartInformation}
import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.scheduler.AgentHandle
import com.sos.jobscheduler.agent.web.AgentWebServer
import com.sos.jobscheduler.base.auth.{SessionToken, SimpleUser, UserId}
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import com.sos.jobscheduler.common.akkahttp.web.session.{SessionRegister, SimpleSession}
import com.sos.jobscheduler.common.guice.GuiceImplicits._
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Closers.implicits.RichClosersCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.promiseFuture
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.StartUp
import java.time.Duration
import monix.eval.Task
import monix.execution.Scheduler
import org.jetbrains.annotations.TestOnly
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

/**
 * JobScheduler Agent.
 *
 * Integration test in engine-tests, for example com.sos.jobscheduler.tests.jira.js1291.JS1291AgentIT.
 *
 * @author Joacim Zschimmer
 */
final class RunningAgent private(
  val webServer: AgentWebServer,
  mainActor: ActorRef,
  val terminated: Future[Completed],
  private[agent] val commandHandler: CommandHandler,
  agentHandle: AgentHandle,
  val sessionToken: SessionToken,
  closer: Closer,
  @TestOnly val injector: Injector)
  (implicit ec: ExecutionContext)
extends AutoCloseable {

  webServer.setRunningAgent(this)

  val localUri: Uri = webServer.localUri
  //val sessionTokenHeader: HttpHeader = RawHeader(SessionToken.HeaderName, sessionToken.secret.string)

  logger.debug("Ready")

  def close() = closer.close()

  def terminate(sigkillProcessesAfter: Option[FiniteDuration] = Some(5.seconds)): Task[Completed] =
    if (terminated.isCompleted)  // Works only if previous termination has been completed
      Task.fromFuture(terminated)
    else {
      logger.debug("terminate")
      for {
        _ ← directExecuteCommand(AgentCommand.Terminate(sigtermProcesses = true, sigkillProcessesAfter = sigkillProcessesAfter))
        t ← Task.fromFuture(terminated)
      } yield t
    }

  /** Circumvents the CommandHandler which is possibly replaced by a test via DI. */
  private def directExecuteCommand(command: AgentCommand): Task[AgentCommand.Response] =
    Task.deferFuture(
      promiseFuture[AgentCommand.Response](promise ⇒
        mainActor ! MainActor.Input.ExternalCommand(UserId.Anonymous, command, promise)))

  def api(meta: CommandMeta): DirectAgentApi =
    new DirectAgentApi(commandHandler, agentHandle, meta)

  def executeCommand(command: AgentCommand, meta: CommandMeta = CommandMeta.Default): Task[Checked[AgentCommand.Response]] =
    Task.deferFuture(
      commandHandler.execute(command, meta)).materialize map Checked.fromTry
}

object RunningAgent {
  private val logger = Logger(getClass)
  private val WebServerReadyTimeout = 60.s

  def run[A](configuration: AgentConfiguration, timeout: Option[Duration] = None)(body: RunningAgent ⇒ A)(implicit ec: ExecutionContext): A =
    autoClosing(apply(configuration) await timeout) { agent ⇒
      val a = body(agent)
      agent.terminated await 99.s
      a
    }

  def startForTest(conf: AgentConfiguration)(implicit ec: ExecutionContext): Future[RunningAgent] =
    startForTest(new AgentModule(conf))

  def startForTest(module: Module)(implicit ec: ExecutionContext): Future[RunningAgent] = {
    val whenAgent = apply(module)
    for (agent ← whenAgent; t ← agent.terminated.failed) logger.error(t.toStringWithCauses, t)
    whenAgent
  }

  def apply(configuration: AgentConfiguration)(implicit ec: ExecutionContext): Future[RunningAgent] =
    apply(new AgentModule(configuration))

  def apply(module: Module)(implicit ec: ExecutionContext): Future[RunningAgent] = {
    AgentStartInformation.initialize()
    val injector = Guice.createInjector(PRODUCTION, module)
    val agentConfiguration = injector.instance[AgentConfiguration]
    StartUp.logStartUp(agentConfiguration.configDirectory, agentConfiguration.dataDirectory)

    val actorSystem = injector.instance[ActorSystem]
    val closer = injector.instance[Closer]
    val webServer = injector.instance[AgentWebServer]
    val webServerReady = webServer.start()
    val sessionRegister = injector.instance[SessionRegister[SimpleSession]]
    val readyPromise = Promise[MainActor.Ready]()
    val stoppedPromise = Promise[Completed]()
    val mainActor = actorSystem.actorOf(
      Props { new MainActor(agentConfiguration, sessionRegister, injector, readyPromise, stoppedPromise) },
      "main")
    implicit val scheduler = injector.instance[Scheduler]

    (agentConfiguration.stateDirectory / "http-uri").contentString = webServer.localHttpUri.fold(_ ⇒ "", _ + "/agent")

    val sessionTokenFile = agentConfiguration.stateDirectory / "session-token"
    val sessionToken = sessionRegister.createSystemSession(SimpleUser.System, sessionTokenFile)
      .runAsync await agentConfiguration.akkaAskTimeout.duration
    closer onClose { sessionTokenFile.delete() }

    for (ready ← readyPromise.future) yield {
      webServerReady await WebServerReadyTimeout
      val terminated = stoppedPromise.future
        .map(identity)  // Change to implicit ExecutionContext (needed?)
        .andThen { case _ ⇒
          blocking {
            logger.debug("Delaying close to let HTTP server respond open requests")
            sleep(500.ms)
          }
          //To early. closer.close()  // Close automatically after termination
        }
        //.andThen {
        //  case Failure(t) ⇒ logger.error(t.toStringWithCauses, t)
        //}
      new RunningAgent(webServer, mainActor, terminated, ready.commandHandler, ready.agentHandle, sessionToken, closer, injector)
    }
  }
}
