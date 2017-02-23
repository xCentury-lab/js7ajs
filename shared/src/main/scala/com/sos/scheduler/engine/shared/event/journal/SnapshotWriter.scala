package com.sos.scheduler.engine.shared.event.journal

import akka.actor.{Actor, ActorRef, Terminated}
import com.sos.scheduler.engine.base.utils.ScalaUtils.RichThrowable
import com.sos.scheduler.engine.common.scalautil.Logger
import com.sos.scheduler.engine.shared.event.journal.SnapshotWriter._
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import spray.json._

/**
  * @author Joacim Zschimmer
  */
final class SnapshotWriter(write: JsValue ⇒ Unit, journalingActors: immutable.Iterable[ActorRef], jsonFormat: JsonFormat[Any]) extends Actor {

  private var remaining = journalingActors.size
  private var snapshotCount = 0

  self ! Internal.Start

  def receive = {
    case Internal.Start ⇒
      if (journalingActors.isEmpty) {
        end()
      } else {
        for (a ← journalingActors) {
          context.watch(a)
          logger.trace(s"Get snapshots from ${a.path}")
          a ! JournalingActor.Input.GetSnapshot
        }
      }

    case Terminated(a) ⇒
      logger.debug(s"${a.path} terminated while taking snapshot")
      countDown()

    case JournalingActor.Output.GotSnapshot(snapshots) ⇒
      context.unwatch(sender())
      abortOnError {
        for (snapshot ← snapshots) {
          write(jsonFormat.write(snapshot))
          logger.debug(s"Stored $snapshot")  // Without sync
          snapshotCount += 1
        }
        countDown()
      }
  }

  private def countDown(): Unit = {
    remaining -= 1
    if (remaining == 0) {
      end()
    }
  }

  private def end(): Unit = {
    // Versiegeln ???
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

object SnapshotWriter {
  private val logger = Logger(getClass)

  object Output {
    final case class Finished(done: Try[Int])
  }

  private object Internal {
    final case object Start
  }
}
