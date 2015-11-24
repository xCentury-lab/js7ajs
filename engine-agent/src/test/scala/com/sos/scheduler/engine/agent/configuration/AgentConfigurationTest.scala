package com.sos.scheduler.engine.agent.configuration

import com.sos.scheduler.engine.common.system.FileUtils.temporaryDirectory
import java.nio.file.Paths
import org.junit.runner.RunWith
import org.scalatest.FreeSpec
import org.scalatest.junit.JUnitRunner

/**
 * @author Joacim Zschimmer
 */
@RunWith(classOf[JUnitRunner])
final class AgentConfigurationTest extends FreeSpec {

  "Empty argument list is rejected" in {
    intercept[NoSuchElementException] { AgentConfiguration(Nil) }
  }

  "-http-port=" in {
    assert(AgentConfiguration(List("-http-port=1234")).httpPort == 1234)
    intercept[IllegalArgumentException] { AgentConfiguration(List("-http-port=65536")) }
    intercept[IllegalArgumentException] { AgentConfiguration(httpPort = 65536) }
  }

  "-ip-address=" in {
    assert(conf(Nil).httpInterfaceRestriction.isEmpty)
    assert(conf(List("-ip-address=1.2.3.4")).httpInterfaceRestriction == Some("1.2.3.4"))
  }

  "-log-directory=" in {
    assert(conf(Nil).uriPathPrefix == "")
    assert(conf(Nil).logDirectory == temporaryDirectory)
    assert(conf(List("-log-directory=test")).logDirectory == Paths.get("test").toAbsolutePath)
  }

  "-uri-prefix=" in {
    assert(conf(Nil).uriPathPrefix == "")
    assert(conf(List("-uri-prefix=test")).strippedUriPathPrefix == "test")
    assert(conf(List("-uri-prefix=/test/")).strippedUriPathPrefix == "test")
  }

  "-kill-script=" in {
    assert(conf(Nil).killScriptFile == None)
    val killScript = Paths.get("kill-script")
    assert(conf(List(s"-kill-script=$killScript")).killScriptFile == Some(killScript.toAbsolutePath))
  }

  private def conf(args: Seq[String]) = AgentConfiguration(List("-http-port=1") ++ args)
}
