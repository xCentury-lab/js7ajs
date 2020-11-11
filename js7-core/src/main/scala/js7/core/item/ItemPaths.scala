package js7.core.item

import java.io.File.separator
import java.nio.file.Path
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.utils.Assertions.assertThat
import js7.data.item.{SourceType, ItemPath}

/**
  * @author Joacim Zschimmer
  */
object ItemPaths
{
  def fileToItemPath(companions: Iterable[ItemPath.AnyCompanion], directory: Path, file: Path): Checked[ItemPath] =
    fileToItemPathAndSourceType(companions, directory, file).map(_._1)

  def fileToItemPathAndSourceType(companions: Iterable[ItemPath.AnyCompanion], directory: Path, file: Path): Checked[(ItemPath, SourceType)] = {
    assertThat(file startsWith directory)
    val relativePath = file.subpath(directory.getNameCount, file.getNameCount)
    val string = ItemPath.fileToString(relativePath)
    companions.iterator
      .map(_.fromFile(string))
      .collectFirst { case Some(o) =>
        o flatMap { case (itemPath, sourceType) => itemPath.officialSyntaxChecked.map(_ -> sourceType) }
      }
      .toChecked(AlienFileProblem(relativePath))
      .flatten
  }

  final case class AlienFileProblem(relativePath: Path)
  extends Problem.Lazy(s"File '...$separator${relativePath.toString.stripPrefix(separator)}' is not recognized as a configuration file")
}
