package com.sos.scheduler.engine.agent.web

import akka.util.ByteString
import com.google.inject.Injector
import com.sos.scheduler.engine.agent.command.{AgentCommandHandler, CommandExecutor}
import com.sos.scheduler.engine.agent.configuration.AgentConfiguration
import com.sos.scheduler.engine.agent.data.commands.Command
import com.sos.scheduler.engine.agent.task.TaskHandlerView
import com.sos.scheduler.engine.agent.views.AgentOverview
import com.sos.scheduler.engine.agent.web.WebServiceActor._
import com.sos.scheduler.engine.agent.web.common.WebService
import com.sos.scheduler.engine.agent.web.views.{CommandHandlerViewService, MainViewService, TaskHandlerViewService}
import com.sos.scheduler.engine.common.scalautil.Logger
import com.sos.scheduler.engine.common.soslicense.LicenseKey
import com.sos.scheduler.engine.tunnel.TunnelHandler
import com.sos.scheduler.engine.tunnel.data.TunnelToken
import javax.inject.{Inject, Provider}
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import spray.routing.HttpServiceActor

/**
 * @author Joacim Zschimmer
 */
// An Actor must not be a singleton!
final class WebServiceActor @Inject private(
  commandExecutor: CommandExecutor,
  tunnelHandler: TunnelHandler,
  agentOverviewProvider: Provider[AgentOverview],
  protected val taskHandlerView: TaskHandlerView,
  protected val commandHandler: AgentCommandHandler,
  webServices: immutable.Seq[WebService],
  agentConfiguration: AgentConfiguration,
  injector: Injector)
extends HttpServiceActor
with CommandService
with TunnelService
with FileStatusService
with MainViewService
with TaskHandlerViewService
with CommandHandlerViewService
{
  private lazy val addWebServices = for (o ← webServices) {
    logger.debug(s"Adding extra web service $o")
    addRawRoute(o.route)  // The route is already wrapped, so add it raw, not wrapping it again with agentStandard
  }

  def receive = {
    addWebServices
    runRoute(route)
  }

  protected def commandHandlerOverview = commandHandler
  protected def commandRunOverviews = commandHandler.commandRuns
  protected def executionContext: ExecutionContext = context.dispatcher
  protected def executeCommand(command: Command, licenseKey: Option[LicenseKey]) = commandExecutor.executeCommand(command, licenseKey)
  protected def agentOverview = agentOverviewProvider.get()
  protected def tunnelRequest(tunnelToken: TunnelToken, requestMessage: ByteString) = tunnelHandler.request(tunnelToken, requestMessage)
  protected def tunnelHandlerOverview = tunnelHandler.overview
  protected def tunnelOverviews = tunnelHandler.tunnelOverviews
  override protected def uriPathPrefix = agentConfiguration.strippedUriPathPrefix
}

object WebServiceActor {
  private val logger = Logger(getClass)
}
