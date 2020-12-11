package js7.controller.web.controller.api

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.http.scaladsl.server.Directives.{as, complete, entity, pathEnd, post, withSizeLimit}
import akka.http.scaladsl.server.Route
import cats.syntax.flatMap._
import io.circe.Json
import js7.base.auth.{Permission, UpdateRepoPermission, ValidUserPermission}
import js7.base.crypt.SignedString
import js7.base.generic.Completed
import js7.base.monixutils.MonixBase.syntax._
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.time.ScalaTime.{RichDeadline, _}
import js7.base.time.Stopwatch.{bytesPerSecondString, itemsPerSecondString}
import js7.base.utils.ScalaUtils.syntax.RichAny
import js7.base.utils.{ByteArrayToLinesObservable, FutureCompletion, IntelliJUtils, SetOnce}
import js7.common.akkahttp.CirceJsonOrYamlSupport.jsonOrYamlMarshaller
import js7.common.akkautils.ByteStrings.syntax._
import js7.common.http.StreamingSupport._
import js7.common.scalautil.Logger
import js7.controller.repo.{RepoUpdater, VerifiedUpdateRepo}
import js7.controller.web.common.ControllerRouteProvider
import js7.controller.web.controller.api.RepoRoute.{ExitStreamException, _}
import js7.core.web.EntitySizeLimitProvider
import js7.data.crypt.VersionedItemVerifier.Verified
import js7.data.item.{ItemPath, UpdateRepoOperation, VersionId, VersionedItem}
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicAny
import scala.collection.mutable
import scala.concurrent.duration.Deadline.now

trait RepoRoute
extends ControllerRouteProvider with EntitySizeLimitProvider
{
  protected def actorSystem: ActorSystem

  protected def repoUpdater: RepoUpdater

  private implicit def implicitScheduler: Scheduler = scheduler
  private implicit def implicitActorsystem = actorSystem

  // TODO Abort POST with error when shutting down
  private lazy val whenShuttingDownCompletion = new FutureCompletion(whenShuttingDown)

  final lazy val repoRoute: Route =
    post {
      pathEnd {
        authorizedUser(Set[Permission](ValidUserPermission, UpdateRepoPermission)) { user =>
          withSizeLimit(entitySizeLimit) (
            entity(as[HttpEntity]) { httpEntity =>
              complete {
                val startedAt = now
                var byteCount = 0L
                val versionId = SetOnce[VersionId]
                val addOrReplace = Vector.newBuilder[Verified[VersionedItem]]
                val delete = mutable.Buffer[ItemPath]()
                val problemOccurred = AtomicAny[Problem](null)
                httpEntity
                  .dataBytes
                  .toObservable
                  .map(_.toByteArray)
                  .pipeIf(logger.underlying.isDebugEnabled)(_.map { o => byteCount += o.length; o })
                  .flatMap(new ByteArrayToLinesObservable)
                  .mapParallelUnorderedBatch()(_
                    .parseJsonAs[UpdateRepoOperation].orThrow match {
                      case UpdateRepoOperation.AddOrReplace(signedJson) =>
                        problemOccurred.get() match {
                          case null =>
                            val checked = verify(signedJson)
                            for (problem <- checked.left) {
                              // Delay error until input stream is completely eaten
                              problemOccurred := problem
                            }
                            checked
                          case problem =>
                            // After error, eat input stream
                            Left(problem)
                        }
                      case o => o
                    })
                  .foreachL {
                    case UpdateRepoOperation.Delete(path) =>
                      delete += path
                    case Right(verifiedItem: Verified[VersionedItem] @unchecked) =>
                      addOrReplace += verifiedItem
                    case Left(_) =>
                    case UpdateRepoOperation.AddVersion(v) =>
                      versionId := v  // throws
                  }
                  .flatTap(_ => problemOccurred.get() match {
                    case null => Task.unit
                    case problem => Task.raiseError(ExitStreamException(problem))
                  })
                  .map(_ => addOrReplace.result() -> delete.toSeq)
                  .map { case (addOrReplace, delete) =>
                    val d = startedAt.elapsed
                    if (d > 1.s) logger.debug(s"post controller/api/repo received and verified - " +
                      itemsPerSecondString(d, delete.size + addOrReplace.size, "items") + " · " +
                      bytesPerSecondString(d, byteCount))
                      addOrReplace -> delete
                  }
                  .flatMap { case (addOrReplace, delete) =>
                    repoUpdater
                      .updateRepo(VerifiedUpdateRepo(
                        versionId.getOrElse(throw ExitStreamException(Problem(s"Missing VersionId in stream"))),
                        addOrReplace,
                        delete))
                      .map { o =>
                        if (startedAt.elapsed > 1.s) logger.debug("post controller/api/repo totally: " +
                          itemsPerSecondString(startedAt.elapsed, delete.size + addOrReplace.size, "items"))
                        o
                      }
                  }
                  .onErrorRecover { case ExitStreamException(problem) => Left(problem) }
                  .map[ToResponseMarshallable] {
                    case Left(problem) =>
                      logger.debug(problem.toString)
                      BadRequest -> problem
                    case Right(Completed) =>
                      OK -> emptyJsonObject
                  }
                  .runToFuture
              }
            })
        }
      }
    }

  private def verify(signedString: SignedString): Checked[Verified[VersionedItem]] = {
    val verified = repoUpdater.itemVerifier.verify(signedString)
    verified match {
      case Left(problem) => logger.warn(problem.toString)
      case Right(verified) => logger.info(Logger.SignatureVerified, verified.toString)
    }
    verified
  }
}

object RepoRoute
{
  private val logger = Logger(getClass)
  private val emptyJsonObject = Json.obj()

  private case class ExitStreamException(problem: Problem) extends Exception

  IntelliJUtils.intelliJuseImport(jsonOrYamlMarshaller[UpdateRepoOperation])
}
