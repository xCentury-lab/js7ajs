package com.sos.jobscheduler.agent.web

import akka.actor.ActorSystem
import com.sos.jobscheduler.agent.Agent
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Futures._
import com.sos.jobscheduler.common.time.ScalaTime._
import org.scalatest.FreeSpec
import spray.client.pipelining._
import spray.http.HttpResponse
import spray.http.StatusCodes.NotFound
import spray.httpx.UnsuccessfulResponseException

/**
 * JS-1509 404 rejection when accessing Universal Agent as Classic Agent.
 *
 * @author Joacim Zschimmer
 */
final class NoJobSchedulerEngineServiceIT extends FreeSpec {

  "Access as Classic Agent is rejected with special message" in {
    implicit lazy val actorSystem = ActorSystem(getClass.getSimpleName)
    import actorSystem.dispatcher
    autoClosing(Agent.forTest()) { agent ⇒
      awaitResult(agent.start(), 5.s)
      val responseFuture = (sendReceive ~> unmarshal[HttpResponse]).apply(Post(s"${agent.localUri}/jobscheduler/engine/command", <TEST/>))
      val e = intercept[UnsuccessfulResponseException] { awaitResult(responseFuture, 5.s) }
      assert(e.response.status == NotFound)
      assert(e.response.entity.asString contains "Classic Agent")
    }
    actorSystem.shutdown()
  }
}
