package com.sos.jobscheduler.agent.client

import akka.http.scaladsl.model.Uri
import com.sos.jobscheduler.common.akkautils.Akkas.newActorSystem
import com.sos.jobscheduler.data.agent.AgentAddress

/**
 * Simple client for JobScheduler Agent.
 * <p>
 * Should be closed after use, to close all remaining HTTP connections.
 *
 * @author Joacim Zschimmer
 */
final class SimpleAgentClient private(val agentUri: Uri) extends AgentClient with AutoCloseable {

  protected val licenseKeys = Nil
  protected val actorSystem = newActorSystem("SimpleAgentClient")
  protected def executionContext = actorSystem.dispatcher
  protected def httpsConnectionContextOption = None
  protected def userAndPasswordOption = None

  override def close() = {
    try super.close()
    finally actorSystem.terminate()
  }
}

object SimpleAgentClient {
  def apply(agentUri: AgentAddress) = new SimpleAgentClient(agentUri.string)
}
