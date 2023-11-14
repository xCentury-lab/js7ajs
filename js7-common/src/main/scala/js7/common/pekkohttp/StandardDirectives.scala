package js7.common.pekkohttp

import js7.base.problem.{Checked, CheckedString}
import js7.base.utils.Collections.implicits.*
import js7.data.item.VersionedItemPath
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.pekko.actor.ActorRefFactory
import org.apache.pekko.http.scaladsl.model.Uri.Path
import org.apache.pekko.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `public`, immutableDirective}
import org.apache.pekko.http.scaladsl.model.headers.{ETag, `Cache-Control`}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import org.apache.pekko.http.scaladsl.server.{Directive0, PathMatcher1, Route}
import scala.concurrent.Future

/**
  * @author Joacim Zschimmer
  */
object StandardDirectives
{
  def remainingPath[A](implicit A: CheckedString[A]): PathMatcher1[A] =
    new PathMatcher1[A] {
      def apply(path: Path) = A.checked(path.toString) match {
        case Right(a) => Matched(Path.Empty, Tuple1(a))
        case _ => Unmatched
      }
    }

  /**
    * A PathMatcher that matches a single segment or the whole remaining path,
    * treating encoded slashes (%2F) like unencoded ones.
    * "a/b" ~ "a%2Fb"
    */
  def remainingItemPath[P <: VersionedItemPath: VersionedItemPath.Companion: CheckedString]: PathMatcher1[P] =
    new PathMatcher1[P] {
      def apply(path: Path) =
        uriPathToCheckedString[P](path) match {
          case Right(itemPath) => Matched(Path.Empty, Tuple1(itemPath))
          case _ => Unmatched
        }
    }

  private def uriPathToCheckedString[P](uriPath: Path)(implicit P: CheckedString[P]): Checked[P] =
    uriPath match {
      case Path.Segment(segment, Path.Empty) =>
        P.checked(segment)  // Slashes encoded as %2F in a single path segment
      case _ =>
        P.checked(uriPath.toString)
    }

  def combineRoutes(routes: Iterable[Route]): Route =
    routes.foldFast(reject)(_ ~ _)

  def lazyRoute(lazyRoute: => Route): Route =
    ctx => Future.successful(lazyRoute(ctx)).flatten

  def routeTask(routeTask: Task[Route])(implicit s: Scheduler, arf: ActorRefFactory): Route = {
    val future = routeTask.runToFuture
    routeFuture(future)
  }

  def routeFuture(routeFuture: Future[Route])(implicit arf: ActorRefFactory): Route = {
    import arf.dispatcher /*Use Pekko's dispatcher for Routes*/
    ctx => routeFuture.flatMap(_(ctx))
  }

  private val removeEtag: Directive0 =
    mapResponse(r => r.withHeaders(r.headers filter {
      case _: ETag => false
      case _ => true
    }))

  val immutableResource: Directive0 =
    respondWithHeader(`Cache-Control`(`public`, `max-age`(365*24*3600), `immutableDirective`)) &
      removeEtag  // Assure client about immutability
}
