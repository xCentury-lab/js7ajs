package js7.data.job

import js7.base.problem.Checked
import js7.base.problem.Checked._
import js7.data.value.expression.{Evaluator, Scope, ValueSearch}
import js7.data.value.{NumericValue, StringValue}
import org.scalatest.freespec.AnyFreeSpec

final class CommandLineEvaluatorTest extends AnyFreeSpec
{
  "Constant" in {
    assert(eval("ÄBC") ==
      Right(CommandLine(Seq("ÄBC"))))
  }

  "Reference" in {
    assert(eval("XX $NAME YY $NUMERIC") ==
      Right(CommandLine(Seq("XX", "MY NAME", "YY", "7"))))
  }

  "Spaces" in {
    assert(eval(" XX   $NAME   YY \t  $NUMERIC  ") ==
      Right(CommandLine(Seq("XX", "MY NAME", "YY", "7"))))
  }

  "Escaped characters" in {
    assert(eval(""" \\ \" \' \$ """) ==
      Right(CommandLine(Seq("\\", "\"", "'", "$"))))
  }

  "Constant in quotes" in {
    assert(eval("""" CONSTANT WITH SPACES """") ==
      Right(CommandLine(Seq(" CONSTANT WITH SPACES "))))
  }

  "Reference in quotes" in {
    assert(eval(""">> "$NAME" <<""") ==
      Right(CommandLine(Seq(">>", "MY NAME", "<<"))))
  }

  "Quotes without space" in {
    assert(eval(""">>"Hi $NAME"<< TWO""") ==
      Right(CommandLine(Seq(">>Hi MY NAME<<", "TWO"))))}

  "Concatenated quotes" in {
    assert(eval(""""$NAME""/APPENDED"""") ==
      Right(CommandLine(Seq("MY NAME/APPENDED"))))
  }

  "Reference and escaped characters in quotes" in {
    assert(eval("""XX "$NAME \"QUOTED\"\\\$"""") ==
      Right(CommandLine(Seq("XX", """MY NAME "QUOTED"\$"""))))
  }

  private val commandLineEvaluator =
    new CommandLineEvaluator(
      new Evaluator(
        new Scope {
          val symbolToValue = PartialFunction.empty

          val findValue = {
            case ValueSearch(ValueSearch.LastOccurred, ValueSearch.NamedValue("NAME")) =>
              Right(Some(StringValue("MY NAME")))
            case ValueSearch(ValueSearch.LastOccurred, ValueSearch.NamedValue("NUMERIC")) =>
              Right(Some(NumericValue(7)))
          }
        }))

  private def eval(commandLine: String): Checked[CommandLine] =
    commandLineEvaluator.eval(
      CommandLineParser.parse(commandLine).orThrow)
}
