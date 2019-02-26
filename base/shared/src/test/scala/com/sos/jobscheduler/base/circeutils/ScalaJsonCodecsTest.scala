package com.sos.jobscheduler.base.circeutils

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.circeutils.ScalaJsonCodecs._
import com.sos.jobscheduler.tester.CirceJsonTester.testJson
import org.scalatest.FreeSpec
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class ScalaJsonCodecsTest extends FreeSpec {

  "FinitedDuration" in {
    testJson(Duration(Long.MaxValue, NANOSECONDS), json"9223372036.854775807")
    assert(""" "1x" """.parseJsonOrThrow.as[FiniteDuration].isLeft)
    //assert(""" "PT1m2.123S" """.parseJsonOrThrow.as[Duration].orThrow == Duration.ofSeconds(62, 123456789))
  }
}
