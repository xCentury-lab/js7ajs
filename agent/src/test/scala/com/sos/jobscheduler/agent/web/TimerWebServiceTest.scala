package com.sos.jobscheduler.agent.web

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.headers.Accept
import com.sos.jobscheduler.agent.web.test.WebServiceTest
import com.sos.jobscheduler.common.scalautil.Closers.implicits.RichClosersAutoCloseable
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.{TimerOverview, TimerService, TimerServiceOverview}
import java.time.Instant
import org.scalatest.FreeSpec
import scala.collection.immutable
import spray.json.DefaultJsonProtocol._
import spray.json._

/**
  * @author Joacim Zschimmer
  */
final class TimerWebServiceTest extends FreeSpec with WebServiceTest with TimerWebService {

  protected def executionContext = system.dispatcher
  protected lazy val timerService = TimerService(Some(5.s)).closeWithCloser

  "timerService (empty)" in {
    Get("/jobscheduler/agent/api/timer") ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[TimerServiceOverview] == timerService.overview)
      assert(responseAs[JsObject] == JsObject(
        "count" → JsNumber(0),
        "completeCount" → JsNumber(0),
        "wakeCount" → JsNumber(0)
      ))
    }
  }

  "timerService/ (empty)" in {
    Get("/jobscheduler/agent/api/timer/") ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[immutable.Seq[TimerOverview]] == timerService.timerOverviews)
      assert(responseAs[JsArray] == JsArray())
    }
  }

  "timerService (3 timers)" in {
    timerService.at(Instant.parse("2111-01-01T12:11:11Z"), name = "TEST-A")
    timerService.at(Instant.parse("2222-01-02T12:22:22Z"), name = "TEST-B")
    timerService.at(Instant.parse("2333-01-03T12:33:33Z"), name = "TEST-C")
    Get("/jobscheduler/agent/api/timer") ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[TimerServiceOverview] == timerService.overview)
      assert(responseAs[JsObject] == JsObject(
        "count" → JsNumber(3),
        "completeCount" → JsNumber(0),
        "wakeCount" → JsNumber(0),
        "first" → JsObject(
          "at" → JsString("2111-01-01T12:11:11Z"),
          "name" → JsString("TEST-A")),
        "last" → JsObject(
          "at" → JsString("2333-01-03T12:33:33Z"),
          "name" → JsString("TEST-C"))))
    }
  }

  "timerService/ (3 timers)" in {
    Get("/jobscheduler/agent/api/timer/") ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[immutable.Seq[TimerOverview]] == timerService.timerOverviews)
      assert(responseAs[JsArray] == JsArray(
        JsObject(
          "at" → JsString("2111-01-01T12:11:11Z"),
          "name" → JsString("TEST-A")),
        JsObject(
          "at" → JsString("2222-01-02T12:22:22Z"),
          "name" → JsString("TEST-B")),
        JsObject(
          "at" → JsString("2333-01-03T12:33:33Z"),
          "name" → JsString("TEST-C"))))
    }
  }
}
