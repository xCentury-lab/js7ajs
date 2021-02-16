package js7.executor.process

import java.nio.file.Path
import js7.base.log.Logger
import js7.data.job.JobKey
import js7.executor.process.FilePool._
import js7.executor.process.RichProcess.tryDeleteFiles
import scala.collection.mutable

// Not used !!!
/**
  * @author Joacim Zschimmer
  */
private[process] final class FilePool(jobKey: JobKey, temporaryDirectory: Path) extends AutoCloseable
{
  private var free = List.empty[FileSet]
  private val used = mutable.Set.empty[FileSet]

  def get(): FileSet = {
    val fileSet = free match {
      case head :: tail =>
        free = tail
        head.clear()
        head
      case Nil =>
        val fileSet = FileSet(new ShellReturnValuesProvider(temporaryDirectory))
        logger.trace(s"Job '${jobKey.keyName}': Using file ${fileSet.shellReturnValuesProvider} for order namedValues")
        fileSet
    }
    used += fileSet
    fileSet
  }

  def release(fileSet: FileSet): Unit = {
    val removed = used.remove(fileSet)
    require(removed, s"Job '${jobKey.keyName}': Releasing unknown FileSet")
    free = fileSet :: free
  }

  def close() = {
    if (used.nonEmpty) {
      logger.debug(s"Job '${jobKey.keyName}': Closing while files are in use: $used")
    }
    tryDeleteFiles((free ++ used).flatMap(_.files))
  }

  override def toString = s"FilePool(Job '${jobKey.keyName}' ${used.size} used and ${free.size} free file sets)"
}

private[process] object FilePool
{
  private val logger = Logger(getClass)

  final case class FileSet(shellReturnValuesProvider: ShellReturnValuesProvider)
  {
    def clear() =
      shellReturnValuesProvider.clear()

    def files: Seq[Path] =
      shellReturnValuesProvider.file :: Nil
  }
}
