package com.sos.jobscheduler.tests.master.cluster

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.http.AkkaHttpUtils._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.utils.FreeTcpPortFinder.findFreeTcpPorts
import com.sos.jobscheduler.core.event.journal.data.JournalSeparators.EventFooter
import com.sos.jobscheduler.core.event.journal.files.JournalFiles.JournalMetaOps
import com.sos.jobscheduler.data.cluster.ClusterEvent.{Coupled, FailedOver, SwitchedOver}
import com.sos.jobscheduler.data.cluster.ClusterState
import com.sos.jobscheduler.data.common.Uri
import com.sos.jobscheduler.data.event.KeyedEvent.NoKey
import com.sos.jobscheduler.data.event._
import com.sos.jobscheduler.data.order.OrderEvent.{OrderFinished, OrderProcessingStarted}
import com.sos.jobscheduler.data.order.{FreshOrder, OrderId}
import com.sos.jobscheduler.master.cluster.PassiveClusterNode
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import com.sos.jobscheduler.master.data.MasterCommand.ClusterSwitchOver
import com.sos.jobscheduler.tests.master.cluster.MasterClusterTester._
import java.nio.file.Files.size
import monix.execution.Scheduler.Implicits.global
import scala.collection.immutable.Seq
import scala.concurrent.duration.Deadline.now

final class FailoverClusterTest extends MasterClusterTester
{
  "Failover and recouple" in {
    val primaryHttpPort :: backupHttpPort :: Nil = findFreeTcpPorts(2)
    withMasterAndBackup(primaryHttpPort, backupHttpPort) { (primary, backup) =>
      var primaryMaster = primary.startMaster(httpPort = Some(primaryHttpPort)) await 99.s
      var backupMaster = backup.startMaster(httpPort = Some(backupHttpPort)) await 99.s
      primaryMaster.eventWatch.await[Coupled]()

      val t = now
      val sleepWhileFailing = 10.s  // Failover takes some seconds anyway
      val orderId = OrderId("💥")
      primaryMaster.addOrderBlocking(FreshOrder(orderId, TestWorkflow.id.path, arguments = Map(
        "SLEEP" -> sleepWhileFailing.toSeconds.toString)))
      primaryMaster.eventWatch.await[OrderProcessingStarted](_.key == orderId)
      backupMaster.eventWatch.await[OrderProcessingStarted](_.key == orderId)
      // KILL PRIMARY
      primaryMaster.terminate() await 99.s
      assert(now < t + sleepWhileFailing, "The shell script should run while Master fails")

      val Stamped(failedOverEventId, _, NoKey <-: failedOver) = backupMaster.eventWatch.await[FailedOver](_.key == NoKey).head
      assert(failedOver.failedAt.fileEventId == backupMaster.eventWatch.fileEventIds.last ||
             failedOver.failedAt.fileEventId == backupMaster.eventWatch.fileEventIds.dropRight(1).last)
      val expectedFailedFile = primaryMaster.injector.instance[MasterConfiguration].journalMeta.file(failedOver.failedAt.fileEventId)
      assert(failedOver.failedAt.position == size(expectedFailedFile) ||
             failedOver.failedAt.position == size(expectedFailedFile) - (EventFooter.compactPrint + "\n").length)

      assert(backupMaster.clusterState.await(99.s) ==
        ClusterState.IsFailedOver(Seq(primaryMaster.localUri.asUri, backupMaster.localUri.asUri), active = 1, failedOver.failedAt))

      backupMaster.eventWatch.await[OrderFinished](_.key == orderId, after = failedOverEventId)

      primaryMaster = primary.startMaster(httpPort = Some(primaryHttpPort)) await 99.s
      primaryMaster.eventWatch.await[Coupled](after = failedOverEventId)
      backupMaster.eventWatch.await[Coupled](after = failedOverEventId)
      assertEqualJournalFiles(primary.master, backup.master, 3)

      backupMaster.executeCommandForTest(ClusterSwitchOver).orThrow
      val recoupledEventId = backupMaster.eventWatch.await[SwitchedOver](after = failedOverEventId).head.eventId
      primaryMaster.eventWatch.await[SwitchedOver](after = failedOverEventId)

      backupMaster.terminated await 99.s
      backupMaster = backup.startMaster(httpPort = Some(backupHttpPort)) await 99.s
      backupMaster.eventWatch.await[Coupled](after = recoupledEventId)
      primaryMaster.eventWatch.await[Coupled](after = recoupledEventId)

      // When heartbeat from passive to active node is broken, the ClusterWatch will nonetheless not agree to a failover
      val stillCoupled = ClusterState.IsCoupled(
        List(
          Uri(primaryMaster.localUri.toString),
          Uri(backupMaster.localUri.toString)),
        active = 0)
      assert(primaryMaster.clusterState.await(99.s) == stillCoupled)
      assert(backupMaster.clusterState.await(99.s) == stillCoupled)

      val whenAgentAgrees = backupMaster.testEventBus.when[PassiveClusterNode.ClusterWatchAgreesToActivation.type]
      val whenAgentDoesNotAgree = backupMaster.testEventBus.when[PassiveClusterNode.ClusterWatchDisagreeToActivation.type]
      sys.props(testHeartbeatLossPropertyKey) = "true"
      whenAgentDoesNotAgree await 99.s
      assert(!whenAgentAgrees.isCompleted)
      assert(primaryMaster.clusterState.await(99.s) == stillCoupled)
      assert(backupMaster.clusterState.await(99.s) == stillCoupled)

      primaryMaster.terminate() await 99.s
      backupMaster.terminate() await 99.s
    }
  }
}
