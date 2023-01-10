package js7.common.akkahttp

import akka.http.scaladsl.model.StatusCodes.{InternalServerError, ServiceUnavailable}
import akka.http.scaladsl.model.{HttpRequest, StatusCode}
import akka.http.scaladsl.server.Directives.{complete, extractRequest}
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import com.typesafe.config.Config
import js7.base.log.Logger
import js7.base.problem.{Problem, ProblemException}
import js7.base.utils.ScalaUtils.syntax.*
import js7.common.akkahttp.ExceptionHandling.*
import js7.common.akkahttp.StandardMarshallers.*
import scala.concurrent.Future
import scala.concurrent.duration.Deadline

/**
  * @author Joacim Zschimmer
  */
trait ExceptionHandling
{
  protected def whenShuttingDown: Future[Deadline]
  protected def config: Config

  private lazy val respondWithException = config.getBoolean("js7.web.server.verbose-error-messages")

  implicit protected final lazy val exceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case e: HttpStatusCodeException =>
        complete(e.statusCode -> e.problem)

      case e: akka.pattern.AskTimeoutException =>
        if (whenShuttingDown.isCompleted) {
          extractRequest { request =>
            webLogger.debug(toLogMessage(request, e), e.nullIfNoStackTrace)
            complete(ServiceUnavailable -> Problem.pure("Shutting down"))
          }
        } else
          completeWithError(InternalServerError, e)

      case e: ProblemException =>
        // TODO Better use Checked instead of ProblemException
        extractRequest { request =>
          for (t <- e.ifStackTrace) webLogger.debug(toLogMessage(request, e), t)
          complete(e.problem.httpStatusCode -> e.problem)
        }

      case e =>
        completeWithError(InternalServerError, e)
    }

  private def completeWithError(status: StatusCode, e: Throwable): Route =
    extractRequest { request =>
      def msg = toLogMessage(request, e)
      //if (whenShuttingDown.isCompleted) {
        webLogger.debug(msg, e)
      //} else {
      //  webLogger.warn(msg, e.nullIfNoStackTrace)
      //}
      if (respondWithException)
        complete(status -> Problem.fromThrowable(e))
      else
        complete(status)
    }

  protected final def seal(route: Route): Route =
    Route.seal(route)(exceptionHandler = exceptionHandler)
}

object ExceptionHandling
{
  val webLogger = Logger("js7.web.exception")

  private def toLogMessage(request: HttpRequest, throwable: Throwable) =
    s"Error while handling ${request.method.value} ${request.uri}: ${throwable.toStringWithCauses}"
}
