package js7.common.http

import js7.base.time.ScalaTime._
import js7.common.akkautils.Akkas
import js7.common.akkautils.Akkas.newActorSystem
import js7.common.http.StreamingSupport._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.Await

/**
  * @author Joacim Zschimmer
  */
final class StreamingSupportTest extends AnyFreeSpec
{
  "Observable toAkkaSource" in {
    implicit val actorSystem = newActorSystem("StreamingSupportTest")

    var closed = 0
    val observable = Observable(1, 2, 3).guarantee(Task { closed += 1 })
    assert(Await.result(observable.toAkkaSource.runFold(0)(_ + _), 9.s) == 6)
    assert(closed == 1)

    Akkas.terminateAndWait(actorSystem, 99.s)
  }
}
