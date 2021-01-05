package js7.agent

import java.nio.file.Files.createDirectory
import java.nio.file.Path
import js7.agent.AgentTest._
import js7.agent.configuration.AgentConfiguration
import js7.agent.data.commands.AgentCommand
import js7.agent.data.commands.AgentCommand.{AttachOrder, RegisterAsController}
import js7.agent.tests.AgentTester
import js7.agent.tests.TestAgentDirectoryProvider.provideAgentDirectory
import js7.base.auth.SimpleUser
import js7.base.system.OperatingSystem.isWindows
import js7.base.time.ScalaTime._
import js7.common.process.Processes.{ShellFileExtension => sh}
import js7.common.scalautil.FileUtils.WorkingDirectory
import js7.common.scalautil.FileUtils.syntax._
import js7.common.scalautil.MonixUtils.syntax._
import js7.core.command.CommandMeta
import js7.data.agent.AgentId
import js7.data.controller.ControllerId
import js7.data.job.RelativeExecutablePath
import js7.data.order.OrderEvent.OrderProcessed
import js7.data.order.{Order, OrderId, Outcome}
import js7.data.value.{NumericValue, StringValue}
import js7.data.workflow.instructions.Execute
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.test.TestSetting.TestAgentId
import js7.data.workflow.{Workflow, WorkflowPath}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class AgentTest extends AnyFreeSpec with AgentTester
{
  "state/http-uri" in {
    assert((agentConfiguration.stateDirectory / "http-uri").contentString == s"${agent.localUri}/agent")
    agent.terminate() await 99.s
  }

  "Job working directory" - {
    for ((testName, toWorkingDirectory) <-
           Array[(String, Path => Path)](
             ("default", _ => WorkingDirectory),
             ("not default", _ / "working")))
      testName in {
        provideAgentDirectory { directory =>
          createDirectory(directory / "working")
          val workingDirectory = toWorkingDirectory(directory).toRealPath()
          TestExecutablePath.toFile(directory / "config" / "executables").writeExecutable(TestScript)
          var agentConf = AgentConfiguration.forTest(directory)
          if (directory != WorkingDirectory) {
            agentConf = agentConf.copy(jobWorkingDirectory = workingDirectory)
          }
          RunningAgent.run(agentConf, timeout = Some(99.s)) { agent =>
            val agentApi = agent.api(CommandMeta(TestUser))
            assert(agentApi.commandExecute(RegisterAsController(agentId)).await(99.s).toOption.get
              .isInstanceOf[RegisterAsController.Response])

            val order = Order(OrderId("TEST"), TestWorkflow.id, Order.Ready)
            assert(agentApi.commandExecute(AttachOrder(order, TestAgentId, itemSigner.sign(TestWorkflow))).await(99.s)
              == Right(AgentCommand.Response.Accepted))
            val Right(eventWatch) = agentApi.eventWatchForController(TestControllerId).await(99.s)
            val orderProcessed = eventWatch.await[OrderProcessed]().head.value.event
            assert(orderProcessed.outcome == Outcome.Succeeded(Map("returnCode" -> NumericValue(0), "WORKDIR" -> StringValue(workingDirectory.toString))))
            agent.terminate() await 99.s
          }
        }
      }
  }
}

object AgentTest {
  private val TestControllerId = ControllerId("CONTROLLER")
  private val TestUser = SimpleUser(TestControllerId.toUserId)
  private val agentId = AgentId("AGENT")

  private val TestScript =
    if (isWindows) """
      |@echo off
      |set /p dummy=WORKDIR= <nul >%SCHEDULER_RETURN_VALUES%
      |cd >>%SCHEDULER_RETURN_VALUES%
      |""".stripMargin
    else """
      |echo TEST TEST
      |echo "WORKDIR=$(pwd)" >$SCHEDULER_RETURN_VALUES
      |""".stripMargin

  private val TestExecutablePath = RelativeExecutablePath(s"TEST$sh")

  private val TestWorkflow = Workflow.of(
    WorkflowPath("/WORKFLOW") ~ "VERSION",
    Execute(WorkflowJob(TestAgentId, TestExecutablePath)))
}
