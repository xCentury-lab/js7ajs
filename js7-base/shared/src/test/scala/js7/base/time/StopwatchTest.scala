package js7.base.time

import js7.base.test.Test
import js7.base.time.ScalaTime.*
import js7.base.time.Stopwatch.*

/**
  * @author Joacim Zschimmer
  */
final class StopwatchTest extends Test {

  "itemsPerSecondString" in {
    assert(itemsPerSecondString(2.s, 3000, "items") == "2s/3000 items (⌀0.667ms), 1500 items/s")
    assert(itemsPerSecondString(2.s, 3000) == "2s/3000 ops (⌀0.667ms), 1500 ops/s")
  }

  "durationAndPerSecondString" in {
    assert(durationAndPerSecondString(2.s, 3000) == "2s/3000 ops, 1500 ops/s")
  }

  "numberAndPerSecondString" in {
    assert(numberAndPerSecondString(2.s, 3000) == "3000 ops, 1500/s")
  }
}
