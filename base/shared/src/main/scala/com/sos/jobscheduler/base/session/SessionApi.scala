package com.sos.jobscheduler.base.session

import com.sos.jobscheduler.base.auth.UserAndPassword
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import monix.eval.Task
import scala.concurrent.duration.FiniteDuration

// Test in SessionRouteTest

/**
  * @author Joacim Zschimmer
  */
trait SessionApi
{
  def login(userAndPassword: Option[UserAndPassword], onlyIfNotLoggedIn: Boolean = false): Task[Completed]

  def logout(): Task[Completed]
}

object SessionApi
{
  trait LoginUntilReachable extends SessionApi
  {
    protected def isTemporaryLoginError(throwable: Throwable): Boolean

    def hasSession: Boolean

    final def loginUntilReachable(userAndPassword: Option[UserAndPassword], delays: Iterator[FiniteDuration],
      onError: Throwable => Unit = logThrowable, onlyIfNotLoggedIn: Boolean = false)
    : Task[Completed] =
      Task.defer {
        if (onlyIfNotLoggedIn && hasSession)
          Task.pure(Completed)
        else
          login(userAndPassword)
            .onErrorRestartLoop(()) { (throwable, _, retry) =>
              onError(throwable)
              if (isTemporaryLoginError(throwable) && delays.hasNext)
                retry(()) delayExecution delays.next()
              else
                Task.raiseError(throwable)
            }
      }

    protected def logThrowable(throwable: Throwable): Unit = {
      scribe.error(s"$toString: ${throwable.toStringWithCauses}")
      scribe.debug(s"$toString: ${throwable.toString}", throwable)
    }
  }
}
