package js7.tests.order

import js7.base.problem.Checked.Ops
import js7.base.time.ScalaTime._
import js7.base.time.Timestamp
import js7.common.configutils.Configs.HoconStringInterpolator
import js7.common.scalautil.MonixUtils.syntax._
import js7.controller.data.ControllerCommand.{Batch, CancelOrder, Response, ResumeOrder, SuspendOrder}
import js7.controller.data.events.ControllerAgentEvent.AgentReady
import js7.data.Problems.UnknownOrderProblem
import js7.data.agent.AgentRefPath
import js7.data.command.CancelMode
import js7.data.item.VersionId
import js7.data.job.{ExecutablePath, ReturnCode}
import js7.data.order.OrderEvent.{OrderAdded, OrderAttachable, OrderAttached, OrderCancelled, OrderCatched, OrderDetachable, OrderDetached, OrderFailed, OrderFinished, OrderForked, OrderJoined, OrderMoved, OrderProcessed, OrderProcessingStarted, OrderResumeMarked, OrderResumed, OrderRetrying, OrderStarted, OrderStdWritten, OrderStdoutWritten, OrderSuspendMarked, OrderSuspended}
import js7.data.order.{FreshOrder, OrderEvent, OrderId, Outcome}
import js7.data.problems.{CannotResumeOrderProblem, CannotSuspendOrderProblem}
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{Execute, Fail, Fork, Retry, TryInstruction}
import js7.data.workflow.position.BranchId.{Try_, catch_, try_}
import js7.data.workflow.position.Position
import js7.data.workflow.{Workflow, WorkflowPath}
import js7.tests.order.SuspendResumeOrderTest._
import js7.tests.testenv.ControllerAgentForScalaTest
import js7.tests.testenv.DirectoryProvider.script
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

final class SuspendResumeOrderTest extends AnyFreeSpec with ControllerAgentForScalaTest
{
  protected val agentRefPaths = agentRefPath :: Nil
  protected val inventoryItems = singleJobWorkflow :: twoJobsWorkflow :: forkWorkflow :: tryWorkflow :: Nil
  override def controllerConfig = config"js7.journal.remove-obsolete-files = false" withFallback super.controllerConfig

  override def beforeAll() = {
    for (a <- directoryProvider.agents) {
      a.writeExecutable(executablePath, script(300.ms))
      a.writeExecutable(quickExecutablePath, script(0.s))
    }
    super.beforeAll()
  }

  "Suspend and resume a fresh order" in {
    controller.eventWatch.await[AgentReady]()
    val order = FreshOrder(OrderId("🔺"), singleJobWorkflow.path, scheduledFor = Some(Timestamp.now + 2.s))
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderAttached](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderSuspended](_.key == order.id)

    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id) == Seq(
      OrderAdded(singleJobWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderSuspendMarked,
      OrderDetachable,
      OrderDetached,
      OrderSuspended))
    val lastEventId = controller.eventWatch.lastAddedEventId

    controller.executeCommandAsSystemUser(ResumeOrder(order.id)).await(99.s).orThrow

    // ResumeOrder command expected a suspended or suspending order
    assert(controller.executeCommandAsSystemUser(ResumeOrder(order.id)).await(99.s) == Left(CannotResumeOrderProblem))

    controller.eventWatch.await[OrderFinished](_.key == order.id)
    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id, after = lastEventId) == Seq(
      OrderResumed(None),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderStdoutWritten("TEST ☘\n"),
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderFinished))
  }

  "An order reaching end of workflow is suspendible" in {
    val order = FreshOrder(OrderId("⭕️"), singleJobWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderSuspended](_.key == order.id)
    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id).filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
      OrderAdded(singleJobWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderSuspendMarked,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderSuspended))

    val lastEventId = controller.eventWatch.lastAddedEventId
    controller.executeCommandAsSystemUser(ResumeOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderFinished](_.key == order.id)

    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id, after = lastEventId) == Seq(
      OrderResumed(None),
      OrderFinished))
  }

  "Suspend and resume an order between two jobs" in {
    val order = FreshOrder(OrderId("🔴"), twoJobsWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderSuspended](_.key == order.id)
    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id).filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
      OrderAdded(twoJobsWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderSuspendMarked,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderSuspended))

    val lastEventId = controller.eventWatch.lastAddedEventId
    controller.executeCommandAsSystemUser(ResumeOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderFinished](_.key == order.id)
    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id, after = lastEventId)
      .filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
        OrderResumed(None),
        OrderAttachable(agentRefPath),
        OrderAttached(agentRefPath),
        OrderProcessingStarted,
        OrderProcessed(Outcome.succeeded),
        OrderMoved(Position(2)),
        OrderDetachable,
        OrderDetached,
        OrderFinished))
  }

  "An order being cancelled is not suspendible nor resumable" in {
    val order = FreshOrder(OrderId("🔷"), twoJobsWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(CancelOrder(order.id, CancelMode.FreshOrStarted())).await(99.s).orThrow
    assert(controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s) == Left(CannotSuspendOrderProblem))
  }

  "Suspend a forked order - child orders are not suspended" in {
    val order = FreshOrder(OrderId("FORK"), forkWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id / "🥕")

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderSuspended](_.key == order.id)

    assert(controller.eventWatch
      .keyedEvents[OrderEvent]
      .filter(_.key.string startsWith "FORK")
      .filterNot(_.event.isInstanceOf[OrderStdWritten]) ==
      Seq(
        OrderId("FORK") <-: OrderAdded(forkWorkflow.id, order.scheduledFor),
        OrderId("FORK") <-: OrderStarted,
        OrderId("FORK") <-: OrderForked(Seq(OrderForked.Child(Fork.Branch.Id("🥕"), OrderId("FORK/🥕")))),
        OrderId("FORK/🥕") <-: OrderAttachable(agentRefPath),
        OrderId("FORK/🥕") <-: OrderAttached(agentRefPath),
        OrderId("FORK/🥕") <-: OrderProcessingStarted,
        OrderId("FORK") <-: OrderSuspendMarked,
        OrderId("FORK/🥕") <-: OrderProcessed(Outcome.succeeded),
        OrderId("FORK/🥕") <-: OrderMoved(Position(0) / "fork+🥕" % 1),
        OrderId("FORK/🥕") <-: OrderProcessingStarted,
        OrderId("FORK/🥕") <-: OrderProcessed(Outcome.succeeded),
        OrderId("FORK/🥕") <-: OrderMoved(Position(0) / "fork+🥕" % 2),
        OrderId("FORK/🥕") <-: OrderDetachable,
        OrderId("FORK/🥕") <-: OrderDetached,
        OrderId("FORK") <-: OrderJoined(Outcome.succeeded),
        OrderId("FORK") <-: OrderMoved(Position(1)),
        OrderId("FORK") <-: OrderSuspended))
  }

  "Suspend unknown order" in {
    assert(controller.executeCommandAsSystemUser(SuspendOrder(OrderId("UNKNOWN"))).await(99.s) ==
      Left(UnknownOrderProblem(OrderId("UNKNOWN"))))
  }

  "Suspend multiple orders with Batch" in {
    val orders = for (i <- 1 to 3) yield
      FreshOrder(OrderId(i.toString), singleJobWorkflow.path, scheduledFor = Some(Timestamp.now + 99.s))
    for (o <- orders) controller.addOrderBlocking(o)
    for (o <- orders) controller.eventWatch.await[OrderAttached](_.key == o.id)
    val response = controller.executeCommandAsSystemUser(Batch(for (o <- orders) yield SuspendOrder(o.id))).await(99.s).orThrow
    assert(response == Batch.Response(Vector.fill(orders.length)(Right(Response.Accepted))))
    for (o <- orders) controller.eventWatch.await[OrderSuspended](_.key == o.id)
  }

  "Resume a still suspending order" in {
    val order = FreshOrder(OrderId("🔹"), twoJobsWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.executeCommandAsSystemUser(ResumeOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderFinished](_.key == order.id)

    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id).filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
      OrderAdded(twoJobsWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderSuspendMarked,
      OrderResumeMarked(None),
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),

      // AgentOrderKeeper does not properly handle simulataneous ExecuteMarkOrder commands
      // and so order is detached for suspending (which has been withdrawn by ResumeOrder).
      OrderDetachable,
      OrderDetached,
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),

      OrderProcessingStarted,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(2)),
      OrderDetachable,
      OrderDetached,
      OrderFinished))
  }

  "Resume with position a still suspending order is inhibited" in {
    val order = FreshOrder(OrderId("🔵"), twoJobsWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    assert(controller.executeCommandAsSystemUser(ResumeOrder(order.id, Some(Position(0)))).await(99.s) ==
      Left(CannotResumeOrderProblem))

    controller.eventWatch.await[OrderSuspended](_.key == order.id)
    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id).filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
      OrderAdded(twoJobsWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderSuspendMarked,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderSuspended))

    controller.executeCommandAsSystemUser(CancelOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderCancelled](_.key == order.id)
  }

  "Resume with changed position" in {
    val order = FreshOrder(OrderId("🔶"), tryWorkflow.path)
    controller.addOrderBlocking(order)
    controller.eventWatch.await[OrderProcessingStarted](_.key == order.id)

    controller.executeCommandAsSystemUser(SuspendOrder(order.id)).await(99.s).orThrow
    controller.eventWatch.await[OrderSuspended](_.key == order.id)

    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id).filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
      OrderAdded(tryWorkflow.id, order.scheduledFor),
      OrderAttachable(agentRefPath),
      OrderAttached(agentRefPath),
      OrderStarted,
      OrderProcessingStarted,
      OrderSuspendMarked,
      OrderProcessed(Outcome.succeeded),
      OrderMoved(Position(1)),
      OrderDetachable,
      OrderDetached,
      OrderSuspended))

    val lastEventId = controller.eventWatch.lastAddedEventId
    controller.executeCommandAsSystemUser(ResumeOrder(order.id, Some(Position(2) / Try_ % 0))).await(99.s).orThrow
    controller.eventWatch.await[OrderFailed](_.key == order.id)

    assert(controller.eventWatch.keyedEvents[OrderEvent](order.id, after = lastEventId)
      .filterNot(_.isInstanceOf[OrderStdWritten]) == Seq(
        OrderResumed(Some(Position(2) / Try_ % 0)),
        OrderCatched(Outcome.Failed(None, ReturnCode(0)), Position(2) / catch_(0) % 0),
        OrderRetrying(Position(2) / try_(1) % 0, None),
        OrderFailed(Outcome.Failed(None, ReturnCode(0)))))
  }
}

object SuspendResumeOrderTest
{
  private val executablePath = ExecutablePath("/executable.cmd")
  private val quickExecutablePath = ExecutablePath("/quick.cmd")
  private val agentRefPath = AgentRefPath("/AGENT")
  private val versionId = VersionId("INITIAL")

  private val singleJobWorkflow = Workflow.of(
    WorkflowPath("/SINGLE") ~ versionId,
    Execute(WorkflowJob(agentRefPath, executablePath)))

  private val twoJobsWorkflow = Workflow.of(
    WorkflowPath("/TWO") ~ versionId,
    Execute(WorkflowJob(agentRefPath, executablePath)),
    Execute(WorkflowJob(agentRefPath, executablePath)))

  private val forkWorkflow = Workflow.of(
    WorkflowPath("/FORK") ~ versionId,
    Fork.of(
      "🥕" -> Workflow.of(
        Execute(WorkflowJob(agentRefPath, executablePath)),
        Execute(WorkflowJob(agentRefPath, executablePath)))),
    Execute(WorkflowJob(agentRefPath, executablePath)))

  private val tryWorkflow = Workflow.of(
    WorkflowPath("/TRY") ~ versionId,
    Execute(WorkflowJob(agentRefPath, executablePath)),
    Execute(WorkflowJob(agentRefPath, executablePath)),
    TryInstruction(
      Workflow.of(
        Fail()),
      Workflow.of(
        Retry()),
      maxTries = Some(2)))
}
