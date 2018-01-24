package com.sos.jobscheduler.data.workflow

import cats.syntax.option.catsSyntaxOptionId
import com.sos.jobscheduler.base.circeutils.CirceUtils.JsonStringInterpolator
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.job.ReturnCode
import com.sos.jobscheduler.data.workflow.Instruction.simplify._
import com.sos.jobscheduler.data.workflow.instructions.{ExplicitEnd, ForkJoin, Goto, IfFailedGoto, IfReturnCode, ImplicitEnd, Job}
import com.sos.jobscheduler.data.workflow.test.ForkTestSetting
import com.sos.jobscheduler.data.workflow.test.ForkTestSetting._
import com.sos.jobscheduler.tester.CirceJsonTester.testJson
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class WorkflowTest extends FreeSpec {

  "labelToPosition" in {
    val workflow = Workflow(Vector(
      "A" @: Job(AgentJobPath(AgentPath("/AGENT"), JobPath("/JOB"))),
      IfReturnCode(List(ReturnCode(1)), Vector(Workflow(Vector(
        "B" @: Job(AgentJobPath(AgentPath("/AGENT"), JobPath("/JOB"))))))),
      "B" @: ExplicitEnd))
    assert(workflow.labelToPosition(Nil, Label("A")) == Some(Position(0)))
    assert(workflow.labelToPosition(Nil, Label("B")) == Some(Position(2)))
    assert(workflow.labelToPosition(Position.Parent(1, 0) :: Nil, Label("B")) == Some(Position(1, 0, 0)))
  }

  "Duplicate labels" in {
    assert(intercept[RuntimeException] {
      Workflow(Vector(
        "A" @: Job(AgentJobPath(AgentPath("/AGENT"), JobPath("/JOB"))),
        "A" @: Job(AgentJobPath(AgentPath("/AGENT"), JobPath("/JOB")))))
    }
    .toString contains "Duplicate labels")
  }

  "Missing Label for Goto" in {
    intercept[RuntimeException] {
      Workflow.of(Goto(Label("A")))
    }
  }

  "Missing Label for IfFailedGoto" in {
    intercept[RuntimeException] {
      Workflow.of(IfFailedGoto(Label("A")))
    }
  }

  "jobOption" in {
    assert(TestWorkflow.jobOption(Position(6)) == Job(AAgentJobPath).some)
    assert(TestWorkflow.jobOption(Position(7)) == None)  // ImplicitEnd
    intercept[IndexOutOfBoundsException] {
      assert(TestWorkflow.jobOption(Position(8)) == None)
    }
  }

  "workflowOption" in {
    assert(TestWorkflow.workflowOption(Position(0)) == TestWorkflow.some)
    assert(TestWorkflow.workflowOption(Position(1)) == TestWorkflow.some)
    assert(TestWorkflow.workflowOption(Position(1, "🥕", 1)) == Some(
      TestWorkflow.instruction(1).asInstanceOf[ForkJoin].workflowOption(Position.BranchId("🥕")).get))
  }

  "reduce" in {
    val agentJobPath = AgentJobPath(AgentPath("/AGENT"), JobPath("/JOB-A"))
    val B = Label("B")
    val C = Label("C")
    val D = Label("D")
    val END = Label("END")

    val instructions = Vector[(Instruction.Labeled, Boolean)](
      (()  @: Job(agentJobPath)) → true,
      (()  @: Goto(B))           → true,
      (C   @: Job(agentJobPath)) → true,
      (()  @: Goto(D))           → true,   // reducible?
      (()  @: IfFailedGoto(D))    → false,  // reducible
      (()  @: Goto(D))           → false,  // reducible
      (D   @: Job(agentJobPath)) → true,
      (()  @: Goto(END))         → false,  // reducible
      (END @: ExplicitEnd)       → true,
      (B   @: Job(agentJobPath)) → true,
      (()  @: Goto(C))           → true)
    val a = Workflow(instructions map (_._1))
    assert(a.reduce == Workflow(instructions collect { case (s, true) ⇒ s }))
  }

  "flatten" in {
    assert(ForkTestSetting.TestWorkflow.flatten == Vector[(Position, Instruction.Labeled)](
      (Position(0         ), Job(AAgentJobPath)),
      (Position(1         ), ForkTestSetting.TestWorkflow.instruction(1)),
      (Position(1, "🥕", 0), Job(AAgentJobPath)),
      (Position(1, "🥕", 1), Job(AAgentJobPath)),
      (Position(1, "🥕", 2), ImplicitEnd),
      (Position(1, "🍋", 0), Job(AAgentJobPath)),
      (Position(1, "🍋", 1), Job(BAgentJobPath)),
      (Position(1, "🍋", 2), ImplicitEnd),
      (Position(2         ), Job(AAgentJobPath)),
      (Position(3         ), ForkTestSetting.TestWorkflow.instruction(3)),
      (Position(3, "🥕", 0), Job(AAgentJobPath)),
      (Position(3, "🥕", 1), Job(AAgentJobPath)),
      (Position(3, "🥕", 2), ImplicitEnd),
      (Position(3, "🍋", 0), Job(AAgentJobPath)),
      (Position(3, "🍋", 1), Job(AAgentJobPath)),
      (Position(3, "🍋", 2), ImplicitEnd),
      (Position(4         ), Job(AAgentJobPath)),
      (Position(5         ), ForkTestSetting.TestWorkflow.instruction(5)),
      (Position(5, "🥕", 0), Job(AAgentJobPath)),
      (Position(5, "🥕", 1), Job(AAgentJobPath)),
      (Position(5, "🥕", 2), ImplicitEnd),
      (Position(5, "🍋", 0), Job(BAgentJobPath)),
      (Position(5, "🍋", 1), Job(BAgentJobPath)),
      (Position(5, "🍋", 2), ImplicitEnd),
      (Position(6         ), Job(AAgentJobPath)),
      (Position(7         ), ImplicitEnd)))
  }

  "isDefinedAt, instruction" in {
    val addressToInstruction = List(
      Position(0) → Job(AAgentJobPath),
      Position(1) → ForkJoin.of(
        "🥕" → Workflow.of(Job(AAgentJobPath), Job(AAgentJobPath)),
        "🍋" → Workflow.of(Job(AAgentJobPath), Job(BAgentJobPath))),
      Position(1, "🥕", 0) → Job(AAgentJobPath),
      Position(1, "🥕", 1) → Job(AAgentJobPath),
      Position(1, "🥕", 2) → ImplicitEnd,
      Position(1, "🍋", 0) → Job(AAgentJobPath),
      Position(1, "🍋", 1) → Job(BAgentJobPath),
      Position(1, "🍋", 2) → ImplicitEnd,
      Position(2) → Job(AAgentJobPath),
      Position(3) → ForkJoin.of(
        "🥕" → Workflow.of(Job(AAgentJobPath), Job(AAgentJobPath)),
        "🍋" → Workflow.of(Job(AAgentJobPath), Job(AAgentJobPath))),
      Position(3, "🥕", 0) → Job(AAgentJobPath),
      Position(3, "🥕", 1) → Job(AAgentJobPath),
      Position(3, "🥕", 2) → ImplicitEnd,
      Position(3, "🍋", 0) → Job(AAgentJobPath),
      Position(3, "🍋", 1) → Job(AAgentJobPath),
      Position(3, "🍋", 2) → ImplicitEnd,
      Position(4) → Job(AAgentJobPath),
      Position(5) → ForkJoin.of(
        "🥕" → Workflow.of(Job(AAgentJobPath), Job(AAgentJobPath)),
        "🍋" → Workflow.of(Job(BAgentJobPath), Job(BAgentJobPath))),
      Position(5, "🥕", 0) → Job(AAgentJobPath),
      Position(5, "🥕", 1) → Job(AAgentJobPath),
      Position(5, "🥕", 2) → ImplicitEnd,
      Position(5, "🍋", 0) → Job(BAgentJobPath),
      Position(5, "🍋", 1) → Job(BAgentJobPath),
      Position(5, "🍋", 2) → ImplicitEnd,
      Position(6) → Job(AAgentJobPath),
      Position(7) → ImplicitEnd)

    for ((address, instruction) ← addressToInstruction) {
      assert(TestWorkflow isDefinedAt address)
      assert(TestWorkflow.instruction(address) == instruction, s" - $address")
    }
    assert(!TestWorkflow.isDefinedAt(Position(8)))
  //assert(!TestWorkflow.isDefinedAt(Position(0, "🥕")))
    assert(!TestWorkflow.isDefinedAt(Position(0, "🥕", 0)))
  //assert(!TestWorkflow.isDefinedAt(Position(0, "🥕")))
    assert(!TestWorkflow.isDefinedAt(Position(0, "🥕", 3)))
  }

  "JSON" in {
    testJson(ForkTestSetting.TestWorkflow, json"""{
      "source": "job \"JOB\" on \"AGENT-A\";\nfork(\n  \"🥕\" { job \"JOB\" on \"AGENT-A\"; job \"JOB\" on \"AGENT-A\"; },\n  \"🍋\" { job \"JOB\" on \"AGENT-A\"; job \"JOB\" on \"AGENT-B\"; });\njob \"JOB\" on \"AGENT-A\";\nfork(\n  \"🥕\" { job \"JOB\" on \"AGENT-A\"; job \"JOB\" on \"AGENT-A\"; },\n  \"🍋\" { job \"JOB\" on \"AGENT-A\"; job \"JOB\" on \"AGENT-A\"; });\njob \"JOB\" on \"AGENT-A\";\nfork(\n  \"🥕\" { job \"JOB\" on \"AGENT-A\"; job \"JOB\" on \"AGENT-A\"; },\n  \"🍋\" { job \"JOB\" on \"AGENT-B\"; job \"JOB\" on \"AGENT-B\"; });\njob \"JOB\" on \"AGENT-A\";",
      "instructions": [
        { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
        {
          "TYPE": "ForkJoin",
          "branches": [
            {
              "id": "🥕",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }, {
              "id": "🍋",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-B", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }
          ]
        },
        { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
        {
          "TYPE": "ForkJoin",
          "branches": [
            {
              "id": "🥕",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }, {
              "id": "🍋",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }
          ]
        },
        { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
        {
          "TYPE": "ForkJoin",
          "branches": [
            {
              "id": "🥕",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }, {
              "id": "🍋",
              "workflow": {
                "instructions": [
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-B", "jobPath": "/JOB" }},
                  { "TYPE": "Job", "job": { "agentPath": "/AGENT-B", "jobPath": "/JOB" }},
                  { "TYPE": "ImplicitEnd" }
                ]
              }
            }
          ]
        },
        { "TYPE": "Job", "job": { "agentPath": "/AGENT-A", "jobPath": "/JOB" }},
        { "TYPE": "ImplicitEnd" }
      ]
    }""")
  }
}
