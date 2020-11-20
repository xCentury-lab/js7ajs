package js7.data.job

import fastparse.NoWhitespace._
import fastparse._
import js7.base.problem.Checked
import js7.data.job.CommandLineExpression.optimizeCommandLine
import js7.data.parser.Parsers
import js7.data.value.expression.Expression.{ListExpression, MkString, StringConstant, StringExpression}
import js7.data.value.expression.ExpressionParser.dollarNamedValue

object CommandLineParser
{
  def parse(pattern: String): Checked[CommandLineExpression] =
    Parsers.checkedParse(pattern, commandLine(_).map(optimizeCommandLine))

  private def commandLine[_: P]: P[CommandLineExpression] = P(
    (wordBoundary.? ~~/ firstWord ~~/ (wordBoundary ~~ word).rep ~~/ wordBoundary.?)
      .map { case (part, tail) => CommandLineExpression(part :: tail.toList) })

  private def firstWord[_: P]: P[StringExpression] = P(
    word.opaque("The command line must not be empty"))

  private def word[_: P]: P[StringExpression] = P(
    ((stringConstant | doubleQuotedWord | dollarNamedValue).rep(1))
      .map(exprs =>
        MkString(ListExpression(exprs.toList))))

  private def stringConstant[_: P]: P[StringConstant] = P(
    (normalCharInUnquotedWord | escapedCharInUnquotedWord).rep(1)
      .map(chars => StringConstant(chars.mkString)))

  private def escapedCharInUnquotedWord[_: P]: P[String] = P(
    ("""\\""" | """\"""" | """\'""" | """\$""").!
      .map(_.tail))

  private def normalCharInUnquotedWord[_: P]: P[Char] = P(
    CharPred(c => !isWhite(c) && c != '$' && c != '"' && c != '\'' && c != '\\').!.map(_.head))

  private def wordBoundary[_: P]: P[Unit] = P(
    CharsWhile(isWhite))

  private def isWhite(c: Char) = c >= 0 && c <= ' '

  private def doubleQuotedWord[_: P]: P[StringExpression] = P(
    ("\"" ~~/
      constantInDoubleQuotedWord ~~/
      ((escapedInDoubleQuotedWord | dollarNamedValue) ~~/ constantInDoubleQuotedWord).rep ~~/
      "\""
    ) .map { case (constant, tail) =>
        MkString(ListExpression(
          constant ::
            tail.flatMap { case (a, b) => a :: b :: Nil }.toList))
      })

  private def constantInDoubleQuotedWord[_: P]: P[StringConstant] = P(
    CharsWhile(c => c != '\\' && c != '"' && c != '$', 0).!
      .map(StringConstant.apply))

  private def escapedInDoubleQuotedWord[_: P]: P[StringExpression] = P(
    ("""\\""" | """\"""" | """\$""").!
      .map(o => StringConstant(o.tail)))
}
