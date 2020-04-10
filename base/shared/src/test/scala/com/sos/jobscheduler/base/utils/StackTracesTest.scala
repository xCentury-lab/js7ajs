package com.sos.jobscheduler.base.utils

import com.sos.jobscheduler.base.utils.StackTraces._
import com.sos.jobscheduler.base.utils.StackTracesTest._
import scala.util.Try
import org.scalatest.freespec.AnyFreeSpec

/**
 * @author Joacim Zschimmer
 */
final class StackTracesTest extends AnyFreeSpec {

  "withThisSackTrace extends possible failures stack strace with own stack trace" in {
    val t = Try[Unit] { throw new TestException }
    assert(!stackTraceContainsCreationsStackTrace { t.get })
    assert(stackTraceContainsCreationsStackTrace { new MyTest().f(t).get })
  }

  private def stackTraceContainsCreationsStackTrace(body: => Unit): Boolean =
    intercept[TestException] { body } .getStackTrace exists { _.toString contains classOf[MyTest].getName }

  private class TestException extends Exception
}

private object StackTracesTest {
  private class MyTest {
    def f[A](t: Try[A]) = t.appendCurrentStackTrace
  }
}
