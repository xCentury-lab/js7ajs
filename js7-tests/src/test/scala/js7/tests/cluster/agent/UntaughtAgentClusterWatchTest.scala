package js7.tests.cluster.agent

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import js7.agent.data.commands.AgentCommand
import js7.agent.{RunningAgent, TestAgent}
import js7.base.configutils.Configs.HoconStringInterpolator
import js7.base.io.process.ProcessSignal.SIGKILL
import js7.base.log.Logger
import js7.base.test.OurTestSuite
import js7.base.thread.CatsBlocking.syntax.*
import js7.base.time.ScalaTime.*
import js7.base.utils.Allocated
import js7.base.utils.CatsUtils.syntax.RichResource
import js7.base.utils.ScalaUtils.syntax.RichEither
import js7.common.auth.SecretStringGenerator
import js7.common.utils.FreeTcpPortFinder.findFreeLocalUri
import js7.data.agent.AgentRefStateEvent.{AgentClusterWatchConfirmationRequired, AgentClusterWatchManuallyConfirmed, AgentMirroredEvent}
import js7.data.agent.{AgentPath, AgentRef, AgentRefState}
import js7.data.cluster.ClusterEvent.{ClusterCoupled, ClusterFailedOver, ClusterPassiveLost}
import js7.data.cluster.ClusterTiming
import js7.data.cluster.ClusterWatchProblems.ClusterNodeLossNotConfirmedProblem
import js7.data.controller.ControllerCommand.ConfirmClusterNodeLoss
import js7.data.node.NodeId
import js7.data.subagent.{SubagentId, SubagentItem, SubagentSelection, SubagentSelectionId}
import js7.data.value.expression.Expression.StringConstant
import js7.data.workflow.{Workflow, WorkflowPath}
import js7.tests.cluster.agent.UntaughtAgentClusterWatchTest.*
import js7.tests.cluster.controller.ControllerClusterTester.*
import js7.tests.jobs.SemaphoreJob
import js7.tests.testenv.DirectoryProviderForScalaTest

final class UntaughtAgentClusterWatchTest extends OurTestSuite, DirectoryProviderForScalaTest:

  private given IORuntime = ioRuntime

  private final val testHeartbeatLossPropertyKey = "js7.TEST." + SecretStringGenerator.randomString()
  private final val testAckLossPropertyKey = "js7.TEST." + SecretStringGenerator.randomString()
  sys.props(testAckLossPropertyKey) = "false"
  sys.props(testHeartbeatLossPropertyKey) = "false"

  override def agentConfig = config"""
    js7.journal.cluster.heartbeat = ${clusterTiming.heartbeat}
    js7.journal.cluster.heartbeat-timeout = ${clusterTiming.heartbeatTimeout}
    js7.journal.cluster.TEST-HEARTBEAT-LOSS = "$testHeartbeatLossPropertyKey"
    js7.journal.cluster.TEST-ACK-LOSS = "$testAckLossPropertyKey"
    """

  protected override val agentPaths = Nil

  private lazy val subagentItems =
    for subagentId <- subagentIds yield SubagentItem(subagentId, agentPath, findFreeLocalUri())

  override protected def items =
    Seq(TestWorkflow, workflow, agentRef, subagentSelection) ++ subagentItems

  "test" in:
    def allocateDirector(director: SubagentItem, otherDirectorId: SubagentId, backup: Boolean = false)
    : Allocated[IO, RunningAgent] =
      directoryProvider
        .directorEnvResource(director, otherSubagentIds = Seq(otherDirectorId),
          isClusterBackup = backup)
        .flatMap(_.directorResource)
        .toAllocated
        .await(99.s)

    val primaryDirectorAllocated: Allocated[IO, RunningAgent] =
      allocateDirector(subagentItems(0), subagentItems(1).id)

    val backupDirectorAllocated: Allocated[IO, RunningAgent] =
      allocateDirector(subagentItems(1), subagentItems(0).id, backup = true)

    TestAgent(primaryDirectorAllocated).useSync(99.s) { primaryDirector =>
      TestAgent(backupDirectorAllocated).useSync(99.s) { backupDirector =>
        directoryProvider.runController() { controller =>
          controller.eventWatch.await[AgentMirroredEvent](
            _.event.keyedEvent.event.isInstanceOf[ClusterCoupled])
        }

        // Suppress acknowledges heartbeat, simulating a connection loss between the cluster nodes
        logger.info("💥 Break connection between cluster nodes 💥")
        sys.props(testAckLossPropertyKey) = "true"
        sys.props(testHeartbeatLossPropertyKey) = "true"
        sleep(clusterTiming.activeLostTimeout + 1.s)
        // Now, both cluster nodes require confirmation for their ClusterNodeLostEvent

        directoryProvider.runController() { controller =>
          // The newly started, untaught AgentDriver's ClusterWatch cannot decide to confirm:
          controller.eventWatch.await[AgentClusterWatchConfirmationRequired](ke =>
            ke.key == agentPath && ke.event.problem.fromNodeId == NodeId("Primary"))

          controller.eventWatch.await[AgentClusterWatchConfirmationRequired](ke =>
            ke.key == agentPath && ke.event.problem.fromNodeId == NodeId("Backup"))

          val nodeToClusterWatchConfirmationRequired =
            controller.controllerState().keyTo(AgentRefState)(agentPath).nodeToClusterNodeProblem
          logger.info(s"nodeToClusterNodeProblem=$nodeToClusterWatchConfirmationRequired")

          assert(nodeToClusterWatchConfirmationRequired(NodeId.primary) ==
            ClusterNodeLossNotConfirmedProblem(
              NodeId.primary, ClusterPassiveLost(NodeId.backup)))
          assert(nodeToClusterWatchConfirmationRequired(NodeId.backup).event.isInstanceOf[ClusterFailedOver])

          // FIXME Delay until AgentOrderKeeper does not persist anything,
          // because it cannot be terminated while persisting and we would stick in a deadlock.
          sleep(1.s)

          // Now, the user (we) kill the primary node and confirm this to the ClusterWatch:
          primaryDirector
            .terminate(
              Some(SIGKILL),
              clusterAction = Some(AgentCommand.ShutDown.ClusterAction.Failover))
            .await(99.s)

          controller.api
            .executeCommand(ConfirmClusterNodeLoss(agentPath, lostNodeId = NodeId.primary,
              confirmer = "UntaughtAgentClusterWatchTest"))
            .await(99.s).orThrow

          controller.eventWatch.await[AgentClusterWatchManuallyConfirmed](_.key == agentPath)
          assert(controller.controllerState().keyTo(AgentRefState)(agentPath)
            .nodeToClusterNodeProblem.isEmpty)

          backupDirector.eventWatch.await[ClusterFailedOver]()
        }
      }
    }


object UntaughtAgentClusterWatchTest:
  private val logger = Logger[this.type]

  private val clusterTiming = ClusterTiming(heartbeat = 500.ms, heartbeatTimeout = 500.ms)

  private val subagentIds = Seq(
    SubagentId("SUBAGENT-0"),
    SubagentId("SUBAGENT-1"))

  private val agentPath = AgentPath("AGENT")
  private val agentRef = AgentRef(agentPath, subagentIds)
  private val subagentSelection = SubagentSelection(
    SubagentSelectionId("SUBAGENT-SELECTION"),
    Map(
      subagentIds(0) -> 2,
      subagentIds(1) -> 1))

  private val workflow = Workflow(
    WorkflowPath("MY-WORKFLOW"),
    Seq(ASemaphoreJob.execute(
      AgentPath("AGENT"),
      subagentSelectionId = Some(StringConstant(subagentSelection.id.string)))))

  final class ASemaphoreJob extends SemaphoreJob(ASemaphoreJob)
  object ASemaphoreJob extends SemaphoreJob.Companion[ASemaphoreJob]
