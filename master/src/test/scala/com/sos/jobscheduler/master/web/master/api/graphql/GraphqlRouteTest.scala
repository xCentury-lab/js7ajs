package com.sos.jobscheduler.master.web.master.api.graphql

import akka.http.scaladsl.model.MediaTypes.{`application/json`, `text/html`, `text/plain`}
import akka.http.scaladsl.model.StatusCodes.NotAcceptable
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.RouteTestTimeout
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.util.ByteString
import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.utils.Collections.implicits.RichTraversable
import com.sos.jobscheduler.common.akkahttp.AkkaHttpServerUtils.pathSegments
import com.sos.jobscheduler.common.event.EventIdGenerator
import com.sos.jobscheduler.common.http.AkkaHttpUtils.RichHttpResponse
import com.sos.jobscheduler.common.http.CirceJsonSupport._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.filebased.FileBasedApi
import com.sos.jobscheduler.data.order.{FreshOrder, Order, OrderId}
import com.sos.jobscheduler.data.workflow.{Position, WorkflowPath}
import com.sos.jobscheduler.master.OrderApi
import com.sos.jobscheduler.master.web.master.api.graphql.GraphqlRouteTest._
import com.sos.jobscheduler.master.web.master.api.test.RouteTester
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.FreeSpec
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class GraphqlRouteTest extends FreeSpec with RouteTester with GraphqlRoute {

  private implicit val timeout = RouteTestTimeout(10.seconds)
  protected implicit def scheduler = Scheduler.Implicits.global
  protected val fileBasedApi = FileBasedApi.forTest(Map.empty)
  private val eventIdGenerator = new EventIdGenerator
  protected val orderApi = new OrderApi {
    def addOrder(order: FreshOrder) = throw new NotImplementedError
    def order(orderId: OrderId) = Task.now(TestOrders.get(orderId))
    def orders = Task.now(eventIdGenerator.stamp(TestOrders.values.toVector))
    def orderCount = Task.now(TestOrders.values.size)
  }

  private def route = Route.seal {
    pathSegments("master/api/graphql") {
      graphqlRoute
    }
  }

  "/master/api/graphql/schema" in {
    Get("/master/api/graphql/schema") ~> Accept(`text/plain`) ~> route ~> check {
      implicit val u = Unmarshaller.byteStringUnmarshaller.forContentTypes(`text/plain`)
      assert(responseAs[ByteString].utf8String contains """
        |type Order {
        |  id: OrderId!
        |
        |  "A child Order has a parent Order"
        |  parent: OrderId
        |
        |  "The Order's current WorkflowId and Position in this Workflow"
        |  workflowPosition: WorkflowPosition!
        |  workflowPath: WorkflowPath!
        |
        |  "Agent the Order is attached to"
        |  attachedTo: Order_AttachedTo
        |  state: OrderState!
        |  variables: StringMap
        |}
        |""".stripMargin)
    }
    Get("/master/api/graphql/schema") ~> Accept(`application/json`) ~> route ~> check {
      assert(status == NotAcceptable)
      assert(response.utf8StringFuture.await(99.s) contains "text/plain")
    }
  }

  "/master/api/graphql" - {
    "text/html - GraphiQL" in {
      Get("/master/api/graphql") ~> Accept(`text/html`) ~> route ~> check {
        implicit val u = Unmarshaller.byteStringUnmarshaller.forContentTypes(`text/html`)
        assert(responseAs[ByteString].utf8String contains "<title>JobScheduler Master · GraphQL</title>")
      }
    }

    "query" in {
      Get("""/master/api/graphql?query={order(id:"1"){id,workflowPath}}""") ~> Accept(`application/json`) ~> route ~> check {
        assert(responseAs[Json] ==
          json"""{
            "data": {
              "order": {
                "id": "1",
                "workflowPath": "/A-WORKFLOW"
              }
            }
          }""")
      }
    }
  }

  // More tests in MasterWebServiceTest
}

object GraphqlRouteTest {
  private val TestOrders: Map[OrderId, Order[Order.State]] =
    Vector(
      Order(OrderId("1"), (WorkflowPath("/A-WORKFLOW") % "1") /: Position(0), Order.Fresh(None))
    ).toKeyedMap(_.id)
}
