package js7.base.utils

import js7.base.test.Test
import js7.base.utils.JavaCollections.syntax.*

/**
  * @author Joacim Zschimmer
  */
final class JavaCollectionsTest extends Test
{
  "java.util.stream.Stream.asScala" in {
    for ((a, b) <- java.util.stream.Stream.of(1, 2, 3).asScala zip Iterator(1, 2, 3))
      assert(a == b)
  }
}
