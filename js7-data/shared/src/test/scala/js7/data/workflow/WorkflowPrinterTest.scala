package js7.data.workflow

import cats.syntax.show._
import js7.base.time.ScalaTime._
import js7.data.agent.AgentId
import js7.data.job.{ExecutablePath, ExecutableScript}
import js7.data.order.OrderId
import js7.data.value.StringValue
import js7.data.value.expression.Expression.{BooleanConstant, Equal, In, LastReturnCode, ListExpression, NamedValue, NumericConstant, Or, StringConstant}
import js7.data.workflow.WorkflowPrinter.WorkflowShow
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{AwaitOrder, Execute, ExplicitEnd, Fork, Goto, If, IfFailedGoto, Offer, ReturnCodeMeaning}
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class WorkflowPrinterTest extends AnyFreeSpec
{
  // Also tested by WorkflowParserTest.

  "execute" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("my-script"))),
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("my-script", v1Compatible = true))))),
      """define workflow {
        |  execute agent="AGENT", executable="my-script";
        |  execute agent="AGENT", v1Compatible=true, executable="my-script";
        |}
        |""".stripMargin)
  }

  "execute defaultArguments" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("my-script"), Map("KEY" -> StringValue("VALUE")))))),
      """define workflow {
        |  execute agent="AGENT", arguments={"KEY": "VALUE"}, executable="my-script";
        |}
        |""".stripMargin)
  }

  "Newline in string (not string expression)" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("my-script"), Map("KEY\n\"$" -> StringValue("VALUE")))))),
      """define workflow {
        |  execute agent="AGENT", arguments={'KEY
        |"$': "VALUE"}, executable="my-script";
        |}
        |""".stripMargin)
  }

  "execute successReturnCodes=(), script" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(
            WorkflowJob(AgentId("AGENT"),
              ExecutableScript("LINE 1\nLINE 2\n'''LINE 3'''\n"),
              Map("KEY" -> StringValue("VALUE")),
              ReturnCodeMeaning.Success.of(0, 1))),
          Execute.Anonymous(
            WorkflowJob(AgentId("AGENT"), ExecutableScript("SCRIPT", v1Compatible = true))))),
      """define workflow {
        |  execute agent="AGENT", arguments={"KEY": "VALUE"}, successReturnCodes=[0, 1], script=
        |''''LINE 1
        |   |LINE 2
        |   |'''LINE 3'''
        |   |''''.stripMargin;
        |  execute agent="AGENT", v1Compatible=true, script="SCRIPT";
        |}
        |""".stripMargin)
  }

  "execute failureReturnCodes=()" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("my-script"), Map("KEY" -> StringValue("VALUE")),
            ReturnCodeMeaning.NoFailure, taskLimit = 3, sigkillAfter = Some(10.s))))),
      """define workflow {
        |  execute agent="AGENT", taskLimit=3, arguments={"KEY": "VALUE"}, failureReturnCodes=[], sigkillAfter=10, executable="my-script";
        |}
        |""".stripMargin)
  }

  "job JOB" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Named(WorkflowJob.Name("A")),
          Execute.Named(WorkflowJob.Name("B"))),
        Map(
          WorkflowJob.Name("A") -> WorkflowJob(AgentId("AGENT"), ExecutablePath("a-script"), Map("KEY" -> StringValue("VALUE")), ReturnCodeMeaning.Success.of(0, 1)),
          WorkflowJob.Name("B") -> WorkflowJob(AgentId("AGENT"), ExecutablePath("b-script")))),
      """define workflow {
        |  job A;
        |  job B;
        |
        |  define job A {
        |    execute agent="AGENT", arguments={"KEY": "VALUE"}, successReturnCodes=[0, 1], executable="a-script"
        |  }
        |  define job B {
        |    execute agent="AGENT", executable="b-script"
        |  }
        |}
        |""".stripMargin)
  }

  "Label and single instruction" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          "A" @: Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("EXECUTABLE"))))),
      """define workflow {
        |  A: execute agent="AGENT", executable="EXECUTABLE";
        |}
        |""".stripMargin)
  }

  "if (...)" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          If(
            Or(
              In(LastReturnCode, ListExpression(NumericConstant(1) :: NumericConstant(2) :: Nil)),
              Equal(NamedValue.last("KEY"), StringConstant("VALUE"))),
            Workflow.of(
              Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("EXECUTABLE"))))))),
      """define workflow {
        |  if (($returnCode in [1, 2]) || $KEY == 'VALUE') {
        |    execute agent="AGENT", executable="EXECUTABLE";
        |  }
        |}
        |""".stripMargin)
  }

  "if (...) else" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          If(Equal(LastReturnCode, NumericConstant(-1)),
            Workflow.of(
              Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A-THEN"))),
              If(BooleanConstant(true),
                Workflow.of(
                  Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("B-THEN")))),
                Some(Workflow.of(
                  Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("B-ELSE")))))))))),
      """define workflow {
        |  if ($returnCode == -1) {
        |    execute agent="AGENT", executable="A-THEN";
        |    if (true) {
        |      execute agent="AGENT", executable="B-THEN";
        |    } else {
        |      execute agent="AGENT", executable="B-ELSE";
        |    }
        |  }
        |}
        |""".stripMargin)
  }

  "fork" in {
    check(
      Workflow.of(
        Fork.of(
            "🥕" -> Workflow.of(
              Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A")))),
            "🍋" -> Workflow.of(
              Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("B")))))),
      """define workflow {
        |  fork {
        |    "🥕": {
        |      execute agent="AGENT", executable="A";
        |    },
        |    "🍋": {
        |      execute agent="AGENT", executable="B";
        |    }
        |  };
        |}
        |""".stripMargin)
  }

  "offer" in {
    check(
      Workflow(WorkflowPath.NoId, Vector(
        Offer(OrderId("OFFERED"), 60.seconds))),
      """define workflow {
        |  offer orderId="OFFERED", timeout=60;
        |}
        |""".stripMargin)
  }

  "await" in {
    check(
      Workflow(WorkflowPath.NoId, Vector(
        AwaitOrder(OrderId("OFFERED")))),
      """define workflow {
        |  await orderId="OFFERED";
        |}
        |""".stripMargin)
  }

  "onError and goto" in {
    check(
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A"))),
          IfFailedGoto(Label("FAILURE")),
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("B"))),
          Goto(Label("END")),
          "FAILURE" @:
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("OnFailure"))),
          "END" @:
          ExplicitEnd())),
      """define workflow {
        |  execute agent="AGENT", executable="A";
        |  ifFailedGoto FAILURE;
        |  execute agent="AGENT", executable="B";
        |  goto END;
        |  FAILURE: execute agent="AGENT", executable="OnFailure";
        |  END: end;
        |}
        |""".stripMargin)
  }

  private def check(workflow: Workflow, source: String): Unit = {
    assert(workflow.show == source)
    val result = WorkflowParser.parse(source).map(_.withoutSourcePos)
    val expected = Right(workflow.copy(source = Some(source)).withoutSourcePos)
    assert(result == expected)
  }
}

