package com.sos.jobscheduler.data.filebased

import com.sos.jobscheduler.base.utils.ScalaUtils.implicitClass
import com.sos.jobscheduler.data.filebased.TypedPath._
import java.nio.file.{Path, Paths}
import scala.reflect.ClassTag

trait TypedPath
extends AbsolutePath {

  def companion: Companion[_ <: TypedPath]

  def xmlFile: Path =
    Paths.get(withoutStartingSlash + companion.xmlFilenameExtension)

  def jsonFile: Path =
    Paths.get(withoutStartingSlash + companion.jsonFilenameExtension)

  def asTyped[A <: TypedPath: TypedPath.Companion]: A = {
    val c = implicitly[TypedPath.Companion[A]]
    if (c == companion)
      this.asInstanceOf[A]
    else
      c.apply(string)
  }

  override def toString = toTypedString

  def toTypedString: String = s"${companion.camelName}:$string"
}

object TypedPath {
  implicit def ordering[P <: TypedPath]: Ordering[P] =
    new Ordering[P] {
      def compare(a: P, b: P) = a.string compare b.string
    }

  type AnyCompanion = Companion[_ <: TypedPath]

  abstract class Companion[P <: TypedPath: ClassTag] extends AbsolutePath.Companion[P] {
    implicit val implicitCompanion: Companion[P] = this

    def typedPathClass: Class[P] = implicitClass[P]

    val camelName: String = name stripSuffix "Path"
    final lazy val lowerCaseCamelName = camelName.substring(0, 1).toLowerCase + camelName.substring(1)
    final lazy val cppName: String = lowerCaseCamelName map { c ⇒ if (c.isUpper) "_" + c.toLower else c } mkString ""
    lazy val xmlFilenameExtension: String = s".$cppName.xml"
    lazy val jsonFilenameExtension: String = s".$lowerCaseCamelName.json"

    /**
     * Interprets a path as absolute.
     *
     * @param path A string starting with "./" is rejected
     */
    final def makeAbsolute(path: String): P =
      apply(absoluteString(path))
  }

  /**
   * Interprets a path as absolute.
   *
   * @param path A string starting with "./" is rejected
   */
  private def absoluteString(path: String): String =
    if (AbsolutePath.isAbsolute(path))
      path
    else {
      require(!(path startsWith "./"), s"Relative path is not possible here: $path")
      s"/$path"
    }
}
