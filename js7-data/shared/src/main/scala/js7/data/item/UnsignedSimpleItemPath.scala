package js7.data.item

import io.circe.Codec
import js7.data.item.UnsignedSimpleItemPath.*

trait UnsignedSimpleItemPath
extends UnsignedItemPath
with UnsignedItemKey
with SimpleItemPath
{
  protected type Self <: UnsignedSimpleItemPath

  def companion: Companion[? <: UnsignedSimpleItemPath]
}

object UnsignedSimpleItemPath
{
  type Companion_ = Companion[? <: UnsignedSimpleItemPath]

  trait Companion[A <: UnsignedSimpleItemPath]
  extends UnsignedItemPath.Companion[A]
  with UnsignedItemKey.Companion[A]
  with SimpleItemPath.Companion[A]
  {
    type Item <: UnsignedSimpleItem
    override implicit def implicitCompanion = this
  }

  def jsonCodec(companions: Iterable[UnsignedSimpleItemPath.Companion_]): Codec[UnsignedSimpleItemPath] =
    InventoryItemKey.jsonCodec(companions)
      .asInstanceOf[Codec[UnsignedSimpleItemPath]]
}
