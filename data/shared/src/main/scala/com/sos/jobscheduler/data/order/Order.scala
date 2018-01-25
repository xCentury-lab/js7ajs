package com.sos.jobscheduler.data.order

import com.sos.jobscheduler.base.circeutils.CirceCodec
import com.sos.jobscheduler.base.circeutils.CirceUtils.deriveCirceCodec
import com.sos.jobscheduler.base.circeutils.typed.{Subtype, TypedJsonCodec}
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.base.utils.ScalaUtils.{RichJavaClass, implicitClass}
import com.sos.jobscheduler.base.utils.ScalazStyle.OptionRichBoolean
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.order.Order._
import com.sos.jobscheduler.data.order.OrderEvent._
import com.sos.jobscheduler.data.workflow.{InstructionNr, Position, WorkflowPath, WorkflowPosition}
import io.circe.generic.JsonCodec
import scala.collection.immutable.Seq
import scala.reflect.ClassTag

/**
  * @author Joacim Zschimmer
  */
final case class Order[+S <: Order.State](
  id: OrderId,
  workflowPosition: WorkflowPosition,
  state: S,
  attachedTo: Option[AttachedTo] = None,
  parent: Option[OrderId] = None,
  payload: Payload = Payload.empty)
{
  def newForkedOrders(event: OrderForked): Seq[Order[Order.Ready]] =
    for (child ← event.children) yield
      Order(
        child.orderId,
        workflowPosition.copy(position = workflowPosition.position / child.branchId / InstructionNr.First),
        Ready,
        attachedTo,
        parent = Some(id),
        Payload(child.variablesDiff.applyTo(payload.variables)))

  def newPublishedOrder(event: OrderOffered): Order[Offered] = copy(
    event.orderId,
    state = Offered(event.until),
    parent = None)

  def workflowPath: WorkflowPath =
    workflowPosition.workflowPath

  def position: Position =
    workflowPosition.position

  def update(event: OrderEvent.OrderCoreEvent): Order[State] =
    event match {
      case _: OrderAdded | _: OrderAttached ⇒
        throw new IllegalArgumentException("OrderAdded and OrderAttached are not handled by the Order itself")

      case OrderTransferredToAgent(o) ⇒
        copy(
          attachedTo = Some(AttachedTo.Agent(o)))

      case OrderTransferredToMaster ⇒ copy(
        attachedTo = None)

      case OrderProcessingStarted ⇒ copy(
        state = InProcess)

      case OrderProcessed(diff, outcome_) ⇒ copy(
        state = Processed(outcome_),
        payload = Payload(diff.applyTo(payload.variables)))

      case OrderForked(children) ⇒ copy(
        state = Join(children map (_.orderId)))

      case OrderJoined(variablesDiff, outcome_) ⇒
        copy(
          state = Processed(outcome_),
          payload = Payload(variablesDiff applyTo variables))

      case _: OrderOffered ⇒ copy(
        state = Processed(Outcome.succeeded))

      case OrderAwaiting(orderId) ⇒ copy(
        state = Awaiting(orderId))

      case OrderMoved(to) ⇒
        withPosition(to).copy(state = Ready)

      case OrderStopped(message) ⇒ copy(
        state = Stopped(message))

      case OrderDetachable ⇒
        attachedTo match {
          case None ⇒
            throw new IllegalStateException(s"Event OrderDetachable but '$id' is AttachedTo.$attachedTo")
          case Some(AttachedTo.Agent(agentPath)) ⇒
            copy(attachedTo = Some(AttachedTo.Detachable(agentPath)))
          case Some(AttachedTo.Detachable(_)) ⇒
            this
        }

      case OrderDetached ⇒ copy(
        attachedTo = None)

      case OrderFinished ⇒
        position.dropChild match {
          case Some(position) ⇒
            copy(workflowPosition = workflowPosition.copy(position = position))
          case None ⇒
            copy(state = Finished)
        }
    }

  def withInstructionNr(to: InstructionNr): Order[S] =
    withPosition(position.copy(nr = to))

  def withPosition(to: Position): Order[S] = copy(
    workflowPosition = workflowPosition.copy(position = to))

  def variables = payload.variables

  def castAfterEvent(event: OrderProcessingStarted): Order[Order.InProcess] =
    castState[Order.InProcess]

  def castAfterEvent(event: OrderProcessed): Order[Order.Processed] =
    castState[Order.Processed]

  def castState[A <: State: ClassTag]: Order[A] =
    ifState[A] getOrElse (
      throw new IllegalStateException(s"'$id' is expected to be ${implicitClass[A].simpleScalaName}, but is $state"))

  def ifState[A <: State: ClassTag]: Option[Order[A]] = {
    val cls = implicitClass[A]
    cls.isAssignableFrom(state.getClass) option
      this.asInstanceOf[Order[A]]
  }

  def isAttachedToAgent = attachedToAgent.isRight

  def attachedToAgent: Either[IllegalStateException, AgentPath] =
    attachedTo match {
      case Some(AttachedTo.Agent(agentPath)) ⇒
        Right(agentPath)
      case o ⇒
        Left(new IllegalStateException(s"'$id' is expected to be AttachedTo.Agent, but not: $o"))
    }

  def detachableFromAgent: Either[IllegalStateException, AgentPath] =
    attachedTo match {
      case Some(AttachedTo.Detachable(agentPath)) ⇒
        Right(agentPath)
      case o ⇒
        Left(new IllegalStateException(s"'$id' is expected to be AttachedTo.Detachable, but not: $o"))
    }
}

object Order {
  def fromOrderAdded(id: OrderId, event: OrderAdded): Order[Idle] =
    Order(id, event.workflowPath, event.state, payload = event.payload)

  def fromOrderAttached(id: OrderId, event: OrderAttached): Order[Idle] =
    Order(id, event.workflowPosition, event.state, Some(AttachedTo.Agent(event.agentPath)), payload = event.payload)

  sealed trait AttachedTo
  object AttachedTo {
    sealed trait AgentOrDetachable extends AttachedTo {
      val agentPath: AgentPath
    }
    object AgentOrDetachable {
      def unapply(o: AttachedTo): Option[AgentPath] =
        o match {
          case o: AgentOrDetachable ⇒ Some(o.agentPath)
          case _ ⇒ None
        }
    }

    @JsonCodec
    final case class Agent(agentPath: AgentPath) extends AttachedTo

    @JsonCodec
    final case class Detachable(agentPath: AgentPath) extends AgentOrDetachable

    implicit val jsonCodec = TypedJsonCodec[AttachedTo](
      Subtype[Agent],
      Subtype[Detachable])
  }

  sealed trait State
  sealed trait Idle extends State
  sealed trait NotStarted extends Idle
  sealed trait Started extends State
  sealed trait Transitionable extends Started

  @JsonCodec
  final case class Scheduled(at: Timestamp) extends NotStarted

  final case object StartNow extends NotStarted

  sealed trait Ready extends Started with Idle
  case object Ready extends Ready

  @JsonCodec
  final case class Stopped(outcome: Outcome.NotSucceeded) extends Started //TODO with Idle: Idle auf Ready einengen!

  sealed trait InProcess extends Started
  case object InProcess extends InProcess

  @JsonCodec
  final case class Processed(outcome: Outcome) extends Transitionable

  @JsonCodec
  final case class Join(joinOrderIds: Seq[OrderId]) extends Transitionable

  @JsonCodec
  final case class Offered(until: Timestamp)
  extends Started

  @JsonCodec
  final case class Awaiting(offeredOrderId: OrderId) extends Transitionable

  sealed trait Finished extends State
  case object Finished extends Finished

  implicit val NotStartedJsonCodec: TypedJsonCodec[NotStarted] = TypedJsonCodec[NotStarted](
    Subtype[Scheduled],
    Subtype(StartNow))

  implicit val IdleJsonCodec: TypedJsonCodec[Idle] = TypedJsonCodec[Idle](
    Subtype[NotStarted],
    Subtype(Ready))

  implicit val StateJsonCodec: TypedJsonCodec[State] = TypedJsonCodec(
    Subtype[Idle],
    Subtype[Stopped],
    Subtype(InProcess),
    Subtype[Processed],
    Subtype[Join],
    Subtype[Offered],
    Subtype[Awaiting],
    Subtype(Finished))

  implicit val NotStartedOrderJsonCodec: CirceCodec[Order[NotStarted]] = deriveCirceCodec[Order[NotStarted]]
  implicit val IdleOrderJsonCodec: CirceCodec[Order[Idle]] = deriveCirceCodec[Order[Idle]]
  implicit val jsonCodec: CirceCodec[Order[State]] = deriveCirceCodec[Order[State]]
}
