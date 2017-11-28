package com.sos.jobscheduler.taskserver.dotnet

import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.system.OperatingSystem.isWindows
import com.sos.jobscheduler.taskserver.dotnet.DotnetEnvironmentTest._
import java.nio.file.Files.{createTempDirectory, delete}
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class DotnetEnvironmentTest extends FreeSpec {

  if (!isWindows) {
    ".Net is only for Windows" - {}
  } else {
    "DotnetEnvironment provides files and cleans up after use" in {
      val baseDir = createTempDirectory("DotnetEnvironmentTest-")  // Double nesting of directories to get a clear test
      val dotnetEnvironment = new DotnetEnvironment(baseDir)
      assert(baseDir.pathSet == Set(dotnetEnvironment.directory))
      for (o ← dotnetEnvironment.directory.pathSet) logger.info(s"$o")
      assert(dotnetEnvironment.directory.pathSet ==
        (dlls.DotnetDlls.DllsResourcePaths map { o ⇒ dotnetEnvironment.directory resolve o.simpleName }))
      dotnetEnvironment.close()
      assert(baseDir.pathSet == Set())
      delete(baseDir)
    }
  }
}

private object DotnetEnvironmentTest {
  private val logger = Logger(getClass)
}
