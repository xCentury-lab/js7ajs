package com.sos.jobscheduler.agent.scheduler.order

import akka.actor.{ActorContext, ActorRef, DeadLetterSuppression}
import com.sos.jobscheduler.agent.scheduler.order.StdouterrToEvent._
import com.sos.jobscheduler.base.generic.Accepted
import com.sos.jobscheduler.common.configutils.Configs.ConvertibleConfig
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.data.system.{Stderr, Stdout, StdoutOrStderr}
import com.typesafe.config.Config
import java.io.Writer
import java.time.Instant.now
import java.time.{Duration, Instant}
import monix.execution.{Cancelable, Scheduler}
import scala.concurrent.{Future, Promise}

/**
  * @author Joacim Zschimmer
  */
private[order] class StdouterrToEvent(
  orderActorContext: ActorContext,
  config: Config,
  writeEvent: (StdoutOrStderr, String) ⇒ Future[Accepted])
  (implicit scheduler: Scheduler)
{
  import orderActorContext.self

          val charBufferSize = config.getInt    ("jobscheduler.order.stdout-stderr.char-buffer-size")
  private val chunkSize = config.getInt         ("jobscheduler.order.stdout-stderr.chunk-size")
  private val delay = config.as[Duration]       ("jobscheduler.order.stdout-stderr.delay")
  private val noDelayAfter = config.as[Duration]("jobscheduler.order.stdout-stderr.no-delay-after")

  private var lastEventAt = Instant.ofEpochMilli(0)
  private var timer: Cancelable = null

  val writers = Map[StdoutOrStderr, Writer](
    Stdout → new StdWriter(Stdout, self, size = chunkSize, passThroughSize = chunkSize / 2),
    Stderr → new StdWriter(Stderr, self, size = chunkSize, passThroughSize = chunkSize / 2))

  def close(): Unit = {
    if (timer != null) {
      timer.cancel()
      timer = null
    }
    writers.values foreach (_.close())
  }

  def onBufferingStarted(): Unit =
    if (timer == null) {
      val d = if (lastEventAt + noDelayAfter < now) 0.s else delay
      timer = scheduler.scheduleOnce(d.toFiniteDuration) {
        self ! Stdouterr.FlushStdoutStderr
      }
    }

  def handle(msg: Stdouterr): Unit = msg match {
    case Stdouterr.BufferingStarted ⇒
      onBufferingStarted()

    case Stdouterr.FlushStdoutStderr ⇒
      flushStdoutAndStderr()

    case Stdouterr.StdoutStderrWritten(t, chunk, promise) ⇒
      promise.completeWith(writeEvent(t, chunk))
  }

  private def flushStdoutAndStderr(): Unit = {
    for (o ← writers.values) o.flush()
    lastEventAt = now
    timer = null
  }
}

object StdouterrToEvent {
  private class StdWriter(stdoutOrStderr: StdoutOrStderr, orderActorSelf: ActorRef, protected val size: Int, protected val passThroughSize: Int)
    (implicit protected val scheduler: Scheduler)
  extends BufferedStringWriter {

    protected def onFlush(string: String) = {
      val promise = Promise[Accepted]()
      orderActorSelf ! Stdouterr.StdoutStderrWritten(stdoutOrStderr, string, promise)
      promise.future
    }

    protected def onBufferingStarted() =
      orderActorSelf ! Stdouterr.BufferingStarted
  }


  private[order] sealed trait Stdouterr
  private object Stdouterr {
    final case object BufferingStarted extends Stdouterr
    final case class StdoutStderrWritten(typ: StdoutOrStderr, chunk: String, accepted: Promise[Accepted]) extends Stdouterr
    final case object FlushStdoutStderr extends Stdouterr with DeadLetterSuppression  // May arrive after death, due to timer
  }
}

