package com.sos.jobscheduler.data.session

import com.sos.jobscheduler.base.generic.SecretString
import spray.json.DefaultJsonProtocol._

/**
  * @author Joacim Zschimmer
  */
final case class SessionToken(secret: SecretString) {
  override def toString = "SessionToken"
}

object SessionToken {
  private implicit val x = SecretString.implicits.jsonFormat
  implicit val jsonFormat = jsonFormat1(apply)
  val HeaderName = "X-JobScheduler-Session"
}
