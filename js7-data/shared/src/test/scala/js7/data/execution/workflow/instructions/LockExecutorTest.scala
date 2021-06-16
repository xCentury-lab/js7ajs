package js7.data.execution.workflow.instructions

import js7.base.utils.ScalaUtils.syntax._
import js7.data.agent.AgentPath
import js7.data.controller.ControllerId
import js7.data.execution.workflow.context.StateView
import js7.data.execution.workflow.instructions.LockExecutorTest._
import js7.data.job.PathExecutable
import js7.data.lock.{Acquired, Lock, LockPath, LockState}
import js7.data.order.OrderEvent.{OrderLockAcquired, OrderLockQueued, OrderLockReleased}
import js7.data.order.{Order, OrderId}
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{Execute, LockInstruction}
import js7.data.workflow.position.{BranchId, Position}
import js7.data.workflow.{Workflow, WorkflowId, WorkflowPath}
import org.scalatest.freespec.AnyFreeSpec

final class LockExecutorTest extends AnyFreeSpec {

  private lazy val stateView = new StateView {
    def idToOrder = Map(freeLockOrder.id -> freeLockOrder, freeLockedOrder.id -> freeLockedOrder, occupiedLockOrder.id -> occupiedLockOrder).checked
    def childOrderEnded(order: Order[Order.State]) = throw new NotImplementedError
    def idToWorkflow(id: WorkflowId) = Map(workflow.id -> workflow).checked(id)
    val pathToLockState = Map(
      freeLockPath -> LockState(Lock(freeLockPath, limit = 1)),
      occupiedLockPath -> LockState(Lock(occupiedLockPath, limit = 1), Acquired.Exclusive(OrderId("OCCUPANT"))),
    ).checked
    val controllerId = ControllerId("CONTROLLER")
  }

  "Lock acquired" in {
    assert(InstructionExecutor.toEvents(workflow.instruction(freeLockOrder.position), freeLockOrder, stateView) ==
      Right(Seq(freeLockOrder.id <-: OrderLockAcquired(freeLockPath))))
  }

  "Lock released" in {
    assert(InstructionExecutor.toEvents(workflow.instruction(freeLockedOrder.position), freeLockedOrder, stateView) ==
      Right(Seq(freeLockOrder.id <-: OrderLockReleased(freeLockPath))))
  }

  "Lock can not acquired and is queued" in {
    assert(InstructionExecutor.toEvents(workflow.instruction(occupiedLockOrder.position), occupiedLockOrder, stateView) ==
      Right(Seq(occupiedLockOrder.id <-: OrderLockQueued(occupiedLockPath, None))))
  }

  "Lock released and waiting order continues" in {
    assert(InstructionExecutor.toEvents(workflow.instruction(freeLockedOrder.position), freeLockedOrder, stateView) ==
      Right(Seq(freeLockOrder.id <-: OrderLockReleased(freeLockPath))))
  }
}

object LockExecutorTest {
  private val freeLockPath = LockPath("FREE-LOCK")
  private val occupiedLockPath = LockPath("OCCUPIED-LOCK")
  private val exclusiveLockPath = LockPath("EXCLUSIVE-LOCK")
  private val execute = Execute(WorkflowJob(AgentPath("AGENT"), PathExecutable("JOB")))

  private val workflow = Workflow.of(WorkflowPath("WORKFLOW") ~ "VERSION",
    LockInstruction(freeLockPath, None, Workflow.of(execute)),
    LockInstruction(occupiedLockPath, None, Workflow.of(execute)),
    LockInstruction(exclusiveLockPath, None, Workflow.of(execute)))

  private val freeLockOrder = Order(OrderId("ORDER-A"), workflow.id /: Position(0), Order.Ready)
  private val freeLockedOrder = Order(OrderId("ORDER-A"), workflow.id /: (Position(0) / BranchId.Lock % 1), Order.Ready)

  private val occupiedLockOrder = Order(OrderId("ORDER-B"), workflow.id /: Position(1), Order.Ready)
}
