package js7.data.workflow

import cats.Show
import js7.base.time.ScalaTime._
import js7.base.utils.ScalaUtils.syntax._
import js7.data.job.{CommandLineExecutable, ExecutablePath, ExecutableScript}
import js7.data.value.{BooleanValue, ListValue, NamedValues, NumericValue, StringValue, Value}
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{AwaitOrder, Execute, ExplicitEnd, Fail, Finish, Fork, Gap, Goto, If, IfFailedGoto, ImplicitEnd, Offer, Retry, ReturnCodeMeaning, TryInstruction}
import scala.annotation.tailrec

/**
  * @author Joacim Zschimmer
  */
object WorkflowPrinter
{
  implicit val WorkflowShow: Show[Workflow] = w => print(w)

  def print(workflow: Workflow): String = {
    val sb = new StringBuilder(1000)
    sb ++= "define workflow {\n"
    appendWorkflowContent(sb, nesting = 1, workflow)
    sb ++= "}\n"
    sb.toString
  }

  private def appendWorkflowContent(sb: StringBuilder, nesting: Int, workflow: Workflow): Unit =
  {
    def appendValue(value: Value) = WorkflowPrinter.appendValue(sb, value)

    def appendQuoted(string: String) = WorkflowPrinter.appendQuoted(sb, string)

    def appendQuotedExpression(string: String) =
      if (string.contains('\n')) {
        @tailrec def q(quote: String): Unit =
          if (string contains quote) q(quote + "'")
          else sb.append('\n')
            .append(quote)
            .append(string.split('\n').map(o => o + "\n" + " "*(quote.length - 1) + "|").mkString)
            .append(quote)
            .append(".stripMargin")
        if (!string.contains('\'')) sb.append('\'').append(string).append('\'')
        else q("''")
      } else appendQuoted(string)

    def appendNamedValues(namedValues: NamedValues): Unit = WorkflowPrinter.appendNamedValues(sb, namedValues)

    def indent(nesting: Int) = for (_ <- 0 until nesting) sb ++= "  "

    def appendWorkflowExecutable(job: WorkflowJob): Unit = {
      sb ++= "agent="
      appendQuoted(job.agentName.string)
      if (job.taskLimit != WorkflowJob.DefaultTaskLimit) {
        sb ++= ", taskLimit="
        sb.append(job.taskLimit)
      }
      if (job.defaultArguments.nonEmpty) {
        sb ++= ", arguments="
        appendNamedValues(job.defaultArguments)
      }
      job.returnCodeMeaning match {
        case ReturnCodeMeaning.Default =>
        case ReturnCodeMeaning.Success(returnCodes) =>
          sb ++= ", successReturnCodes=["
          sb ++= returnCodes.map(_.number).toVector.sorted.mkString(", ")
          sb += ']'
        case ReturnCodeMeaning.Failure(returnCodes) =>
          sb ++= ", failureReturnCodes=["
          sb ++= returnCodes.map(_.number).toVector.sorted.mkString(", ")
          sb += ']'
      }
      for (o <- job.sigkillAfter) {
        sb.append(", sigkillAfter=")
        sb.append(o.toBigDecimalSeconds)  // TODO Use floating point
      }
      job.executable match {
        case ExecutablePath(path) =>
          sb ++= ", executable="
          appendQuoted(path)
        case CommandLineExecutable(command) =>
          sb ++= ", command="
          appendQuoted(command.toString)
        case ExecutableScript(script) =>
          sb ++= ", script="
          appendQuotedExpression(script)  // Last argument, because the script may have multiple lines
      }
    }

    for (labelled <- workflow.labeledInstructions if !labelled.instruction.isInstanceOf[ImplicitEnd]) {
      indent(nesting)
      for (label <- labelled.maybeLabel) {
        sb ++= label.string
        sb ++= ": "
      }
      labelled.instruction match {
        case AwaitOrder(orderId, _) =>
          sb ++= "await orderId="
          appendQuoted(orderId.string)
          sb ++= ";\n"

        case ExplicitEnd(_) =>
          sb ++= "end;\n"

        case Execute.Anonymous(workflowExecutable, _) =>
          sb ++= "execute "
          appendWorkflowExecutable(workflowExecutable)
          sb ++= ";\n"

        case Execute.Named(name, arguments, _) =>
          sb ++= "job "
          sb ++= name.string
          if (arguments.nonEmpty) {
            sb ++= ", arguments="
            appendNamedValues(arguments)
          }
          sb ++= ";\n"

        case Fail(maybeErrorMessage, namedValues, uncatchable, _) =>
          sb ++= "fail"
          if (maybeErrorMessage.isDefined || namedValues.nonEmpty) {
            sb ++= (
              (uncatchable ? "uncatchable=true") ++
              maybeErrorMessage.map(o => "message=" + o.toString) ++
                (namedValues.nonEmpty ? ("namedValues=" + namedValuesToString(namedValues)))
              ).mkString(" (", ", ", ")")
          }
          sb ++= ";\n"

        case Finish(_) =>
          sb ++= "finish;\n"

        case Fork(branches, _) =>
          def appendBranch(branch: Fork.Branch) = {
            indent(nesting + 1)
            appendQuoted(branch.id.string)
            sb ++= ": {\n"
            appendWorkflowContent(sb, nesting + 2, branch.workflow)
            indent(nesting + 1)
            sb += '}'
          }

          sb ++= "fork {\n"
          for (b <- branches.take(branches.length - 1)) {
            appendBranch(b)
            sb.append(",\n")
          }
          appendBranch(branches.last)
          sb += '\n'
          indent(nesting)
          sb ++= "};\n"

        case Gap(_) =>
          sb ++= "/*gap*/\n"

        case Goto(label, _) =>
          sb ++= "goto "++= label.string ++= ";\n"

        case IfFailedGoto(label, _) =>
          sb ++= "ifFailedGoto " ++= label.string ++= ";\n"

        case If(predicate, thenWorkflow, elseWorkflowOption, _) =>
          sb ++= "if (" ++= predicate.toString ++= ") {\n"
          appendWorkflowContent(sb, nesting + 1, thenWorkflow)
          for (els <- elseWorkflowOption) {
            indent(nesting)
            sb ++= "} else {\n"
            appendWorkflowContent(sb, nesting + 1, els)
          }
          indent(nesting)
          sb ++= "}\n"

        case TryInstruction(tryWorkflow, catchWorkflow, retryDelays, maxTries, _) =>
          sb ++= "try "
          if (retryDelays.isDefined || maxTries.isDefined) {
            sb ++= (
              (for (delays <- retryDelays) yield
                "retryDelays=" + delays.map(_.toBigDecimalSeconds.toString).mkString("[", ", ", "]")) ++
              (for (n <- maxTries) yield "maxTries=" + n.toString)
            ).mkString("(", ", ", ")")
          }
          sb ++= "{\n"
          appendWorkflowContent(sb, nesting + 1, tryWorkflow)
          indent(nesting)
          sb ++= "} catch {\n"
          appendWorkflowContent(sb, nesting + 1, catchWorkflow)
          indent(nesting)
          sb ++= "}\n"

        case Retry(_) =>
          sb ++= "retry"
          sb ++= "\n"

        case Offer(orderId, timeout, _) =>
          sb ++= s"offer orderId="
          appendQuoted(orderId.string)
          sb ++= ", timeout="
          sb.append(timeout.toSeconds)
          sb ++= ";\n"
      }
    }

    if (workflow.nameToJob.nonEmpty) sb ++= "\n"
    for ((name, job) <- workflow.nameToJob) {
      indent(nesting)
      sb ++= "define job "
      sb ++= name.string
      sb ++= " {\n"
      indent(nesting + 1)
      sb ++= "execute "
      appendWorkflowExecutable(job)
      sb ++= "\n"
      indent(nesting)
      sb ++= "}\n"
    }
  }

  def namedValuesToString(namedValues: NamedValues) = {
    val sb = new StringBuilder(128)
    appendNamedValues(sb, namedValues)
    sb.toString()
  }

  def appendNamedValues(sb: StringBuilder, namedValues: NamedValues): Unit = {
    sb ++= "{"
    var needComma = false
    for ((k, v) <- namedValues) {
      if (needComma) sb ++= ", "
      needComma = true
      appendQuoted(sb, k)
      sb ++= ": "
      appendValue(sb, v)
    }
    sb ++= "}"
  }

  def appendValue(sb: StringBuilder, value: Value): Unit =
    value match {
      case BooleanValue(bool) => sb.append(bool)
      case NumericValue(number) => sb.append(number)
      case StringValue(string) => appendQuoted(sb, string)
      case ListValue(values) =>
        sb.append('[')
        val it = values.iterator
        if (it.hasNext) {
          appendValue(sb, it.next())
          while (it.hasNext) {
            sb.append(", ")
            appendValue(sb, it.next())
          }
        }
        sb.append(']')
    }

  def quotedString(string: String) = {
    val sb = new StringBuilder
    appendQuoted(sb, string)
    sb.toString
  }

  def appendQuoted(sb: StringBuilder, string: String): Unit =
    sb.append('"')
      .append(string
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\"", "\\\"")
        .replace("$", "\\$"))
      .append('"')
}
