package js7.agent.scheduler.order

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}
import js7.base.problem.Problem
import js7.base.utils.ScalaUtils.syntax.RichEither
import js7.data.orderwatch.OrderWatchId
import js7.data.value.{NumberValue, StringValue}
import org.scalatest.freespec.AnyFreeSpec

final class FileWatchScopeTest extends AnyFreeSpec
{
  private val orderWatchId = OrderWatchId("FILE-WATCH")
  private val pattern = """file-(.*((A)|(B)))\.csv""".r.pattern
  private val filename = "file-100B.csv"

  private lazy val fileWatchScope = {
    val matcher = pattern.matcher(filename)
    val matches = matcher.matches()  // required !!!
    assert(matches)
    new FileWatchScope(orderWatchId, matcher)
  }

  "Example with now() and $epochSecond" in {
    val checkedValue = fileWatchScope.eval(
      "'#' ++ now(format='yyyy-MM-dd', timezone='Antarctica/Troll') ++ \"#F$epochSecond-$orderWatchId:$1\"")
    val yyyymmdd = LocalDateTime.ofInstant(fileWatchScope.now, ZoneId.of("Antarctica/Troll"))
      .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    val epochSecond = fileWatchScope.now.toEpochMilli / 1000

    assert(checkedValue == Right(StringValue(s"#$yyyymmdd#F$epochSecond-FILE-WATCH:100B")))
  }

  "$epochMilli" in {
    val epochMilli = fileWatchScope.now.toEpochMilli
    val checkedValue = fileWatchScope.eval("$epochMilli")
    assert(checkedValue == Right(NumberValue(epochMilli)))
    val n = checkedValue.flatMap(_.toNumber).orThrow.toBigDecimal.longValue
    assert(n == epochMilli)
  }

  "$0...$n" in {
    //assert(newFileWatchScope("X").eval("$0") == Left(Problem("???")))
    assert(fileWatchScope.eval("$0") == Right(StringValue("file-100B.csv")))
    assert(fileWatchScope.eval("\"$0\"") == Right(StringValue("file-100B.csv")))
    assert(fileWatchScope.eval("$1") == Right(StringValue("100B")))
    assert(fileWatchScope.eval("$2") == Right(StringValue("B")))
    assert(fileWatchScope.eval("$3") == Left(Problem("Unknown regular expression group index 3 or group does not match (known groups are $0...$4)")))
    assert(fileWatchScope.eval("$4") == Right(StringValue("B")))
    assert(fileWatchScope.eval("$5") == Left(Problem("Unknown regular expression group index 5 or group does not match (known groups are $0...$4)")))
  }
}
