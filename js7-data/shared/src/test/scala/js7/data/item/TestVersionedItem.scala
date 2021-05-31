package js7.data.item

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class TestPath(string: String) extends ItemPath {
  def companion = TestPath
}

object TestPath extends ItemPath.Companion[TestPath] {
  val sourceTypeToFilenameExtension = Map(
    SourceType.Json -> ".test.json")

  protected def unchecked(string: String) = new TestPath(string)
}


final case class TestVersionedItem(id: TestVersionedItem.Key, content: String) extends VersionedItem {
  type Self = TestVersionedItem
  val companion = TestVersionedItem

  def withId(id: VersionedItemId[Path]) = copy(id)

  def referencedItemPaths = Set.empty
}

object TestVersionedItem extends VersionedItem.Companion[TestVersionedItem] {
  type Item = TestVersionedItem
  val cls = classOf[TestVersionedItem]

  type Path = TestPath
  val Path = TestPath

  override type Key = VersionedItemId[Path]

  implicit val jsonCodec: Codec.AsObject[TestVersionedItem] = deriveCodec[TestVersionedItem]
}
