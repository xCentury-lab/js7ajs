package js7.data.execution.workflow.instructions

import js7.base.circeutils.CirceUtils._
import js7.base.problem.Checked._
import js7.base.problem.Problem
import js7.base.utils.ScalaUtils.syntax._
import js7.data.agent.AgentId
import js7.data.execution.workflow.context.StateView
import js7.data.execution.workflow.instructions.IfExecutorTest._
import js7.data.job.PathExecutable
import js7.data.order.{HistoricOutcome, Order, OrderId, Outcome}
import js7.data.value.expression.Expression._
import js7.data.value.{NamedValues, StringValue}
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{Execute, If}
import js7.data.workflow.position.BranchId.{Else, Then}
import js7.data.workflow.position.Position
import js7.data.workflow.{Workflow, WorkflowId, WorkflowPath}
import js7.tester.CirceJsonTester.testJson
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class IfExecutorTest extends AnyFreeSpec {

  private lazy val stateView = new StateView {
    def idToOrder = Map(AOrder.id -> AOrder, BOrder.id -> BOrder).checked
    def childOrderEnded(order: Order[Order.State]) = throw new NotImplementedError
    def idToWorkflow(id: WorkflowId) = Map(TestWorkflowId -> Workflow.of(TestWorkflowId)).checked(id)
    val idToLockState = _ => Left(Problem("idToLockState is not implemented here"))
  }

  "JSON BranchId" - {
    "then" in {
      testJson(IfExecutor.nextPosition(ifThenElse(BooleanConstant(true)), AOrder, stateView).orThrow,
        json"""[ 7, "then", 0 ]""")
    }

    "else" in {
      testJson(IfExecutor.nextPosition(ifThenElse(BooleanConstant(false)), AOrder, stateView).orThrow,
        json"""[ 7, "else", 0 ]""")
    }
  }

  "If true" in {
    assert(InstructionExecutor.nextPosition(ifThenElse(BooleanConstant(true)), AOrder, stateView) ==
      Right(Some(Position(7) / Then % 0)))
  }

  "If false" in {
    assert(InstructionExecutor.nextPosition(ifThenElse(BooleanConstant(false)), AOrder, stateView) ==
      Right(Some(Position(7) / Else % 0)))
  }

  "If false, no else branch" in {
    assert(InstructionExecutor.nextPosition(ifThen(BooleanConstant(false)), AOrder, stateView) ==
      Right(Some(Position(8))))
  }

  "Naned value comparison" in {
    val expr = Equal(NamedValue.last("A"), StringConstant("AA"))
    assert(InstructionExecutor.nextPosition(ifThenElse(expr), AOrder, stateView) == Right(Some(Position(7) / Then % 0)))
    assert(InstructionExecutor.nextPosition(ifThenElse(expr), BOrder, stateView) == Right(Some(Position(7) / Else % 0)))
  }

  "Error in expression" in {
    val expr = Equal(ToNumber(StringConstant("X")), NumericConstant(1))
    assert(InstructionExecutor.nextPosition(ifThenElse(expr), AOrder, stateView) == Left(Problem("Not a valid number: X")))
  }
}

object IfExecutorTest {
  private val TestWorkflowId = WorkflowPath("WORKFLOW") ~ "VERSION"
  private val AOrder = Order(OrderId("ORDER-A"), TestWorkflowId /: Position(7), Order.Processed,
    historicOutcomes = HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(1) ++ Map("A" -> StringValue("AA")))) :: Nil)
  private val BOrder = Order(OrderId("ORDER-B"), TestWorkflowId /: Position(7), Order.Processed,
    historicOutcomes = HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(1) ++ Map("A" -> StringValue("XX")))) :: Nil)
  private val ThenJob = Execute(WorkflowJob(AgentId("AGENT"), PathExecutable("THEN")))
  private val ElseJob = Execute(WorkflowJob(AgentId("AGENT"), PathExecutable("ELSE")))

  private def ifThenElse(booleanExpr: BooleanExpression) =
    If(booleanExpr, Workflow.of(ThenJob), Some(Workflow.of(ElseJob)))

  private def ifThen(booleanExpr: BooleanExpression) =
    If(booleanExpr, Workflow.of(ThenJob))
}
