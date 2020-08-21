package js7.common.akkahttp

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, MediaType, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatcher.{Matched, Unmatched}
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.{ContentNegotiator, Directive0, Directive1, PathMatcher, PathMatcher0, Route, RouteResult, UnacceptedResponseContentTypeRejection, ValidationRejection}
import akka.shapeless.HNil
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.{Done, NotUsed}
import monix.eval.Task
import monix.execution.Scheduler
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.{Success, Try}

/**
  * @author Joacim Zschimmer
  */
object AkkaHttpServerUtils
{
  object implicits {
    implicit final class RichOption[A](private val delegate: Option[A]) extends AnyVal {
      def applyRoute(f: A => Route): Route =
        delegate match {
          case Some(a) => f(a)
          case None => reject
        }
    }
  }

  // Test via ConcurrentRequestsLimiterTest
  def whenResponseTerminated(onTerminated: Try[RouteResult] => Unit)(implicit ec: ExecutionContext): Directive0 =
    mapInnerRoute {
      _ andThen {
        _ transform {
          case Success(complete @ Complete(response)) =>
            Success(Complete(
              response mapEntity {
                case entity if entity.isKnownEmpty || entity.isInstanceOf[HttpEntity.Strict] =>
                  onTerminated(Success(complete))
                  entity
                case entity =>
                  entity.transformDataBytes(Flow[ByteString].watchTermination() { case (NotUsed, whenTerminated) =>
                    whenTerminated.map((_: Done) => complete).onComplete(onTerminated)
                    NotUsed
                  })
              }))
          case o =>
            onTerminated(o)
            o
        }
      }
    }

  def accept(mediaType: MediaType, mediaTypes: MediaType*): Directive0 =
    accept(mediaTypes.toSet + mediaType)

  def accept(mediaTypes: Set[MediaType]): Directive0 =
    mapInnerRoute { route =>
      headerValueByType[Accept](()) {
        case Accept(requestedMediaTypes) if requestedMediaTypes exists { o => mediaTypes exists o.matches } =>
          route
        case _ =>
          reject(UnacceptedResponseContentTypeRejection(mediaTypes collect {
            case m: MediaType.WithOpenCharset => ContentNegotiator.Alternative.MediaType(m)
            case m: MediaType.WithFixedCharset => ContentNegotiator.Alternative.ContentType(m)
          }))
      }
    }

  /**
    * Passes x iff argument is Some(x).
    */
  def passSome[A](option: Option[A]): Directive1[A] =
    new Directive1[A] {
      def tapply(inner: Tuple1[A] => Route) =
        option match {
          case Some(o) => inner(Tuple1(o))
          case None => reject
        }
    }

  def passRight[R](either: Either[String, R]): Directive1[R] =
    new Directive1[R] {
      def tapply(inner: Tuple1[R] => Route) =
        either match {
          case Right(r) => inner(Tuple1(r))
          case Left(message) => reject(ValidationRejection(message))
        }
    }

  /**
    * Passes x iff argument is true.
    */
  def passIf(condition: Boolean): Directive0 =
    mapInnerRoute { inner =>
      if (condition)
        inner
      else
        reject
    }

  def addHeader(header: HttpHeader): Directive0 =
    mapRequest(o => o.copy(headers = o.headers :+ header))

  /*
  private type ParameterMap = Map[String, String]

  private def eatParameterOption(parameterMap: ParameterMap, key: String) =
    new Directive[ParameterMap :: Option[String] :: HNil] {
      override def tapply(inner: (ParameterMap :: Option[String] :: HNil) => Route) =
        inner((parameterMap - key) :: parameterMap.get(key) :: HNil)
    }

  private def eatParameter(parameterMap: ParameterMap, key: String) =
    new Directive[ParameterMap :: String :: HNil] {
      override def tapply(inner: (ParameterMap :: String :: HNil) => Route) =
        parameterMap.get(key) match {
          case Some(value) => inner((parameterMap - key) :: value :: HNil)
          case None => reject
        }
    }

  def removeParameters(keys: Set[String]): Directive0 =
    mapRequest { request =>
      request.copy(uri = request.uri.copy(query = removeKeysFromQuery(keys, request.uri.query)))
    }

  private def removeKeysFromQuery(keys: Set[String], query: Uri.Query): Uri.Query = {
    query match {
      case Uri.Query.Empty => Uri.Query.Empty
      case q @ Uri.Query.Cons(key, value, tail, keep) =>
        if (keys contains key)
          removeKeysFromQuery(keys, tail)
        else
          Uri.Query.Cons(key, value, removeKeysFromQuery(keys, tail), keep)
      case q: Uri.Query.Raw => q
    }
  }

  def noParameters(keys: Set[String]): Directive0 =
    mapInnerRoute { inner =>
      requestUri { uri =>
        if (uri.query.isEmpty)
          inner
        else
          reject(ValidationRejection(s"Invalid parameters: ${keys mkString ", "}"))
      }
    }
*/

  def emptyParameterMap(parameterMap: Map[String, String]) =
    mapInnerRoute { route =>
      if (parameterMap.isEmpty) route
      else reject(ValidationRejection(s"Invalid parameters: ${parameterMap.keys mkString ", "}"))
    }

  //implicit def asFromStringOptionDeserializer[A](implicit stringAsA: As[String, A]) =
  //  simpleFromStringOptionDeserializer(stringAsA.apply)
  //
  //implicit val DurationFromStringOptionDeserializer: FromStringOptionDeserializer[Duration] =
  //  simpleFromStringOptionDeserializer(parseDuration)
  //
  //final def simpleFromStringOptionDeserializer[A](fromString: String => A) =
  //  new FromStringOptionDeserializer[A] {
  //    def apply(value: Option[String]): Deserialized[A] =
  //      value match {
  //        case Some(string) =>
  //          try Right(fromString(string))
  //          catch {
  //            case NonFatal(t) => Left(new MalformedContent(t.toSimplifiedString, Some(t)))
  //          }
  //        case None =>
  //          Left(ContentExpected)
  //      }
  //  }

  implicit final class RichPath(private val delegate: Uri.Path) extends AnyVal {
    import Uri.Path._

    /**
      * Matches complete segments (not characters, as `startWith`).
      */
    @tailrec
    def startsWithPath(prefix: Uri.Path): Boolean =
      (delegate, prefix) match {
        case (Slash(a), Slash(b)) => a startsWithPath b
        case (Segment(aHead, aTail), Segment(bHead, bTail)) => aHead == bHead && (aTail startsWithPath bTail)
        case _ => prefix.isEmpty
      }

    @tailrec
    def drop(n: Int): Uri.Path =
      if (n == 0)
        delegate
      else {
        require(n > 0)
        delegate.tail drop n - 1
      }
  }

  /** Like `pathPrefix`, but `prefix` denotes a path segment. */
  def pathSegment(segment: String): Directive0 = {
    pathPrefix(matchSegment(segment))
  }

  private def matchSegment(segment: String): PathMatcher0 =
    new SegmentPathMatcher(segment)

  private class SegmentPathMatcher(segment: String) extends PathMatcher0 {
    def apply(path: Uri.Path) = path match {
      case Uri.Path.Segment(`segment`, tail) =>
        Matched(tail, HNil)
      case _ =>
        Unmatched
    }
  }

  /**
    * Like `pathPrefix`, but `prefix` denotes a path of complete path segments.
    */
  def pathSegments(prefix: String): Directive0 =
    if (prefix.isEmpty)
      pass
    else
      pathSegments(Uri.Path(prefix))

  /**
    * Like `pathPrefix`, but matches complete path segments.
    */
  def pathSegments(prefix: Uri.Path): Directive0 =
    pathPrefix(matchSegments(prefix))

  /**
    * A `PathMatcher` for Directive `pathPrefix` matching complete segments.
    */
  private def matchSegments(prefix: Uri.Path): PathMatcher0 = {
    import akka.http.scaladsl.server.PathMatcher._
    if (prefix.isEmpty)
      provide(HNil)
    else
      new PathMatcher[Unit] {
        def apply(path: Uri.Path) =
          if (path startsWithPath prefix)
            Matched(path drop prefix.length, HNil)
          else
            Unmatched
        }
  }

  def completeTask[A: ToResponseMarshaller](task: Task[A])(implicit s: Scheduler): Route =
    complete(task.runToFuture)
}
