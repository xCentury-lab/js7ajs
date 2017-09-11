package com.sos.jobscheduler.base.sprayjson.typed

import com.sos.jobscheduler.base.sprayjson.SprayJson.implicits.RichJsValue
import com.sos.jobscheduler.base.utils.ScalaUtils.implicitClass
import scala.reflect.ClassTag
import spray.json._

/**
 * @author Joacim Zschimmer
 */
trait TypedJsonFormat[A] extends RootJsonFormat[A] with HasOwnTypeField[A] {

  def asJsObjectJsonFormat: TypedJsonFormat[A] = this

  def canSerialize(a: A): Boolean

  def canDeserialize(o: JsObject): Boolean

  final lazy val classes: Set[Class[_ <: A]] = classToJsonWriter.keySet map { _.asInstanceOf[Class[_ <: A]] }

  implicit final lazy val classJsonFormat = new JsonFormat[Class[_ <: A]] {
    private val toClass: Map[String, Class[_ <: A]] = (classes map { o ⇒ classToTypeName(o) → o }).toMap

    def write(o: Class[_ <: A]) = JsString(classToTypeName(o))

    def read(json: JsValue) = toClass(json.asJsString.value)
  }
}

object TypedJsonFormat {
  val DefaultTypeFieldName = "TYPE"
  private val DefaultShortenTypeOnlyValue = false

  /**
    * A RootJsonType for polymorphic types.
    * <p><b>On compile error using TypedJsonFormat(Subtype ...):</b> check if every Subtype really denotes a subtype of A.
    */
  def apply[A: ClassTag](subtype: Subtype[_], subtypes: Subtype[_]*): TypedJsonFormat[A] =
    _apply[A](superclassName = implicitClass[A].getSimpleName, subtypes = subtype +: subtypes)

  /**
    * A RootJsonType for polymorphic types.
    * <p><b>On compile error using TypedJsonFormat(Subtype ...):</b> check if every Subtype really denotes a subtype of A.
    *
    * @param typeField            Name of the subtype discriminating field
    * @param shortenTypeOnlyValue To be short and more readable,
    *                             typename-only values are serialized as simple string "typename"
    *                             instead of { "TYPE": "typename" }
    * @param subtypes
    * @tparam A
    * @return
    */
  def apply[A: ClassTag](
    typeField: String = DefaultTypeFieldName,
    name: String = "",  // This name is only for users of typeNameToClass, like "Event"
    shortenTypeOnlyValue: Boolean = DefaultShortenTypeOnlyValue)
    (subtypes: Subtype[_]*)
  : TypedJsonFormat[A] =
    _apply[A](typeFieldName = typeField, superclassName = name, shortenTypeOnlyValue = shortenTypeOnlyValue, subtypes = subtypes)

  private def _apply[A: ClassTag](
    typeFieldName: String = DefaultTypeFieldName,
    superclassName: String,  // This name is only for users of typeNameToClass, like "Event"
    shortenTypeOnlyValue: Boolean = DefaultShortenTypeOnlyValue,
    subtypes: Seq[Subtype[_]]): TypedJsonFormat[A]
  = {
    val mySuperclassName = if (superclassName.nonEmpty) superclassName else implicitClass[A].getSimpleName
    val typeNamesAndClasses = subtypes flatMap { _.nameToClass mapValues { _.asInstanceOf[Class[_ <: A]] }}
    new WithSubtypeRegister[A](
      implicitClass[A],
      classToJsonWriter = (subtypes flatMap { _.toClassToJsonWriter(typeFieldName) }).toMap,
      typeNameToClass = (Vector(mySuperclassName → implicitClass[A]) ++ typeNamesAndClasses).toMap withDefault throwUnknownType,
      typeNameToJsonReader = (subtypes flatMap { _.toTypeToReader(typeFieldName) }).toMap withDefault throwUnknownType,
      (typeNamesAndClasses map { _._1 }).toVector,
      typeFieldName = typeFieldName,
      shortenTypeOnlyValue = shortenTypeOnlyValue)
  }

  private def throwUnknownType(name: String) = throw new NoSuchElementException(s"Unknown type '$name'")

  /**
    * For recursive structures.
    */
  def asLazy[A](lazyTypedJsonFormat: ⇒ TypedJsonFormat[A]): AsLazy[A] =
    new AsLazy[A] {
      lazy val delegate = lazyTypedJsonFormat
    }

  sealed trait AsLazy[A] extends RootJsonFormat[A] with TypedJsonFormat[A] {
    def delegate: TypedJsonFormat[A]
    final def canDeserialize(o: JsObject) = delegate.canDeserialize(o)
    final def classToJsonWriter = delegate.classToJsonWriter
    final def typeNameToJsonReader = delegate.typeNameToJsonReader
    final def typeNameToClass = delegate.typeNameToClass
    final def subtypeNames = delegate.subtypeNames
    final def canSerialize(a: A) = delegate canSerialize a
    final def write(x: A) = delegate.write(x)
    final def read(value: JsValue) = delegate.read(value)
  }
}
