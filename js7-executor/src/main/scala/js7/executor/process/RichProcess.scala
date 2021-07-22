package js7.executor.process

import java.io.{BufferedOutputStream, OutputStream, OutputStreamWriter}
import java.lang.ProcessBuilder.Redirect.INHERIT
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files.delete
import java.nio.file.Path
import js7.base.io.process.ProcessSignal.{SIGKILL, SIGTERM}
import js7.base.io.process.Processes._
import js7.base.io.process.{JavaProcess, Js7Process, ProcessSignal, ReturnCode, StdoutOrStderr}
import js7.base.log.LogLevel.syntax._
import js7.base.log.{LogLevel, Logger}
import js7.base.system.OperatingSystem.{isMac, isWindows}
import js7.base.thread.IOExecutor
import js7.base.thread.IOExecutor.ioTask
import js7.base.time.ScalaTime._
import js7.base.utils.ScalaUtils.syntax._
import js7.executor.process.RichProcess._
import monix.eval.Task
import org.jetbrains.annotations.TestOnly
import scala.concurrent.duration.Deadline.now
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/**
  * @author Joacim Zschimmer
  */
class RichProcess protected[process](
  val processConfiguration: ProcessConfiguration,
  process: Js7Process)
  (implicit iox: IOExecutor)
{
  private val runningSince = now
  val pidOption: Option[Pid] = process.pid
  @volatile var _killed = false
  private val logger = Logger.withPrefix[this.type](toString)
  /**
   * UTF-8 encoded stdin.
   */
  lazy val stdinWriter = new OutputStreamWriter(new BufferedOutputStream(stdin), UTF_8)

  private lazy val _terminated: Task[ReturnCode] =
    Task.defer {
      process.returnCode.map(Task.pure)
        .getOrElse(ioTask {
          waitForProcessTermination(process)
        })
    }.memoize

  def duration = runningSince.elapsed

  def terminated: Task[ReturnCode] =
    _terminated

  final def sendProcessSignal(signal: ProcessSignal): Task[Unit] =
    Task.deferAction { implicit s =>
      _killed = true
      signal match {
        case SIGTERM =>
          if (isWindows)
            Task.raiseError(new UnsupportedOperationException(
              "SIGTERM is a Unix process signal and cannot be handled by Microsoft Windows"))
          else {
            logger.info("destroy (SIGTERM)")
            process.destroy()
            Task.unit
          }

        case SIGKILL =>
          processConfiguration
            .toKillScriptCommandArgumentsOption(pidOption)
            .fold(kill)(args =>
              executeKillScript(args ++ pidOption.map(o => s"--pid=${o.string}"))
                .onErrorHandle(t => logger.error(
                  s"Cannot start kill script command '$args': ${t.toStringWithCauses}"))
                .tapEval(_ => kill))
      }
    }

  private def executeKillScript(args: Seq[String]): Task[Unit] =
    if (isMac)
      Task {
        logger.warn("Execution of kill script is suppressed on MacOS")  // TODO On MacOS, the kill script may kill a foreign process like the developers IDE
      }
    else
      Task.defer {
        logger.info("Executing kill script: " + args.mkString("  "))
        val processBuilder = new ProcessBuilder(args.asJava)
          .redirectOutput(INHERIT)
          .redirectError(INHERIT)
        processBuilder
          .startRobustly()
          .executeOn(iox.scheduler)
          .flatMap(onKillProcess =>
            ioTask {
              waitForProcessTermination(JavaProcess(onKillProcess))
            } >> Task {
              val exitCode = onKillProcess.exitValue
              val logLevel = if (exitCode == 0) LogLevel.Debug else LogLevel.Warn
              logger.log(logLevel, s"Kill script '${args(0)}' has returned exit code $exitCode")
            })
      }

  private def kill = Task {
    logger.info("destroyForcibly" + (!isWindows ?? " (SIGKILL)"))
    process.destroyForcibly()
  }

  final def isKilled = _killed

  @TestOnly
  private[process] final def isAlive = process.isAlive

  final def stdin: OutputStream =
    process.stdin

  override def toString =
    process.toString
}

object RichProcess
{
  private val logger = Logger(getClass)

  def createStdFiles(directory: Path, id: String): Map[StdoutOrStderr, Path] =
    (StdoutOrStderr.values map { o => o -> newLogFile(directory, id, o) }).toMap

  private def waitForProcessTermination(process: Js7Process): ReturnCode = {
    logger.trace(s"waitFor $process ...")
    val rc = process.waitFor()
    logger.trace(s"waitFor $process exitCode=$rc")
    rc
  }

  def tryDeleteFile(file: Path) = tryDeleteFiles(file :: Nil)

  def tryDeleteFiles(files: Iterable[Path]): Boolean = {
    var allFilesDeleted = true
    for (file <- files) {
      try {
        logger.debug(s"Delete file '$file'")
        delete(file)
      }
      catch { case NonFatal(t) =>
        allFilesDeleted = false
        logger.warn(s"Cannot delete file '$file': ${t.toStringWithCauses}")
      }
    }
    allFilesDeleted
  }
}
