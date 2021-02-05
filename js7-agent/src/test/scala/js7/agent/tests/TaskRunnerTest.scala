package js7.agent.tests

import com.google.inject.Guice
import java.io.Writer
import java.nio.file.Files.{createTempDirectory, setPosixFilePermissions}
import java.nio.file.attribute.PosixFilePermissions
import js7.agent.configuration.AgentConfiguration
import js7.agent.configuration.inject.AgentModule
import js7.agent.tests.TaskRunnerTest.{TestScript, TestStdoutStderrWriter}
import js7.base.io.file.FileUtils.deleteDirectoryRecursively
import js7.base.io.file.FileUtils.syntax.RichPath
import js7.base.io.process.Processes.{ShellFileExtension => sh}
import js7.base.io.process.ReturnCode
import js7.base.system.OperatingSystem.{isUnix, isWindows}
import js7.base.time.ScalaTime._
import js7.base.time.Stopwatch.measureTime
import js7.base.utils.Closer
import js7.common.guice.GuiceImplicits.RichInjector
import js7.common.scalautil.MonixUtils.syntax.RichTask
import js7.data.job.{CommandLine, JobKey, RelativePathExecutable}
import js7.data.order.{HistoricOutcome, Order, OrderId, Outcome}
import js7.data.value.{NamedValues, NumberValue, StringValue}
import js7.data.workflow.WorkflowPath
import js7.data.workflow.position.Position
import js7.executor.configuration.TaskConfiguration
import js7.executor.process.{RichProcess, SimpleShellTaskRunner}
import js7.executor.task.StdChannels
import monix.execution.Scheduler.Implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class TaskRunnerTest extends AnyFreeSpec with BeforeAndAfterAll with TestAgentDirectoryProvider
{
  private lazy val injector = Guice.createInjector(new AgentModule(AgentConfiguration.forTest(agentDirectory)))

  override protected def afterAll() = {
    injector.instance[Closer].close()
    closer.close()
    super.afterAll()
  }

  "SimpleShellTaskRunner" in {
    val newTaskRunner = injector.instance[SimpleShellTaskRunner.Factory]
    val executableDirectory = createTempDirectory("TaskRunnerTest-")

    val pathExecutable = RelativePathExecutable(s"TEST$sh", v1Compatible = true)
    val shellFile = pathExecutable.toFile(executableDirectory)
    shellFile := TestScript
    if (isUnix) setPosixFilePermissions(shellFile, PosixFilePermissions.fromString("rwx------"))
    def toOutcome(namedValues: NamedValues, returnCode: ReturnCode) =
      Outcome.Succeeded(namedValues + ("returnCode" -> NumberValue(returnCode.number)))
    val taskConfiguration = TaskConfiguration(JobKey.forTest, toOutcome, CommandLine.fromFile(shellFile))
    info(measureTime(10, "TaskRunner") {
      val order = Order(
        OrderId("TEST"),
        WorkflowPath("JOBCHAIN") ~ "VERSION",
        Order.Processing,
        historicOutcomes = Seq(HistoricOutcome(Position(999), Outcome.Succeeded(Map("a" -> StringValue("A"))))))
      val taskRunner = newTaskRunner(taskConfiguration)
      val stdoutWriter = new TestStdoutStderrWriter
      val stderrWriter = new TestStdoutStderrWriter
      val stdChannels = new StdChannels(charBufferSize = 10,
        stdoutWriter = stdoutWriter, stderrWriter = stderrWriter)
      val ended = taskRunner.processOrder(order.id, Map("VAR1" -> "VALUE1"), stdChannels)
        .guarantee(taskRunner.terminate) await 30.s
      assert(ended == Outcome.Succeeded(Map(
        "result" -> StringValue("TEST-RESULT-VALUE1"),
        "returnCode" -> NumberValue(0))))
      val nl = System.lineSeparator
      assert(stdoutWriter.string == s"Hej!${nl}var1=VALUE1$nl")
      assert(stderrWriter.string == s"THIS IS STDERR$nl")
    }.toString)
    RichProcess.tryDeleteFiles(shellFile :: Nil)
    deleteDirectoryRecursively(executableDirectory)
  }
}

object TaskRunnerTest {
  private val TestScript =
    if (isWindows) """
      |@echo off
      |echo Hej!
      |echo THIS IS STDERR>&2
      |echo var1=%VAR1%
      |echo result=TEST-RESULT-%VAR1% >>"%JS7_RETURN_VALUES%"
      |""".stripMargin
    else """
      |echo "Hej!"
      |echo THIS IS STDERR >&2
      |echo "var1=$VAR1"
      |echo "result=TEST-RESULT-$VAR1" >>"$JS7_RETURN_VALUES"
      |""".stripMargin

  private final class TestStdoutStderrWriter extends Writer {
    private val stringBuilder = new StringBuilder

    def chunkSize = 10

    def write(chars: Array[Char], offset: Int, length: Int) =
      stringBuilder.appendAll(chars, offset, length)

    def string = stringBuilder.toString

    def flush() = {}

    def close() = {}
  }
}
