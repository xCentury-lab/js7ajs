package com.sos.jobscheduler.agent

import com.google.inject.{AbstractModule, Provides}
import com.sos.jobscheduler.agent.AgentClientCommandMarshallingTest._
import com.sos.jobscheduler.agent.client.SimpleAgentClient
import com.sos.jobscheduler.agent.command.CommandHandler
import com.sos.jobscheduler.agent.data.commands.AgentCommand
import com.sos.jobscheduler.agent.data.commands.AgentCommand.{EmergencyStop, ShutDown}
import com.sos.jobscheduler.agent.tests.AgentTester
import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.base.utils.ScalaUtils._
import com.sos.jobscheduler.base.utils.SideEffect.ImplicitSideEffect
import com.sos.jobscheduler.common.scalautil.Closer.ops._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.core.command.CommandMeta
import javax.inject.Singleton
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._

/**
 * @author Joacim Zschimmer
 */
final class AgentClientCommandMarshallingTest
extends FreeSpec with ScalaFutures with AgentTester {

  override protected def extraAgentModule = new AbstractModule {
    @Provides @Singleton
    def commandHandler(): CommandHandler = new CommandHandler {
      def execute(command: AgentCommand, meta: CommandMeta): Task[Checked[command.Response]] =
        Task {
          (command match {
            case ExpectedTerminate => Right(AgentCommand.Response.Accepted)
            case EmergencyStop => Right(AgentCommand.Response.Accepted)
            case _ => throw new NotImplementedError
          })
          .map(_.asInstanceOf[command.Response])
        }

      def overview = throw new NotImplementedError
      def detailed = throw new NotImplementedError
    }
  }
  override implicit val patienceConfig = PatienceConfig(timeout = 10.s)
  private lazy val client = new SimpleAgentClient(agent.localUri).closeWithCloser
    .sideEffect(_.setSessionToken(agent.sessionToken))

  List[(AgentCommand, Checked[AgentCommand.Response])](
    ExpectedTerminate -> Right(AgentCommand.Response.Accepted),
    EmergencyStop -> Right(AgentCommand.Response.Accepted))
  .foreach { case (command, response) =>
    command.getClass.simpleScalaName in {
      assert(client.commandExecute(command).await(99.s) == response)
    }
  }
}

private object AgentClientCommandMarshallingTest {
  private val ExpectedTerminate = ShutDown(sigtermProcesses = true, sigkillProcessesAfter = Some(123.seconds))
}
