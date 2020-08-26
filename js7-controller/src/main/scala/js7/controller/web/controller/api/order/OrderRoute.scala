package js7.controller.web.controller.api.order

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.{Conflict, Created, NotFound, OK}
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive, Route}
import cats.Monad.ops.toAllMonadOps
import io.circe.Json
import js7.base.auth.ValidUserPermission
import js7.base.circeutils.CirceUtils._
import js7.base.data.ByteSequence.ops._
import js7.base.generic.Completed
import js7.base.monixutils.MonixBase.syntax.RichMonixObservable
import js7.base.problem.Checked._
import js7.base.problem.Problem
import js7.base.time.ScalaTime._
import js7.base.time.Stopwatch.{bytesPerSecondString, itemsPerSecondString}
import js7.base.utils.ByteVectorToLinesObservable
import js7.base.utils.ScalaUtils.syntax.RichAny
import js7.base.utils.ScodecUtils.syntax._
import js7.common.akkahttp.AkkaHttpServerUtils.completeTask
import js7.common.akkahttp.CirceJsonOrYamlSupport.{jsonOrYamlMarshaller, jsonUnmarshaller}
import js7.common.akkahttp.StandardMarshallers._
import js7.common.http.AkkaHttpUtils.ScodecByteString
import js7.common.http.JsonStreamingSupport.`application/x-ndjson`
import js7.common.http.StreamingSupport._
import js7.common.scalautil.Logger
import js7.controller.OrderApi
import js7.controller.web.common.{ControllerRouteProvider, EntitySizeLimitProvider}
import js7.controller.web.controller.api.order.OrderRoute._
import js7.data.order.{FreshOrder, OrderId}
import monix.eval.Task
import monix.execution.Scheduler
import scala.concurrent.duration.Deadline.now

/**
  * @author Joacim Zschimmer
  */
trait OrderRoute
extends ControllerRouteProvider with EntitySizeLimitProvider
{
  protected def orderApi: OrderApi.WithCommands
  protected def actorSystem: ActorSystem

  private implicit def implicitScheduler: Scheduler = scheduler
  private implicit def implicitActorsystem = actorSystem

  final lazy val orderRoute: Route =
    authorizedUser(ValidUserPermission) { _ =>
      post {
        pathEnd {
          withSizeLimit(entitySizeLimit)/*call before entity*/(
            entity(as[HttpEntity])(httpEntity =>
              if (httpEntity.contentType == `application/x-ndjson`.toContentType)
                completeTask {
                  val startedAt = now
                  var byteCount = 0L
                  httpEntity
                    .dataBytes
                    .toObservable  // TODO eat observable even in case if error
                    .map(_.toByteVector)
                    .pipeIf(logger.underlying.isDebugEnabled, _.map { o => byteCount += o.size; o })
                    .flatMap(new ByteVectorToLinesObservable)
                    .mapParallelOrderedBatch()(_
                      .parseJsonAs[FreshOrder].orThrow)
                    .toL(Vector)
                    .flatTap(orders => Task {
                      val d = startedAt.elapsed
                      if (d > 1.s) logger.debug(s"post controller/api/order received - " +
                        itemsPerSecondString(d, orders.size, "orders") + " · " +
                        bytesPerSecondString(d, byteCount))
                    })
                    .flatMap(orderApi.addOrders)
                    .map[ToResponseMarshallable](_.map((_: Completed) =>
                      OK -> emptyJsonObject))
                }
              else
                entity(as[Json]) { json =>
                  if (json.isArray)
                    json.as[Seq[FreshOrder]] match {
                      case Left(failure) => complete(failure.toProblem)
                      case Right(orders) =>
                        completeTask(
                          orderApi.addOrders(orders)
                            .map[ToResponseMarshallable](_.map((_: Completed) => OK -> emptyJsonObject)))
                    }
                  else
                    json.as[FreshOrder] match {
                      case Left(failure) => complete(failure.toProblem)
                      case Right(order) =>
                        extractUri { uri =>
                          onSuccess(orderApi.addOrder(order).runToFuture) {
                            case Left(problem) => complete(problem)
                            case Right(isNoDuplicate) =>
                              respondWithHeader(Location(uri.withPath(uri.path / order.id.string))) {
                                complete(
                                  if (isNoDuplicate) Created -> emptyJsonObject
                                  else Conflict -> Problem.pure(s"Order '${order.id.string}' has already been added"))
                              }
                          }
                        }
                    }
                }))
        }
      } ~
      get {
        pathEnd {
          parameter("return".?) {
            case None =>
              complete(orderApi.ordersOverview.runToFuture)  // TODO Should be streamed
            case _ =>
              complete(Problem.pure("Parameter return is not supported here"))
          }
        } ~
        pathSingleSlash {
          parameter("return".?) {
            case None =>
              complete(orderApi.orderIds.runToFuture)  // TODO Should be streamed
            case Some("Order") =>
              complete(orderApi.orders.runToFuture)   // TODO Should be streamed
            case Some(unrecognized) =>
              complete(Problem.pure(s"Unrecognized return=$unrecognized"))
          }
        } ~
        matchOrderId { orderId =>
          singleOrder(orderId)
        }
      }
    }

  private def singleOrder(orderId: OrderId): Route =
    completeTask(
      orderApi.order(orderId).map(_.map {
        case Some(o) =>
          o: ToResponseMarshallable
        case None =>
          Problem.pure(s"Does not exist: $orderId"): ToResponseMarshallable
      }))
}

object OrderRoute
{
  private val emptyJsonObject = Json.obj()
  private val logger = Logger(getClass)

  private val matchOrderId = new Directive[Tuple1[OrderId]] {
    def tapply(inner: Tuple1[OrderId] => Route) =
      path(Segment) { orderIdString =>
        inner(Tuple1(OrderId(orderIdString)))
      } ~
      extractUnmatchedPath {
        case Uri.Path.Slash(tail) if !tail.isEmpty =>
          inner(Tuple1(OrderId(tail.toString)))  // Slashes not escaped
        case _ =>
          complete(NotFound)  // Invalid OrderId syntax
      }
  }
}
