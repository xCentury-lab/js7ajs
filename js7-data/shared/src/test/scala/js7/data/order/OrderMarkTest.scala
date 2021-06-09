package js7.data.order

import js7.base.circeutils.CirceUtils._
import js7.data.command.{CancellationMode, SuspensionMode}
import js7.data.workflow.position.Position
import js7.tester.CirceJsonTester.testJson
import org.scalatest.freespec.AnyFreeSpec

final class OrderMarkTest extends AnyFreeSpec
{
  "Cancelling" in {
    testJson[OrderMark](OrderMark.Cancelling(CancellationMode.FreshOnly), json"""
      {
        "TYPE": "Cancelling",
        "mode": {
          "TYPE": "FreshOnly"
        }
      }""")
  }

  "Suspending" in {
    testJson[OrderMark](OrderMark.Suspending(), json"""
      {
        "TYPE": "Suspending",
        "mode": {}
      }""")

    testJson[OrderMark](OrderMark.Suspending(SuspensionMode(Some(CancellationMode.Kill()))), json"""
      {
        "TYPE": "Suspending",
        "mode": {
          "kill": {
            "immediately": false
          }
        }
      }""")
  }

  "Resuming" in {
    testJson[OrderMark](OrderMark.Resuming(Some(Position(1))), json"""
      {
        "TYPE": "Resuming",
        "position": [ 1 ]
      }""")
  }
}
