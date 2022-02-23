package js7.data.order

import cats.syntax.option._
import io.circe.Json
import io.circe.syntax.EncoderOps
import js7.base.circeutils.CirceUtils._
import js7.base.problem.{Problem, ProblemException}
import js7.base.time.ScalaTime._
import js7.base.time.{TimeInterval, Timestamp}
import js7.base.utils.ScalaUtils.implicitClass
import js7.base.utils.ScalaUtils.syntax._
import js7.data.agent.AgentPath
import js7.data.board.{Notice, NoticeId}
import js7.data.command.{CancellationMode, SuspensionMode}
import js7.data.job.{InternalExecutable, JobKey}
import js7.data.lock.LockPath
import js7.data.order.Order.{Attached, AttachedState, Attaching, BetweenCycles, Broken, Cancelled, DelayedAfterError, Detaching, ExpectingNotice, Failed, FailedInFork, FailedWhileFresh, Finished, Forked, Fresh, InapplicableOrderEventProblem, IsFreshOrReady, Processed, Processing, ProcessingKilled, Prompting, Ready, State, WaitingForLock}
import js7.data.order.OrderEvent.{OrderAdded, OrderAttachable, OrderAttached, OrderAttachedToAgent, OrderAwoke, OrderBroken, OrderCancellationMarked, OrderCancellationMarkedOnAgent, OrderCancelled, OrderCatched, OrderCoreEvent, OrderCycleFinished, OrderCycleStarted, OrderCyclingPrepared, OrderDeleted, OrderDeletionMarked, OrderDetachable, OrderDetached, OrderFailed, OrderFailedInFork, OrderFinished, OrderForked, OrderJoined, OrderLockAcquired, OrderLockDequeued, OrderLockQueued, OrderLockReleased, OrderMoved, OrderNoticeExpected, OrderNoticePosted, OrderNoticeRead, OrderOrderAdded, OrderProcessed, OrderProcessingKilled, OrderProcessingStarted, OrderPromptAnswered, OrderPrompted, OrderResumed, OrderResumptionMarked, OrderRetrying, OrderStarted, OrderSuspended, OrderSuspensionMarked, OrderSuspensionMarkedOnAgent}
import js7.data.subagent.SubagentId
import js7.data.value.{NamedValues, NumberValue, StringValue, Value}
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{Execute, Fork}
import js7.data.workflow.position.BranchId.Then
import js7.data.workflow.position.Position
import js7.data.workflow.{Workflow, WorkflowPath}
import js7.tester.CirceJsonTester.testJson
import org.scalactic.source
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.View
import scala.collection.immutable.ListSet
import scala.reflect.ClassTag

/**
  * @author Joacim Zschimmer
  */
final class OrderTest extends AnyFreeSpec
{
  private val testOrder = Order(
    OrderId("ID"),
    WorkflowPath("WORKFLOW") ~ "VERSION",
    Ready,
    arguments = Map(
      "key1" -> StringValue("value1"),
      "key2" -> StringValue("value2")),
    historicOutcomes = Vector(
      HistoricOutcome(Position(123), Outcome.Succeeded(NamedValues.rc(0)))))

  private val subagentId = SubagentId("SUBAGENT")

  "JSON" - {
    "Order" - {
      "Ready" in {
        check(
          testOrder.copy(
            attachedState = Some(Attached(AgentPath("AGENT"))),
            parent = Some(OrderId("PARENT")),
            scheduledFor = Some(Timestamp.parse("2121-04-26T12:33:44.789Z"))),
          json"""{
            "id": "ID",
            "workflowPosition": {
              "workflowId": {
                "path": "WORKFLOW",
                "versionId": "VERSION"
              },
              "position": [ 0 ]
            },
            "state": {
              "TYPE": "Ready"
            },
            "arguments": {
              "key1": "value1",
              "key2": "value2"
            },
            "scheduledFor": 4775114024789,
            "historicOutcomes": [
              {
                "position": [ 123 ],
                "outcome": {
                  "TYPE": "Succeeded",
                  "namedValues": {
                    "returnCode": 0
                  }
                }
              }
            ],
            "attachedState": {
              "TYPE": "Attached",
              "agentPath":"AGENT"
            },
            "parent": "PARENT"
          }""")
      }

      "Processing (extra Codec)" in {
        testJson[Order[Processing]](
          Order(OrderId("ID"), WorkflowPath("WORKFLOW") ~ "VERSION", Processing(subagentId)),
          json"""{
            "id": "ID",
            "workflowPosition": {
              "workflowId": {
                "path": "WORKFLOW",
                "versionId": "VERSION"
              },
              "position": [ 0 ]
            },
            "state": {
              "TYPE": "Processing",
              "subagentId": "SUBAGENT"
            }
          }""")

        testJson[Order[Processing]](
          Order(OrderId("ID"), WorkflowPath("WORKFLOW") ~ "VERSION", Processing(None)),
          json"""{
            "id": "ID",
            "workflowPosition": {
              "workflowId": {
                "path": "WORKFLOW",
                "versionId": "VERSION"
              },
              "position": [ 0 ]
            },
            "state": {
              "TYPE": "Processing"
            }
          }""")
      }

      "mark" in {
        check(
          Order(OrderId("ID"), WorkflowPath("WORKFLOW") ~ "VERSION", Fresh,
            mark = Some(OrderMark.Cancelling(CancellationMode.FreshOnly)),
            isSuspended = true),
          json"""{
            "id": "ID",
            "workflowPosition": {
              "workflowId": {
                "path": "WORKFLOW",
                "versionId": "VERSION"
              },
              "position": [ 0 ]
            },
            "state": {
              "TYPE": "Fresh"
            },
            "mark": {
              "TYPE": "Cancelling",
              "mode": {
                "TYPE": "FreshOnly"
              }
            },
            "isSuspended": true
          }""")
      }

      def check(o: Order[State], json: Json) = testJson(o, json)
    }

    "State" - {
      "Fresh immediately" in {
        testJson[State](Fresh,
          json"""{
            "TYPE": "Fresh"
          }""")
      }

      "Ready" in {
        testJson[State](Ready,
          json"""{
            "TYPE": "Ready"
          }""")
      }

      "Processing" in {
        testJson[State](Processing(SubagentId("SUBAGENT")),
          json"""{
            "TYPE": "Processing",
            "subagentId": "SUBAGENT"
          }""")
      }

      "Processed" in {
        testJson[State](Processed,
          json"""{
            "TYPE": "Processed"
          }""")
      }

      "FailedWhileFresh" in {
        testJson[State](FailedWhileFresh,
          json"""{
            "TYPE": "FailedWhileFresh"
          }""")
      }

      "DelayedAfterError" in {
        testJson[State](DelayedAfterError(Timestamp("2019-03-07T12:00:00Z")),
          json"""{
            "TYPE": "DelayedAfterError",
            "until": 1551960000000
          }""")
      }

      "Forked (distinct branches)" in {
        testJson[State](Forked(Vector(
          Forked.Child(Fork.Branch.Id("A"), OrderId("A") / "1"),
          Forked.Child(Fork.Branch.Id("B"), OrderId("B") / "2"))),
          json"""{
            "TYPE": "Forked",
              "children": [
                {
                  "orderId": "A|1",
                  "branchId": "A"
                }, {
                  "orderId": "B|2",
                  "branchId": "B"
                }
              ]
            }""")
      }

      "Forked (ForkList) " in {
        testJson[State](Forked(Vector(
          Forked.Child(OrderId("A") / "1", Map("x" -> NumberValue(1))),
          Forked.Child(OrderId("B") / "2", Map.empty[String, Value]))),
          json"""{
            "TYPE": "Forked",
              "children": [
                {
                  "orderId": "A|1",
                  "arguments": {
                    "x": 1
                  }
                }, {
                  "orderId": "B|2"
                }
              ]
            }""")
      }

      "WaitingForLock" in {
        testJson[State](WaitingForLock,
          json"""{
            "TYPE": "WaitingForLock"
          }""")
      }

      "ExpectingNotice" in {
        testJson[State](ExpectingNotice(NoticeId("NOTICE")),
          json"""{
            "TYPE": "ExpectingNotice",
            "noticeId": "NOTICE"
          }""")
      }

      "Prompting" in {
        testJson[State](Prompting(StringValue("QUESTION")),
          json"""{
            "TYPE": "Prompting",
            "question": "QUESTION"
          }""")
      }

      "BetweenCycles" in {
        testJson[State](
          BetweenCycles(Some(CycleState(
            end = Timestamp("2021-10-01T00:00:00Z"),
            schemeIndex = 1,
            index = 2,
            next = Timestamp("2021-10-01T12:00:00Z")))),
          json"""{
            "TYPE": "BetweenCycles",
            "cycleState": {
              "end": 1633046400000,
              "schemeIndex": 1,
              "index": 2,
              "next": 1633089600000
            }
          }""")
      }

      "Cancelled" in {
        testJson[State](Cancelled,
          json"""{
            "TYPE": "Cancelled"
          }""")
      }

      "Finished" in {
        testJson[State](Finished,
          json"""{
            "TYPE": "Finished"
          }""")
      }

      "Broken" in {
        testJson[State](Broken(Problem("PROBLEM")),
          json"""{
            "TYPE": "Broken",
            "problem": {
              "message": "PROBLEM"
            }
          }""")
      }
    }

    "AttachedState" - {
      "Attached" in {
        testJson[AttachedState](Attached(AgentPath("AGENT")),
          json"""{
            "TYPE": "Attached",
            "agentPath": "AGENT"
          }""")
      }

      "Detaching" in {
        testJson[AttachedState](Detaching(AgentPath("AGENT")),
          json"""{
            "TYPE": "Detaching",
            "agentPath": "AGENT"
          }""")
      }
    }
  }

  "Order transitions: event to state" - {
    val orderId = OrderId("ID")
    val workflowId = WorkflowPath("WORKFLOW") ~ "VERSION"
    val agentPath = AgentPath("AGENT")
    val cycleState = CycleState.initial(TimeInterval(Timestamp.Epoch, 100*9000.h))
    val allEvents = ListSet[OrderCoreEvent](
      OrderAdded(workflowId),
      OrderOrderAdded(OrderId("ADDED"), workflowId),
      OrderDeletionMarked,
      OrderDeleted,

      OrderAttachable(agentPath),
      OrderAttachedToAgent(workflowId /: Position(0), Fresh, Map.empty, None, None, Vector.empty,
        agentPath, None, None, false, false),
      OrderAttached(agentPath),

      OrderStarted,
      OrderProcessingStarted(subagentId),
      //OrderStdoutWritten("stdout") is not an OrderCoreEvent
      //OrderStderrWritten("stderr") is not an OrderCoreEvent
      OrderProcessed(Outcome.Succeeded(NamedValues.rc(0))),
      OrderProcessingKilled,
      OrderFailed(Position(1), Some(Outcome.Failed(NamedValues.rc(1)))),
      OrderCatched(Position(1)),
      OrderRetrying(Position(1)),
      OrderAwoke,
      OrderMoved(Position(1)),
      OrderForked(Vector(OrderForked.Child("BRANCH", orderId / "BRANCH"))),
      OrderJoined(Outcome.Succeeded(NamedValues.rc(0))),
      OrderFailedInFork(Position(1), None),
      OrderFinished,

      OrderCancellationMarked(CancellationMode.FreshOnly),
      OrderCancellationMarkedOnAgent,
      OrderCancelled,
      OrderSuspensionMarked(),
      OrderSuspensionMarkedOnAgent,
      OrderSuspended,
      OrderResumptionMarked(),
      OrderResumed(),

      OrderLockAcquired(LockPath("LOCK")),
      OrderLockQueued(LockPath("LOCK"), None),
      OrderLockDequeued(LockPath("LOCK")),
      OrderLockReleased(LockPath("LOCK")),

      OrderNoticePosted(Notice(NoticeId("NOTICE"), endOfLife = Timestamp.ofEpochSecond(1))),
      OrderNoticeExpected(NoticeId("NOTICE")),
      OrderNoticeRead,

      OrderPrompted(StringValue("QUESTION")),
      OrderPromptAnswered(),

      OrderCyclingPrepared(cycleState),
      OrderCycleStarted,
      OrderCycleFinished(None),

      OrderBroken(Problem("Problem")),

      OrderDetachable,
      OrderDetached)

    "Event list is complete" in {
      assert(allEvents.map(_.getClass) == OrderEvent.jsonCodec.classes[OrderCoreEvent])
    }

    val IsDetached  = none[AttachedState]
    val IsAttaching = Some(Attaching(agentPath))
    val IsAttached  = Some(Attached(agentPath))
    val IsDetaching = Some(Detaching(agentPath))

    val NoMark     = none[OrderMark]
    val Cancelling = OrderMark.Cancelling(CancellationMode.FreshOrStarted()).some
    val Suspending = OrderMark.Suspending().some
    val SuspendingWithKill = OrderMark.Suspending(SuspensionMode(Some(CancellationMode.Kill()))).some
    val Resuming   = OrderMark.Resuming().some

    case object IsSuspended {
      def unapply(order: Order[Order.State]) = Some(order.isSuspended)
    }

    case object IsSuspendingWithKill {
      def unapply(order: Order[Order.State]) = Some(order.isSuspendingWithKill)
    }

    case object IsChild {
      def unapply(order: Order[Order.State]) = Some(order.parent.nonEmpty)
    }

    "Fresh" in {
      checkAllEvents(Order(orderId, workflowId, Fresh),
        deletionMarkable[Fresh] orElse
        markable[Fresh] orElse
        attachingAllowed[Fresh] orElse
        detachingAllowed[Fresh] orElse
        cancelMarkedAllowed[Fresh] orElse
        suspendMarkedAllowed[Fresh] orElse {
          case (_: OrderMoved       , _                 , _, IsDetached | IsAttached) => _.isInstanceOf[Fresh]
          case (_: OrderFailed      , IsSuspended(false), _, IsDetached             ) => _.isInstanceOf[FailedWhileFresh]  // Expression error
          case (_: OrderStarted     , IsSuspended(false), _, IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (OrderCancelled      , _                 , _, IsDetached             ) => _.isInstanceOf[Cancelled]
          case (OrderSuspended      , _                 , _, IsDetached             ) => _.isInstanceOf[Fresh]
          case (OrderSuspended      , IsSuspended(true) , _, IsAttached             ) => _.isInstanceOf[Fresh]
          case (_: OrderResumptionMarked, _             , _, _                      ) => _.isInstanceOf[Fresh]
          case (_: OrderResumed     , IsSuspended(true) , _, IsDetached | IsAttached) => _.isInstanceOf[Fresh]
          case (_: OrderBroken      , _                 , _, _                      ) => _.isInstanceOf[Broken]
        })
    }

    "Ready" in {
      checkAllEvents(Order(orderId, workflowId, Ready),
        deletionMarkable[Ready] orElse
        markable[Ready] orElse
        attachingAllowed[Ready] orElse
        detachingAllowed[Ready] orElse
        cancelMarkedAllowed[Ready] orElse
        suspendMarkedAllowed[Ready] orElse {
          case (_: OrderMoved            , _                 , _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderProcessingStarted, IsSuspended(false), _            , IsAttached             ) => _.isInstanceOf[Processing]
          case (_: OrderForked           , IsSuspended(false), _            , IsDetached | IsAttached) => _.isInstanceOf[Forked]
          case (_: OrderFailedInFork     , IsSuspended(false), IsChild(true), IsDetached | IsAttached) => _.isInstanceOf[FailedInFork]
          case (_: OrderCatched          , IsSuspended(false), _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderFailed           , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[Failed]
          case (_: OrderRetrying         , IsSuspended(false), _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderFinished         , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[Finished]
          case (OrderCancelled           , _                 , _            , IsDetached             ) => _.isInstanceOf[Cancelled]
          case (OrderSuspended           , _                 , _            , IsDetached             ) => _.isInstanceOf[Ready]
          case (OrderSuspended           , IsSuspended(true) , _            , IsAttached             ) => _.isInstanceOf[Ready]
          case (_: OrderResumptionMarked , _                 , _            , _                      ) => _.isInstanceOf[Ready]
          case (_: OrderResumed          , IsSuspended(true) , _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderLockAcquired     , _                 , _            , IsDetached             ) => _.isInstanceOf[Ready]
          case (_: OrderLockQueued       , _                 , _            , IsDetached             ) => _.isInstanceOf[WaitingForLock]
          case (_: OrderNoticePosted     , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[Ready]
          case (_: OrderNoticeExpected   , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[ExpectingNotice]
          case (_: OrderNoticeRead       , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[Ready]
          case (_: OrderPrompted         , _                 , _            , IsDetached             ) => _.isInstanceOf[Prompting]
          case (_: OrderCyclingPrepared  , IsSuspended(false), _            , IsDetached | IsAttached) => _.isInstanceOf[BetweenCycles]
          case (_: OrderOrderAdded       , _                 , _            , IsDetached             ) => _.isInstanceOf[Ready]
          case (_: OrderBroken           , _                 , _            , _                      ) => _.isInstanceOf[Broken]
        })
    }

    "WaitingForLock" in {
      checkAllEvents(Order(orderId, workflowId, WaitingForLock),
        deletionMarkable[WaitingForLock] orElse
        markable[WaitingForLock] orElse
        cancelMarkedAllowed[WaitingForLock] orElse
        suspendMarkedAllowed[WaitingForLock] orElse {
          case (_: OrderLockAcquired, _, _, IsDetached) => _.isInstanceOf[Ready]
          case (_: OrderLockDequeued, _, _, IsDetached) => _.isInstanceOf[Ready]
          case (_: OrderCancelled   , _, _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken      , _, _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "ExpectingNotice" in {
      checkAllEvents(Order(orderId, workflowId, ExpectingNotice(NoticeId("NOTICE"))),
        deletionMarkable[ExpectingNotice] orElse
        markable[ExpectingNotice] orElse
        cancelMarkedAllowed[ExpectingNotice] orElse
        suspendMarkedAllowed[ExpectingNotice] orElse {
          case (_: OrderNoticeRead, IsSuspended(false), _, IsDetached) => _.isInstanceOf[Ready]
          case (_: OrderCancelled , _                 , _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken    , _                 , _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Processing" in {
      checkAllEvents(Order(orderId, workflowId, Processing(subagentId)),
        deletionMarkable[Processing] orElse
        markable[Processing] orElse
        cancelMarkedAllowed[Processing] orElse
        suspendMarkedAllowed[Processing] orElse {
          case (_: OrderProcessed, IsSuspended(false), _, IsAttached) => _.isInstanceOf[Processed]
          case (_: OrderBroken   , _                 , _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Processed" in {
      checkAllEvents(Order(orderId, workflowId, Processed,
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(0))))),
        deletionMarkable[Processed] orElse
        markable[Processed] orElse
        cancelMarkedAllowed[Processed] orElse
        suspendMarkedAllowed[Processed] orElse
        detachingAllowed[Processed] orElse {
          case (_: OrderMoved           , _                 , _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderProcessingKilled, IsSuspended(false), _            ,              IsAttached) => _.isInstanceOf[ProcessingKilled]
          case (_: OrderFailed          , IsSuspended(false), _            , IsDetached             ) => _.isInstanceOf[Failed]
          case (_: OrderFailedInFork    , IsSuspended(false), IsChild(true), IsDetached | IsAttached) => _.isInstanceOf[FailedInFork]
          case (_: OrderCatched         , IsSuspended(false), _            , IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderBroken          , _                 , _            , _                      ) => _.isInstanceOf[Broken]
        })
    }

    "ProcessingKilled" in {
      checkAllEvents(Order(orderId, workflowId, ProcessingKilled,
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(0))))),
        deletionMarkable[ProcessingKilled] orElse
        markable[ProcessingKilled] orElse
        detachingAllowed[ProcessingKilled] orElse {
          case (OrderCancelled, _                         , _, IsDetached) => _.isInstanceOf[Cancelled]
          case (OrderSuspended, IsSuspendingWithKill(true), _, IsDetached) => _.isInstanceOf[Ready]
          case (OrderSuspended, order, _, IsAttached) if order.isSuspendingWithKill && order.isSuspended => _.isInstanceOf[Ready]
          case (_: OrderBroken, _                         , _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Prompting" in {
      checkAllEvents(Order(orderId, workflowId, Prompting(StringValue("QUESTION")),
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(0))))),
        deletionMarkable[Prompting] orElse
        markable[Prompting] orElse
        cancelMarkedAllowed[Prompting] orElse
        suspendMarkedAllowed[Prompting] orElse {
          case (_: OrderPromptAnswered, _, _, IsDetached) => _.isInstanceOf[Ready]
          case (OrderCancelled        , _, _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken        , _, _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "BetweenCycles" in {
      checkAllEvents(Order(orderId, workflowId, BetweenCycles(Some(cycleState)),
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Succeeded(NamedValues.rc(0))))),
        deletionMarkable[BetweenCycles] orElse
        markable[BetweenCycles] orElse
        cancelMarkedAllowed[BetweenCycles] orElse
        suspendMarkedAllowed[BetweenCycles] orElse
        detachingAllowed[BetweenCycles] orElse {
          case (_: OrderCyclingPrepared, IsSuspended(false), _, IsDetached | IsAttached) => _.isInstanceOf[BetweenCycles]
          case (OrderCycleStarted      , IsSuspended(false), _, IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (_: OrderMoved          , _                 , _, IsDetached | IsAttached) => _.isInstanceOf[Ready]
          case (OrderCancelled         , _                 , _, IsDetached             ) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken         , _                 , _, _                      ) => _.isInstanceOf[Broken]
        })
    }


    "FailedWhileFresh" in {
      checkAllEvents(Order(orderId, workflowId, FailedWhileFresh,
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Failed(NamedValues.rc(1))))),
        deletionMarkable[FailedWhileFresh] orElse
        markable[FailedWhileFresh] orElse
        detachingAllowed[FailedWhileFresh] orElse
        cancelMarkedAllowed[FailedWhileFresh] orElse
        suspendMarkedAllowed[FailedWhileFresh] orElse {
          case (OrderCancelled, _, _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken, _, _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Failed" in {
      checkAllEvents(Order(orderId, workflowId, Failed,
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.failed))),
        deletionMarkable[Failed] orElse
        markable[Failed] orElse
        detachingAllowed[Failed] orElse
        cancelMarkedAllowed[Failed] orElse {
          case (_: OrderResumed, IsSuspended(false), _, IsDetached) => _.isInstanceOf[Ready]
          case (OrderCancelled, _, _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken, _, _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "FailedInFork" in {
      checkAllEvents(Order(orderId, workflowId, FailedInFork, parent = Some(OrderId("PARENT")),
          historicOutcomes = Vector(HistoricOutcome(Position(0), Outcome.Failed(NamedValues.rc(1))))),
        detachingAllowed[FailedInFork] orElse
        deletionMarkable[FailedInFork] orElse
        cancelMarkedAllowed[FailedInFork] orElse {
          case (_: OrderSuspensionMarked, IsSuspended(_), _, _) => _.isInstanceOf[FailedInFork]
          case (_: OrderResumptionMarked, IsSuspended(_), _, _) => _.isInstanceOf[FailedInFork]
          case (_: OrderBroken          , _             , _, _) => _.isInstanceOf[Broken]
        })
    }

    "DelayedAfterError" in {
      checkAllEvents(Order(orderId, workflowId, DelayedAfterError(Timestamp("2019-03-07T12:00:00Z"))),
        deletionMarkable[DelayedAfterError] orElse
        markable[DelayedAfterError] orElse
        cancelMarkedAllowed[DelayedAfterError] orElse
        suspendMarkedAllowed[DelayedAfterError] orElse {
          case (OrderAwoke    , IsSuspended(false), _, IsAttached) => _.isInstanceOf[Order.Ready]
          case (OrderCancelled, _                 , _, IsDetached) => _.isInstanceOf[Cancelled]
          case (_: OrderBroken, _                 , _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Broken" in {
      checkAllEvents(Order(orderId, workflowId, Broken(Problem("PROBLEM"))),
        deletionMarkable[Broken] orElse
        markable[Broken] orElse
        detachingAllowed[Broken] orElse
        cancelMarkedAllowed[Broken] orElse {
          case (OrderCancelled          , _, _, IsDetached                           ) => _.isInstanceOf[Cancelled]
          case (_: OrderResumptionMarked, _, _, IsDetached | IsAttached | IsDetaching) => _.isInstanceOf[Broken]
          case (_: OrderResumed         , _, _, IsDetached | IsAttached              ) => _.isInstanceOf[Ready]
          case (_: OrderBroken          , _, _, _                                    ) => _.isInstanceOf[Broken]
        })
    }

    "Forked" in {
      checkAllEvents(Order(orderId, workflowId, Forked(Vector(Forked.Child("BRANCH", orderId / "CHILD")))),
        deletionMarkable[Forked] orElse
        markable[Forked] orElse
        attachingAllowed[Forked] orElse
        detachingAllowed[Forked] orElse
        cancelMarkedAllowed[Forked] orElse
        suspendMarkedAllowed[Forked] orElse {
          case (_: OrderJoined, IsSuspended(false), _, IsDetached) => _.isInstanceOf[Processed]
          case (_: OrderBroken, _                 , _, _         ) => _.isInstanceOf[Broken]
        })
    }

    "Cancelled" in {
      checkAllEvents(Order(orderId, workflowId, Cancelled),
        deletionMarkable[Cancelled] orElse {
          case (OrderDeleted, _, IsChild(false), IsDetached) => _.isInstanceOf[Order.Deleted]
        })
    }

    "Finished" in {
      checkAllEvents(Order(orderId, workflowId, Finished),
        deletionMarkable[Finished] orElse {
          case (OrderDeleted, _, IsChild(false), IsDetached) => _.isInstanceOf[Order.Deleted]
        })
    }

    "attachedState" - {
      "attachedState=None" in {
        val order = Order(orderId, workflowId, Ready, attachedState = None)
        assert(order.applyEvent(OrderAttachable(agentPath)) == Right(order.copy(attachedState = Some(Attaching(agentPath)))))
        assert(order.applyEvent(OrderAttached(agentPath)).isLeft)
        assert(order.applyEvent(OrderDetachable).isLeft)
        assert(order.applyEvent(OrderDetached).isLeft)
      }

      "attachedState=Attaching" in {
        val order = Order(orderId, workflowId, Ready, attachedState = Some(Attaching(agentPath)))
        assert(order.applyEvent(OrderAttachable(agentPath)).isLeft)
        assert(order.applyEvent(OrderAttached(agentPath)) == Right(order.copy(attachedState = Some(Attached(agentPath)))))
        assert(order.applyEvent(OrderAttached(AgentPath("OTHER"))).isLeft)
        assert(order.applyEvent(OrderDetachable).isLeft)
        assert(order.applyEvent(OrderDetached) == Right(order.copy(attachedState = None)))
      }

      "attachedState=Attached" in {
        val order = Order(orderId, workflowId, Ready, attachedState = Some(Attached(agentPath)))
        assert(order.applyEvent(OrderAttachable(agentPath)).isLeft)
        assert(order.applyEvent(OrderAttached(agentPath)).isLeft)
        assert(order.applyEvent(OrderAttached(AgentPath("OTHER"))).isLeft)
        assert(order.applyEvent(OrderDetachable) == Right(order.copy(attachedState = Some(Detaching(agentPath)))))
        assert(order.applyEvent(OrderDetached) == Right(order.copy(attachedState = None)))
      }

      "attachedState=Detaching" in {
        val order = Order(orderId, workflowId, Ready, attachedState = Some(Detaching(agentPath)))
        assert(order.applyEvent(OrderAttachable(agentPath)).isLeft)
        assert(order.applyEvent(OrderAttached(agentPath)).isLeft)
        assert(order.applyEvent(OrderAttached(AgentPath("OTHER"))).isLeft)
        assert(order.applyEvent(OrderDetachable).isLeft)
        assert(order.applyEvent(OrderDetached) == Right(order.copy(attachedState = None)))
      }
    }

    type ToPredicate = PartialFunction[
      (OrderEvent, Order[Order.State], Order[Order.State], Option[AttachedState]),
      State => Boolean]

    def deletionMarkable[S <: Order.State: ClassTag]: ToPredicate = {
      case (_: OrderDeletionMarked, _, IsChild(false), _) =>
        implicitClass[S] isAssignableFrom _.getClass
    }

    def markable[S <: Order.State: ClassTag]: ToPredicate = {
      case (_: OrderCancellationMarked | _: OrderSuspensionMarked | _: OrderResumptionMarked, _, _, _) =>
        implicitClass[S] isAssignableFrom _.getClass
    }

    def cancelMarkedAllowed[S <: Order.State: ClassTag]: ToPredicate = {
      case (_: OrderCancellationMarked, _, _, _) => implicitClass[S] isAssignableFrom _.getClass
    }

    def suspendMarkedAllowed[S <: Order.State: ClassTag]: ToPredicate = {
      case (_: OrderSuspensionMarked, IsSuspended(false), _, _) => implicitClass[S] isAssignableFrom _.getClass
      case (_: OrderResumptionMarked , _                , _, _) => implicitClass[S] isAssignableFrom _.getClass
    }

    def attachingAllowed[S <: Order.State: ClassTag]: ToPredicate = {
      case (_: OrderAttachable, _, _, IsDetached ) => implicitClass[S] isAssignableFrom _.getClass
      case (_: OrderAttached  , _, _, IsAttaching) => implicitClass[S] isAssignableFrom _.getClass
    }

    def detachingAllowed[S <: Order.State: ClassTag]: ToPredicate = {
      case (OrderDetachable, _, _, IsAttached ) => implicitClass[S] isAssignableFrom _.getClass
      case (OrderDetached  , _, _, IsAttaching | IsAttached | IsDetaching) => implicitClass[S] isAssignableFrom _.getClass
    }

    /** Checks each event in `allEvents`. */
    def checkAllEvents(templateOrder: Order[State], toPredicate: ToPredicate)(implicit pos: source.Position): Unit =
      allEvents foreach {
        case OrderCancellationMarkedOnAgent =>
        case OrderSuspensionMarkedOnAgent =>
        case event =>
          for (m <- View[Option[OrderMark]](NoMark, Cancelling, Suspending, SuspendingWithKill, Resuming)) {
            for (isSuspended <- View(false, true)) {
              for (isChild <- View(false, true)) {
                for (a <- View(IsDetached, IsAttaching, IsAttached, IsDetaching)) /*SLOW (too many tests): s"${a getOrElse "Controller"}" -*/ {
                  val mString = m.fold("no mark")(_.getClass.simpleScalaName)
                  val aString = a.fold("detached")(_.getClass.simpleScalaName)
                  val order = templateOrder.copy(attachedState = a, mark = m,
                    parent = isChild ? OrderId("PARENT"),
                    isSuspended = isSuspended)
                  val updated = order.applyEvent(event)
                  val maybeState = updated.map(_.state)
                  val maybePredicate = toPredicate.lift((event, order, order, a))
                  (maybeState, maybePredicate) match {
                    case (Right(state), Some(predicate)) =>
                      assert(predicate(state), s"- for  ${templateOrder.state} state ($mString, isSuspended=$isSuspended, isChild=$isChild, $aString) <-: $event -> $state\n  $order")
                    case (Right(state), None) =>
                      fail(s"Missing test case for ${templateOrder.state} state ($mString, isSuspended=$isSuspended, isChild=$isChild, $aString) <-: $event -> $state\n  $order")
                    case (Left(problem), Some(_)) =>
                      fail(s"Failed test case for $order <-: $event -> 💥 $problem\n  $order")
                    case (Left(_), None) =>
                  }
                }
              }
            }
          }
      }
  }

  "Operations" - {
    "attached" in {
      val agentPath = AgentPath("A")
      assert(testOrder.attached.isLeft)
      assert(testOrder.copy(attachedState = Some(Attached(agentPath))) .attached == Right(agentPath))
      assert(testOrder.copy(attachedState = Some(Detaching(agentPath))).attached.isLeft)
    }

    "detaching" in {
      val agentPath = AgentPath("A")
      assert(testOrder.detaching.isLeft)
      assert(testOrder.copy(attachedState = Some(Attached(agentPath))) .detaching.isLeft)
      assert(testOrder.copy(attachedState = Some(Detaching(agentPath))).detaching == Right(agentPath))
    }

    "castState" in {
      assert(testOrder.castState[Ready] eq testOrder)
      assert(testOrder.castState[IsFreshOrReady] eq testOrder)
      assert(testOrder.castState[State] eq testOrder)
      intercept[ProblemException] {
        testOrder.castState[Processed]
      }
    }

    "ifState" in {
      assert(testOrder.ifState[Ready] == Some(testOrder))
      assert(testOrder.ifState[IsFreshOrReady] == Some(testOrder))
      assert(testOrder.ifState[State] == Some(testOrder))
      assert(testOrder.ifState[Processed] == None)
    }

    "isAttaching" in {
      val order = Order(OrderId("ORDER-ID"), WorkflowPath("WORKFLOW") ~ "VERSION", Ready,
        attachedState = Some(Detaching(AgentPath("AGENT"))))
      assert(order.detaching == Right(AgentPath("AGENT")))

      for (o <- Array(
            order.copy(attachedState = Some(Attached(AgentPath("AGENT")))),
            order.copy(attachedState = None))) {
        val Left(problem) = o.detaching
        assert(problem.toString contains "ORDER-ID")
      }
    }

  }

  "Events" - {
    "OrderResumed" - {
      import OrderResumed.{AppendHistoricOutcome, DeleteHistoricOutcome, HistoryOperation, InsertHistoricOutcome, ReplaceHistoricOutcome}

      lazy val order = Order(OrderId("ORDER-ID"), WorkflowPath("WORKFLOW") ~ "VERSION", Ready,
        historicOutcomes = Vector(
          HistoricOutcome(Position(0), Outcome.succeeded),
          HistoricOutcome(Position(1), Outcome.succeeded),
          HistoricOutcome(Position(2), Outcome.succeeded)),
        isSuspended = true)

      "Truncate history at position" in {
        for (i <- 0 to 2) withClue(s"Position $i: ") {
          assert(order.applyEvent(OrderResumed(Some(Position(i)), Nil)).toOption.get.historicOutcomes
            == order.historicOutcomes.take(i))
        }
      }

      def resume(operations: Seq[HistoryOperation]): Seq[HistoricOutcome] =
        order
          .applyEvent(OrderResumed(None, operations))
          .toOption.get.historicOutcomes

      "ReplaceHistoricOutcome" in {
        assert(resume(Seq(
          ReplaceHistoricOutcome(Position(1), Outcome.failed))) ==
          Seq(
            HistoricOutcome(Position(0), Outcome.succeeded),
            HistoricOutcome(Position(1), Outcome.failed),
            HistoricOutcome(Position(2), Outcome.succeeded)))
      }

      "DeletedHistoricOutcome" in {
        assert(resume(Seq(
          DeleteHistoricOutcome(Position(1)))) ==
          Seq(
            HistoricOutcome(Position(0), Outcome.succeeded),
            HistoricOutcome(Position(2), Outcome.succeeded)))
      }

      "InsertHistoricOutcome" in {
        assert(resume(Seq(
          InsertHistoricOutcome(Position(1), Position(1) / Then % 0, Outcome.failed),
          InsertHistoricOutcome(Position(1), Position(1) / Then % 1, Outcome.failed))) ==
          Seq(
            HistoricOutcome(Position(0), Outcome.succeeded),
            HistoricOutcome(Position(1) / Then % 0, Outcome.failed),
            HistoricOutcome(Position(1) / Then % 1, Outcome.failed),
            HistoricOutcome(Position(1), Outcome.succeeded),
            HistoricOutcome(Position(2), Outcome.succeeded)))
      }

      "AppendHistoricOutcome" in {
        assert(resume(Seq(
          AppendHistoricOutcome(Position(3), Outcome.failed),
          AppendHistoricOutcome(Position(4), Outcome.failed))) ==
          Seq(
            HistoricOutcome(Position(0), Outcome.succeeded),
            HistoricOutcome(Position(1), Outcome.succeeded),
            HistoricOutcome(Position(2), Outcome.succeeded),
            HistoricOutcome(Position(3), Outcome.failed),
            HistoricOutcome(Position(4), Outcome.failed)))
      }

      "Mixed" in {
        assert(resume(Seq(
          InsertHistoricOutcome(Position(2), Position(2) / Then % 0, Outcome.failed),
          DeleteHistoricOutcome(Position(1)),
          DeleteHistoricOutcome(Position(2)))) ==
          Seq(
            HistoricOutcome(Position(0), Outcome.succeeded),
            HistoricOutcome(Position(2) / Then % 0, Outcome.failed)))
      }
    }
  }

  "forkPositionOf" in {
    assert(testOrder.withPosition(Position(1)).forkPosition.isLeft)
    assert(testOrder.withPosition(Position(1) / "fork+A" % 2).forkPosition == Right(Position(1)))
    assert(testOrder.withPosition(Position(1) / "fork+A" % 2 / Then % 3).forkPosition == Right(Position(1)))
    assert(testOrder.withPosition(Position(1) / "fork+A" % 2 / Then % 3 / "fork+B" % 4).forkPosition == Right(Position(1) / "fork+A" % 2 / Then % 3))
  }

  "Error message when updated failed" in {
    assert(testOrder.applyEvent(OrderDetachable) ==
      Left(InapplicableOrderEventProblem(OrderDetachable, testOrder))) // "Order 'ID' at position 'WORKFLOW~VERSION:0' in state 'Ready', at Controller, received an inapplicable event: OrderDetachable")))
  }

  "historicJobExecutionCount" in {
    val jobName = WorkflowJob.Name("JOB")
    val workflow = Workflow(WorkflowPath("WORKFLOW") ~ "1",
      Vector(
        Execute(WorkflowJob(AgentPath("AGENT"), InternalExecutable(classOf[OrderTest].getName))),
        Execute(jobName)),
      nameToJob = Map(
        jobName ->
          WorkflowJob(AgentPath("AGENT"), InternalExecutable(classOf[OrderTest].getName))))
    val order = testOrder.copy(historicOutcomes = Vector(
      HistoricOutcome(Position(0), Outcome.succeeded),
      HistoricOutcome(Position(1), Outcome.succeeded),
      HistoricOutcome(Position(0), Outcome.succeeded),
      HistoricOutcome(Position(1), Outcome.succeeded),
      HistoricOutcome(Position(0), Outcome.succeeded)))

    assert(order.historicJobExecutionCount(JobKey(workflow.id /: Position(0)), workflow) == 3)
    assert(order.historicJobExecutionCount(JobKey(workflow.id /: Position(1)), workflow) == 0)
    assert(order.historicJobExecutionCount(JobKey(workflow.id, jobName), workflow) == 2)
  }

  if (sys.props contains "test.speed") "Speed" in {
    val order = Order(OrderId("ORDER-1"), (WorkflowPath("WORKFLOW") ~ "VERSION") /: Position(1), Ready,
      attachedState = Some(Attached(AgentPath("AGENT"))))
    val json = (order: Order[State]).asJson
    testSpeed(100000, "asOrder")(json.as[Order[State]])
    def testSpeed(n: Int, ops: String)(what: => Unit): Unit = {
      val start = Timestamp.currentTimeMillis
      for (_ <- 1 to n) what
      val duration = Timestamp.currentTimeMillis - start
      println(s"${duration}ms/$n $ops ${(n * 1000L / duration).toString} $ops/s")
    }
  }
}
