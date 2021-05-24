package js7.data.item

import io.circe.generic.semiauto.deriveCodec
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}

sealed trait ItemAttachedState

object ItemAttachedState
{
  sealed trait NotDetached extends ItemAttachedState
  object NotDetached {
    implicit val jsonCodec = TypedJsonCodec[NotDetached](
      Subtype(Attachable),
      Subtype(deriveCodec[Attached]),
      Subtype(Detachable))
  }
  case object Attachable
  extends NotDetached

  final case class Attached(itemRevision: Option[ItemRevision])
  extends NotDetached
  object Attached {
    private val none = new Attached(None)

    def apply(itemRevision: Option[ItemRevision]): Attached =
      if (itemRevision.isEmpty)
        none
      else
        new Attached(itemRevision)
  }

  case object Detachable
  extends NotDetached

  case object Detached extends ItemAttachedState
}
