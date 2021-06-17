package js7.data.value.expression

import cats.instances.either._
import cats.instances.list._
import cats.instances.vector._
import cats.syntax.apply._
import cats.syntax.traverse._
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.utils.ScalaUtils.syntax._
import js7.data.value.expression.Expression._
import js7.data.value.{BooleanValue, ListValue, MissingValue, NullValue, NumberValue, ObjectValue, StringValue, Value}
import js7.data.workflow.Label
import js7.data.workflow.instructions.executable.WorkflowJob

/**
  * @author Joacim Zschimmer
  */
final class Evaluator(scope: Scope)
{
  def eval(expr: Expression): Checked[Value] =
    evalAllowMissingValue(expr)
      .flatMap {
        case MissingValue(problem) => Left(problem)
        case o => Right(o)
      }

  private def evalAllowMissingValue(expr: Expression): Checked[Value] =
    expr match {
      case BooleanConstant(o)    => Right(BooleanValue(o))
      case ListExpression(list) => list.traverse(eval) map ListValue.apply
      case e: ObjectExpression => evalObjectExpression(e)
      case Concat         (a, b) => evalString(a).map2(evalString(b))(_.string ++ _.string).map(StringValue.apply)
      case Add            (a, b) => numNumToNum(a, b)(_.number + _.number)
      case Substract      (a, b) => numNumToNum(a, b)(_.number - _.number)
      case Equal          (a, b) => anyAnyToBool(a, b)(_ == _)
      case NotEqual       (a, b) => anyAnyToBool(a, b)(_ != _)
      case LessThan       (a, b) => numNumToBool(a, b)(_.number <  _.number)
      case GreaterThan    (a, b) => numNumToBool(a, b)(_.number >  _.number)
      case LessOrEqual    (a, b) => numNumToBool(a, b)(_.number <= _.number)
      case GreaterOrEqual (a, b) => numNumToBool(a, b)(_.number >= _.number)
      case In(a, set: ListExpression) => eval(a).map2(evalListExpression(set))((a1, set1) => BooleanValue(set1.list contains a1))
      case Matches        (a, b) => evalString(a).map2(evalString(b))(_.string matches _.string).map(BooleanValue.apply)
      case Not            (a)    => evalBoolean(a).map(o => !o.booleanValue) map BooleanValue.apply
      case And            (a, b) => evalBoolean(a).flatMap(o => if (!o.booleanValue) Right(o) else evalBoolean(b))
      case Or             (a, b) => evalBoolean(a).flatMap(o => if (o.booleanValue) Right(o) else evalBoolean(b))
      case ToBoolean(a) => evalString(a) flatMap toBoolean
      case OrElse(a, b) => orElse(a, b)
      case OrNull(a) => orNull(a)
      case NumericConstant(o) => Right(NumberValue(o))
      case OrderCatchCount => scope.symbolToValue("catchCount")
        .getOrElse(Left(Problem(s"Unknown symbol: $OrderCatchCount")))
        .flatMap(_.toNumber)
      case ToNumber(e) => eval(e) flatMap toNumber

      case InterpolatedString(expressions) =>
        expressions
          .traverse(e => eval(e).map(_.convertToString))
          .map(seq => StringValue(seq.mkString))

      case MkString(e) => eval(e) flatMap mkString
      case StringConstant(o) => Right(StringValue(o))

      case NamedValue(where, what, default) =>
        val w = where match {
          case NamedValue.Argument => ValueSearch.Argument
          case NamedValue.LastOccurred => ValueSearch.LastOccurred
          case NamedValue.LastOccurredByPrefix(prefix) => ValueSearch.LastExecuted(PositionSearch.ByPrefix(prefix))
          case NamedValue.ByLabel(label) => ValueSearch.LastExecuted(PositionSearch.ByLabel(label))
          case NamedValue.LastExecutedJob(jobName) => ValueSearch.LastExecuted(PositionSearch.ByWorkflowJob(jobName))
        }
        what match {
          case NamedValue.KeyValue(stringExpr) =>
            for {
              key <- evalString(stringExpr).map(_.string)
              maybeValue <- scope.findValue(ValueSearch(w, ValueSearch.Name(key)))
              value <- maybeValue
                .map(Right(_))
                .getOrElse(
                  default.map(evalString).toChecked(Problem(where match {
                    case NamedValue.Argument =>
                      s"No such order argument: $key"
                    case NamedValue.LastOccurred =>
                      s"No such named value: $key"
                    case NamedValue.LastOccurredByPrefix(prefix) =>
                      s"Order has not passed a position '$prefix'"
                    case NamedValue.ByLabel(Label(label)) =>
                      s"Workflow instruction at label $label did not return a named value '$key'"
                    case NamedValue.LastExecutedJob(WorkflowJob.Name(jobName)) =>
                      s"Last execution of job '$jobName' did not return a named value '$key'"
                  })).flatten)
            } yield value
        }

      case StripMargin(a) => evalString(a).map(o => StringValue(o.string.stripMargin))

      case call: FunctionCall => evalFunctionCall(call)

      case call: JobResourceVariable => evalJobResourceVariable(call)

      case MissingConstant(problem) => Right(MissingValue(problem))

      case NullConstant => Right(NullValue)

      case _ => Left(Problem(s"Expression is not evaluable: $expr"))  // Should not happen
    }

  private def evalFunctionCall(functionCall: Expression.FunctionCall): Checked[Value] =
    scope.evalFunctionCall(functionCall)
      .getOrElse(Left(Problem(s"Unknown function or non-matching arguments: ${functionCall.name}")))

  private def evalJobResourceVariable(itemAccess: Expression.JobResourceVariable): Checked[Value] =
    scope.evalJobResourceVariable(itemAccess)
      .getOrElse(Left(Problem(s"Unknown item access or non-matching arguments: $itemAccess")))

  private def evalListExpression(expr: ListExpression): Checked[ListValue] =
    expr.subexpressions.traverse(eval).map(ListValue.apply)

  def evalObjectExpression(expr: ObjectExpression): Checked[ObjectValue] =
    evalExpressionMap(expr.nameToExpr).map(ObjectValue(_))

  def evalExpressionMap(expr: Map[String, Expression]): Checked[Map[String, Value]] =
    expr.toVector
      .traverse { case (k, v) => eval(v) map k.-> }
      .map(_.toMap)

  private def numNumToNum[A <: Expression, B <: Expression](a: A, b: B)(op: (NumberValue, NumberValue) => BigDecimal): Checked[NumberValue] =
    for {
      a1 <- evalNumeric(a)
      b1 <- evalNumeric(b)
    } yield NumberValue(op(a1, b1))

  private def anyAnyToBool[A <: Expression, B <: Expression](a: A, b: B)(op: (Value, Value) => Boolean): Checked[BooleanValue] =
    for {
      a1 <- eval(a)
      b1 <- eval(b)
    } yield BooleanValue(op(a1, b1))

  private def numNumToBool[A <: Expression, B <: Expression](a: A, b: B)(op: (NumberValue, NumberValue) => Boolean): Checked[BooleanValue] =
    for {
      a1 <- evalNumeric(a)
      b1 <- evalNumeric(b)
    } yield BooleanValue(op(a1, b1))

  private[expression] def evalBoolean(e: Expression): Checked[BooleanValue] = eval(e).flatMap(_.toBoolean)
  private[expression] def evalNumeric(e: Expression): Checked[NumberValue] = eval(e).flatMap(_.toNumber)
  private[expression] def evalString(e: Expression): Checked[StringValue] = eval(e).flatMap(_.toStringValue)
  private[expression] def evalList(e: Expression): Checked[ListValue] = eval(e).flatMap(_.toList)

  private def toNumber(v: Value): Checked[NumberValue] =
    v match {
      case v: NumberValue => Right(v)
      case BooleanValue(false) => Right(NumberValue(0))
      case BooleanValue(true) => Right(NumberValue(1))
      case StringValue(o) =>
        try Right(NumberValue(BigDecimal(o)))
        catch { case _: NumberFormatException =>
          // getMessage returns null
          Problem(s"Not a valid number: ${o.truncateWithEllipsis(10)}")
        }
      case o => Problem(s"Operator .toNumber may not applied to a value of type ${o.getClass.simpleScalaName}")
    }

  private def toBoolean(v: Value): Checked[BooleanValue] =
    v match {
      case v: BooleanValue => Right(v)
      case StringValue("false") => Right(BooleanValue(false))
      case StringValue("true") => Right(BooleanValue(true))
      case StringValue(o) => Problem(s"Not a valid Boolean value: ${o.truncateWithEllipsis(10)}")
      case o => Problem(s"Operator .toBoolean may not applied to a value of type ${o.getClass.simpleScalaName}")
    }

  private def orElse(a: Expression, default: Expression): Checked[Value] =
    evalAllowMissingValue(a).flatMap {
      case _: MissingValue | NullValue => eval(default)
      case o => Right(o)
    }

  private def orNull(a: Expression): Checked[Value] =
    evalAllowMissingValue(a).map {
      case _: MissingValue => NullValue
      case o => o
    }

  private def mkString(value: Value): Checked[StringValue] = {
    value match {
      case ListValue(list) => list.toVector.traverse(_.toStringValue).map(o => StringValue(o.map(_.string).mkString))
      case _ => value.toStringValue
    }
  }

  //private def castValue[A: ClassTag](v: Value): Checked[A] =
  //  if (implicitClass[A] isAssignableFrom v.getClass)
  //
  // v.asInstanceOf[A].valid
  //  else
  //    Problem(s"Not a ${implicitClass[A].simpleScalaName}")
}

object Evaluator
{
  def apply(scope: Scope) = new Evaluator(scope)

  def eval(expression: Expression, scope: Scope = Scope.empty): Checked[Value] =
    new Evaluator(scope).eval(expression)

  final case object MissingValueProblem extends Problem.ArgumentlessCoded
}
