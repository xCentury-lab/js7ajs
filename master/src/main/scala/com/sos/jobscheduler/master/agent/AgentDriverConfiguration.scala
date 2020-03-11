package com.sos.jobscheduler.master.agent

import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.common.http.configuration.RecouplingStreamReaderConf
import com.sos.jobscheduler.common.time.JavaTimeConverters._
import com.sos.jobscheduler.core.configuration.RecouplingStreamReaderConfs
import com.sos.jobscheduler.core.event.journal.JournalConf
import com.typesafe.config.Config
import scala.concurrent.duration._

final case class AgentDriverConfiguration(
  recouplingStreamReader: RecouplingStreamReaderConf,
  eventBufferDelay: FiniteDuration,
  eventBufferSize: Int,
  commitDelay: FiniteDuration,
  commandBatchSize: Int,
  commandBatchDelay: FiniteDuration,
  commandErrorDelay: FiniteDuration,
  commandParallelism: Int,
  keepEventsPeriod: FiniteDuration)

object AgentDriverConfiguration
{
  def fromConfig(config: Config, journalConf: JournalConf): Checked[AgentDriverConfiguration] =
    RecouplingStreamReaderConfs.fromConfig(config)
      .flatMap(recouplingStreamReader =>
        Checked.catchNonFatal {
          new AgentDriverConfiguration(
            recouplingStreamReader,
            eventBufferDelay    = config.getDuration("jobscheduler.master.agent-driver.event-buffer-delay").toFiniteDuration,
            eventBufferSize     = config.getInt     ("jobscheduler.master.agent-driver.event-buffer-size"),
            commitDelay         = journalConf.delay,
            commandBatchSize    = config.getInt     ("jobscheduler.master.agent-driver.command-batch-size"),
            commandBatchDelay   = config.getDuration("jobscheduler.master.agent-driver.command-batch-delay").toFiniteDuration,
            commandErrorDelay   = config.getDuration("jobscheduler.master.agent-driver.command-error-delay").toFiniteDuration,
            commandParallelism  = config.getInt     ("jobscheduler.master.agent-driver.command-parallelism"),
            keepEventsPeriod    = config.getDuration("jobscheduler.master.agent-driver.keep-events-period").toFiniteDuration)
        })
}
