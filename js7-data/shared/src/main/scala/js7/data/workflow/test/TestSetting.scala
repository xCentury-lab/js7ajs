package js7.data.workflow.test

import js7.data.agent.AgentId
import js7.data.job.RelativeExecutablePath
import js7.data.order.{Order, OrderId}
import js7.data.value.StringValue
import js7.data.workflow.instructions.Execute
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.{Workflow, WorkflowPath}

/**
  * For tests only.
  */
private[js7] object TestSetting
{
  val TestAgentId = AgentId("AGENT")
  val AJobName = WorkflowJob.Name("A")
  val BJobName = WorkflowJob.Name("B")
  val AExecutablePath = RelativeExecutablePath("A.cmd", v1Compatible = true)
  val BExecutablePath = RelativeExecutablePath("B.cmd", v1Compatible = true)
  val AJob = WorkflowJob(TestAgentId, AExecutablePath, Map("JOB_A" -> StringValue("A-VALUE")), taskLimit = 3)
  val BJob = WorkflowJob(TestAgentId, BExecutablePath, Map("JOB_B" -> StringValue("B-VALUE")), taskLimit = 3)
  val B1Job = WorkflowJob(TestAgentId, BExecutablePath, Map("JOB_B1" -> StringValue("B1-VALUE")), taskLimit = 3)
  val AExecute = Execute(AJob)
  val BExecute = Execute(BJob)
  val TestExecutablePaths = Vector(AExecutablePath, BExecutablePath)

  val SimpleTestWorkflow = Workflow.of(
    WorkflowPath("/WORKFLOW") ~ "VERSION",
    AExecute,
    BExecute)

  val TestOrder = Order(OrderId("TEST"), SimpleTestWorkflow.id, Order.Ready, Map("KEY" -> StringValue("VALUE")))
}
