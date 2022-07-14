package js7.base.io.process

import java.nio.file.Files.*
import java.nio.file.attribute.PosixFilePermissions.*
import java.nio.file.attribute.{FileAttribute, PosixFilePermissions}
import java.nio.file.{FileAlreadyExistsException, Path}
import js7.base.io.process.OperatingSystemSpecific.*
import js7.base.log.Logger
import js7.base.system.OperatingSystem.isWindows

/**
  * @author Joacim Zschimmer
  */
private[process] sealed trait OperatingSystemSpecific {

  /**
    * Including dot.
    * For example ".sh" or ".cmd".
    */
  def shellFileExtension: String

  def shellFileAttributes: Seq[FileAttribute[java.util.Set[_]]]

  def newTemporaryShellFile(name: String) = createTempFile(filenamePrefix(name), shellFileExtension, shellFileAttributes: _*)

  def newLogFile(directory: Path, name: String, outerr: StdoutOrStderr) = {
    val file = directory resolve s"$name-$outerr.log"
    try createFile(file, outputFileAttributes: _*)
    catch { case t: FileAlreadyExistsException =>
      logger.debug(t.toString)  // Should rarely happen
    }
    file
  }

  protected def outputFileAttributes: Seq[FileAttribute[java.util.Set[_]]]

  def directShellCommandArguments(argument: String): Seq[String]

  protected final def filenamePrefix(name: String) = s"JS7-Agent-$name-"
}

private object OperatingSystemSpecific
{
  private val logger = Logger[this.type]

  private[process] val OS: OperatingSystemSpecific = if (isWindows) OperatingSystemSpecific.Windows else OperatingSystemSpecific.Unix

  private object Unix extends OperatingSystemSpecific {
    val shellFileExtension = ".sh"
    val shellFileAttributes = List(asFileAttribute(PosixFilePermissions fromString "rwx------"))
      .asInstanceOf[Seq[FileAttribute[java.util.Set[_]]]]
    val outputFileAttributes = List(asFileAttribute(PosixFilePermissions fromString "rw-------"))
      .asInstanceOf[Seq[FileAttribute[java.util.Set[_]]]]

    def directShellCommandArguments(argument: String) = Vector("/bin/sh", "-c", argument)
  }

  private object Windows extends OperatingSystemSpecific {
    private val Cmd: String = sys.env.get("ComSpec") orElse sys.env.get("COMSPEC" /*cygwin*/) getOrElse """C:\Windows\system32\cmd.exe"""
    val shellFileExtension = ".cmd"
    val shellFileAttributes = Nil
    val outputFileAttributes = Nil

    def directShellCommandArguments(argument: String) = Vector(Cmd, "/C", argument)
  }
}
