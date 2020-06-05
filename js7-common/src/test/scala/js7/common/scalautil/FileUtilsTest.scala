package js7.common.scalautil

import com.google.common.io.MoreFiles.touch
import io.circe.Json
import java.io.File
import java.io.File.separator
import java.nio.charset.StandardCharsets.{UTF_16BE, UTF_8}
import java.nio.file.Files.{createTempDirectory, createTempFile, delete, exists}
import java.nio.file.{Files, NotDirectoryException, Path, Paths}
import js7.base.problem.ProblemException
import js7.common.scalautil.FileUtils.implicits._
import js7.common.scalautil.FileUtils.syntax._
import js7.common.scalautil.FileUtils.{autoDeleting, checkRelativePath, withTemporaryFile}
import js7.common.scalautil.FileUtilsTest._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers._
import scodec.bits.ByteVector

/**
 * @author Joacim Zschimmer
 */
final class FileUtilsTest extends AnyFreeSpec with BeforeAndAfterAll
{
  private lazy val path = createTempFile("FileUtilTest-", ".tmp")

  override def afterAll() = delete(path)

  "implicit fileToPath" in {
    new File("/a"): Path
  }

  "implicit pathToFile" in {
    new File("/a").toPath: File
  }

  "Path extention methods" - {
    "slash" - {
      val a = Paths.get("a")
      "valid" in {
        assert((a / "b").toString == s"a${separator}b")
      }
      "invalid" - {
        for (invalid <- InvalidRelativePaths) {
          invalid in {
            intercept[ProblemException] {
              a / invalid
            }
          }
        }
      }
    }

    "contentString" in {
      path.contentString = TestString
      path.contentString shouldEqual TestString
      new String(Files.readAllBytes(path), UTF_8) shouldEqual TestString
    }

    "contentBytes" in {
      path.contentBytes shouldEqual TestBytes
      path.contentBytes = Array[Byte](1, 2)
      path.contentBytes shouldEqual Vector[Byte](1, 2)
    }

    ":= String" in {
      path := TestString
      path.contentString shouldEqual TestString
      new String(Files.readAllBytes(path), UTF_8) shouldEqual TestString
    }

    "++= String" in {
      path ++= "-APPENDED"
      path.contentString shouldEqual TestString + "-APPENDED"
      new String(Files.readAllBytes(path), UTF_8) shouldEqual TestString + "-APPENDED"
    }

    ":= Array[Byte]" in {
      path := Array[Byte](1, 2)
      path.contentBytes shouldEqual Vector[Byte](1, 2)
    }

    ":= Seq[Byte]" in {
      path := Seq[Byte](1, 2)
      path.contentBytes shouldEqual Vector[Byte](1, 2)
    }

    ":= ByteVector" in {
      val bytes = "A-Å".getBytes(UTF_8)
      path := ByteVector(bytes)
      assert(path.byteVector == ByteVector(bytes))
      assert(path.contentBytes.toSeq == bytes.toSeq)
    }

    "+= ByteVector" in {
      val complete = "A-Å-APPENDED".getBytes(UTF_8)
      val bytes = "-APPENDED".getBytes(UTF_8)
      path ++= ByteVector(bytes)
      assert(path.byteVector == ByteVector(complete))
      assert(path.contentBytes.toSeq == complete.toSeq)
    }

    ":= JSON" in {
      path := Json.obj("key" -> Json.fromInt(7))
      path.contentString shouldEqual """{"key":7}"""
    }

    "write" in {
      path.write(TestString, UTF_16BE)
      path.contentBytes shouldEqual TestString.getBytes(UTF_16BE)
    }

    "append" in {
      path.append("X", UTF_16BE)
      path.contentString(UTF_16BE) shouldEqual TestString + "X"
    }

    "directoryContentsAs" in {
      intercept[NotDirectoryException] { path.directoryContentsAs(Set) }
      val dir = createTempDirectory("FileUtilsTest-")
      assert(dir.directoryContentsAs(Set).isEmpty)
      val files = Set("a.tmp", "b.tmp") map dir.resolve
      files foreach { o => touch(o) }
      assert(dir.directoryContentsAs(Set) == files)
      assert(dir.directoryContentsAs(Set) == files)
      files foreach delete
      delete(dir)
    }
  }

  "createShortNamedDirectory" in {
    assert(FileUtils.ShortNamePermutationCount == 2176782336L)
    val dir = createTempDirectory("test-")
    val n = 100
    val dirs = List.fill(n) {
      val prefix = "test-"
      val d = FileUtils.createShortNamedDirectory(dir, prefix)
      assert(Files.isDirectory(d))
      assert(d.getFileName.toString.length == prefix.length + 6)
      d
    }
    assert(dirs.toSet.size == n)
    dirs foreach delete
    delete(dir)
  }

  "withTemporaryFile" in {
    val f = withTemporaryFile { file =>
      assert(exists(file))
      file
    }
    assert(!exists(f))
  }

  "withTemporaryFile, named" in {
    val f = withTemporaryFile("TEST-", ".tmp") { file =>
      assert(exists(file))
      file
    }
    assert(!exists(f))
  }

  "autoDeleting" in {
    val file = createTempFile("TEST-", ".tmp")
    val a = autoDeleting(file) { f =>
      assert(file eq f)
      assert(exists(f))
      123
    }
    assert(a == 123)
    assert(!exists(file))
  }

  "autoDeleting with exception" in {
    val file = createTempFile("TEST-", ".tmp")
    intercept[IllegalStateException] {
      autoDeleting(file) { _ => throw new IllegalStateException }
    }
    assert(!exists(file))
  }

  "checkRelativePath" - {
    "valid" in {
      assert(checkRelativePath("relative").isRight)
    }

    "invalid" - {
      for (invalid <- InvalidRelativePaths) {
        invalid in {
          assert(checkRelativePath(invalid).isLeft)
        }
      }
    }
  }
}

private object FileUtilsTest {
  private val TestString = "AÅ"
  private val TestBytes = TestString.getBytes(UTF_8)
  assert(TestBytes.length == 3)

  private val InvalidRelativePaths = List(
    "",
    "/b",
    """\b""",
    "./b",
    "../b",
    """.\b""",
    """..\b""",
    "b/./c",
    "b/../c",
    """b/.\c""",
    """b/..\c""",
    """b\.\c""",
    """b\..\c""",
    """b\./c""",
    """b\../c""",
    "b/.",
    "b/..",
    """b\.""",
    """b\..""")
}
