package js7.agent.data.commands

import io.circe.generic.JsonCodec
import io.circe.generic.extras.Configuration.default.withDefaults
import io.circe.{Decoder, Encoder, Json, JsonObject}
import js7.agent.data.AgentState
import js7.agent.data.AgentState.{inventoryItemIdJsonCodec, inventoryItemJsonCodec, signableItemJsonCodec}
import js7.base.circeutils.CirceCodec
import js7.base.circeutils.CirceUtils.{deriveCodec, deriveConfiguredCodec, singletonCodec}
import js7.base.circeutils.ScalaJsonCodecs._
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.base.crypt.Signed
import js7.base.io.process.ProcessSignal
import js7.base.problem.Checked
import js7.base.problem.Checked._
import js7.base.problem.Checked.implicits.{checkedJsonDecoder, checkedJsonEncoder}
import js7.base.utils.Big
import js7.base.utils.IntelliJUtils.intelliJuseImport
import js7.base.utils.ScalaUtils.syntax._
import js7.data.agent.{AgentPath, AgentRunId}
import js7.data.command.CommonCommand
import js7.data.event.{EventId, JournaledState}
import js7.data.item.{InventoryItem, InventoryItemId, SignableItem}
import js7.data.order.{Order, OrderId, OrderMark}

/**
 * @author Joacim Zschimmer
 */
sealed trait AgentCommand extends CommonCommand {
  type Response <: AgentCommand.Response
}

object AgentCommand extends CommonCommand.Companion
{
  protected type Command = AgentCommand

  trait Response
  object Response {
    sealed trait Accepted extends Response
    case object Accepted extends Accepted {
      implicit val jsonCodec: CirceCodec[Accepted] = singletonCodec(Accepted)
    }
  }

  final case class Batch(commands: Seq[AgentCommand])
  extends AgentCommand with CommonBatch with Big {
    type Response = Batch.Response
    override def toShortString = s"Batch(${commands.size} commands, ${commands.take(1).map(o => o.toShortString + ", ").mkString} ...)"
  }
  object Batch {
    final case class Response(responses: Seq[Checked[AgentCommand.Response]])
    extends AgentCommand.Response with Big {
      override def toString = {
        val succeeded = responses count (_.isRight)
        s"Batch($succeeded succeeded and ${responses.size - succeeded} failed)"
      }
    }
  }

  final case class MarkOrder(orderId: OrderId, mark: OrderMark) extends OrderCommand {
    type Response = Response.Accepted
  }

  final case class EmergencyStop(restart: Boolean = false) extends AgentCommand {
    /** The JVM is halted before responding. */
    type Response = Response.Accepted
  }
  object EmergencyStop {
    implicit val jsonEncoder: Encoder.AsObject[EmergencyStop] = o =>
      JsonObject.fromIterable(
        o.restart.thenList("restart" -> Json.True))

    implicit val jsonDecoder: Decoder[EmergencyStop] = c =>
      for {
        restart <- c.getOrElse[Boolean]("restart")(false)
      } yield EmergencyStop(restart)
  }

  /** Some outer component no longer needs the events until (including) the given `untilEventId`.
    * JS7 may delete these events to reduce the journal,
    * keeping all events after `untilEventId`.
    */
  final case class ReleaseEvents(untilEventId: EventId) extends OrderCommand {
    type Response = Response.Accepted
  }

  case object NoOperation extends AgentCommand {
    type Response = Response.Accepted
  }

  /** Registers the Controller identified by current User as a new Controller and couples it.
    * The Agent Server starts a new Agent, dedicated to the Controller.
    * Command may be given twice (in case of a sudden restart).
    */
  final case class RegisterAsController(agentId: AgentPath) extends AgentCommand {
    type Response = RegisterAsController.Response
  }
  object RegisterAsController {
    /**
      * @param agentRunId Use the value for `CoupleController`. */
    final case class Response(agentRunId: AgentRunId) extends AgentCommand.Response
  }

  /** Couples the registered Controller identified by current User.
    * @param agentRunId Must be the value returned by `RegisterAsController`. */
  final case class CoupleController(agentId: AgentPath, agentRunId: AgentRunId, eventId: EventId) extends AgentCommand {
    type Response = CoupleController.Response
  }
  object CoupleController {
    final case class Response(orderIds: Set[OrderId])
    extends AgentCommand.Response with Big
  }

  case object TakeSnapshot extends AgentCommand {
    type Response = Response.Accepted
  }

  sealed trait ShutdownOrAbort extends AgentCommand

  final case class ShutDown(
    processSignal: Option[ProcessSignal] = None,
    restart: Boolean = false)
  extends ShutdownOrAbort {
    type Response = Response.Accepted
  }
  object ShutDown {
    private implicit val customConfig = withDefaults
    implicit val jsonCodec = deriveConfiguredCodec[ShutDown]
  }

  sealed trait OrderCommand extends AgentCommand

  final case class AttachItem(item: InventoryItem)
  extends AgentCommand
  {
    type Response = Response.Accepted
  }

  final case class AttachSignedItem(signed: Signed[SignableItem])
  extends AgentCommand
  {
    type Response = Response.Accepted
  }
  object AttachSignedItem {
    // Same serialization as SignedItemAdded event
    implicit val jsonEncoder: Encoder.AsObject[AttachSignedItem] =
      o => SignableItem.signedEncodeJson(o.signed.signedString, o.signed.value.itemRevision)

    implicit def jsonDecoder[S <: JournaledState[S]](implicit S: JournaledState.Companion[S])
    : Decoder[AttachSignedItem] =
      c => SignableItem.signedJsonDecoder(S).decodeJson(c.value).map(AttachSignedItem(_))
  }

  final case class DetachItem(id: InventoryItemId)
  extends AgentCommand
  {
    type Response = Response.Accepted
  }

  sealed trait AttachOrDetachOrder extends OrderCommand

  final case class AttachOrder(order: Order[Order.IsFreshOrReady])
  extends AttachOrDetachOrder with Big {
    order.workflowId.requireNonAnonymous()
    order.attached.orThrow

    type Response = Response.Accepted

    override def toShortString = s"AttachOrder(${order.id.string}, ${order.workflowPosition}, " +
      s"${order.state.getClass.simpleScalaName}))"
  }
  object AttachOrder {
    def apply(order: Order[Order.IsFreshOrReady], agentId: AgentPath) =
      new AttachOrder(order.copy(attachedState = Some(Order.Attached(agentId))))
  }

  final case class DetachOrder(orderId: OrderId)
  extends AttachOrDetachOrder {
    type Response = Response.Accepted
  }

  final case class GetOrder(orderId: OrderId)
  extends OrderCommand {
    type Response = GetOrder.Response
  }
  object GetOrder {
    @JsonCodec
    final case class Response(order: Order[Order.State]) extends AgentCommand.Response
  }

  case object GetOrderIds extends OrderCommand {
    final case class Response(orderIds: Seq[OrderId]) extends AgentCommand.Response with Big
  }

  case object GetOrders extends OrderCommand {
    final case class Response(orders: Seq[Order[Order.State]]) extends AgentCommand.Response with Big
  }

  implicit val jsonCodec: TypedJsonCodec[AgentCommand] = {
    implicit val S = AgentState
    TypedJsonCodec[AgentCommand](
      Subtype(deriveCodec[Batch]),
      Subtype(deriveCodec[MarkOrder]),
      Subtype[EmergencyStop],
      Subtype(deriveCodec[ReleaseEvents]),
      Subtype(NoOperation),
      Subtype(deriveCodec[RegisterAsController]),
      Subtype(deriveCodec[CoupleController]),
      Subtype[ShutDown],
      Subtype(deriveCodec[AttachItem]),
      Subtype[AttachSignedItem],
      Subtype(deriveCodec[DetachItem]),
      Subtype(deriveCodec[AttachOrder]),
      Subtype(deriveCodec[DetachOrder]),
      Subtype(deriveCodec[GetOrder]),
      Subtype(GetOrders),
      Subtype(TakeSnapshot),
      Subtype(GetOrderIds))
  }

  implicit val responseJsonCodec: TypedJsonCodec[AgentCommand.Response] =
    TypedJsonCodec[AgentCommand.Response](
      Subtype.named(deriveCodec[CoupleController.Response], "CoupleController.Response"),
      Subtype.named(deriveCodec[Batch.Response], "Batch.Response"),
      Subtype(Response.Accepted),
      Subtype.named(deriveCodec[RegisterAsController.Response], "RegisterAsController.Response"))

  intelliJuseImport((FiniteDurationJsonDecoder,
    checkedJsonEncoder[Int], checkedJsonDecoder[Int],
    inventoryItemIdJsonCodec, inventoryItemJsonCodec, signableItemJsonCodec))
}
