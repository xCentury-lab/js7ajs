package com.sos.jobscheduler.data.expression

import com.sos.jobscheduler.base.circeutils.CirceUtils.CirceUtilsChecked
import com.sos.jobscheduler.base.utils.Identifier.isIdentifier
import com.sos.jobscheduler.data.workflow.Label
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.parser.ExpressionParser
import com.sos.jobscheduler.data.workflow.parser.Parsers.checkedParse
import fastparse.NoWhitespace._
import io.circe.{Decoder, Encoder, Json}
import java.lang.Character.{isUnicodeIdentifierPart, isUnicodeIdentifierStart}

/**
  * @author Joacim Zschimmer
  */
sealed trait Expression extends Expression.Precedence

object Expression
{
  sealed trait BooleanExpression extends Expression
  object BooleanExpression {
    implicit val jsonEncoder: Encoder[BooleanExpression] = expr => Json.fromString(expr.toString)
    implicit val jsonDecoder: Decoder[BooleanExpression] =
      _.as[String] flatMap (string => checkedParse(string, ExpressionParser.booleanExpression(_)).toDecoderResult)
  }
  sealed trait NumericExpression extends Expression
  sealed trait StringExpression extends Expression

  final case class Not(a: BooleanExpression) extends BooleanExpression {
    def precedence = Precedence.Factor
    override def toString = "!" + Precedence.inParentheses(a, precedence)
  }

  final case class And(a: BooleanExpression, b: BooleanExpression) extends BooleanExpression {
    def precedence = Precedence.And
    override def toString = toString(a, "&&", b)
  }

  final case class Or(a: BooleanExpression, b: BooleanExpression) extends BooleanExpression {
    def precedence = Precedence.Or
    override def toString = toString(a, "||", b)
  }

  final case class Equal(a: Expression, b: Expression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, "==", b)
  }

  final case class NotEqual(a: Expression, b: Expression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, "!=", b)
  }

  final case class LessOrEqual(a: NumericExpression, b: NumericExpression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, "<=", b)
  }

  final case class GreaterOrEqual(a: NumericExpression, b: NumericExpression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, ">=", b)
  }

  final case class LessThan(a: NumericExpression, b: NumericExpression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, "<", b)
  }

  final case class GreaterThan(a: NumericExpression, b: NumericExpression) extends BooleanExpression {
    def precedence = Precedence.Comparison
    override def toString = toString(a, ">", b)
  }

  final case class In(a: Expression, b: ListExpression) extends BooleanExpression {
    def precedence = Precedence.WordOperator
    override def toString = toString(a, "in", b)
  }

  final case class Matches(a: Expression, b: StringExpression) extends BooleanExpression {
    def precedence = Precedence.WordOperator
    override def toString = toString(a, "matches", b)
  }

  final case class ListExpression(expressions: List[Expression]) extends Expression {
    def precedence = Precedence.Factor
    override def toString = expressions.mkString("[", ", ", "]")
  }

  final case class ToNumber(expression: Expression) extends NumericExpression {
    def precedence = Precedence.Factor
    override def toString = Precedence.inParentheses(expression, precedence) + ".toNumber"
  }

  final case class ToBoolean(expression: Expression) extends BooleanExpression {
    def precedence = Precedence.Factor
    override def toString = Precedence.inParentheses(expression, precedence) + ".toBoolean"
  }

  final case class BooleanConstant(bool: Boolean) extends BooleanExpression {
    def precedence = Precedence.Factor
    override def toString = bool.toString
  }

  final case class NumericConstant(number: BigDecimal) extends NumericExpression {
    def precedence = Precedence.Factor
    override def toString = number.toString
  }

  final case class StringConstant(string: String) extends StringExpression {
    def precedence = Precedence.Factor
    override def toString =
      if (string.isEmpty) "\"\""
      else if (string contains '\'')
        '"' + string + '"'
      else
        '\'' + string + '\''
  }

  final case class NamedValue(where: NamedValue.Where, name: StringExpression, default: Option[StringExpression] = None)
  extends StringExpression {
    def precedence = Precedence.Factor
    override def toString = (where, name, default) match {
      case (NamedValue.LastOccurred, StringConstant(key), None) if NamedValue.isSimpleName(key) => "$" + key
      case (NamedValue.LastOccurred, StringConstant(key), None) if isIdentifier(key) => "${" + key + "}"
      case (NamedValue.Argument, StringConstant(key), None) if isIdentifier(key) => "${arg:" + key + "}"
      case (NamedValue.ByLabel(Label(label)), StringConstant(key), None) if isIdentifier(key) => "${label:" + label + ":" + key + "}"
      case (NamedValue.ByWorkflowJob(WorkflowJob.Name(jobName)), StringConstant(key), None) if isIdentifier(key) => "${job:" + jobName + ":" + key + "}"
      case (NamedValue.LastOccurred, _, None) => s"variable($name)"
      case (NamedValue.LastOccurred, _, Some(o)) => s"variable($name, $o)"
      case _ => s"NamedValue($where, $name, $default)"
    }
  }
  object NamedValue {
    def isSimpleName(name: String) = isSimpleNameStart(name.head) && name.tail.forall(isSimpleNamePart)
    def isSimpleNameStart(c: Char) = isUnicodeIdentifierStart(c)
    def isSimpleNamePart(c: Char) = isUnicodeIdentifierPart(c)

    sealed trait Where
    case object LastOccurred extends Where
    final case class ByLabel(label: Label) extends Where
    final case class ByWorkflowJob(jobName: WorkflowJob.Name) extends Where
    case object Argument extends Where
  }

  final case class StripMargin(expression: StringExpression) extends StringExpression {
    def precedence = Precedence.Factor
    override def toString = Precedence.inParentheses(expression, precedence) + ".stripMargin"
  }

  final case object OrderReturnCode extends NumericExpression {
    def precedence = Precedence.Factor
    override def toString = "returnCode"
  }

  final case object OrderCatchCount extends NumericExpression {
    def precedence = Precedence.Factor
    override def toString = "catchCount"
  }

  sealed trait Precedence {
    protected def precedence: Int

    protected def inParentheses(o: Precedence): String = Precedence.inParentheses(o, precedence)

    protected def toString(a: Expression, op: String, b: Expression) =
      Precedence.toString(a, op, precedence, b)
  }
  object Precedence {
    val WordOperator = 1
    val Or = 2
    val And = 3
    val Comparison = 4
    val Factor = 99

    def toString(a: Expression, op: String, opPrecedence: Int, b: Expression): String =
      inParentheses(a, opPrecedence) + " " + op + " " + inParentheses(b, opPrecedence + 1)

    def inParentheses(o: Precedence, opPrecedence: Int): String =
      if (o.precedence >= opPrecedence)
        o.toString
      else
        s"($o)"
  }
}
