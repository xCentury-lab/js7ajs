package js7.data.item

import js7.base.circeutils.CirceUtils.deriveCodec
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.data.agent.AgentPath
import js7.data.event.JournaledState
import js7.data.item.ItemAttachedState.{Attachable, Attached, Detachable, Detached}

sealed trait BasicItemEvent extends InventoryItemEvent

object BasicItemEvent
{
  sealed trait ForController extends BasicItemEvent
  sealed trait ForAgent extends BasicItemEvent

  /** Used for OrderWatch to allow to attach it from Agent. */
  final case class ItemDeletionMarked(key: InventoryItemKey)
  extends ForController {
    def attachedState = None
  }

  final case class ItemDeleted(key: InventoryItemKey)
  extends ForController

  sealed trait ItemAttachedStateEvent
  extends ForController {
    def agentPath: AgentPath
    def attachedState: ItemAttachedState
  }
  object ItemAttachedStateEvent {
    def apply(key: InventoryItemKey, agentPath: AgentPath, attachedState: ItemAttachedState)
    : ItemAttachedStateEvent =
      attachedState match {
        case Attachable => ItemAttachable(key, agentPath)
        case Attached(itemRevision) => ItemAttached(key, itemRevision, agentPath)
        case Detachable => ItemDetachable(key, agentPath)
        case Detached => ItemDetached(key, agentPath)
      }
    def unapply(event: ItemAttachedStateEvent) =
      Some((event.key, event.agentPath, event.attachedState))
  }

  final case class ItemAttachable(key: InventoryItemKey, agentPath: AgentPath)
  extends ItemAttachedStateEvent {
    def attachedState = Attachable
  }

  final case class ItemAttached(key: InventoryItemKey, itemRevision: Option[ItemRevision], agentPath: AgentPath)
  extends ItemAttachedStateEvent {
    def attachedState = Attached(itemRevision)
  }

  /** Agent only. */
  final case class ItemAttachedToAgent(item: InventoryItem)
  extends ForAgent {
    def key = item.key
  }

  final case class ItemDetachable(key: InventoryItemKey, agentPath: AgentPath)
  extends ItemAttachedStateEvent {
    def attachedState = Detachable
  }

  final case class ItemDetached(key: InventoryItemKey, agentPath: AgentPath)
  extends ItemAttachedStateEvent with ForAgent {
    def attachedState = Detached
  }

  def jsonCodec[S <: JournaledState[S]](implicit S: JournaledState.Companion[S])
  : TypedJsonCodec[BasicItemEvent] = {
    implicit val x = S.inventoryItemJsonCodec
    implicit val y = S.inventoryItemKeyJsonCodec

    TypedJsonCodec(
      Subtype(deriveCodec[ItemDeletionMarked]),
      Subtype(deriveCodec[ItemDeleted]),
      Subtype(deriveCodec[ItemAttachable]),
      Subtype(deriveCodec[ItemAttached]),
      Subtype(deriveCodec[ItemAttachedToAgent]),
      Subtype(deriveCodec[ItemDetachable]),
      Subtype(deriveCodec[ItemDetached]))
  }
}
