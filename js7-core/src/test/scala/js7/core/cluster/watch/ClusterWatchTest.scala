package js7.core.cluster.watch

import js7.base.generic.Completed
import js7.base.monixutils.MonixDeadline.now
import js7.base.monixutils.MonixDeadline.syntax.DeadlineSchedule
import js7.base.problem.Checked
import js7.base.problem.Checked.*
import js7.base.test.OurTestSuite
import js7.base.thread.MonixBlocking.syntax.*
import js7.base.time.ScalaTime.*
import js7.base.web.Uri
import js7.common.message.ProblemCodeMessages
import js7.core.cluster.watch.ClusterWatch.*
import js7.data.cluster.ClusterEvent.{ClusterCoupled, ClusterCouplingPrepared, ClusterFailedOver, ClusterNodeLostEvent, ClusterPassiveLost, ClusterSwitchedOver}
import js7.data.cluster.ClusterState.{Coupled, FailedOver, HasNodes, NodesAppointed, PassiveLost, PreparedToBeCoupled, SwitchedOver}
import js7.data.cluster.{ClusterEvent, ClusterSetting, ClusterState, ClusterTiming}
import js7.data.controller.ControllerId
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.{EventId, JournalPosition}
import js7.data.node.NodeId
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.TestScheduler
import scala.concurrent.duration.FiniteDuration

final class ClusterWatchTest extends OurTestSuite
{
  ProblemCodeMessages.initialize()

  private val aId = NodeId("A")
  private val bId = NodeId("B")
  private val timing = ClusterTiming(3.s, 10.s)
  private val setting = ClusterSetting(
    Map(
      NodeId("A") -> Uri("http://A"),
      NodeId("B") -> Uri("http://B")),
    activeId = NodeId("A"),
    Seq(ClusterSetting.Watch(Uri("https://CLUSTER-WATCH"))),
    timing)
  private val failedAt = JournalPosition(EventId(0), 0)
  private lazy val scheduler = TestScheduler()

  "ClusterWatch" - {
    def newClusterWatch(initialState: Option[HasNodes] = None) = {
      val w = new ClusterWatch(ControllerId("CONTROLLER"), () => scheduler.now)
      for (s <- initialState) w.heartbeat(s.activeId, s).await(99.s).orThrow
      w
    }

    "Initial (untaught) state" in {
      val watch = newClusterWatch()
      val clusterState = Coupled(setting)
      val event = ClusterFailedOver(aId, bId, failedAt)
      val failedOver = clusterState.applyEvent(event).orThrow.asInstanceOf[FailedOver]
      assert(watch.applyEvents(ClusterWatchEvents(bId, Seq(event), failedOver)).await(99.s) ==
        Left(UntaughtClusterWatchProblem))

      assert(watch.heartbeat(aId, clusterState).await(99.s) == Right(Completed))
      scheduler.tick(timing.clusterWatchHeartbeatValidDuration)
      assert(watch.applyEvents(ClusterWatchEvents(bId, Seq(event), failedOver)).await(99.s) ==
        Right(Completed))
    }

    "Early heartbeat" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))
      scheduler.tick(1.s)
      assert(watch.heartbeat(aId, clusterState).await(99.s) == Right(Completed))
    }

    "Late heartbeat" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))

      scheduler.tick(11.s)
      assert(watch.heartbeat(aId, clusterState).await(99.s) == Right(Completed))

      scheduler.tick(timing.clusterWatchHeartbeatValidDuration + 1.s)
      assert(watch.heartbeat(aId, clusterState).await(99.s) == Right(Completed))
      assert(watch.isActive(aId).await(99.s).orThrow)
    }

    "Coupling" in {
      val clusterState = NodesAppointed(setting)
      val watch = newClusterWatch()
      val events = Seq(
        ClusterCouplingPrepared(aId),
        ClusterCoupled(aId))

      val clusterState2 = clusterState.applyEvents(events.map(NoKey <-: _))
        .orThrow.asInstanceOf[Coupled]
      assert(watch.applyEvents(ClusterWatchEvents(aId, events, clusterState2)).await(99.s) ==
        Right(Completed))
      assert(watch.isActive(aId).await(99.s).orThrow)
      assert(watch.clusterState.await(99.s) == Right(clusterState2))
    }

    "Late heartbeat after coupled" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))
      scheduler.tick(timing.clusterWatchHeartbeatValidDuration + 1.s)

      assert(watch.heartbeat(aId, clusterState).await(99.s) == Right(Completed))
      assert(watch.isActive(aId).await(99.s).orThrow)
    }

    "Heartbeat with different ClusterState from same active node is rejected" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))

      val reportedClusterState = PassiveLost(setting)
      assert(watch.heartbeat(aId, reportedClusterState).await(99.s) ==
        Left(ClusterWatchHeartbeatMismatchProblem(clusterState, reportedClusterState)))
    }

    "Heartbeat from wrong node is rejected" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))

      assert(watch.heartbeat(bId, clusterState).await(99.s) ==
        Left(InvalidClusterWatchHeartbeatProblem(bId, clusterState)))

      locally {
        assert(watch.heartbeat(bId, Coupled(setting.copy(activeId = bId))).await(99.s) ==
          Left(ClusterWatchInactiveNodeProblem(bId, clusterState, 0.s,
            "heartbeat Coupled(passive A: http://A, active B: http://B)")))
      }

      assert(watch.clusterState.await(99.s) == Right(clusterState))
    }

    "Heartbeat must not change active URI" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))

      // The inactive primary node should not send a heartbeat
      val badCoupled = Coupled(setting.copy(activeId = bId))
      assert(watch.heartbeat(aId, badCoupled).await(99.s) ==
        Left(InvalidClusterWatchHeartbeatProblem(aId, badCoupled)))
      assert(watch.clusterState.await(99.s) == Right(clusterState))
    }

    "FailedOver before heartbeat loss is rejected" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))
      val duration = timing.clusterWatchHeartbeatValidDuration - 1.ms
      scheduler.tick(duration)

      val events = Seq(ClusterFailedOver(aId, bId, failedAt))
      val clusterWatch2 = clusterState.applyEvents(events.map(NoKey <-: _))
        .orThrow.asInstanceOf[FailedOver]
      assert(watch.applyEvents(ClusterWatchEvents(bId,  events, clusterWatch2)).await(99.s) ==
        Left(ClusterWatchInactiveNodeProblem(bId, clusterState, duration,
          "event ClusterFailedOver(A --> B, JournalPosition(0,0)) --> FailedOver(A --> B at JournalPosition(0,0))")))

      assert(watch.isActive(aId).await(99.s).orThrow)
    }

    "FailedOver but concurrent PassiveLost" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))
      scheduler.tick(timing.clusterWatchHeartbeatValidDuration - 1.ms)
      val failedOverEvent = Seq(ClusterFailedOver(aId, bId, failedAt))
      val failedOver = clusterState.applyEvents(failedOverEvent.map(NoKey <-: _))
        .orThrow.asInstanceOf[FailedOver]

      val passiveLostEvent = Seq(ClusterPassiveLost(bId))
      val passiveLost = clusterState.applyEvents(passiveLostEvent.map(NoKey <-: _))
        .orThrow.asInstanceOf[PassiveLost]
      assert(watch.applyEvents(ClusterWatchEvents(aId, passiveLostEvent, passiveLost)).await(99.s) ==
        Right(Completed))

      assert(watch.applyEvents(ClusterWatchEvents(bId, failedOverEvent, failedOver)).await(99.s)
        == Left(ClusterFailOverWhilePassiveLostProblem))

      assert(watch.isActive(aId).await(99.s).orThrow)
    }

    "FailedOver after heartbeat loss" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))
      scheduler.tick(timing.clusterWatchHeartbeatValidDuration)

      val events = Seq(ClusterFailedOver(aId, bId, failedAt))
      val clusterWatch2 = clusterState.applyEvents(events.map(NoKey <-: _))
        .orThrow.asInstanceOf[FailedOver]
      assert(watch.applyEvents(ClusterWatchEvents(bId, events, clusterWatch2)).await(99.s) ==
        Right(Completed))
      assert(watch.isActive(bId).await(99.s).orThrow)
    }

    "Coupled" in {
      val clusterState = NodesAppointed(setting)
      implicit val watch = newClusterWatch()

      val couplingPreparedEvent = ClusterCouplingPrepared(aId)
      val couplingPrepared = clusterState.applyEvent(couplingPreparedEvent)
        .orThrow.asInstanceOf[PreparedToBeCoupled]
      assert(applyEvents(clusterState, aId, Seq(couplingPreparedEvent), couplingPrepared).isRight)

      val coupledEvent = ClusterCoupled(aId)
      val coupled = couplingPrepared.applyEvent(coupledEvent).orThrow.asInstanceOf[Coupled]
      assert(applyEvents(couplingPrepared, aId, Seq(coupledEvent), coupled).isRight)
    }

    "SwitchedOver before heartbeat" in {
      testSwitchOver(1.s)
    }

    "SwitchedOver after heartbeat loss" in {
      testSwitchOver(timing.clusterWatchHeartbeatValidDuration + 1.s)
    }

    def testSwitchOver(duration: FiniteDuration): Unit = {
      val clusterState = Coupled(setting)
      implicit val watch = newClusterWatch(Some(clusterState))
      scheduler.tick(duration)
      val event = ClusterSwitchedOver(bId)
      val switchedOver = SwitchedOver(setting.copy(activeId = bId))
      assert(applyEvents(clusterState, aId, Seq(event), switchedOver).isRight)
      assert(applyEvents(clusterState, bId, Seq(event), switchedOver).isRight)
      assert(watch.isActive(bId).await(99.s).orThrow)
    }

    "applyEvents after event loss" in {
      val clusterState = Coupled(setting)
      val watch = newClusterWatch(Some(clusterState))

      // We test the loss of a PassiveLost event, and then apply Coupled
      val passiveLostEvent = ClusterPassiveLost(bId)
      val passiveLost = PassiveLost(setting.copy(activeId = aId))
      assert(clusterState.applyEvent(passiveLostEvent) == Right(passiveLost))

      val nextEvents = Seq(
        ClusterCouplingPrepared(aId),
        ClusterCoupled(aId))
      val coupled = Coupled(setting.copy(activeId = aId))
      assert(passiveLost.applyEvents(nextEvents.map(NoKey <-: _)) == Right(coupled))

      assert(watch.applyEvents(ClusterWatchEvents(aId, nextEvents, coupled)).await(99.s) == Right(Completed))
      assert(watch.clusterState.await(99.s) == Right(coupled))
    }

    def applyEvents(
      clusterState: ClusterState,
      from: NodeId,
      events: Seq[ClusterEvent],
      expectedClusterState: HasNodes)
      (implicit watch: ClusterWatch)
    : Checked[Completed] = {
      assert(expectedClusterState == clusterState.applyEvents(events.map(NoKey <-: _)).orThrow)
      val response = watch.applyEvents(ClusterWatchEvents(from, events, expectedClusterState))
        .await(99.s)
      assert(watch.clusterState.await(99.s) == Right(expectedClusterState))
      response
    }
  }

  "State.canBeTheActiveNode" in {
    assert(ClusterWatch.State(Coupled(setting), now - setting.timing.heartbeat, None).canBeTheActiveNode(aId))
  }

  "requireLostAck" - {
    "ClusterFailedOver" in {
      import setting.{activeId, passiveId}
      checkRequireLostAck(from = passiveId, ClusterFailedOver(activeId, activatedId = passiveId, failedAt))
    }

    "ClusterPassiveLost" in {
      import setting.{activeId, passiveId}
      checkRequireLostAck(from = activeId, ClusterPassiveLost(passiveId))
    }

    def checkRequireLostAck(from: NodeId, event: ClusterNodeLostEvent): Unit = {
      val coupled = Coupled(setting)
      import coupled.{activeId, passiveId}

      lazy val watch = new ClusterWatch(
        ControllerId("CONTROLLER"),
        () => scheduler.now,
        requireLostAck = true)

      // Initialize ClusterWatch
      watch.heartbeat(activeId, coupled).await(99.s).orThrow

      assert(watch.acknowledgeLostNode(activeId).await(99.s) == Left(NoClusterNodeLostProblem))
      assert(watch.acknowledgeLostNode(passiveId).await(99.s) == Left(NoClusterNodeLostProblem))

      scheduler.tick(setting.timing.clusterWatchHeartbeatValidDuration)
      assert(watch.acknowledgeLostNode(activeId).await(99.s) == Left(NoClusterNodeLostProblem))
      assert(watch.acknowledgeLostNode(passiveId).await(99.s) == Left(NoClusterNodeLostProblem))

      val expectedClusterState = coupled.applyEvent(NoKey <-: event).orThrow.asInstanceOf[HasNodes]

      // Event is rejected because Node loss has not yet been acknowledged
      val response = watch
        .applyEvents(ClusterWatchEvents(from, Seq(event), expectedClusterState))
        .await(99.s)
      assert(response == Left(ClusterNodeLossNotAcknowledgedProblem(event)))
      assert(watch.currentClusterState == Some(coupled))

      // Try to acknowledge a loss of the not lost Node
      import event.lostNodeId
      val notLostNodeId = setting.other(lostNodeId)
      assert(watch.acknowledgeLostNode(notLostNodeId).await(99.s) ==
        Left(NoClusterNodeLostProblem))

      // Acknowledge the loss of the Node
      watch.acknowledgeLostNode(lostNodeId).await(99.s).orThrow
      //scheduler.tick(setting.timing.clusterWatchHeartbeatValidDuration)
      watch
        .applyEvents(ClusterWatchEvents(notLostNodeId, Seq(event), expectedClusterState))
        .await(99.s)
        .orThrow
      assert(watch.currentClusterState == Some(expectedClusterState))

      assert(watch.acknowledgeLostNode(activeId).await(99.s) == Left(NoClusterNodeLostProblem))
      assert(watch.acknowledgeLostNode(passiveId).await(99.s) == Left(NoClusterNodeLostProblem))
    }
  }
}
