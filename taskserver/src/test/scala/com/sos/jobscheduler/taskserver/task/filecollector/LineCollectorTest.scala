package com.sos.jobscheduler.taskserver.task.filecollector

import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Closers._
import com.sos.jobscheduler.common.scalautil.Closers.implicits._
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import java.io.{BufferedReader, FileInputStream, FileOutputStream, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files.{createTempFile, delete}
import org.scalatest.FreeSpec
import org.scalatest.Matchers._

/**
  * @author Joacim Zschimmer
  */
final class LineCollectorTest extends FreeSpec {

  "LineCollector" in {
    withCloser { implicit closer ⇒
      val file = createTempFile("test-", ".tmp") withCloser delete
      autoClosing(new OutputStreamWriter(new FileOutputStream(file), UTF_8)) { writer ⇒
        autoClosing(new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8))) { reader ⇒
          val fileLogger = new LineCollector(reader)
          for (lines ← List(List("1a", "1b", "1c"), List("2a", "2b"))) {
            for (line ← lines) writer.write(s"$line\n")
            writer.flush()
            fileLogger.nextLinesIterator().toList shouldEqual lines
          }
        }
      }
    }
  }
}
