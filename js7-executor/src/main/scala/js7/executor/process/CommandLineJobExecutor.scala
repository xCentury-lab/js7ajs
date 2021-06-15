package js7.executor.process

import js7.base.problem.Checked
import js7.data.job.{CommandLineEvaluator, CommandLineExecutable, JobConf, JobResource, JobResourcePath}
import js7.executor.configuration.JobExecutorConf
import js7.executor.internal.JobExecutor.warnIfNotExecutable
import js7.executor.process.ProcessJobExecutor.StartProcess
import js7.executor.{OrderProcess, ProcessOrder}
import monix.eval.Task

final class CommandLineJobExecutor(
  protected val executable: CommandLineExecutable,
  protected val jobConf: JobConf,
  protected val jobExecutorConf: JobExecutorConf,
  protected val pathToJobResource: JobResourcePath => Checked[JobResource])
extends ProcessJobExecutor
{
  override def stop = Task.unit

  def prepareOrderProcess(processOrder: ProcessOrder): Task[Checked[OrderProcess]] =
    Task {
      new CommandLineEvaluator(processOrder.scope.evaluator)
        .eval(executable.commandLineExpression)
        .flatMap { commandLine =>
          warnIfNotExecutable(commandLine.file)
          evalEnv(executable.env, processOrder.scope)
            .map(env =>
              makeOrderProcess(
                processOrder,
                StartProcess(
                  commandLine,
                  name = commandLine.file.getFileName.toString,
                  env)))
        }
    }
}
