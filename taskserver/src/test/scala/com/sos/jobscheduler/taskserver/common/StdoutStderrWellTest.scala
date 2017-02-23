package com.sos.jobscheduler.taskserver.common

import com.sos.jobscheduler.common.process.StdoutStderr.{Stderr, Stdout}
import com.sos.jobscheduler.common.scalautil.Closers.implicits.{RichClosersAny, RichClosersAutoCloseable}
import com.sos.jobscheduler.common.scalautil.Closers.withCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import java.io.{FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Files.createTempFile
import org.scalatest.FreeSpec
import scala.collection.mutable

/**
 * @author Joacim Zschimmer
 */
final class StdoutStderrWellTest extends FreeSpec {

  "StdoutStderrWell" in {
    withCloser { implicit closer ⇒
      val collectedLines = Map(Stdout → mutable.Buffer[String](), Stderr → mutable.Buffer[String]())
      val stdFiles = Map(Stdout → createTempFile("test-", ".tmp"), Stderr → createTempFile("test-", ".tmp").withCloser(Files.delete))
      val List(out, err) = List(Stdout, Stderr) map { o ⇒ new OutputStreamWriter(new FileOutputStream(stdFiles(o)).closeWithCloser) }
      val well = new StdoutStderrWell(stdFiles, UTF_8, batchThreshold = 100, (t, batches) ⇒ collectedLines(t) ++= batches).closeWithCloser

      out.write("OUT\n")
      out.flush()
      err.write("ERR\n")
      err.flush()
      well.apply()
      assert(collectedLines == Map(Stdout → List("OUT"), Stderr → List("ERR")))

      collectedLines.values foreach { _.clear() }
      out.write("OUT-2\n")
      out.flush()
      err.write("ERR-2\n")
      err.flush()
      well.apply()
      assert(collectedLines == Map(Stdout → List("OUT-2"), Stderr → List("ERR-2")))
    }
  }
}
