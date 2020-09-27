package js7.data

import js7.base.problem.Problem
import js7.data.event.EventId
import js7.data.item.RepoEvent.ItemAddedOrChanged
import js7.data.item.{ItemId, TypedPath, VersionId}
import js7.data.order.OrderId

object Problems
{
  case object PassiveClusterNodeShutdownNotAllowedProblem extends Problem.ArgumentlessCoded

  final case class CancelChildOrderProblem(orderId: OrderId) extends Problem.Coded {
    def arguments = Map("orderId" -> orderId.string)
  }

  final case class CancelStartedOrderProblem(orderId: OrderId) extends Problem.Coded {
    def arguments = Map("orderId" -> orderId.string)
  }

  final case class UnknownOrderProblem(orderId: OrderId) extends Problem.Coded {
    def arguments = Map("orderId" -> orderId.string)
  }

  final case class ItemDeletedProblem(path: TypedPath) extends Problem.Coded {
    def arguments = Map("path" -> path.pretty)
  }

  final case class ItemVersionDoesNotMatchProblem(versionId: VersionId, itemId: ItemId[_ <: TypedPath]) extends Problem.Coded {
    def arguments = Map("versionId" -> versionId.string, "id" -> itemId.toString)
  }

  final case class EventVersionDoesNotMatchProblem(versionId: VersionId, event: ItemAddedOrChanged) extends Problem.Coded {
    def arguments = Map("versionId" -> versionId.string, "event" -> event.toShortString)
  }

  final case class SnapshotForUnknownEventIdProblem(eventId: EventId) extends Problem.Coded {
    def arguments = Map(
      "eventId" -> eventId.toString,
      "eventIdString" -> EventId.toString(eventId))
  }
  object SnapshotForUnknownEventIdProblem extends Problem.Coded.Companion

  case object CannotRemoveOrderProblem extends Problem.ArgumentlessCoded

  final case class UnknownSignatureTypeProblem(typeName: String) extends Problem.Coded {
    def arguments = Map("typeName" -> typeName)
  }
}
