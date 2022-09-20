package js7.data.execution.workflow.instructions

import js7.base.utils.ScalaUtils.syntax.*
import js7.data.event.KeyedEvent
import js7.data.order.Order
import js7.data.order.OrderEvent.{OrderActorEvent, OrderDetachable, OrderLocksAcquired, OrderLocksQueued, OrderLocksReleased}
import js7.data.state.StateView
import js7.data.workflow.instructions.LockInstruction

private[instructions] final class LockExecutor(protected val service: InstructionExecutorService)
extends EventInstructionExecutor
{
  type Instr = LockInstruction
  val instructionClass = classOf[LockInstruction]

  def toEvents(instruction: LockInstruction, order: Order[Order.State], state: StateView) =
    detach(order)
      .orElse(start(order))
      .getOrElse(
        if (order.isState[Order.Ready] || order.isState[Order.WaitingForLock])
          state
            .foreachLockDemand(instruction.demands)(_
              .isAvailable(order.id, _))
            .flatMap(availability =>
              if (availability.forall(identity))
                state
                  .foreachLockDemand(instruction.demands)(_
                    .acquire(order.id, _)/*check only*/)
                  .rightAs(
                    OrderLocksAcquired(instruction.demands) :: Nil)
              else if (order.isState[Order.WaitingForLock])
                Right(Nil)
              else
                state
                  .foreachLockDemand(instruction.demands)(_
                    .enqueue(order.id, _)/*check only*/)
                  .rightAs(
                    OrderLocksQueued(instruction.demands) :: Nil))
            .map(_.map(order.id <-: _))
        else
          Right(Nil))


  def onReturnFromSubworkflow(order: Order[Order.State], instruction: LockInstruction)
  : Option[KeyedEvent[OrderActorEvent]] =
    Some(order.id <-: (
      if (order.isAttached)
        OrderDetachable
      else
        OrderLocksReleased(instruction.lockPaths)))
}
