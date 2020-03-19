package com.sos.jobscheduler.master.data

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.cluster.ClusterState.ClusterEmpty
import com.sos.jobscheduler.data.command.CancelMode
import com.sos.jobscheduler.data.common.Uri
import com.sos.jobscheduler.data.crypt.{GenericSignature, SignedString}
import com.sos.jobscheduler.data.filebased.VersionId
import com.sos.jobscheduler.data.order.OrderId
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.master.data.MasterCommand._
import com.sos.jobscheduler.tester.CirceJsonTester.testJson
import org.scalatest.FreeSpec

final class MasterCommandTest extends FreeSpec
{
  "ClusterAppointBackup" in {
    testJson[MasterCommand](ClusterAppointBackup(Uri("http://ACTIVE"), Uri("http://BACKUP")),
      json"""{
        "TYPE": "ClusterAppointBackup",
        "activeUri": "http://ACTIVE",
        "backupUri": "http://BACKUP"
      }""")
  }

  "Batch" - {
    "Batch" in {
      testJson[MasterCommand](Batch(List(NoOperation, EmergencyStop())),
        json"""{
          "TYPE": "Batch",
          "commands": [
            { "TYPE": "NoOperation" },
            { "TYPE": "EmergencyStop" }
          ]
        }""")
    }

    "Batch.toString" in {
      assert(Batch(List(NoOperation, EmergencyStop(), NoOperation)).toString == "Batch(NoOperation, EmergencyStop, NoOperation)")
      assert(Batch(List(NoOperation, EmergencyStop(), NoOperation, NoOperation)).toString == "Batch(NoOperation, EmergencyStop, 2×NoOperation)")
    }

    "BatchResponse" in {
      testJson[MasterCommand.Response](Batch.Response(Right(Response.Accepted) :: Left(Problem("PROBLEM")) :: Nil),
        json"""{
          "TYPE": "BatchResponse",
          "responses": [
            {
              "TYPE": "Accepted"
            }, {
              "TYPE": "Problem",
              "message": "PROBLEM"
            }
          ]
        }""")
    }

    "BatchResponse.toString" in {
      val threeResponses = Right(Response.Accepted) :: Left(Problem("PROBLEM")) :: Right(Response.Accepted) :: Nil
      assert(Batch.Response(threeResponses).toString == "BatchResponse(2 succeeded and 1 failed)")
      assert(Batch.Response(threeResponses ::: Right(Response.Accepted) :: Nil).toString == "BatchResponse(3 succeeded and 1 failed)")
    }
  }

  "CancelOrder" - {
    "CancelOrder NotStarted" in {
      testJson[MasterCommand](CancelOrder(OrderId("ORDER"), CancelMode.NotStarted),
        json"""{
          "TYPE": "CancelOrder",
          "orderId": "ORDER"
        }""")
    }

    "CancelOrder FreshOrStarted" in {
      testJson[MasterCommand](CancelOrder(OrderId("ORDER"), CancelMode.FreshOrStarted),
        json"""{
          "TYPE": "CancelOrder",
          "orderId": "ORDER",
          "mode": {
            "TYPE": "FreshOrStarted"
          }
        }""")
    }
  }

  "ClusterPrepareCoupling" in {
    testJson[MasterCommand](
      ClusterPrepareCoupling(Uri("http://ACTIVE"), Uri("http://PASSIVE")),
      json"""{
        "TYPE": "ClusterPrepareCoupling",
        "activeUri": "http://ACTIVE",
        "passiveUri": "http://PASSIVE"
      }""")
  }

  "ClusterCouple" in {
    testJson[MasterCommand](
      ClusterCouple(Uri("http://ACTIVE"), Uri("http://PASSIVE")),
      json"""{
        "TYPE": "ClusterCouple",
        "activeUri": "http://ACTIVE",
        "passiveUri": "http://PASSIVE"
      }""")
  }

  "ReplaceRepo" in {
    testJson[MasterCommand](ReplaceRepo(
      VersionId("1"),
      objects = SignedString(
        string = """{"TYPE": "Workflow", ...}""",
        GenericSignature(
          "PGP",
          """|-----BEGIN PGP SIGNATURE-----
             |
             |...
             |-----END PGP SIGNATURE-----
             |""".stripMargin)) :: Nil),
      json"""{
        "TYPE": "ReplaceRepo",
        "versionId": "1",
        "objects": [
          {
            "string": "{\"TYPE\": \"Workflow\", ...}",
            "signature": {
              "TYPE": "PGP",
              "signatureString": "-----BEGIN PGP SIGNATURE-----\n\n...\n-----END PGP SIGNATURE-----\n"
            }
          }
        ]
      }""")
  }

  "UpdateRepo" in {
    testJson[MasterCommand](UpdateRepo(
      VersionId("1"),
      change = SignedString(
        string = """{"TYPE": "Workflow", ...}""",
        GenericSignature(
          "PGP",
           """-----BEGIN PGP SIGNATURE-----
            |
            |...
            |-----END PGP SIGNATURE-----
            |""".stripMargin)) :: Nil,
      delete = WorkflowPath("/WORKFLOW-A") :: AgentRefPath("/AGENT-A") :: Nil),
      json"""{
        "TYPE": "UpdateRepo",
        "versionId": "1",
        "change": [
          {
            "string": "{\"TYPE\": \"Workflow\", ...}",
            "signature": {
              "TYPE": "PGP",
              "signatureString": "-----BEGIN PGP SIGNATURE-----\n\n...\n-----END PGP SIGNATURE-----\n"
            }
          }
        ],
        "delete": [
          {
            "TYPE": "WorkflowPath",
            "path": "/WORKFLOW-A"
          }, {
            "TYPE": "AgentRefPath",
            "path": "/AGENT-A"
          }
        ]
      }""")
  }

  "ClusterInhibitActivation" in {
    testJson[MasterCommand](ClusterInhibitActivation(7.s),
      json"""{
        "TYPE": "ClusterInhibitActivation",
        "duration": 7
      }""")
  }

  "ClusterInhibitActivation.Response" in {
    testJson[MasterCommand.Response](ClusterInhibitActivation.Response(ClusterEmpty),
      json"""{
        "TYPE": "ClusterInhibitActivationResponse",
        "clusterState": {
          "TYPE": "ClusterEmpty"
        }
      }""")
  }

  "EmergencyStop" - {
    "restart=false" in {
      testJson[MasterCommand](EmergencyStop(),
        json"""{
          "TYPE": "EmergencyStop"
        }""")
    }

    "restart=true" in {
      testJson[MasterCommand](EmergencyStop(restart = true),
        json"""{
          "TYPE": "EmergencyStop",
          "restart": true
        }""")
    }
  }

  "KeepEvents" in {
    testJson[MasterCommand](
      KeepEvents(123),
      json"""{
        "TYPE": "KeepEvents",
        "after": 123
      }""")
  }

  "NoOperation" in {
    testJson[MasterCommand](NoOperation,
      json"""{
        "TYPE": "NoOperation"
      }""")
  }

  "IssueTestEvent" in {  // For tests only
    testJson[MasterCommand](IssueTestEvent,
      json"""{
        "TYPE": "IssueTestEvent"
      }""")
  }

  "TakeSnapshot" in {
    testJson[MasterCommand](TakeSnapshot,
      json"""{
        "TYPE": "TakeSnapshot"
      }""")
  }

  "ShutDown" - {
    "restart=false" in {
      testJson[MasterCommand](ShutDown(),
        json"""{
          "TYPE": "ShutDown"
        }""")
    }

    "restart=true" in {
      testJson[MasterCommand](ShutDown(restart = true),
        json"""{
          "TYPE": "ShutDown",
          "restart": true
        }""")
    }
  }

  "Response.Accepted" in {
    testJson[MasterCommand.Response](
      Response.Accepted,
      json"""{
        "TYPE": "Accepted"
      }""")
  }
}
