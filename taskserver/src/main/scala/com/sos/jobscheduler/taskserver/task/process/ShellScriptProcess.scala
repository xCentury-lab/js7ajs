package com.sos.jobscheduler.taskserver.task.process

import com.sos.jobscheduler.common.process.Processes._
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.promiseFuture
import com.sos.jobscheduler.common.scalautil.IOExecutor
import com.sos.jobscheduler.common.scalautil.IOExecutor.ioFuture
import com.sos.jobscheduler.data.job.ReturnCode
import com.sos.jobscheduler.taskserver.task.process.RichProcess._
import java.io.{InputStream, InputStreamReader, Reader, Writer}
import java.nio.file.Path
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/**
  * @author Joacim Zschimmer
  */
class ShellScriptProcess private(
  processConfiguration: ProcessConfiguration,
  process: Process,
  private[process] val temporaryScriptFile: Path,
  argumentsForLogging: Seq[String])
  (implicit iox: IOExecutor, ec: ExecutionContext)
extends RichProcess(processConfiguration, process, argumentsForLogging) {

  stdin.close() // Process gets an empty stdin
}

object ShellScriptProcess
{
  def startShellScript(
    processConfiguration: ProcessConfiguration,
    name: String = "shell-script",
    scriptString: String)
    (implicit ec: ExecutionContext, iox: IOExecutor)
  : ShellScriptProcess = {
    val shellFile = newTemporaryShellFile(name)
    try {
      shellFile.write(scriptString, processConfiguration.encoding)
      val process = startProcessBuilder(processConfiguration, shellFile, arguments = Nil) { _.startRobustly() }
      new ShellScriptProcess(processConfiguration, process, shellFile, argumentsForLogging = shellFile.toString :: Nil) {
        override val terminated = promiseFuture[ReturnCode] { p ⇒
          super.terminated onComplete { o ⇒
            tryDeleteFile(shellFile)
            p.complete(o)
          }
        }
      }
    }
    catch { case NonFatal(t) ⇒
      tryDeleteFile(shellFile)
      throw t
    }
  }

  def startPipedShellScript(shellFile: Path, conf: ProcessConfiguration, stdChannels: StdChannels)
    (implicit ec: ExecutionContext, iox: IOExecutor): ShellScriptProcess =
  {
    val processBuilder = new ProcessBuilder(toShellCommandArguments(shellFile, conf.idArgumentOption.toList).asJava)
    for (o ← conf.workingDirectory) processBuilder.directory(o)
    processBuilder.environment.putAll(conf.additionalEnvironment.asJava)
    val process = processBuilder.startRobustly()
    def copy(in: InputStream, w: Writer) = copyChunks(new InputStreamReader(in, conf.encoding), stdChannels.charBufferSize, w)
    val stdoutClosed = ioFuture {
      copy(process.getInputStream, stdChannels.stdoutWriter)
    }
    val stderrClosed = ioFuture {
      copy(process.getErrorStream, stdChannels.stderrWriter)
    }

    new ShellScriptProcess(conf, process, shellFile, argumentsForLogging = shellFile.toString :: Nil) {
      override def terminated = for {
        _ ← stdoutClosed
        _ ← stderrClosed
        returnCode ← super.terminated
      } yield returnCode
    }
  }

  private def copyChunks(reader: Reader, charBufferSize: Int, writer: Writer): Unit = {
    val array = new Array[Char](charBufferSize)

    @tailrec def loop(): Unit =
      reader.read(array) match {
        case -1 ⇒
        case len ⇒
          writer.write(array, 0, len)
          loop()
      }

    try loop()
    finally writer.close()  // Send "end of file"
  }
}
