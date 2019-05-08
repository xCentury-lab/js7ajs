package com.sos.jobscheduler.core.command

import com.sos.jobscheduler.data.command.{CommandHandlerDetailed, CommandHandlerOverview, CommonCommand, InternalCommandId}
import scala.collection.mutable
import scala.concurrent.duration.Deadline.now

/**
  * @author Joacim Zschimmer
  */
final class CommandRegister[C <: CommonCommand]
{
  private var totalCounter = 0L
  private val idToCommand = mutable.Map[InternalCommandId, CommandRun[C]]()
  private val idGenerator = InternalCommandId.newGenerator()

  def add(command: C, batchId: Option[InternalCommandId]): CommandRun[C] =
    synchronized {
      totalCounter += 1
      val id = idGenerator.next()
      val run = CommandRun[C](id, command, now, batchId)
      idToCommand += id -> run
      run
    }

  def remove(id: InternalCommandId): Unit =
    synchronized {
      idToCommand -= id
    }

  def overview: CommandHandlerOverview =
    synchronized {
      CommandHandlerOverview(currentCommandCount = idToCommand.size, totalCommandCount = totalCounter)
    }

  def detailed: CommandHandlerDetailed[C] =
    synchronized {
      CommandHandlerDetailed((idToCommand.values map { _.overview }).toVector)
    }
}
