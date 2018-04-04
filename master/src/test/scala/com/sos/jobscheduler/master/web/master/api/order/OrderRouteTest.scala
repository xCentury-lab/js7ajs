package com.sos.jobscheduler.master.web.master.api.order

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.{Conflict, Created, OK}
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.base.utils.Collections.implicits.RichTraversable
import com.sos.jobscheduler.common.akkahttp.AkkaHttpServerUtils.pathSegments
import com.sos.jobscheduler.common.event.EventIdGenerator
import com.sos.jobscheduler.common.http.CirceJsonSupport._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.event.Stamped
import com.sos.jobscheduler.data.order.{FreshOrder, Order, OrderId, OrderOverview, OrdersOverview, Payload}
import com.sos.jobscheduler.data.workflow.{Position, WorkflowPath}
import com.sos.jobscheduler.master.OrderClient
import com.sos.jobscheduler.master.web.master.api.order.OrderRouteTest._
import org.scalatest.FreeSpec
import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * @author Joacim Zschimmer
  */
final class OrderRouteTest extends FreeSpec with ScalatestRouteTest with OrderRoute {

  protected implicit def executionContext = system.dispatcher
  private implicit val timerService = new TimerService(idleTimeout = Some(1.s))
  protected val eventIdGenerator = new EventIdGenerator
  protected val orderClient = new OrderClient {
    def executionContext = OrderRouteTest.this.executionContext
    def addOrder(order: FreshOrder) = Future { order.id != DuplicateOrderId }
    def order(orderId: OrderId) = Future.successful(TestOrders.get(orderId))
    def orders = Future.successful(eventIdGenerator.stamp(TestOrders.values.toVector))
    def orderCount = Future.successful(TestOrders.values.size)
  }

  private def route: Route =
    pathSegments("master/api/order") {
      orderRoute
    }

  // OrdersOverview
  "/master/api/order" in {
    Get("/master/api/order") ~> Accept(`application/json`) ~> route ~> check {
      assert(responseAs[OrdersOverview] == OrdersOverview(orderCount = TestOrders.size))
    }
  }

  // Seq[OrderOverview]
  for (uri ← List("/master/api/order/")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        val Stamped(_, _, orders) = responseAs[Stamped[Seq[OrderOverview]]]
        assert(status == OK && orders == (TestOrders.values.toList map OrderOverview.fromOrder))
      }
    }
  }

  // Seq[Order]
  for (uri ← List("/master/api/order/?return=Order")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        val Stamped(_, _, orders) = responseAs[Stamped[Seq[Order[Order.State]]]]
        assert(status == OK && orders == TestOrders.values.toList)
      }
    }
  }

  // Order
  for (uri ← List(
       "/master/api/order//PATH/ORDER-1",
       "/master/api/order/%2FPATH%2FORDER-1")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK && responseAs[Order[Order.State]] == TestOrders.values.head)
      }
    }
  }

  "POST new order" in {
    val order = FreshOrder(OrderId("ORDER-ID"), WorkflowPath("/WORKFLOW"), Some(Timestamp.parse("2017-03-07T12:00:00Z")), Payload(Map("KEY" → "VALUE")))
    Post(s"/master/api/order", order) ~> route ~> check {
      assert(status == Created)  // New order
    }
  }

  "POST duplicate order" in {
    val order = FreshOrder(DuplicateOrderId, WorkflowPath("/WORKFLOW"))
    Post(s"/master/api/order", order) ~> route ~> check {
      assert(status == Conflict)  // Duplicate order
    }
  }
}

object OrderRouteTest {
  private val TestWorkflowId = WorkflowPath("/WORKFLOW") % "VERSION"
  private val TestOrders: Map[OrderId, Order[Order.State]] = List(
    Order(OrderId("/PATH/ORDER-1"), TestWorkflowId, Order.Fresh.StartImmediately),
    Order(OrderId("ORDER-2"), TestWorkflowId /: Position(2), Order.Finished)
  ).toKeyedMap { _.id }
  private val DuplicateOrderId = OrderId("DUPLICATE")
}
