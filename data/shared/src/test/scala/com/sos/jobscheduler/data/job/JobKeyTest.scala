package com.sos.jobscheduler.data.job

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.{Position, WorkflowPath}
import com.sos.jobscheduler.tester.CirceJsonTester._
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class JobKeyTest extends FreeSpec
{
  "JSON" - {
    "JobKey.Anonymous" in {
      testJson[JobKey](JobKey.Anonymous((WorkflowPath("/WORKFLOW") % "VERSION") /: Position(1, 2, 3)),
        json"""
      {
        "workflowId": {
          "path": "/WORKFLOW",
          "versionId": "VERSION"
        },
        "position": [ 1, 2, 3 ]
      }""")
    }

    "JobKey.Named" in {
      testJson[JobKey](JobKey.Named(WorkflowPath("/WORKFLOW") % "VERSION", WorkflowJob.Name("NAME")),
        json"""
      {
        "workflowId": {
          "path": "/WORKFLOW",
          "versionId": "VERSION"
        },
        "name": "NAME"
      }""")
    }
  }
}
