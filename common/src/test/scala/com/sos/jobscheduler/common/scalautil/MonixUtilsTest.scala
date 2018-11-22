package com.sos.jobscheduler.common.scalautil

import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.base.time.Timestamp.now
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.time.ScalaTime._
import monix.execution.{Cancelable, Scheduler}
import org.scalatest.FreeSpec
import scala.concurrent.Promise
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class MonixUtilsTest extends FreeSpec
{
  "Scheduler.scheduleFor" in {
    val scheduler = Scheduler.global
    var called = false

    var cancelable = scheduler.scheduleFor(Timestamp("2500-01-01T00:00:00Z")) { called = true }
    assert(cancelable.isInstanceOf[Cancelable.IsDummy])
    sleep(10.ms)
    cancelable.cancel()
    assert(!called)

    var p = Promise[Unit]()
    scheduler.scheduleFor(Timestamp("1500-01-01T00:00:00Z")) { p.success(()) }
    p.future await  99.s

    p = Promise[Unit]()
    cancelable = scheduler.scheduleFor(now + 10.millis) { p.success(()) }
    p.future await 99.s
  }
}
