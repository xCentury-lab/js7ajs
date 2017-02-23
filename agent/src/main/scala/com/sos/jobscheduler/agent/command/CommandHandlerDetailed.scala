package com.sos.jobscheduler.agent.command

import com.google.inject.ImplementedBy
import scala.collection.immutable

/**
 * @author Joacim Zschimmer
 */
@ImplementedBy(classOf[AgentCommandHandler])
trait CommandHandlerDetailed  {
  def commandRuns: immutable.Iterable[CommandRunOverview]
}
