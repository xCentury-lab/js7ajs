package js7.executor.process

import cats.syntax.all._
import java.util.Locale.ROOT
import js7.base.io.process.ProcessSignal.{SIGKILL, SIGTERM}
import js7.base.problem.Checked
import js7.data.job.{CommandLine, JobResource, ProcessExecutable}
import js7.data.order.Outcome
import js7.data.value.expression.Scope.evalExpressionMap
import js7.data.value.expression.scopes.LazyNamedValueScope
import js7.data.value.expression.{Expression, Scope}
import js7.data.value.{NullValue, StringValue}
import js7.executor.configuration.{JobExecutorConf, TaskConfiguration}
import js7.executor.internal.JobExecutor
import js7.executor.process.ProcessJobExecutor._
import js7.executor.{OrderProcess, ProcessOrder}
import monix.eval.{Fiber, Task}

trait ProcessJobExecutor extends JobExecutor
{
  protected val executable: ProcessExecutable
  protected def jobExecutorConf: JobExecutorConf

  import executable.v1Compatible
  import jobConf.jobKey

  final val start = Task.pure(Right(()))

  protected final def makeOrderProcess(processOrder: ProcessOrder, startProcess: StartProcess)
  : OrderProcess = {
    import processOrder.{evalLazilyJobResourceVariables, order, scopeForJobResourceEnv}

    def evalJobResourceEnv(jobResource: JobResource): Checked[Map[String, String]] =
      evalEnv(
        jobResource.env,
        scopeForJobResourceEnv |+| LazyNamedValueScope(evalLazilyJobResourceVariables(jobResource)))

    val checkedJobResourcesEnv: Checked[Map[String, String]] =
      processOrder.jobResources
        .reverse/*left overrides right*/
        .traverse(evalJobResourceEnv)
        .map(_.fold(Map.empty)(_ ++ _))

    val processDriver = new ProcessDriver(
      TaskConfiguration(jobKey, executable.toOutcome, startProcess.commandLine, executable.login,
        v1Compatible = v1Compatible),
      jobExecutorConf)

    new OrderProcess {
      def run: Task[Fiber[Outcome.Completed]] = {
        val checkedEnv = for {
          jobResourcesEnv <- checkedJobResourcesEnv
          v1 <- v1Env(processOrder)
        } yield (v1.view ++ startProcess.env ++ jobResourcesEnv).toMap
        checkedEnv match {
          case Left(problem) =>
            Task.pure(Outcome.Failed.fromProblem(problem): Outcome.Completed).start
          case Right(env) =>
            processDriver
              .startAndRunProcess(
                order.id,
                env,
                processOrder.stdObservers)
        }
      }

      override def cancel(immediately: Boolean) =
        processDriver.kill(if (immediately) SIGKILL else SIGTERM)
    }
  }

  private def v1Env(processOrder: ProcessOrder): Checked[Map[String, String]] =
    if (!v1Compatible)
      Right(Map.empty)
    else
      for (defaultArguments <- processOrder.checkedDefaultArguments) yield
        (defaultArguments.view ++
          processOrder.order.v1CompatibleNamedValues(
            processOrder.workflow.defaultArguments)
        ) .map { case (k, v) => k -> v.toStringValue }
          .collect {
            case (name, Right(v)) => name -> v // ignore toStringValue errors (like ListValue)
          }
          .map { case (k, StringValue(v)) => (V1EnvPrefix + k.toUpperCase(ROOT)) -> v }
          .toMap

  protected final def evalEnv(nameToExpr: Map[String, Expression], scope: => Scope)
  : Checked[Map[String, String]] =
    evalExpressionMap(nameToExpr, scope)
      .flatMap(_
        .view
        .filter(_._2 != NullValue)  // TODO Experimental
        .toVector.traverse { case (k, v) => v.toStringValueString.map(k -> _) })
      .map(_.toMap)
}

object ProcessJobExecutor
{
  private val V1EnvPrefix = "SCHEDULER_PARAM_"

  private[process] final case class StartProcess(
    commandLine: CommandLine,
    name: String,
    env: Map[String, String])
  {
    override def toString = name
  }
}
