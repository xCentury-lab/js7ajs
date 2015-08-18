package com.sos.scheduler.engine.agent.web

import akka.util.ByteString
import com.sos.scheduler.engine.agent.web.common.ServiceStandards
import com.sos.scheduler.engine.tunnel.data.{TunnelHandlerOverview, TunnelId, TunnelOverview, TunnelToken}
import com.sos.scheduler.engine.tunnel.web.TunnelWebService.{tunnelHandlerOverviewRoute, tunnelOverviewsRoute, tunnelRequestRoute}
import scala.collection.immutable
import scala.concurrent.Future
import spray.http.CacheDirectives.`max-age`
import spray.http.HttpHeaders.`Cache-Control`
import spray.routing.Directives._

/**
 * @author Joacim Zschimmer
 */
trait TunnelService extends ServiceStandards {

  protected def tunnelRequest(tunnelToken: TunnelToken, requestMessage: ByteString): Future[ByteString]
  protected def tunnelHandlerOverview: Future[TunnelHandlerOverview]
  protected def tunnelOverviews: Future[immutable.Iterable[TunnelOverview]]

  private implicit val executionContext = actorRefFactory.dispatcher

  addJobschedulerRoute {
    pathPrefix("agent" / "tunnels") {
      path("item" / Segment) { idString ⇒
        post {
          tunnelRequestRoute(TunnelId(idString))(tunnelRequest)
        }
      } ~
      respondWithHeader(`Cache-Control`(`max-age`(0))) {
        (pathEndOrSingleSlash & get) {
          tunnelHandlerOverviewRoute(tunnelHandlerOverview _)
        } ~
        (path("details") & get) {
          tunnelOverviewsRoute(tunnelOverviews _)
        }
      }
    }
  }
}
