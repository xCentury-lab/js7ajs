package com.sos.jobscheduler.tests

import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.base.auth.{SimpleUser, UserAndPassword, UserId}
import com.sos.jobscheduler.base.generic.SecretString
import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.session.HttpAutoRelogin
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.common.process.Processes.{ShellFileExtension => sh}
import com.sos.jobscheduler.common.scalautil.FileUtils.syntax._
import com.sos.jobscheduler.common.scalautil.MonixUtils.syntax._
import com.sos.jobscheduler.common.time.WaitForCondition.waitForCondition
import com.sos.jobscheduler.core.command.CommandMeta
import com.sos.jobscheduler.core.event.journal.files.JournalFiles.listJournalFiles
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.job.ExecutablePath
import com.sos.jobscheduler.data.order.OrderEvent.OrderFinished
import com.sos.jobscheduler.data.order.{FreshOrder, OrderId}
import com.sos.jobscheduler.data.workflow.instructions.Execute
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.{Workflow, WorkflowPath}
import com.sos.jobscheduler.master.RunningMaster
import com.sos.jobscheduler.master.client.AkkaHttpMasterApi
import com.sos.jobscheduler.master.data.MasterCommand.{KeepEvents, TakeSnapshot}
import com.sos.jobscheduler.master.data.events.MasterEvent
import com.sos.jobscheduler.tests.KeepEventsTest._
import com.sos.jobscheduler.tests.testenv.DirectoryProvider.script
import com.sos.jobscheduler.tests.testenv.DirectoryProviderForScalaTest
import com.typesafe.config.ConfigFactory
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class KeepEventsTest extends FreeSpec with DirectoryProviderForScalaTest
{
  protected val agentRefPaths = TestAgentRefPath :: Nil
  protected val fileBased = TestWorkflow :: Nil
  override protected val masterConfig = ConfigFactory.parseString(
     """jobscheduler {
       |  journal.users-allowed-to-keep-events = [ "A", "B" ]
       |  auth.users {
       |    A = "plain:PASSWORD"
       |    B = "plain:PASSWORD"
       |  }
       |  master.agent-driver.keep-events-period = 0ms
       |}""".stripMargin)

  "KeepEvents" in {
    for ((_, tree) <- directoryProvider.agentToTree) {
      tree.writeExecutable(TestExecutablePath, script(0.s))
    }

    directoryProvider.run { (master, _) =>
      master.eventWatch.await[MasterEvent.MasterReady]()
      master.runOrder(aOrder)
    }

    def masterJournalFiles = listJournalFiles(directoryProvider.master.dataDir / "state" / "master")
    def agentJournalFiles = listJournalFiles(directoryProvider.agents(0).dataDir / "state" / "master-Master")
    assert(masterJournalFiles.size == 2)
    assert(agentJournalFiles.size == 2)

    directoryProvider.run { case (master, Seq(agent)) =>
      import master.eventWatch.{lastFileTornEventId, tornEventId}

      val finished = master.eventWatch.await[OrderFinished](predicate = _.key == aOrder.id)
      assert(finished.size == 1)
      assert(masterJournalFiles.size == 3)
      assert(agentJournalFiles.size <= 3)

      val a = new TestApi(master, aUserAndPassword)
      val b = new TestApi(master, bUserAndPassword)

      a.executeCommand(KeepEvents(finished.head.eventId)).await(99.s)
      assert(masterJournalFiles.size == 3)

      b.executeCommand(KeepEvents(finished.head.eventId)).await(99.s)
      assert(masterJournalFiles.size == 2)

      // Master sends KeepOrder after some events from Agent have arrived. So we start an order.
      master.executeCommandAsSystemUser(TakeSnapshot).await(99.s).orThrow
      val bAdded = master.runOrder(bOrder).head.eventId
      assert(masterJournalFiles.size == 3)

      master.executeCommandAsSystemUser(TakeSnapshot).await(99.s).orThrow
      assert(masterJournalFiles.size == 4)

      master.runOrder(cOrder)
      master.executeCommandAsSystemUser(TakeSnapshot).await(99.s).orThrow
      assert(masterJournalFiles.size == 5)

      b.executeCommand(KeepEvents(bAdded)).await(99.s)
      assert(masterJournalFiles.size == 5)

      a.executeCommand(KeepEvents(lastFileTornEventId)).await(99.s)
      assert(masterJournalFiles.size == 3 && tornEventId <= bAdded)

      b.executeCommand(KeepEvents(lastFileTornEventId)).await(99.s)
      assert(masterJournalFiles.size == 1)

      // TakeSnapshot and KeepSnapshot on last event written should tear this event
      master.executeCommandAsSystemUser(TakeSnapshot).await(99.s).orThrow
      assert(tornEventId < lastFileTornEventId)
      a.executeCommand(KeepEvents(lastFileTornEventId)).await(99.s)
      b.executeCommand(KeepEvents(lastFileTornEventId)).await(99.s)
      //waitForCondition(5.s, 10.ms) { tornEventId == last }
      assert(tornEventId == lastFileTornEventId)

      // Agent's journal file count should be 1 after TakeSnapshot and after Master has read all events
      agent.executeCommand(AgentCommand.TakeSnapshot, CommandMeta(SimpleUser(UserId("Master"))))
        .await(99.s).orThrow
      assert(agentJournalFiles.size == 2)
      master.runOrder(dOrder)
      waitForCondition(5.s, 10.ms) { agentJournalFiles.size == 1 }
      assert(agentJournalFiles.size == 1)
    }
  }
}

private object KeepEventsTest
{
  private val aUserAndPassword = UserAndPassword(UserId("A"), SecretString("PASSWORD"))
  private val bUserAndPassword = UserAndPassword(UserId("B"), SecretString("PASSWORD"))
  private val TestAgentRefPath = AgentRefPath("/agent-111")
  private val TestExecutablePath = ExecutablePath(s"/TEST$sh")
  private val TestWorkflow = Workflow.of(WorkflowPath("/test"),
    Execute(WorkflowJob(TestAgentRefPath, TestExecutablePath)))
  private val aOrder = FreshOrder(OrderId("🔵"), TestWorkflow.id.path)
  private val bOrder = FreshOrder(OrderId("🔶"), TestWorkflow.id.path)
  private val cOrder = FreshOrder(OrderId("⭕️"), TestWorkflow.id.path)
  private val dOrder = FreshOrder(OrderId("🔺"), TestWorkflow.id.path)

  private class TestApi(master: RunningMaster, protected val credentials: UserAndPassword)
  extends AkkaHttpMasterApi.CommonAkka with HttpAutoRelogin {
    protected val userAndPassword = Some(credentials)
    protected val baseUri = master.localUri
    protected val name = "RunningMaster"
    protected def actorSystem = master.actorSystem

    relogin await 99.s
  }
}
