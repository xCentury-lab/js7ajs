package js7.data.workflow

import cats.syntax.show._
import js7.base.problem.Checked._
import js7.base.problem.Problem
import js7.base.time.ScalaTime._
import js7.data.agent.AgentId
import js7.data.job.{CommandLineExecutable, ExecutablePath, ExecutableScript}
import js7.data.lock.LockId
import js7.data.order.OrderId
import js7.data.source.SourcePos
import js7.data.value.expression.Expression.{Equal, In, LastReturnCode, ListExpression, NamedValue, NumericConstant, Or, StringConstant}
import js7.data.value.{NumericValue, StringValue}
import js7.data.workflow.WorkflowPrinter.WorkflowShow
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{AwaitOrder, Execute, ExplicitEnd, Fail, Finish, Fork, Goto, If, IfFailedGoto, ImplicitEnd, LockInstruction, Offer, Retry, ReturnCodeMeaning, TryInstruction}
import js7.data.workflow.test.ForkTestSetting.{TestWorkflow, TestWorkflowSource}
import js7.tester.DiffxAssertions.assertEqual
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
  * @author Joacim Zschimmer
  */
final class WorkflowParserTest extends AnyFreeSpec
{
  "parse" in {
    assert(parse(TestWorkflowSource).withoutSourcePos == TestWorkflow.withId(WorkflowPath.NoId))
  }

  "Unknown job" in {
    val source = """
      define workflow {
        if (true) {
          job A;
        }
      }"""
    assert(WorkflowParser.parse(source)
      == Left(Problem("""Expected known job name ('A' is unknown):6:8, found """"")))  // TODO Wrong position in error message, should be 4:12
  }

  "Execute anonymous" in {
    checkWithSourcePos("""define workflow { execute executable="my/executable", agent="AGENT"; }""",
      Workflow.of(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("my/executable")),
          sourcePos(18, 67)),
        ImplicitEnd(sourcePos(69, 70))))
  }

  "Execute anonymous with relative agent path" in {
    checkWithSourcePos("""define workflow { execute executable="my/executable", agent="AGENT"; }""",
      Workflow.of(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("my/executable")),
          sourcePos(18, 67)),
        ImplicitEnd(sourcePos(69, 70))))
  }

  "Execute anonymous with default arguments 'SCHEDULER_PARAM_'" in {
    checkWithSourcePos(
       """define workflow {
         |  execute executable = "my/executable",
         |          agent = "AGENT",
         |          arguments = { "A": "aaa", "B": "bbb", "I": -123 },
         |          taskLimit = 3,
         |          sigkillAfter = 30;
         |}""".stripMargin,
      Workflow.of(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"),
            ExecutablePath("my/executable"),
            Map(
              "A" -> StringValue("aaa"),
              "B" -> StringValue("bbb"),
              "I" -> NumericValue(-123)),
            taskLimit = 3,
            sigkillAfter = Some(30.s)),
          sourcePos(20, 198)),
        ImplicitEnd(sourcePos(200, 201))))
  }

  "Execute script with \\n" in {
    checkWithSourcePos(
      """define workflow { execute script="LINE 1\nLINE 2\nLINE 3", agent="AGENT"; }""",
      Workflow.of(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutableScript("LINE 1\nLINE 2\nLINE 3")),
          sourcePos(18, 72)),
        ImplicitEnd(sourcePos(74, 75))))
  }

  "Execute script with multi-line string" in {
    checkWithSourcePos(
"""define workflow {
  execute agent="AGENT", script=
   'LINE 1
   |LINE 2
   |LINE 3
   |'.stripMargin;
}""",
      Workflow.of(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutableScript("LINE 1\nLINE 2\nLINE 3\n")),
          sourcePos(20, 101)),
        ImplicitEnd(sourcePos(103, 104))))
  }

  "Execute named" in {
    checkWithSourcePos("""
      define workflow {
        job A;
        job B, arguments = { "KEY": "VALUE" };
        job C;
        define job A {
          execute executable="my/executable", agent="AGENT", successReturnCodes=[0, 1, 3];
        }
        define job B {
          execute executable="my/executable", agent="AGENT"
        }
        define job C {
          execute script="SCRIPT", agent="AGENT"
        }
      }""",
      Workflow(
        WorkflowPath.NoId,
        Vector(
          Execute.Named(WorkflowJob.Name("A"), sourcePos = sourcePos(33, 38)),
          Execute.Named(WorkflowJob.Name("B"), defaultArguments = Map("KEY" -> StringValue("VALUE")), sourcePos(48, 85)),
          Execute.Named(WorkflowJob.Name("C"), sourcePos = sourcePos(95, 100)),
          ImplicitEnd(sourcePos(407, 408))),
        Map(
          WorkflowJob.Name("A") ->
            WorkflowJob(
              AgentId("AGENT"),
              ExecutablePath("my/executable"),
              returnCodeMeaning = ReturnCodeMeaning.Success.of(0, 1, 3)),
          WorkflowJob.Name("B") ->
            WorkflowJob(
              AgentId("AGENT"),
              ExecutablePath("my/executable")),
          WorkflowJob.Name("C") ->
            WorkflowJob(
              AgentId("AGENT"),
              ExecutableScript("SCRIPT")))))
  }

  "Execute named with duplicate jobs" in {
    assert(WorkflowParser.parse("""
      define workflow {
        job DUPLICATE;
        define job DUPLICATE {
          execute executable="my/executable", agent="AGENT";
        }
        define job DUPLICATE {
          execute executable="my/executable", agent="AGENT"
        }
      }""")
      == Left(Problem("""Expected unique job definitions (duplicates: DUPLICATE):10:8, found """"")))
  }

  "Single instruction with relative job path" in {
    checkWithSourcePos("""define workflow { execute executable="A", agent="AGENT"; }""",
      Workflow.anonymous(
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A")), sourcePos(18, 55)),
          ImplicitEnd(sourcePos(57, 58)))))
  }

  "Single instruction with absolute job path" in {
    checkWithSourcePos("""define workflow { execute executable="A", agent="AGENT"; }""",
      Workflow.anonymous(
        Vector(
          Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A")), sourcePos(18, 55)),
          ImplicitEnd(sourcePos(57, 58)))))
  }

  "execute with successReturnCodes" in {
    checkWithSourcePos("""define workflow { execute executable="A", agent="AGENT", successReturnCodes=[0, 1, 3]; }""",
      Workflow.anonymous(
        Vector(
          Execute.Anonymous(
            WorkflowJob(AgentId("AGENT"), ExecutablePath("A"), returnCodeMeaning = ReturnCodeMeaning.Success.of(0, 1, 3)),
            sourcePos(18, 85)),
          ImplicitEnd(sourcePos(87, 88)))))
  }

  "execute with failureReturnCodes" in {
    checkWithSourcePos("""define workflow { execute executable="A", agent="AGENT", failureReturnCodes=[1, 3]; }""",
      Workflow.anonymous(
        Vector(
          Execute.Anonymous(
            WorkflowJob(AgentId("AGENT"), ExecutablePath("A"), returnCodeMeaning = ReturnCodeMeaning.Failure.of(1, 3)),
            sourcePos(18, 82)),
          ImplicitEnd(sourcePos(84, 85)))))
  }

  "execute with command line" in {
    check("""define workflow { execute agent="AGENT", command="COMMAND"; }""",
      Workflow.anonymous(
        Vector(
          Execute.Anonymous(
            WorkflowJob(AgentId("AGENT"), CommandLineExecutable.fromString("COMMAND").orThrow)))))
  }

  "Label and single instruction" in {
    checkWithSourcePos("""define workflow { A: execute executable="A", agent="AGENT"; }""",
      Workflow.anonymous(
        Vector(
          "A" @: Execute.Anonymous(WorkflowJob(AgentId("AGENT"), ExecutablePath("A")), sourcePos(21, 58)),
          ImplicitEnd(sourcePos(60, 61)))))
  }

  "if (...) {...}" in {
    checkWithSourcePos("""define workflow { if (($returnCode in [1, 2]) || $KEY == "VALUE") { execute executable="/THEN", agent="AGENT" } }""",
      Workflow.anonymous(
        Vector(
          If(
            Or(
              In(LastReturnCode, ListExpression(NumericConstant(1) :: NumericConstant(2) :: Nil)),
              Equal(NamedValue.last("KEY"), StringConstant("VALUE"))),
            Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("AGENT"), ExecutablePath("/THEN")),
                sourcePos = sourcePos(68, 109)),
              ImplicitEnd(sourcePos(110, 111))),
            sourcePos = sourcePos(18, 65)),
          ImplicitEnd(sourcePos(112, 113)))))
  }

  "if (...) {...} else {...}" in {
    checkWithSourcePos("""define workflow { if ($returnCode == -1) { execute executable="/THEN", agent="AGENT" } else { execute executable="/ELSE", agent="AGENT" } }""",
      Workflow.anonymous(
        Vector(
          If(Equal(LastReturnCode, NumericConstant(-1)),
            Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("AGENT"), ExecutablePath("/THEN")),
                sourcePos(43, 84)),
              ImplicitEnd(sourcePos(85, 86))),
            Some(Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("AGENT"), ExecutablePath("/ELSE")),
                sourcePos(94, 135)),
              ImplicitEnd(sourcePos(136, 137)))),
            sourcePos(18, 40)),
          ImplicitEnd(sourcePos(138, 139)))))
  }

 "if (...) instruction" in {
    checkWithSourcePos("""define workflow { if ($returnCode == -1) fail }""",
      Workflow.anonymous(
        Vector(
          If(Equal(LastReturnCode, NumericConstant(-1)),
            Workflow.of(Fail(sourcePos = sourcePos(41, 45))),
            sourcePos = sourcePos(18, 40)),
          ImplicitEnd(sourcePos(46, 47)))))
  }

 "if (...) instruction else instruction" in {
    checkWithSourcePos("""define workflow { if ($returnCode == -1) fail else execute executable="/ELSE", agent="AGENT" }""",
      Workflow.anonymous(
        Vector(
          If(Equal(LastReturnCode, NumericConstant(-1)),
            Workflow.of(Fail(sourcePos = sourcePos(41, 45))),
            Some(Workflow.of(Execute.Anonymous(
              WorkflowJob(AgentId("AGENT"), ExecutablePath("/ELSE")),
              sourcePos = sourcePos(51, 92)))),
            sourcePos(18, 40)),
          ImplicitEnd(sourcePos(93, 94)))))
  }

  "Two consecutive ifs with semicolon" in {
    checkWithSourcePos(
     """define workflow {
          if ($returnCode == 1) {}
          if ($returnCode == 2) {}
        }""",
      Workflow.anonymous(
        Vector(
          If(
            Equal(LastReturnCode, NumericConstant(1)),
            Workflow.of(
              ImplicitEnd(sourcePos(51, 52))),
            sourcePos = sourcePos(28, 49)),
          If(Equal(LastReturnCode, NumericConstant(2)),
            Workflow.of(
              ImplicitEnd(sourcePos(86, 87))),
            sourcePos = sourcePos(63, 84)),
          ImplicitEnd(sourcePos(96, 97)))))
  }

  "Two consecutive ifs without semicolon" in {
    checkWithSourcePos(
     """define workflow {
          if ($returnCode == 1) {
          }
          if ($returnCode == 2) {
          }
        }""",
      Workflow.anonymous(
        Vector(
          If(
            Equal(LastReturnCode, NumericConstant(1)),
            Workflow.of(
              ImplicitEnd(sourcePos(62, 63))),
            sourcePos = sourcePos(28, 49)),
          If(Equal(LastReturnCode, NumericConstant(2)),
            Workflow.of(
              ImplicitEnd(sourcePos(108, 109))),
            sourcePos = sourcePos(74, 95)),
          ImplicitEnd(sourcePos(118, 119)))))
  }

  "fork" in {
    checkWithSourcePos(
      """define workflow {
           fork {
             "🥕": {
               execute executable="/a", agent="agent-a";
             },
             "🍋": execute executable="/b", agent="agent-b";
           }
         }""",
      Workflow.of(
        Fork.forTest(Vector(
            Fork.Branch("🥕", Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("agent-a"), ExecutablePath("/a")),
                sourcePos(71+1, 111+1)),
              ImplicitEnd(sourcePos(126+1, 127+1)))),
            Fork.Branch("🍋", Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("agent-b"), ExecutablePath("/b")),
                sourcePos(147+2, 187+2))))),
            sourcePos(29, 33)),
        ImplicitEnd(sourcePos(211+2, 212+2))))
  }

  "offer" in {
    checkWithSourcePos("""define workflow { offer orderId = "OFFERED", timeout = 60; }""",
      Workflow(WorkflowPath.NoId, Vector(
        Offer(OrderId("OFFERED"), 60.seconds, sourcePos(18, 57)),
        ImplicitEnd(sourcePos(59, 60)))))
  }

  "await" in {
    checkWithSourcePos("""define workflow { await orderId = "OFFERED"; }""",
      Workflow(WorkflowPath.NoId, Vector(
        AwaitOrder(OrderId("OFFERED"), sourcePos(18, 43)),
        ImplicitEnd(sourcePos(45, 46)))))
  }

  "try" - {
    "try" in {
      checkWithSourcePos("""
        define workflow {
          try {
            execute executable="/TRY", agent="AGENT";
          } catch {
            execute executable="/CATCH", agent="AGENT";
          }
        }""",
        Workflow(WorkflowPath.NoId, Vector(
          TryInstruction(
            Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("AGENT"), ExecutablePath("/TRY")),
                sourcePos(55, 95)),
              ImplicitEnd(sourcePos(107, 108))),
            Workflow.of(
              Execute.Anonymous(
                WorkflowJob(AgentId("AGENT"), ExecutablePath("/CATCH")),
                sourcePos(129, 171)),
              ImplicitEnd(sourcePos(183, 184))),
            sourcePos = sourcePos(37, 40)),
          ImplicitEnd(sourcePos(193, 194))))
      )
    }

    "try with retryDelays" in {
      checkWithSourcePos("""
        define workflow {
          try (retryDelays=[1, 2, 3], maxTries=3) fail;
          catch retry;
        }""",
        Workflow(WorkflowPath.NoId, Vector(
          TryInstruction(
            Workflow.of(
              Fail(sourcePos = sourcePos(77, 81))),
            Workflow.of(
              Retry(sourcePos(99, 104))),
            Some(Vector(1.second, 2.seconds, 3.seconds)),
            maxTries = Some(3),
            sourcePos = sourcePos(37, 76)),
          ImplicitEnd(sourcePos(114, 115)))))
    }

    "try with retryDelays but retry is missing" in {
      assert(WorkflowParser.parse("""
        define workflow {
          try (retryDelays=[1, 2, 3]) fail;
          catch {}
        }""") ==
        Left(Problem("""Expected Missing a retry instruction in the catch block to make sense of retryDelays or maxTries:5:9, found "}"""")))
    }

    "try with maxRetries but retry is missing" in {
      assert(WorkflowParser.parse("""
        define workflow {
          try (maxTries=3) fail;
          catch {}
        }""") ==
        Left(Problem("""Expected Missing a retry instruction in the catch block to make sense of retryDelays or maxTries:5:9, found "}"""")))
    }
  }

  "retry" - {
    "no delay" in {
      checkWithSourcePos("""
        define workflow {
          try {
            fail;
          } catch {
            retry;
          }
        }""",
        Workflow.of(
          TryInstruction(
            Workflow.of(
              Fail(sourcePos = sourcePos(55, 59)),
              ImplicitEnd(sourcePos(71, 72))),
            Workflow.of(
              Retry(sourcePos(93, 98)),
              ImplicitEnd(sourcePos(110, 111))),
            sourcePos = sourcePos(37, 40)),
          ImplicitEnd(sourcePos(120, 121))))
    }
  }

  "fail" in {
    checkWithSourcePos("""
      define workflow {
        fail;
        fail (namedValues = { "returnCode": 7 });
        fail (message="ERROR");
        fail (message="ERROR", namedValues = { "returnCode": 7 });
        fail (uncatchable=true, message="ERROR", namedValues = { "returnCode": 7 });
      }""",
      Workflow(WorkflowPath.NoId, Vector(
        Fail(None, Map.empty, sourcePos = sourcePos(33, 37)),
        Fail(None, Map("returnCode" -> NumericValue(7)), sourcePos = sourcePos(47, 87)),
        Fail(Some(StringConstant("ERROR")), Map.empty, sourcePos = sourcePos(97, 119)),
        Fail(Some(StringConstant("ERROR")), Map("returnCode" -> NumericValue(7)), sourcePos = sourcePos(129, 186)),
        Fail(Some(StringConstant("ERROR")), Map("returnCode" -> NumericValue(7)), uncatchable = true, sourcePos(196, 271)),
        ImplicitEnd(sourcePos = sourcePos(279, 280)))))
  }

  "lock" in {
    checkWithSourcePos("""
      define workflow {
        lock (lock="LOCK") fail;
        lock (lock="LOCK", count=3) {}
      }""",
      Workflow(WorkflowPath.NoId, Vector(
        LockInstruction(LockId("LOCK"), None, Workflow.of(Fail(sourcePos = sourcePos(52, 56))), sourcePos = sourcePos(33, 51)),
        LockInstruction(LockId("LOCK"), Some(3), Workflow.of(ImplicitEnd(sourcePos = sourcePos(95, 96))), sourcePos = sourcePos(66, 93)),
        ImplicitEnd(sourcePos = sourcePos(103, 104)))))
  }

  "finish" in {
    checkWithSourcePos("""
      define workflow {
        finish;
      }""",
      Workflow(WorkflowPath.NoId, Vector(
        Finish(sourcePos(33, 39)),
        ImplicitEnd(sourcePos = sourcePos(47, 48)))))
  }

  "onError and goto" in {
    checkWithSourcePos("""
      define workflow {
        execute executable="/A", agent="AGENT";
        ifFailedGoto FAILURE;
        execute executable="/B", agent="AGENT";
        goto END;
        FAILURE: execute executable="/OnFailure", agent="AGENT";
        END: end;
      }""",
    Workflow(
      WorkflowPath.NoId,
      Vector(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("/A")),
          sourcePos(33, 71)),
        IfFailedGoto(Label("FAILURE"), sourcePos(81, 101)),
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("/B")),
          sourcePos(111, 149)),
        Goto(Label("END"), sourcePos(159, 167)),
        "FAILURE" @:
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("/OnFailure")),
          sourcePos(186, 232)),
        "END" @:
        ExplicitEnd(sourcePos(247, 250)))))
  }

  //for (n <- sys.props.get("test.speed").map(_.toInt)) "Speed" - {
  //  s"Parsing $n processes" in {
  //    info(measureTime(n, "processes") {
  //      parse(TestWorkflowSource)
  //    }.toString)
  //  }
  //
  //  s"Parsing and compiling $n processes, parallel" in {
  //    info(measureTimeParallel(n, "processes") {
  //      parse(TestWorkflowSource)
  //    }.toString)
  //  }
  //}

  "Comments" in {
    val source = """/*comment
        */
        define workflow {
          //comment
          /*comment/**/execute/***/executable="/A"/**/,agent/**/=/**/"AGENT"/**/;/**///comment
        }
      """
    assert(parse(source) == Workflow(
      WorkflowPath.NoId,
      Vector(
        Execute.Anonymous(
          WorkflowJob(AgentId("AGENT"), ExecutablePath("/A")),
          sourcePos(90, 143)),
        ImplicitEnd(sourcePos(170, 171))),
      source = Some(source)))
  }

  private def sourcePos(start: Int, end: Int) = Some(SourcePos(start, end))

  private def checkWithSourcePos(source: String, workflow: Workflow): Unit =
    check2(source, workflow, withSourcePos = true)

  private def check(source: String, workflow: Workflow): Unit =
    check2(source, workflow, withSourcePos = false)

  private def check2(source: String, workflow: Workflow, withSourcePos: Boolean): Unit = {
    val parsedWorkflow = WorkflowParser.parse(source).map(o => if (withSourcePos) o else o.withoutSourcePos)
    assertEqual(parsedWorkflow.orThrow, workflow.copy(source = Some(source)))
    val generatedSource = workflow.show
    assert(WorkflowParser.parse(generatedSource).map(_.withoutSourcePos)
      == Right(workflow.copy(source = Some(generatedSource)).withoutSourcePos),
      s"(generated source: $generatedSource)")
  }

  private def parse(workflowString: String): Workflow =
    WorkflowParser.parse(workflowString) match {
      case Right(workflow) => workflow
      case Left(problem) => throw new AssertionError(problem.toString, problem.throwableOption.orNull) with NoStackTrace
    }
}
