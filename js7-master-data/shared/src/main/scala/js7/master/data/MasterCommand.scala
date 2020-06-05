package js7.master.data

import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json, JsonObject}
import js7.base.circeutils.CirceUtils.deriveCodec
import js7.base.circeutils.ScalaJsonCodecs.{FiniteDurationJsonDecoder, FiniteDurationJsonEncoder}
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.base.crypt.SignedString
import js7.base.problem.Checked._
import js7.base.problem.Checked.implicits.{checkedJsonDecoder, checkedJsonEncoder}
import js7.base.problem.{Checked, Problem}
import js7.base.utils.Big
import js7.base.utils.IntelliJUtils.intelliJuseImport
import js7.base.utils.ScalazStyle._
import js7.base.web.Uri
import js7.data.cluster.{ClusterCommand, ClusterNodeId, ClusterSetting}
import js7.data.command.{CancelMode, CommonCommand}
import js7.data.event.EventId
import js7.data.filebased.{TypedPath, VersionId}
import js7.data.master.MasterFileBaseds.typedPathJsonDecoder
import js7.data.order.{FreshOrder, OrderId}

/**
  * @author Joacim Zschimmer
  */
sealed trait MasterCommand extends CommonCommand
{
  type Response <: MasterCommand.Response
}

object MasterCommand extends CommonCommand.Companion
{
  intelliJuseImport((FiniteDurationJsonEncoder, FiniteDurationJsonDecoder, checkedJsonEncoder[Int], checkedJsonDecoder[Int], Problem, typedPathJsonDecoder))

  protected type Command = MasterCommand

  final case class Batch(commands: Seq[MasterCommand])
  extends MasterCommand with CommonBatch with Big {
    type Response = Batch.Response
  }
  object Batch {
    final case class Response(responses: Seq[Checked[MasterCommand.Response]])
    extends MasterCommand.Response with CommonBatch.Response with Big {
      override def productPrefix = "BatchResponse"
    }
  }

  final case class AddOrder(order: FreshOrder) extends MasterCommand {
    type Response = AddOrder.Response
  }
  object AddOrder {
    final case class Response(ignoredBecauseDuplicate: Boolean)
    extends MasterCommand.Response
  }

  final case class CancelOrder(orderId: OrderId, mode: CancelMode) extends MasterCommand {
    type Response = Response.Accepted
  }
  object CancelOrder {
    implicit val jsonEncoder: Encoder.AsObject[CancelOrder] = o =>
      JsonObject.fromIterable(
        ("orderId" -> o.orderId.asJson) ::
          (o.mode != CancelMode.Default).thenList("mode" -> o.mode.asJson))

    implicit val jsonDecoder: Decoder[CancelOrder] = c =>
      for {
        orderId <- c.get[OrderId]("orderId")
        mode <- c.get[Option[CancelMode]]("mode") map (_ getOrElse CancelMode.Default)
      } yield CancelOrder(orderId, mode)
  }

  sealed trait NoOperation extends MasterCommand
  case object NoOperation extends NoOperation {
    type Response = Response.Accepted
  }

  type EmitTestEvent = EmitTestEvent.type
  /** For tests only. */
  case object EmitTestEvent extends MasterCommand {
    type Response = Response.Accepted
  }

  /** Master stops immediately with exit(). */
  final case class EmergencyStop(restart: Boolean = false) extends MasterCommand {
    type Response = Response.Accepted
  }
  object EmergencyStop {
    implicit val jsonEncoder: Encoder.AsObject[EmergencyStop] = o =>
      JsonObject.fromIterable(
        (o.restart).thenList("restart" -> Json.True))

    implicit val jsonDecoder: Decoder[EmergencyStop] = c =>
      for {
        restart <- c.get[Option[Boolean]]("restart") map (_ getOrElse false)
      } yield EmergencyStop(restart)
  }

  /** Some outer component no longer needs the events until (including) the given `untilEventId`.
    * JS7 may delete these events to reduce the journal,
    * keeping all events after `untilEventId`.
    */
  final case class ReleaseEvents(untilEventId: EventId) extends MasterCommand {
    type Response = Response.Accepted
  }

  /** Shut down the Master properly. */
  final case class ShutDown(
    restart: Boolean = false,
    clusterAction: Option[ShutDown.ClusterAction] = None)
  extends MasterCommand {
    type Response = Response.Accepted
  }
  object ShutDown {
    sealed trait ClusterAction
    object ClusterAction {
      case object Switchover extends ClusterAction
      case object Failover extends ClusterAction
      implicit val jsonCodec: TypedJsonCodec[ClusterAction] = TypedJsonCodec[ClusterAction](
        Subtype(Switchover),
        Subtype(Failover))
    }

    implicit val jsonEncoder: Encoder.AsObject[ShutDown] = o =>
      JsonObject.fromIterable(
        o.restart.thenList("restart" -> Json.True) :::
        ("clusterAction" -> o.clusterAction.asJson) ::
        Nil)

    implicit val jsonDecoder: Decoder[ShutDown] = c =>
      for {
        restart <- c.get[Option[Boolean]]("restart").map(_ getOrElse false)
        cluster <- c.get[Option[ClusterAction]]("clusterAction")
      } yield ShutDown(restart, cluster)
  }

  case object TakeSnapshot extends MasterCommand {
    type Response = Response.Accepted
  }

  final case class UpdateRepo(
    versionId: VersionId,
    change: Seq[SignedString] = Nil,
    delete: Seq[TypedPath] = Nil)
  extends MasterCommand with Big {
    type Response = Response.Accepted

    def isEmpty = change.isEmpty && delete.isEmpty

    override def toString = s"UpdateRepo($versionId change=${change.size}× delete=${delete.size}×)"
  }

  final case class ReplaceRepo(versionId: VersionId, objects: Seq[SignedString])
  extends MasterCommand with Big {
    type Response = Response.Accepted

    override def toString = s"ReplaceRepo($versionId, ${objects.size} objects)"
  }

  final case class ClusterAppointNodes(idToUri: Map[ClusterNodeId, Uri], activeId: ClusterNodeId)
  extends MasterCommand {
    type Response = Response.Accepted
    ClusterSetting.checkUris(idToUri, activeId).orThrow
  }

  case object ClusterSwitchOver
  extends MasterCommand {
    type Response = Response.Accepted
  }

  /** For internal use between cluster nodes only. */
  final case class InternalClusterCommand(clusterCommand: ClusterCommand)
  extends MasterCommand {
    type Response = InternalClusterCommand.Response
  }
  object InternalClusterCommand {
    final case class Response(response: ClusterCommand.Response)
    extends MasterCommand.Response
  }

  sealed trait Response

  object Response {
    sealed trait Accepted extends Response
    case object Accepted extends Accepted

    implicit val ResponseJsonCodec: TypedJsonCodec[Response] = TypedJsonCodec[Response](
      Subtype(Accepted),
      Subtype.named(deriveCodec[AddOrder.Response], "AddOrder.Response"),
      Subtype.named(deriveCodec[Batch.Response], "BatchResponse"),
      Subtype.named(deriveCodec[js7.master.data.MasterCommand.InternalClusterCommand.Response], "InternalClusterCommand.Response"))
  }

  implicit val jsonCodec: TypedJsonCodec[MasterCommand] = TypedJsonCodec[MasterCommand](
    Subtype(deriveCodec[Batch]),
    Subtype(deriveCodec[AddOrder]),
    Subtype[CancelOrder],
    Subtype(deriveCodec[ReplaceRepo]),
    Subtype(deriveCodec[UpdateRepo]),
    Subtype(NoOperation),
    Subtype(EmitTestEvent),
    Subtype[EmergencyStop],
    Subtype(deriveCodec[ReleaseEvents]),
    Subtype[ShutDown],
    Subtype(deriveCodec[ClusterAppointNodes]),
    Subtype(ClusterSwitchOver),
    Subtype(deriveCodec[InternalClusterCommand]),
    Subtype(TakeSnapshot))
}
