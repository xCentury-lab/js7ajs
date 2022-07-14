package js7.common.akkautils

import akka.actor.SupervisorStrategy.{Decider, Stop}
import akka.actor.{ActorContext, ActorRef, ChildRestartStats, SupervisorStrategy}
import js7.base.log.Logger
import js7.base.utils.ScalaUtils.syntax.*
import js7.common.akkautils.Akkas.*
import js7.common.akkautils.CatchingSupervisorStrategy.*
import scala.concurrent.Promise

/**
  * @author Joacim Zschimmer
  */
trait CatchingSupervisorStrategy[A] extends SupervisorStrategy {

  protected def promise: Promise[A]

  abstract override def processFailure(context: ActorContext, restart: Boolean, child: ActorRef, throwable: Throwable,
    stats: ChildRestartStats, children: Iterable[ChildRestartStats]): Unit
  = {
    if (!restart) { // That means SupervisorStrategy.Stop
      if (!promise.tryFailure(new ActorTerminatedException(s"Actor '${child.path.pretty}' terminated due to error: ${throwable.toStringWithCauses}", throwable))) {
        logger.warn(s"promise.tryFailure failed: $throwable", throwable)
      }
    }
    super.processFailure(context, restart, child, throwable, stats, children)
  }
}

object CatchingSupervisorStrategy {
  private val logger = Logger(getClass)
  val StoppingDecider: Decider = {
    case _ => Stop
  }
  def defaultDecider = StoppingDecider

  def apply[A](promise: Promise[A], loggingEnabled: Boolean = true, decider: Decider = defaultDecider) = {
    val p = promise
    new LoggingOneForOneStrategy(loggingEnabled = loggingEnabled)(decider)
    with CatchingSupervisorStrategy[A] {
      protected final val promise = p
    }
  }

  final class ActorTerminatedException(message: String, cause: Throwable)
  extends RuntimeException(message, cause)
}
