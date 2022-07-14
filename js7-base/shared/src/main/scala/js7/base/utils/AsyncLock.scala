package js7.base.utils

import js7.base.monixutils.MonixBase.DefaultWorryDurations
import js7.base.monixutils.MonixBase.syntax.*
import js7.base.time.ScalaTime.*
import js7.base.utils.AsyncLock.*
import monix.catnap.MVar
import monix.eval.Task
import scala.concurrent.duration.Deadline.now
import scala.concurrent.duration.FiniteDuration

final class AsyncLock private(
  name: String,
  warnTimeouts: IterableOnce[FiniteDuration],
  suppressLog: Boolean)
{
  private val lockM = MVar[Task].empty[() => String]().memoize
  private val log = if (suppressLog) ScribeUtils.emptyLogger else logger

  def lock[A](task: Task[A])(implicit src: sourcecode.Enclosing): Task[A] =
    lock(src.value)(task)

  def lock[A](acquirer: => String)(task: Task[A]): Task[A] = {
    lazy val acq = acquirer
    lock2(() => acq)(task)
  }

  private def lock2[A](acquirer: () => String)(task: Task[A]): Task[A] =
    acquire(acquirer).bracket(_ => task)(_ => release(acquirer))
      // Because cancel() is asynchronous, the use part may continue even though
      // the lock is released (?). Better we make the whole operation uncancelable.
      // TODO Make cancelable ?
      .uncancelable

  private def acquire(acquirer: () => String): Task[Unit] =
    lockM
      .flatMap(mvar =>
        mvar.tryPut(acquirer).flatMap(hasAcquired =>
          if (hasAcquired) {
            log.trace(s"$name acquired by ${acquirer()}")
            Task.unit
          } else {
            val since = now
            Task.tailRecM(())(_ =>
              if (suppressLog)
                mvar.put(acquirer).as(Right(()))
              else
                mvar.tryRead.flatMap {
                  case Some(lockedBy) =>
                    log.debug(s"↘ $name enqueues ${acquirer()} (currently locked by ${lockedBy()}) ...")
                    mvar.put(acquirer)
                      .whenItTakesLonger(warnTimeouts)(duration =>
                        for (lockedBy <- mvar.tryRead) yield logger.info(
                          s"$name: ⏳ ${acquirer()} is still waiting" +
                            s" (currently locked by ${lockedBy.getOrElse("None")})" +
                            s" for ${duration.pretty} ..."))
                      .map { _ =>
                        log.debug(s"↙ $name acquired by ${acquirer()} after ${since.elapsed.pretty}")
                        Right(())
                    }
                  case None =>  // Lock has just become available
                    for (hasAcquired <- mvar.tryPut(acquirer)) yield {
                      if (!hasAcquired)
                        Left(())  // Locked again by someone else, so try again
                      else {
                        log.trace(s"$name acquired by ${acquirer()}")
                        Right(())  // The lock is ours!
                      }
                    }
                })
          }))

  private def release(acquirer: () => String): Task[Unit] =
    Task.defer {
      log.trace(s"$name released by ${acquirer()}")
      lockM.flatMap(_.take).void
    }

  override def toString = s"AsyncLock:$name"
}

object AsyncLock
{
  private val logger = scribe.Logger[this.type]

  def apply()(implicit enclosing: sourcecode.Enclosing): AsyncLock =
    apply(name = enclosing.value)

  def apply(
    name: String,
    logWorryDurations: IterableOnce[FiniteDuration] = DefaultWorryDurations,
    suppressLog: Boolean = false)
  =
    new AsyncLock(name, logWorryDurations, suppressLog)
}
