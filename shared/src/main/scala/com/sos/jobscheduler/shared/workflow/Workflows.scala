package com.sos.jobscheduler.shared.workflow

import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.workflow.Instruction.{@:, End, ForkJoin, Gap, Goto, IfErrorGoto, IfReturnCode, Job, Labeled}
import com.sos.jobscheduler.data.workflow.Workflow

/**
  * @author Joacim Zschimmer
  */
object Workflows {

  implicit class ExecutableWorkflow(val underlying: Workflow) extends AnyVal {
    import underlying._

    def reduceForAgent(agentPath: AgentPath): Workflow =
      Workflow(labeledInstructions map {
        case labels @: (instr: IfReturnCode) ⇒
          labels @: instr.copy(workflows = instr.workflows map (_.reduceForAgent(agentPath)))

        case labels @: (fj: ForkJoin) if fj isPartiallyExecutableOnAgent agentPath ⇒
          labels @: ForkJoin(
            for (b ← fj.branches) yield
              b.copy(workflow = b.workflow.reduceForAgent(agentPath)))

        case o @ (_ @: (job: Job)) if job isExecutableOnAgent agentPath ⇒
          o

        case o @ (_ @: (_: End | _: IfErrorGoto | _: Goto ))  ⇒
          o

        case Labeled(labels, _) ⇒
          Labeled(labels, Gap)
      })
  }
}
