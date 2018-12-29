package com.sos.jobscheduler.master.web.serviceprovider

import akka.http.scaladsl.server.Route

/**
  * @author Joacim Zschimmer
  */
trait RouteService {
  def pathToRoute: Map[String, Route]
}
