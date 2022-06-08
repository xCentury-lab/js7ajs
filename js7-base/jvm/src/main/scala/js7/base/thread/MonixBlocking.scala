package js7.base.thread

import js7.base.thread.Futures.implicits._
import js7.base.utils.StackTraces.StackTraceThrowable
import monix.eval.Task
import monix.execution.Scheduler
import scala.collection.BuildFrom
import scala.concurrent.duration._
import scala.reflect.runtime.universe._
import scala.util.control.NonFatal

/**
  * @author Joacim Zschimmer
  */
object MonixBlocking
{
  object syntax {
    implicit class RichTask[A](private val underlying: Task[A]) extends AnyVal
    {
      def await(duration: Duration)(implicit s: Scheduler): A =
        try
          underlying
            .runSyncStep
            .fold(_.runSyncUnsafe(duration), identity)
        catch { case NonFatal(t) =>
          if (t.getStackTrace.forall(_.getClassName != getClass.getName)) {
            t.appendCurrentStackTrace
          }
          throw t
        }

      def awaitInfinite(implicit s: Scheduler): A =
        await(Duration.Inf)
    }

    implicit final class RichTaskTraversable[A, M[X] <: Iterable[X]](private val underlying: M[Task[A]]) extends AnyVal
    {
      def await(duration: FiniteDuration)(implicit s: Scheduler, cbf: BuildFrom[M[Task[A]], A, M[A]], MA: WeakTypeTag[M[A]]): M[A] =
        Task.sequence(underlying)(cbf).runToFuture await duration

      def awaitInfinite(implicit s: Scheduler, cbf: BuildFrom[M[Task[A]], A, M[A]]): M[A] =
        Task.sequence(underlying)(cbf).runToFuture.awaitInfinite
    }
  }
}
