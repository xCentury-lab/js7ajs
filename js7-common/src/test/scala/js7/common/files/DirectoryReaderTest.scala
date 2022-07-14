package js7.common.files

import java.nio.file.Files.createDirectory
import java.nio.file.Paths
import java.util.Comparator
import js7.base.io.file.FileUtils.syntax.*
import js7.base.io.file.FileUtils.*
import js7.base.log.Logger
import js7.base.time.JavaTimeConverters.*
import js7.base.time.ScalaTime.*
import js7.base.time.Stopwatch.measureTime
import js7.base.time.Timestamp
import js7.common.files.DirectoryReader.Entry
import js7.common.files.DirectoryReaderTest.*
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

/**
  * @author Joacim Zschimmer
  */
final class DirectoryReaderTest extends AnyFreeSpec
{
  "entries" in {
    withTemporaryDirectory("Z-") { dir =>
      touchFile(dir / "C")
      touchFile(dir / "A")
      touchFile(dir / "B")
      val subdir = dir / "DIR"
      createDirectory(subdir)
      touchFile(subdir / "A")
      touchFile(subdir / "B")

      val entries = DirectoryReader.entries(dir)
      assert(entries.map(_.file) ==
        Vector(
          dir / "A",
          dir / "B",
          dir / "C",
          subdir / "A",
          subdir / "B"))

      def isValidInstant(timestamp: Timestamp) = timestamp > Timestamp.now - 30.s && timestamp <= Timestamp.now
      assert(entries.forall(e => e.attributes.size == 0 && !e.attributes.isDirectory && e.attributes.isRegularFile &&
        isValidInstant(e.attributes.lastModifiedTime.toInstant.toTimestamp)))

      // Second call yields equivalent result
      assert(entries == DirectoryReader.entries(dir))
    }
  }

  if (sys.props contains "test.speed") "Sort speed" - {
    val n = 200000
    val entries = (1 to n).map(_ => Entry(Paths.get(Random.nextString(100)), null))

    "single thread" in {
      for (_ <- 1 to 10) {
        logger.info(
          measureTime(1, "directories", warmUp = 0) {
            entries.sortBy(_.file)
          }.toString)
      }
    }

    "parallel" in {
      for (_ <- 1 to 10) {
        val comparator: Comparator[Entry] = (a, b) => a.file compareTo b.file
        logger.info(
          measureTime(1, "directories", warmUp = 0) {
            val array = entries.toArray
            java.util.Arrays.parallelSort(array, comparator)
            array.toVector
          }.toString)
      }
    }
  }
}

object DirectoryReaderTest {
  private val logger = Logger(getClass)
}
