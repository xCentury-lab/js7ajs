package com.sos.jobscheduler.core.event.journal

import akka.actor.{Actor, ActorRef, Terminated}
import akka.util.ByteString
import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.event.journal.SnapshotTaker._
import com.sos.jobscheduler.core.event.journal.write.ParallelExecutingPipeline
import com.typesafe.config.Config
import io.circe.Encoder
import java.time.Instant
import monix.execution.Scheduler
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * @author Joacim Zschimmer
  */
private[journal] final class SnapshotTaker(
  write: ByteString ⇒ Unit,
  journalingActors: Set[ActorRef],
  jsonEncoder: Encoder[Any],
  config: Config,
  scheduler: Scheduler)
extends Actor {

  private var remaining = mutable.Set() ++ journalingActors
  private var snapshotCount = 0
  private val pipeline = new ParallelExecutingPipeline[ByteString](write)
  private val logProgressPeriod = config.getDuration("jobscheduler.journal.snapshot.log-period").toFiniteDuration
  private val logProgressActorLimit = config.getInt("jobscheduler.journal.snapshot.log-actor-limit")
  private var logProgressCancelable = scheduler.scheduleOnce(logProgressPeriod) { self ! Internal.LogProgress }
  private val startedAt = Instant.now
  private var testLogCount = 0

  self ! Internal.Start

  override def postStop(): Unit = {
    logProgressCancelable.cancel()
    super.postStop()
  }

  def receive = {
    case Internal.Start ⇒
      if (journalingActors.isEmpty) {
        end()
      } else {
        for (a ← journalingActors) {
          context.watch(a)
          a ! JournalingActor.Input.GetSnapshot  // DeadLetter when actor just now terminates (a terminating JournalingActor must not have a snapshot)
        }
      }

    case Internal.LogProgress ⇒
      logProgressCancelable.cancel()
      val limit = remaining.size min logProgressActorLimit
      logger.info(s"Writing journal snapshot for ${(Instant.now - startedAt).pretty}, ${remaining.size} snapshot elements remaining" +
        (if (limit == remaining.size) "" else s" (showing $limit actors)") +
        ":")
      for (o ← remaining take limit) {
        logger.info(s"... awaiting snapshot element from actor ${o.path}")
      }
      logProgressCancelable = scheduler.scheduleOnce(logProgressPeriod) { self ! Internal.LogProgress }
      testLogCount += 1

    case "getTestLogCount" ⇒
      sender() ! testLogCount

    case Terminated(a) ⇒
      logger.debug(s"${a.path} terminated while taking snapshot")
      onDone(a)

    case JournalingActor.Output.GotSnapshot(snapshots) ⇒
      context.unwatch(sender())
      abortOnError {
        for (snapshot ← snapshots) {
          pipeline.blockingAdd { ByteString(jsonEncoder(snapshot).compactPrint) }   // TODO Crash with SerializationException like EventSnapshotWriter
          logger.trace(s"Stored $snapshot")  // Without sync
          snapshotCount += 1
        }
        onDone(sender())
      }
  }

  private def onDone(actor: ActorRef): Unit = {
    remaining -= actor
    if (remaining.isEmpty) {
      end()
    }
  }

  private def end(): Unit = {
    pipeline.flush()
    context.parent ! Output.Finished(Success(snapshotCount))
    context.stop(self)
  }

  private def abortOnError[A](body: ⇒ A): Unit = {
    try body
    catch {
      case NonFatal(t) ⇒
        logger.error(t.toStringWithCauses)
        context.parent ! Output.Finished(Failure(t))
        context.stop(self)
    }
  }
}

private[journal] object SnapshotTaker {
  private val logger = Logger(getClass)

  object Output {
    final case class Finished(done: Try[Int])
  }

  private object Internal {
    case object Start
    case object LogProgress
  }
}
