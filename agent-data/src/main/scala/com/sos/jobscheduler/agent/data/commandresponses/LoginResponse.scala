package com.sos.jobscheduler.agent.data.commandresponses

import com.sos.jobscheduler.data.session.SessionToken
import spray.json.DefaultJsonProtocol._

/**
  * @author Joacim Zschimmer
  */
final case class LoginResponse(sessionToken: SessionToken) extends Response

object LoginResponse {
  implicit val jsonFormat = jsonFormat1(apply)
}
