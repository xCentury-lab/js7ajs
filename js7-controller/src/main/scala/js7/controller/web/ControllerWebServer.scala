package js7.controller.web

import akka.actor.ActorSystem
import cats.effect.Resource
import js7.base.auth.{AgentDirectorForwardPermission, SimpleUser, UpdateItemPermission}
import js7.cluster.ClusterNode
import js7.common.akkahttp.web.AkkaWebServer
import js7.common.akkahttp.web.auth.GateKeeper
import js7.common.akkahttp.web.data.WebServerBinding
import js7.common.akkahttp.web.session.{SessionRegister, SimpleSession}
import js7.controller.OrderApi
import js7.controller.command.ControllerCommandExecutor
import js7.controller.configuration.ControllerConfiguration
import js7.controller.item.ItemUpdater
import js7.data.controller.ControllerState
import js7.journal.watch.FileEventWatch
import monix.eval.Task
import monix.execution.Scheduler
import scala.concurrent.duration.Deadline

object ControllerWebServer
{
  def resource(
    orderApi: OrderApi,
    commandExecutor: ControllerCommandExecutor,
    itemUpdater: ItemUpdater,
    clusterNode: ClusterNode[ControllerState],
    totalRunningSince: Deadline,
    eventWatch: FileEventWatch,
    controllerConfiguration: ControllerConfiguration,
    sessionRegister: SessionRegister[SimpleSession])(
    implicit actorSystem_ : ActorSystem, scheduler: Scheduler)
  : Resource[Task, ControllerWebServer] =
    AkkaWebServer.resource(
      controllerConfiguration.webServerBindings,
      controllerConfiguration.config,
      (binding, whenShuttingDown) => new AkkaWebServer.BoundRoute {
        import controllerConfiguration.config

        private val gateKeeperConf =
          GateKeeper.Configuration.fromConfig(config, SimpleUser.apply, Seq(
            UpdateItemPermission, AgentDirectorForwardPermission))

        def startupSecurityHint(scheme: WebServerBinding.Scheme) =
          gateKeeperConf.secureStateString(scheme)

        def webServerRoute =
          Task.pure(
            new ControllerRoute(
              binding,
              whenShuttingDown,
              controllerConfiguration,
              orderApi,
              commandExecutor,
              itemUpdater,
              clusterNode,
              totalRunningSince,
              sessionRegister,
              eventWatch,
              gateKeeperConf
            ).webServerRoute)
      })
}
