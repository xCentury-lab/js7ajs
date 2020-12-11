package js7.proxy.javaapi.data.common

import io.vavr.control.{Either => VEither}
import js7.base.problem.{Problem, ProblemException}
import js7.proxy.javaapi.data.common.VavrUtils.getOrThrow
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class VavrUtilsTest extends AnyFreeSpec
{
  "getOrThrow" - {
    "Left" in {
      val e = intercept[RuntimeException] {
        getOrThrow(VEither.left(Problem("PROBLEM")))
      }
      assert(e.toString == "java.lang.RuntimeException: PROBLEM")
      assert(e.getMessage == "PROBLEM")
      assert(e.getCause.asInstanceOf[ProblemException].problem == Problem("PROBLEM"))
      assert(e.getCause.asInstanceOf[ProblemException].getMessage == "PROBLEM")
    }

    "Right" in {
      assert(getOrThrow(VEither.right[Problem, Int](7)) == 7)
    }
  }
}
