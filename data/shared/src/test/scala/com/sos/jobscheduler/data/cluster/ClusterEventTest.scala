package com.sos.jobscheduler.data.cluster

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.data.cluster.ClusterEvent.{BackupNodeAppointed, BecameSole, Coupled, CouplingPrepared, FailedOver, PassiveLost, SwitchedOver}
import com.sos.jobscheduler.data.common.Uri
import com.sos.jobscheduler.data.event.JournalPosition
import com.sos.jobscheduler.tester.CirceJsonTester.testJson
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class ClusterEventTest extends FreeSpec
{
  "BecameSole" in {
    testJson[ClusterEvent](BecameSole(Uri("http://ACTIVE")),
      json"""{
        "TYPE": "Cluster.BecameSole",
        "primaryUri": "http://ACTIVE"
      }""")
  }

  "BackupNodeAppointed" in {
    testJson[ClusterEvent](BackupNodeAppointed(Uri("http://BACKUP")),
      json"""{
        "TYPE": "Cluster.BackupNodeAppointed",
        "uri": "http://BACKUP"
      }""")
  }

  "CouplingPrepared" in {
    testJson[ClusterEvent](CouplingPrepared(Uri("http://PASSIVE")),
      json"""{
        "TYPE": "Cluster.CouplingPrepared",
        "passiveUri": "http://PASSIVE"
      }""")
  }

  "Coupled" in {
    testJson[ClusterEvent](Coupled,
      json"""{
        "TYPE": "Cluster.Coupled"
      }""")
  }

  "SwitchedOver" in {
    testJson[ClusterEvent](SwitchedOver(Uri("http://NODE")),
      json"""{
        "TYPE": "Cluster.SwitchedOver",
        "uri": "http://NODE"
      }""")
  }

  "FailedOver" in {
    testJson[ClusterEvent](FailedOver(Uri("http://FAILED"), Uri("http://ACTIVATED"), JournalPosition(0, 1234)),
      json"""{
        "TYPE": "Cluster.FailedOver",
        "failedActiveUri": "http://FAILED",
        "activatedUri": "http://ACTIVATED",
        "failedAt": {
          "fileEventId": 0,
          "position": 1234
        }
      }""")
  }

  "PassiveLost" in {
    testJson[ClusterEvent](PassiveLost(Uri("http://PASSIVE")),
      json"""{
        "TYPE": "Cluster.PassiveLost",
        "uri": "http://PASSIVE"
      }""")
  }
}
