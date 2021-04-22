package js7.data.item

import io.circe.Codec
import js7.data.item.InventoryItemId.Companion

trait SignableSimpleItemPath extends SimpleItemPath with SignableItemId
{
  def companion: Companion[_ <: SignableSimpleItemPath]
}

object SignableSimpleItemPath
{
  type Companion_ = Companion[_ <: SignableSimpleItemPath]

  trait Companion[A <: SignableSimpleItemPath] extends SimpleItemPath.Companion[A] with SignableItemId.Companion[A]

  def jsonCodec(companions: Iterable[SignableSimpleItemPath.Companion_]): Codec[SignableSimpleItemPath] =
    InventoryItemId.jsonCodec(companions)
      .asInstanceOf[Codec[SignableSimpleItemPath]]
}
