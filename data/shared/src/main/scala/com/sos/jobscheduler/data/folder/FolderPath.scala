package com.sos.jobscheduler.data.folder

import com.sos.jobscheduler.data.filebased.{AbsolutePath, TypedPath}
import java.nio.file.Paths

final case class FolderPath(string: String) extends TypedPath {
  import FolderPath._
  validate()

  def companion = FolderPath

  def subfolder(name: String): FolderPath = {
    require(!name.contains('/'), "Name must not contain a slash '/'")
    FolderPath(s"${string stripSuffix "/"}/$name")
  }

  /**
    * Resolves the given path agains this FolderPath.
    * <ul>
    *   <li>An absolute `path` starting with "/" is used as given.
    *   <li>A relative `path` (not starting with "/") is used relative to this `FolderPath`.
    * </ul>
   */
  def resolve[P <: TypedPath: TypedPath.Companion](path: String) =
    implicitly[TypedPath.Companion[P]].apply(absoluteString(this, path))

  def isParentOf(path: TypedPath): Boolean =
    path != FolderPath.Root && this == parentOf(path)

  def isAncestorOf(path: TypedPath): Boolean =
    (path.string startsWith withTrailingSlash) ||
      PartialFunction.cond(path) {
        case path: FolderPath ⇒ this == path
      }

  override def xmlFile = EmptyPath

  override def jsonFile = EmptyPath
}

object FolderPath extends TypedPath.Companion[FolderPath] {

  private val EmptyPath = Paths.get("")
  val Root = FolderPath("/")
  override lazy val xmlFilenameExtension = "/"

  override def isSingleSlashAllowed = true
  override def isCommaAllowed = false

  def fromTrailingSlash(string: String) = {
    require(string endsWith "/", "Trailing slash required for FolderPath")
    FolderPath(if (string == "/") string else string stripSuffix "/")
  }

  def parentOf(path: TypedPath): FolderPath =
    path.string lastIndexOf '/' match {
      case 0 if path.string == "/" ⇒ throw new IllegalStateException("Root path has no parent folder")
      case 0 ⇒ FolderPath.Root
      case -1 ⇒ FolderPath.Root // In case of ProcessClass.Default (the empty string)
      case n ⇒ FolderPath(path.string.substring(0, n))
    }

  /**
   * An absolute `path` starting with "/" is used as given.
   * A relative `path` not starting with "/" is used relative to `defaultFolder`.
   */
  private def absoluteString(defaultFolder: FolderPath, path: String): String = {
    if (AbsolutePath.isAbsolute(path))
      path
    else
      s"${defaultFolder.withTrailingSlash}${path stripPrefix "./"}"
  }
}
