package com.sos.jobscheduler.agent.data.web

import com.sos.jobscheduler.agent.data.AgentTaskId
import com.sos.jobscheduler.base.web.Uri
import org.scalatest.freespec.AnyFreeSpec

/**
 * @author Joacim Zschimmer
 */
final class AgentUrisTest extends AnyFreeSpec
{
  private val agentUris = AgentUris(Uri("https://example.com:9999/testPrefix"))

  "command" in {
    assert(agentUris.command ==
      Uri("https://example.com:9999/testPrefix/agent/api/command"))
  }

  "session" in {
    assert(agentUris.session ==
      Uri("https://example.com:9999/testPrefix/agent/api/session"))
  }

  "task" - {
    "overview" in {
      assert(agentUris.task.overview ==
        Uri("https://example.com:9999/testPrefix/agent/api/task"))
    }

    "tasks" in {
      assert(agentUris.task.tasks ==
        Uri("https://example.com:9999/testPrefix/agent/api/task/"))
    }

    "task" in {
      assert(agentUris.task.tasks ==
        Uri("https://example.com:9999/testPrefix/agent/api/task/"))
    }
  }

  "task.tasks" in {
    assert(agentUris.task(AgentTaskId("TASK-ID")) ==
      Uri("https://example.com:9999/testPrefix/agent/api/task/TASK-ID"))
  }

  "overview" in {
    assert(agentUris.overview ==
      Uri("https://example.com:9999/testPrefix/agent/api"))
  }

  "Trailing slash in URI is ignored" in {
    assert(AgentUris(Uri("https://example.com:9999")).overview == Uri("https://example.com:9999/agent/api"))
    assert(AgentUris(Uri("https://example.com:9999/")).overview == Uri("https://example.com:9999/agent/api"))
    assert(AgentUris(Uri("https://example.com:9999/x")).overview == Uri("https://example.com:9999/x/agent/api"))
    assert(AgentUris(Uri("https://example.com:9999/x/")).overview == Uri("https://example.com:9999/x/agent/api"))
  }
}
