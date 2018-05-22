package com.sos.jobscheduler.master.web.master.api

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/event-stream`}
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.{Accept, `Last-Event-ID`}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.google.common.base.Ascii
import com.sos.jobscheduler.base.circeutils.CirceUtils.RichCirceString
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.base.utils.ScalaUtils.RichEither
import com.sos.jobscheduler.common.akkahttp.AkkaHttpServerUtils.pathSegments
import com.sos.jobscheduler.common.event.collector.EventCollector
import com.sos.jobscheduler.common.http.AkkaHttpUtils.RichHttpResponse
import com.sos.jobscheduler.common.http.CirceJsonSeqSupport.`application/json-seq`
import com.sos.jobscheduler.common.http.CirceJsonSupport._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.event.{EventId, EventSeq, KeyedEvent, Stamped, TearableEventSeq}
import com.sos.jobscheduler.data.order.OrderEvent.OrderAdded
import com.sos.jobscheduler.data.order.{OrderEvent, OrderId, Payload}
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.master.web.master.api.EventRouteTest._
import monix.execution.Scheduler
import org.scalatest.FreeSpec
import scala.collection.immutable.Seq
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class EventRouteTest extends FreeSpec with ScalatestRouteTest with EventRoute {

  private implicit val timeout = 9.seconds
  private implicit val timerService = new TimerService(idleTimeout = Some(1.s))
  protected val eventReader = new EventCollector.ForTest
  protected implicit def scheduler = Scheduler.global

  TestEvents foreach eventReader.addStamped

  private def route = pathSegments("event")(eventRoute)

  for (uri ← List(
    "/event?return=OrderEvent&timeout=60&after=0",
    "/event?timeout=60&after=0"))
  {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
        val EventSeq.NonEmpty(stampeds) = responseAs[TearableEventSeq[Seq, KeyedEvent[OrderEvent]]]
        assert(stampeds == TestEvents)
      }
    }
  }

  "/event application/json-seq" in {
    Get(s"/event?after=0&limit=2") ~> Accept(`application/json-seq`) ~> route ~> check {
      if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
      assert(response.entity.contentType == ContentType(`application/json-seq`))
      val rs = Ascii.RS.toChar
      val lf = Ascii.LF.toChar
      assert(response.utf8StringFuture.await(99.s) ==
        s"""$rs{"eventId":10,"timestamp":999,"key":"1","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}$lf""" +
        s"""$rs{"eventId":20,"timestamp":999,"key":"2","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}$lf""")

      //implicit val x = JsonSeqStreamSupport
      //implicit val y = CirceJsonSeqSupport
      //val stampeds = responseAs[Source[Stamped[KeyedEvent[OrderEvent]], NotUsed]]
      //  .runFold(Vector.empty[Stamped[KeyedEvent[OrderEvent]]])(_ :+ _) await 99.s
      //assert(stampeds == TestEvents)
    }
  }

  "Fetch events with repeated GET requests" - {  // Similar to FatEventRouteTest
    "/event?limit=3&after=30 continue" in {
      val stampeds = getEvents("/event?limit=3&after=30")
      assert(stampeds.head.eventId == 40)
      assert(stampeds.last.eventId == 60)
    }

    "/event?limit=3&after=60 continue" in {
      val stampeds = getEvents("/event?limit=3&after=60")
      assert(stampeds.head.eventId == 70)
      assert(stampeds.last.eventId == 90)
    }

    "/event?limit=1&after=70 rewind in last chunk" in {
      val stampeds = getEvents("/event?limit=3&after=70")
      assert(stampeds.head.eventId ==  80)
      assert(stampeds.last.eventId == 100)
    }

    "/event?limit=3&after=80 continue" in {
      val stampeds = getEvents("/event?limit=3&after=80")
      assert(stampeds.head.eventId ==  90)
      assert(stampeds.last.eventId == 110)
    }

    "/event?limit=3&after=60 rewind to oldest" in {
      val stampeds = getEvents("/event?limit=3&after=60")
      assert(stampeds.head.eventId == 70)
      assert(stampeds.last.eventId == 90)
    }

    "/event?limit=3&after=150 skip some events" in {
      val stampeds = getEvents("/event?limit=3&after=150")
      assert(stampeds.head.eventId == 160)
      assert(stampeds.last.eventId == 180)
    }

    "/event?after=180 no more events" in {
      assert(getEventSeq("/event?after=180") == EventSeq.Empty(180))
    }

    "After truncated journal snapshot" in pending  // TODO Test torn event stream
  }

  private def getEvents(uri: String): Seq[Stamped[KeyedEvent[OrderEvent]]] =
    getEventSeq(uri) match {
      case EventSeq.NonEmpty(stampeds) ⇒
        assert(stampeds.nonEmpty)
        stampeds

      case x ⇒ fail(s"Unexpected response: $x")
    }

  private def getEventSeq(uri: String): TearableEventSeq[Seq, KeyedEvent[OrderEvent]] =
    Get(uri) ~> Accept(`application/json`) ~> route ~> check {
      if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
      responseAs[TearableEventSeq[Seq, KeyedEvent[OrderEvent]]]
    }

  "Server-sent events" - {
    "/event?after=0" in {
      Get(s"/event?after=0&limit=2") ~> Accept(`text/event-stream`) ~> route ~> check {
        if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
        assert(response.entity.contentType == ContentType(`text/event-stream`))
        assert(response.utf8StringFuture.await(99.s) ==
          """data:{"eventId":10,"timestamp":999,"key":"1","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}
            |id:10
            |
            |data:{"eventId":20,"timestamp":999,"key":"2","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}
            |id:20
            |
            |""".stripMargin)
      }
    }

    "/event?after=0, retry with Last-Event-Id" in {
      Get(s"/event?after=0&limit=2") ~> Accept(`text/event-stream`) ~> `Last-Event-ID`("20") ~> route ~> check {
        if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
        assert(response.entity.contentType == ContentType(`text/event-stream`))
        assert(response.utf8StringFuture.await(99.s) ==
          """data:{"eventId":30,"timestamp":999,"key":"3","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}
            |id:30
            |
            |data:{"eventId":40,"timestamp":999,"key":"4","TYPE":"OrderAdded","workflowId":{"path":"/test","versionId":"VERSION"}}
            |id:40
            |
            |""".stripMargin)
      }
    }

    "/event?v=XXX&after=0, buildId changed" in {
      Get(s"/event?v=XXX&after=0") ~> Accept(`text/event-stream`) ~> `Last-Event-ID`("20") ~> route ~> check {
        if (status != OK) fail(s"$status - ${responseEntity.toStrict(timeout).value}")
        assert(response.entity.contentType == ContentType(`text/event-stream`))
        val string = response.utf8StringFuture.await(99.s)
        val problemJson = """{"TYPE":"Problem","message":"BUILD-CHANGED"}"""
        assert(string == s"data:$problemJson\n\n")
        assert(problemJson.parseJson.as[Problem].orThrow.toString == "BUILD-CHANGED")
      }
    }
  }
}

object EventRouteTest
{
  private val TestEvents = for (i ← 1 to 18) yield
    Stamped(EventId(10 * i), Timestamp.ofEpochMilli(999),
      OrderId(i.toString) <-: OrderAdded(WorkflowPath("/test") % "VERSION", None, Payload.empty))
}
