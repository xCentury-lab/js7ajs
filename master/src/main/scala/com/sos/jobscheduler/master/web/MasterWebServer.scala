package com.sos.jobscheduler.master.web

import akka.actor.ActorSystem
import com.google.inject.Injector
import com.sos.jobscheduler.common.sprayutils.WebServerBinding
import com.sos.jobscheduler.common.sprayutils.web.SprayWebServer
import com.sos.jobscheduler.common.sprayutils.web.auth.{CSRF, GateKeeper}
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/**
  * @author Joacim Zschimmer
  */
@Singleton
final class MasterWebServer @Inject private(
  masterConfiguration: MasterConfiguration,
  gateKeeperConfiguration: GateKeeper.Configuration,
  csrf: CSRF,
  injector: Injector)
  (implicit
    protected val actorSystem: ActorSystem,
    protected val executionContext: ExecutionContext)
extends SprayWebServer with SprayWebServer.HasUri {

  protected def uriPathPrefix = ""
  protected val bindings = masterConfiguration.webServerBindings

  protected def newRouteActorRef(binding: WebServerBinding) =
    actorSystem.actorOf(
      WebServiceActor(
        new GateKeeper(gateKeeperConfiguration, csrf, isUnsecuredHttp = binding.isUnsecuredHttp),
        injector),
      name = SprayWebServer.actorName("EngineWebServer", binding))
}
