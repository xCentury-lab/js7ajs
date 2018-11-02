package com.sos.jobscheduler.tests

import akka.actor.ActorSystem
import cats.data.Validated.Invalid
import cats.syntax.option._
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.process.Processes.{ShellFileExtension ⇒ sh}
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.filebased.{SourceType, VersionId}
import com.sos.jobscheduler.data.job.ExecutablePath
import com.sos.jobscheduler.data.order.OrderEvent.{OrderAdded, OrderFinished, OrderStdoutWritten}
import com.sos.jobscheduler.data.order.{FreshOrder, OrderId}
import com.sos.jobscheduler.data.workflow.instructions.Execute
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.{Workflow, WorkflowId, WorkflowPath}
import com.sos.jobscheduler.master.RunningMaster
import com.sos.jobscheduler.master.data.MasterCommand.ReadConfigurationDirectory
import com.sos.jobscheduler.master.tests.TestEventCollector
import java.nio.file.Files.delete
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec
import scala.language.higherKinds
import scala.util.Try

final class MasterRepoTest extends FreeSpec {
  import MasterRepoTest._

  "test" in {
    autoClosing(new DirectoryProvider(List(TestAgentPath))) { directoryProvider ⇒
      val eventCollector = new TestEventCollector
      for (v ← 1 to 4)  // For each version, we use a dedicated job which echos the VersionId
        directoryProvider.agents.head.writeExecutable(ExecutablePath(s"/EXECUTABLE-V$v$sh"), s"echo /VERSION-$v/")

      directoryProvider.runAgents() { _ ⇒
        directoryProvider.runMaster(eventCollector = Some(eventCollector)) { master ⇒
          // Add Workflow
          addWorkflowAndRunOrder(master, V1, AWorkflowPath, OrderId("A"))

          // Command is rejected due to duplicate VersionId
          assert(Try { master.executeCommandAsSystemUser(ReadConfigurationDirectory(V1.some)) await 99.s }
            .failed.get.getMessage contains s"Duplicate VersionId '${V1.string}'")

          // Add Workflow
          addWorkflowAndRunOrder(master, V2, BWorkflowPath, OrderId("B"))

          // Change Workflow
          changeWorkflowAndRunOrder(master, V3, AWorkflowPath, OrderId("A-3"))
        }
        // Recovery
        directoryProvider.runMaster() { master ⇒
          // V2
          eventCollector.start(master.injector.instance[ActorSystem], master.injector.instance[StampedKeyedEventBus])
          // Previously defined workflow is still known
          runOrder(master, BWorkflowPath % V2, OrderId("B-AGAIN"))

          // V4 - Add and use a new workflow
          addWorkflowAndRunOrder(master, V4, CWorkflowPath, OrderId("C"))

          // Change workflow
          directoryProvider.master.writeJson(testWorkflow(V5) withId CWorkflowPath % VersionId.Anonymous)
          master.executeCommandAsSystemUser(ReadConfigurationDirectory(V5.some)) await 99.s

          // Delete workflow
          delete(directoryProvider.master.file(CWorkflowPath, SourceType.Json))
          master.executeCommandAsSystemUser(ReadConfigurationDirectory(V6.some)) await 99.s
          assert(Try { runOrder(master, CWorkflowPath % V6, OrderId("B-6")) }
            .failed.get.getMessage contains s"Has been deleted: Workflow:${CWorkflowPath.string}")

          // Command is rejected due to duplicate VersionId
          assert(Try { master.executeCommandAsSystemUser(ReadConfigurationDirectory(V2.some)) await 99.s }
            .failed.get.getMessage contains s"Duplicate VersionId '${V2.string}'")

          // AWorkflowPath is still version V3
          runOrder(master, AWorkflowPath % V3, OrderId("A-3"))
          runOrder(master, BWorkflowPath % V2, OrderId("B-2"))
        }
      }

      def addWorkflowAndRunOrder(master: RunningMaster, versionId: VersionId, path: WorkflowPath, orderId: OrderId): Unit = {
        val order = FreshOrder(orderId, path)
        // Command will be rejected because workflow is not yet defined
        assert(master.addOrder(order).runAsync.await(99.s) == Invalid(Problem(s"No such key 'Workflow:${path.string}'")))
        defineWorkflowAndRunOrder(master, versionId, path, orderId)
      }

      def changeWorkflowAndRunOrder(master: RunningMaster, versionId: VersionId, path: WorkflowPath, orderId: OrderId): Unit =
        defineWorkflowAndRunOrder(master, versionId, path, orderId)

      def defineWorkflowAndRunOrder(master: RunningMaster, versionId: VersionId, path: WorkflowPath, orderId: OrderId): Unit = {
        val workflow = testWorkflow(versionId)
        assert(workflow.isAnonymous)
        val order = FreshOrder(orderId, path)
        // Add Workflow
        directoryProvider.master.writeJson(workflow withId path % VersionId.Anonymous)
        master.executeCommandAsSystemUser(ReadConfigurationDirectory(versionId.some)) await 99.s
        master.addOrderBlocking(order)
        awaitOrder(order.id, path % versionId)
      }

      def runOrder(master: RunningMaster, workflowId: WorkflowId, orderId: OrderId): Unit = {
        val order = FreshOrder(orderId, workflowId.path)
        master.addOrderBlocking(order)
        awaitOrder(orderId, workflowId)
      }

      def awaitOrder(orderId: OrderId, workflowId: WorkflowId): Unit = {
        val orderAdded: OrderAdded = eventCollector.await[OrderAdded](_.key == orderId).head.value.event
        assert(orderAdded.workflowId == workflowId)
        val written = eventCollector.await[OrderStdoutWritten](_.key == orderId).head.value.event
        assert(written.chunk contains s"/VERSION-${workflowId.versionId.string}/")
        eventCollector.await[OrderFinished](_.key == orderId)
      }
    }
  }
}

object MasterRepoTest {
  private val AWorkflowPath = WorkflowPath("/A")
  private val BWorkflowPath = WorkflowPath("/B")
  private val CWorkflowPath = WorkflowPath("/C")
  private val V1 = VersionId("1")
  private val V2 = VersionId("2")
  private val V3 = VersionId("3")
  private val V4 = VersionId("4")
  private val V5 = VersionId("5")
  private val V6 = VersionId("6")
  private val TestAgentPath = AgentPath("/AGENT")

  private def testWorkflow(versionId: VersionId) = Workflow.of(
    Execute(WorkflowJob(TestAgentPath, ExecutablePath(s"/EXECUTABLE-V${versionId.string}$sh"))))

  private implicit class WithVersionWorkflow(private val underlying: Workflow) extends AnyVal {
    def withVersion(versionId: VersionId): Workflow =
      underlying.copy(id = underlying.id.copy(versionId = versionId))
  }
}
