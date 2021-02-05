package js7.base.circeutils.typed

import io.circe.{CursorOp, Decoder, DecodingFailure, Encoder, HCursor, Json, JsonObject}
import js7.base.circeutils.typed.TypedJsonCodec._
import js7.base.utils.Collections.implicits._
import js7.base.utils.ScalaUtils._
import js7.base.utils.ScalaUtils.syntax._
import scala.reflect.ClassTag

/**
  * @author Joacim Zschimmer
  */
final class TypedJsonCodec[A](
  val superclassName: String,
  val classToEncoder: Map[Class[_], Encoder.AsObject[_ <: A]],
  val nameToDecoder: Map[String, Decoder[_ <: A]],
  val nameToClass: Map[String, Class[_ <: A]])
extends Encoder.AsObject[A] with Decoder[A]
{
  private val _classToName: Map[Class[_ <: A], String] =
    nameToClass.map(o => o._2 -> o._1)

  private val classToNameJson: Map[Class[_ <: A], Json/*String*/] =
    _classToName.view.mapValues(Json.fromString).toMap

  /** Union. */
  def |[B](other: TypedJsonCodec[B]): TypedJsonCodec[Any] = {
    val sameClasses = classToEncoder.keySet & other.classToEncoder.keySet
    if (sameClasses.nonEmpty) throw new IllegalArgumentException(s"Union of TypedJsonCodec has non-unique classes: $sameClasses")
    val sameClassNames = nameToClass.keySet & other.nameToClass.keySet
    if (sameClassNames.nonEmpty) throw new IllegalArgumentException(s"Union of TypedJsonCodec has non-unique decoder names: $sameClassNames")
    val sameDecoderNames = nameToDecoder.keySet & other.nameToDecoder.keySet
    if (sameDecoderNames.nonEmpty) throw new IllegalArgumentException(s"Union of TypedJsonCodec has non-unique class names: $sameDecoderNames")

    new TypedJsonCodec[Any](
      s"$this|$other",
      classToEncoder ++ other.classToEncoder,
      nameToDecoder ++ other.nameToDecoder,
      nameToClass ++ other.nameToClass)
  }

  def apply(c: HCursor) = decode(c)

  def encodeObject(a: A): JsonObject =
    classToEncoder(a.getClass).asInstanceOf[Encoder.AsObject[A]].encodeObject(a)

  def decode(c: HCursor): Decoder.Result[A] =
    c.get[String](TypeFieldName)
      .flatMap(name => nameToDecoder.get(name) match {
        case None => Left(unknownJsonTypeFailure(name, superclassName, c.history))
        case Some(decoder) => decoder.apply(c)
      })

  def canDeserialize(json: Json): Boolean =
    json.asObject match {
      case Some(o) => o.toMap.get(TypeFieldName).flatMap(_.asString) exists nameToDecoder.contains
      case _ => false
    }

  def typeName[A1 <: A](a: A1): String =
    _classToName(a.getClass)

  def classToName(getClass: Class[_ <: A]): String =
    _classToName(getClass)

  def classes[A1 <: A: ClassTag]: Set[Class[_ <: A1]] =
    classToEncoder.keySet collect {
      case c if implicitClass[A1] isAssignableFrom c => c.asInstanceOf[Class[_ <: A1]]
    }

  def isOfType[A1 <: A: ClassTag](json: Json): Boolean =
    json.asObject.flatMap(_(TypeFieldName)) contains classToNameJson(implicitClass[A1])

  def jsonToClass(json: Json): Option[Class[_ <: A]] =
    json.asObject.flatMap(_(TypeFieldName)).flatMap(_.asString).flatMap(nameToClass.get)

  override def toString = s"TypedJsonCodec[$superclassName]"
}

object TypedJsonCodec
{
  val TypeFieldName = "TYPE"

  def typeField[A: ClassTag]: (String, Json) =
    TypeFieldName -> Json.fromString(typeName(implicitClass[A]))

  def typeName[A: ClassTag]: String =
    typeName(implicitClass[A])

  def typeName(cls: Class[_]): String =
    cls.simpleScalaName

  def apply[A: ClassTag](subtypes: Subtype[_ <: A]*): TypedJsonCodec[A] = {
    val cls = implicitClass[A]
    new TypedJsonCodec[A](
      implicitClass[A].shortClassName,
      subtypes.flatMap(_.classToEncoder).uniqueToMap withDefault (o => throw new UnknownClassForJsonException(o, cls)),
      subtypes.flatMap(_.nameToDecoder).uniqueToMap,
      subtypes.flatMap(_.nameToClass).uniqueToMap)
  }

  implicit final class TypedJson(private val underlying: Json) extends AnyVal {
    def isOfType[A: TypedJsonCodec, A1 <: A: ClassTag]: Boolean =
      implicitly[TypedJsonCodec[A]].isOfType[A1](underlying)

    def toClass[A: TypedJsonCodec]: Option[Class[_ <: A]] =
      implicitly[TypedJsonCodec[A]].jsonToClass(underlying)
  }

  final class UnknownClassForJsonException(subclass: Class[_], superclass: Class[_])
    extends NoSuchElementException(s"Class ${subclass.getName} is not registered with TypedJsonCodec[${superclass.shortClassName}]")

  final def unknownJsonTypeFailure(typeName: String, superclassName: String, history: List[CursorOp]): DecodingFailure =
    DecodingFailure(s"""Unexpected JSON {"$TypeFieldName": "$typeName", ...} for class '$superclassName'""", history)
}
