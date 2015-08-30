package com.sos.scheduler.engine.agent

import com.google.common.io.Closer
import com.google.inject.Stage.PRODUCTION
import com.google.inject.{Guice, Module}
import com.sos.scheduler.engine.agent.command.CommandExecutor
import com.sos.scheduler.engine.agent.configuration.AgentConfiguration
import com.sos.scheduler.engine.agent.configuration.inject.AgentModule
import com.sos.scheduler.engine.agent.data.commandresponses.Response
import com.sos.scheduler.engine.agent.data.commands.Command
import com.sos.scheduler.engine.agent.task.TaskHandler
import com.sos.scheduler.engine.agent.views.AgentStartInformation
import com.sos.scheduler.engine.agent.web.AgentWebServer
import com.sos.scheduler.engine.common.guice.GuiceImplicits._
import com.sos.scheduler.engine.common.scalautil.Futures.awaitResult
import com.sos.scheduler.engine.common.time.ScalaTime.MaxDuration
import com.sos.scheduler.engine.common.utils.FreeTcpPortFinder._
import scala.concurrent.Future

/**
 * JobScheduler Agent.
 *
 * Integration test in engine-tests, for example com.sos.scheduler.engine.tests.jira.js1291.JS1291AgentIT.
 *
 * @author Joacim Zschimmer
 */
final class Agent(module: Module) extends AutoCloseable {

  def this(configuration: AgentConfiguration) = {
    this(new AgentModule(configuration))
  }

  private val injector = Guice.createInjector(PRODUCTION, module)
  val configuration = injector.instance[AgentConfiguration]
  val localUri = s"http://127.0.0.1:${configuration.httpPort}/${configuration.strippedUriPathPrefix}"
  private val server = injector.instance[AgentWebServer]
  private val closer = injector.instance[Closer]
  private val taskHandler = injector.instance[TaskHandler]
  private val commandExecutor = injector.instance[CommandExecutor]

  AgentStartInformation.initialize()

  def start(): Future[Unit] = server.start()

  def close(): Unit = closer.close()

  def run(): Unit = {
    start()
    awaitResult(terminated, MaxDuration)
  }

  def executeCommand(command: Command): Future[Response] = commandExecutor.executeCommand(command)

  def terminated: Future[Unit] = taskHandler.terminated
}

object Agent {
  def forTest(): Agent = forTest(httpPort = findRandomFreeTcpPort())

  def forTest(httpPort: Int): Agent = new Agent(AgentConfiguration.forTest(httpPort))
}
