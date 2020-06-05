package js7.base.utils

import js7.base.utils.SideEffect._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers._

final class SideEffectTest extends AnyFreeSpec {

  "sideEffect" in {
    val a = A(1) sideEffect { _.x = 2 }
    a.x shouldEqual 2
  }

  private case class A(var x: Int)
}
