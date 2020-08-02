package js7.controller.web.controller.api

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{as, complete, entity, pathEnd, post, withSizeLimit}
import akka.http.scaladsl.server.Route
import io.circe.Json
import js7.base.auth.{Permission, UpdateRepoPermission, ValidUserPermission}
import js7.base.circeutils.CirceUtils._
import js7.base.crypt.SignedString
import js7.base.generic.Completed
import js7.base.monixutils.MonixBase.syntax._
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.time.ScalaTime.{RichDeadline, _}
import js7.base.time.Stopwatch.{bytesPerSecondString, itemsPerSecondString}
import js7.base.utils.ScalaUtils.syntax.{RichAny, RichThrowableEither}
import js7.base.utils.{ByteVectorToLinesObservable, FutureCompletion, IntelliJUtils, SetOnce}
import js7.common.akkahttp.CirceJsonOrYamlSupport.jsonOrYamlMarshaller
import js7.common.http.AkkaHttpUtils.ScodecByteString
import js7.common.http.StreamingSupport._
import js7.common.scalautil.Logger
import js7.controller.repo.{RepoUpdater, VerifiedUpdateRepo}
import js7.controller.web.common.{ControllerRouteProvider, EntitySizeLimitProvider}
import js7.controller.web.controller.api.RepoRoute.{ExitStreamException, _}
import js7.data.crypt.FileBasedVerifier.Verified
import js7.data.filebased.{FileBased, TypedPath, UpdateRepoOperation, VersionId}
import monix.eval.Task
import monix.execution.Scheduler
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
          withSizeLimit(entitySizeLimit)/*call before entity*/(
            entity(as[HttpEntity]) { httpEntity =>
              // TODO eat observable even in case if error
              val startedAt = now
              complete {
                var byteCount = 0L
                val versionId = SetOnce[VersionId]
                val addOrReplace = mutable.Buffer[Verified[FileBased]]()
                val delete = mutable.Buffer[TypedPath]()
                httpEntity
                  .dataBytes
                  .toObservable
                  .map(_.toByteVector)
                  .pipeIf(logger.underlying.isDebugEnabled, _.map { o => byteCount += o.size; o })
                  .flatMap(new ByteVectorToLinesObservable)
                  .mapParallelUnorderedBatch(batchSize = 200)(_
                    .decodeUtf8.orThrow
                    .parseJsonCheckedAs[UpdateRepoOperation].orThrow
                    match {
                      case UpdateRepoOperation.AddOrReplace(signedJson) =>
                        verify(signedJson).fold(problem => throw ExitStreamException(problem), identity)
                      case o => o
                    })
                  .foreachL {
                    case UpdateRepoOperation.Delete(path) =>
                      delete += path
                    case verifiedFileBased: Verified[FileBased] @unchecked =>
                      addOrReplace += verifiedFileBased
                    case UpdateRepoOperation.AddVersion(v) =>
                      versionId := v  // throws
                  }
                  .doOnFinish(_ => Task {
                    val d = startedAt.elapsed
                    if (d > 1.s) logger.debug(s"controller/api/repo received and verified: " +
                      itemsPerSecondString(d, delete.size + addOrReplace.size, "items") + " · " +
                      bytesPerSecondString(d, byteCount))
                  })
                  .flatMap(_ =>
                    repoUpdater.updateRepo(VerifiedUpdateRepo(
                      versionId.getOrElse(throw ExitStreamException(Problem(s"Missing VersionId in stream"))),
                      addOrReplace.toSeq,
                      delete.toSeq)))
                  .onErrorRecover { case ExitStreamException(problem) => Left(problem) }
                  .doOnFinish(_ => Task(if (startedAt.elapsed > 1.s) logger.debug("controller/api/repo totally: " +
                    itemsPerSecondString(startedAt.elapsed, delete.size + addOrReplace.size, "items"))))
                  .map[ToResponseMarshallable](_.map((_: Completed) =>
                    OK -> emptyJsonObject))
                  .runToFuture
              }
            })
        }
      }
    }

  private def verify(signedString: SignedString): Checked[Verified[FileBased]] =
    for (verified <- repoUpdater.fileBasedVerifier.verify(signedString)) yield {
      logger.info(Logger.Signature, verified.toString)
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
