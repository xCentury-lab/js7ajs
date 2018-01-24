package com.sos.jobscheduler.data.workflow.instructions

import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.order.OrderEvent.OrderMoved
import com.sos.jobscheduler.data.order.Outcome.Disrupted.JobSchedulerRestarted
import com.sos.jobscheduler.data.order.{Order, Outcome}
import com.sos.jobscheduler.data.workflow.{AgentJobPath, EventInstruction, OrderContext}
import io.circe.generic.JsonCodec

/**
  * @author Joacim Zschimmer
  */
@JsonCodec
final case class Job(job: AgentJobPath) extends EventInstruction
{
  def toEvent(order: Order[Order.State], context: OrderContext) =
    // Order.Ready: Job start has to be done by the caller
    for (order ← order.ifState[Order.Processed]) yield
      order.id <-: OrderMoved(
        if (order.state.outcome == Outcome.Disrupted(JobSchedulerRestarted))
          order.position  // Repeat
        else
          order.position.increment)


  def agentPath = job.agentPath

  def jobPath = job.jobPath

  def isExecutableOnAgent(agentPath: AgentPath): Boolean =
    job.agentPath == agentPath

  override def toString = s"job ${jobPath.string} on ${agentPath.string}"
}
