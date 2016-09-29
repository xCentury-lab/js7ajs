package com.sos.scheduler.engine.data.jobchain

import com.sos.scheduler.engine.data.job.JobPath
import com.sos.scheduler.engine.data.jobchain.NodeObstacle.MissingJob
import org.junit.runner.RunWith
import org.scalatest.FreeSpec
import org.scalatest.junit.JUnitRunner
import spray.json._

/**
  * @author Joacim Zschimmer
  */
@RunWith(classOf[JUnitRunner])
final class NodeOverviewTest extends FreeSpec {

  "Job" in {
    check(
      SimpleJobNodeOverview(
        nodeKey = NodeKey(JobChainPath("/JOB-CHAIN"), NodeId("NODE-ID")),
        nextNodeId = NodeId("NEXT"),
        errorNodeId = NodeId("ERROR"),
        jobPath = JobPath("/JOB"),
        action = JobChainNodeAction.process,
        orderCount = 100,
        obstacles = Set(MissingJob(JobPath("/JOB")))),
      """{
        "TYPE": "Job",
        "nodeKey": {
          "jobChainPath": "/JOB-CHAIN",
          "nodeId": "NODE-ID"
        },
        "nextNodeId": "NEXT",
        "errorNodeId": "ERROR",
        "jobPath": "/JOB",
        "action": "process",
        "orderCount": 100,
        "obstacles": [
          {
            "TYPE": "MissingJob",
            "jobPath": "/JOB"
          }
        ]
      }""")
  }

  "Sink" in {
    check(
      SinkNodeOverview(
        nodeKey = NodeKey(JobChainPath("/JOB-CHAIN"), NodeId("NODE-ID")),
        nextNodeId = NodeId("NEXT"),
        errorNodeId = NodeId("ERROR"),
        jobPath = JobPath("/SINK"),
        action = JobChainNodeAction.next_state,
        orderCount = 100,
        obstacles = Set()),
      """{
        "TYPE": "Sink",
        "nodeKey": {
          "jobChainPath": "/JOB-CHAIN",
          "nodeId": "NODE-ID"
        },
        "nextNodeId": "NEXT",
        "errorNodeId": "ERROR",
        "jobPath": "/SINK",
        "action": "next_state",
        "orderCount": 100,
        "obstacles": []
      }""")
  }

  "NestedJobChain" in {
    check(
      NestedJobChainNodeOverview(
        nodeKey = NodeKey(JobChainPath("/JOB-CHAIN"), NodeId("NODE-ID")),
        nextNodeId = NodeId("NEXT"),
        errorNodeId = NodeId("ERROR"),
        nestedJobChainPath = JobChainPath("/NESTED")),
      """{
        "TYPE": "NestedJobChain",
        "nodeKey": {
          "jobChainPath": "/JOB-CHAIN",
          "nodeId": "NODE-ID"
        },
        "nextNodeId": "NEXT",
        "errorNodeId": "ERROR",
        "nestedJobChainPath": "/NESTED"
      }""")
  }

  "End" in {
    check(
      EndNodeOverview(
        nodeKey = NodeKey(JobChainPath("/JOB-CHAIN"), NodeId("END"))),
      """{
        "TYPE": "End",
        "nodeKey": {
          "jobChainPath": "/JOB-CHAIN",
          "nodeId": "END"
        }
      }""")
  }

 def check(q: NodeOverview, json: String) = {
    assert(q.toJson == json.parseJson)
    assert(json.parseJson.convertTo[NodeOverview] == q)
  }
}
