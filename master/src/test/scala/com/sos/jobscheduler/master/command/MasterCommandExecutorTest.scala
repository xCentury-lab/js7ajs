package com.sos.jobscheduler.master.command

import com.sos.jobscheduler.base.auth.{SimpleUser, UserId}
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.core.command.{CommandExecutor, CommandMeta}
import com.sos.jobscheduler.data.command.CancelMode
import com.sos.jobscheduler.data.order.OrderId
import com.sos.jobscheduler.master.data.MasterCommand
import com.sos.jobscheduler.master.data.MasterCommand.{Batch, CancelOrder, KeepEvents, NoOperation, Response}
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FreeSpec
import scala.concurrent.duration._
import scala.language.reflectiveCalls

/**
  * @author Joacim Zschimmer
  */
final class MasterCommandExecutorTest extends FreeSpec
{
  private val cancelOrder = CancelOrder(OrderId("ORDER-ID"), CancelMode.NotStarted)
  private val meta = CommandMeta(SimpleUser(UserId("USER")))

  private val otherCommandExecutor = new CommandExecutor[MasterCommand] {
    var canceled = 0

    def executeCommand(command: MasterCommand, meta: CommandMeta): Task[Either[Problem, command.Response]] =
      (command, meta) match {
        case (`cancelOrder`, `meta`) =>
          canceled += 1
          Task.pure(Right(Response.Accepted.asInstanceOf[command.Response]))
        case (NoOperation, `meta`) =>
          Task.pure(Right(Response.Accepted.asInstanceOf[command.Response]))
        case _ =>
          Task.pure(Left(Problem("COMMAND NOT IMPLEMENTED")))
      }
  }

  private val commandExecutor = new MasterCommandExecutor(otherCommandExecutor)

  "NoOperation" in {
    assert(commandExecutor.executeCommand(NoOperation, meta).await(99.seconds) == Right(Response.Accepted))
  }

  "CancelOrder" in {
    assert(commandExecutor.executeCommand(cancelOrder, meta).await(99.seconds) == Right(Response.Accepted))
    assert(otherCommandExecutor.canceled == 1)
  }

  "Batch" in {
    assert(commandExecutor.executeCommand(Batch(NoOperation :: KeepEvents(999) :: cancelOrder :: Nil), meta).await(99.seconds) ==
      Right(Batch.Response(Right(Response.Accepted) :: Left(Problem("COMMAND NOT IMPLEMENTED")) :: Right(Response.Accepted) :: Nil)))
    assert(otherCommandExecutor.canceled == 2)
  }

  "detailed" in {
    assert(commandExecutor.overview.currentCommandCount == 0)
    assert(commandExecutor.overview.totalCommandCount == 6)
    assert(commandExecutor.detailed.commandRuns.isEmpty)
  }
}
