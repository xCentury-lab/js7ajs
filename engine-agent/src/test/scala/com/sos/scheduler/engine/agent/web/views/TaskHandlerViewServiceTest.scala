package com.sos.scheduler.engine.agent.web.views

import akka.actor.ActorSystem
import com.sos.scheduler.engine.agent.data.AgentTaskId
import com.sos.scheduler.engine.agent.data.views.{TaskHandlerView, TaskOverview}
import com.sos.scheduler.engine.common.sprayutils.JsObjectMarshallers._
import com.sos.scheduler.engine.data.job.TaskId
import com.sos.scheduler.engine.tunnel.data.TunnelId
import java.time.Instant
import org.junit.runner.RunWith
import org.scalatest.FreeSpec
import org.scalatest.junit.JUnitRunner
import spray.http.HttpHeaders.Accept
import spray.http.MediaTypes.`application/json`
import spray.http.Uri
import spray.json._
import spray.testkit.ScalatestRouteTest

/**
 * @author Joacim Zschimmer
 */
@RunWith(classOf[JUnitRunner])
final class TaskHandlerViewServiceTest extends FreeSpec with ScalatestRouteTest with TaskHandlerViewService {

  protected implicit lazy val actorRefFactory = ActorSystem()

  protected def taskHandlerView = TaskHandlerView(
    isTerminating = false,
    currentTaskCount = 777,
    totalTaskCount = 999,
    tasks = List(TaskOverview(
      AgentTaskId("1-123"),
      TunnelId("99"),
      masterAddress = "127.0.0.1:999999999",
      Instant.parse("2015-06-10T12:00:00Z"),
      taskArguments = Map("testName" → "testValue"))))
  }

  "task" in {
    Get(Uri("/jobscheduler/agent/api/task")) ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[JsObject] == JsObject(
        "currentTaskCount" → JsNumber(777),
        "totalTaskCount" -> JsNumber(999),
        "isTerminating" → JsFalse,
        "tasks" → JsArray(
          JsObject(
            "id" → JsString("1-123"),
            "tunnelId" → JsString("99"),
            "masterAddress" → JsString("127.0.0.1:999999999"),
            "startedAt" → JsString("2015-06-10T12:00:00Z"),
            "taskArguments" → JsObject(
              "testName" → JsString("testValue"))))))
    }
  }
}
