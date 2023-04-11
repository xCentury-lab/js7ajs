package js7.agent.tests

import js7.agent.TestAgent
import js7.agent.configuration.AgentConfiguration
import js7.agent.tests.AgentAkkaNoStackoverflowTest.*
import js7.agent.tests.TestAgentDirectoryProvider.provideAgentDirectory
import js7.base.log.Logger
import js7.base.test.OurTestSuite
import js7.base.time.ScalaTime.*

/**
  * @author Joacim Zschimmer
  */
final class AgentAkkaNoStackoverflowTest extends OurTestSuite with AgentTester
{
  "Job working directory" in {
    val exception = intercept[RuntimeException] {
      provideAgentDirectory { directory =>
        val conf = AgentConfiguration.forTest(directory, "AgentAkkaNoStackoverflowTest")
        TestAgent.blockingRun(conf, 99.s) { _ =>
          logger.warn("THROW TEST ERROR")
          sys.error("TEST ERROR")
        }
      }
    }
    assert(exception.getMessage == "TEST ERROR")
  }
}

object AgentAkkaNoStackoverflowTest {
  private val logger = Logger(getClass)
}
