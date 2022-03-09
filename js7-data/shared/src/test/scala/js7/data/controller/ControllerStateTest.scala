package js7.data.controller

import com.softwaremill.diffx.generic.auto._
import js7.base.auth.UserId
import js7.base.circeutils.CirceUtils._
import js7.base.crypt.silly.SillySigner
import js7.base.problem.Checked._
import js7.base.time.ScalaTime._
import js7.base.time.{Timestamp, Timezone}
import js7.base.utils.Collections.RichMap
import js7.base.utils.Collections.implicits._
import js7.base.web.Uri
import js7.data.agent.{AgentPath, AgentRef, AgentRefState}
import js7.data.board.{Board, BoardPath, BoardState, Notice, NoticeExpectation, NoticeId}
import js7.data.calendar.{Calendar, CalendarPath}
import js7.data.cluster.{ClusterSetting, ClusterState, ClusterStateSnapshot, ClusterTiming}
import js7.data.controller.ControllerStateTest._
import js7.data.delegate.DelegateCouplingState
import js7.data.event.SnapshotMeta.SnapshotEventId
import js7.data.event.{EventId, JournalState, SnapshotableState, Stamped}
import js7.data.item.BasicItemEvent.{ItemAttachable, ItemDeletionMarked}
import js7.data.item.ItemAttachedState.{Attachable, Attached}
import js7.data.item.SignedItemEvent.SignedItemAdded
import js7.data.item.UnsignedSimpleItemEvent.{UnsignedSimpleItemAdded, UnsignedSimpleItemChanged}
import js7.data.item.VersionedEvent.{VersionAdded, VersionedItemAdded, VersionedItemChanged}
import js7.data.item.{ClientAttachments, InventoryItemKey, InventoryItemPath, ItemRevision, ItemSigner, Repo, UnsignedSimpleItemEvent, VersionId}
import js7.data.job.{JobResource, JobResourcePath, ShellScriptExecutable}
import js7.data.lock.{Lock, LockPath, LockState}
import js7.data.node.NodeId
import js7.data.order.{Order, OrderId}
import js7.data.orderwatch.OrderWatchState.{HasOrder, Vanished}
import js7.data.orderwatch.{AllOrderWatchesState, ExternalOrderKey, ExternalOrderName, FileWatch, OrderWatchPath, OrderWatchState}
import js7.data.subagent.{SubagentId, SubagentRef, SubagentRefState, SubagentSelection, SubagentSelectionId}
import js7.data.value.expression.ExpressionParser.expr
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.instructions.{Execute, ExpectNotice, LockInstruction}
import js7.data.workflow.position.Position
import js7.data.workflow.{Workflow, WorkflowPath}
import js7.tester.CirceJsonTester.testJson
import js7.tester.DiffxAssertions.assertEqual
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AsyncFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class ControllerStateTest extends AsyncFreeSpec
{
  "estimatedSnapshotSize" in {
    assert(controllerState.estimatedSnapshotSize == 20)
    for (n <- controllerState.toSnapshotObservable.countL.runToFuture) yield
      assert(controllerState.estimatedSnapshotSize == n)
  }

  "pathToSimpleItem" in {
    val sum =
      controllerState.pathToCalendar.values ++
        controllerState.pathToAgentRefState.map(_._2.item) ++
        controllerState.idToSubagentRefState.map(_._2.item) ++
        controllerState.idToSubagentSelection.values ++
        controllerState.pathToLockState.map(_._2.item) ++
        controllerState.allOrderWatchesState.pathToOrderWatchState.map(_._2.item) ++
        controllerState.pathToSignedSimpleItem.values.map(_.value)
    assert(controllerState.pathToSimpleItem.toMap == sum.toKeyedMap(_.key))
  }

  "toSnapshotObservable" in {
    for (list <- controllerState.toSnapshotObservable.toListL.runToFuture)
      yield assert(list ==
        Seq(
          SnapshotEventId(1001L),
          JournalState(Map(UserId("A") -> EventId(1000))),
          ClusterStateSnapshot(
            ClusterState.Coupled(
              ClusterSetting(
                Map(
                  NodeId("A") -> Uri("https://A"),
                  NodeId("B") -> Uri("https://B")),
                activeId = NodeId("A"),
                Seq(ClusterSetting.Watch(Uri("https://CLUSTER-WATCH"))),
                ClusterTiming(10.s, 20.s)))),
          controllerState.controllerMetaState
        ) ++
          controllerState.pathToAgentRefState.values ++
          controllerState.idToSubagentRefState.values ++
          controllerState.idToSubagentSelection.values ++
          controllerState.pathToLockState.values ++
          Seq(board) ++
          boardState.notices.map(Notice.Snapshot(board.path, _)) ++
          Seq(calendar) ++
          Seq(
            UnsignedSimpleItemAdded(FileWatch(
              fileWatch.path,
              workflow.path,
              agentRef.path,
              expr("'/tmp/directory'"),
              itemRevision = Some(ItemRevision(7)))),
            OrderWatchState.ExternalOrderSnapshot(
              fileWatch.path,
              ExternalOrderName("ORDER-NAME"),
              HasOrder(OrderId("ORDER"), Some(Vanished))),
            SignedItemAdded(signedJobResource),
            VersionAdded(versionId),
            VersionedItemAdded(signedWorkflow),
            ItemAttachable(jobResource.path, agentRef.path),
            ItemDeletionMarked(fileWatch.path)
          ) ++
          controllerState.idToOrder.values)
  }

  "isWorkflowUsedByOrders" in {
    assert(controllerState.isWorkflowUsedByOrders == Set(workflow.id))
  }

  "isWorkflowUsedByOrders does not include orderless workflows" in {
    val v = VersionId("X")
    val workflowId = WorkflowPath("X") ~ v
    val controllerStateWithOrderlessWorkflow = controllerState
      .applyEvents(Seq(
        VersionAdded(v),
        VersionedItemAdded(itemSigner.sign(Workflow(workflowId, Nil)))))
      .orThrow
    assert(controllerStateWithOrderlessWorkflow.isWorkflowUsedByOrders == Set(workflow.id))
  }

  "pathToReferencingItemKeys" in {
    val x = controllerState.pathToReferencingItemKeys.mapValuesStrict(_.toSet)
    assert(x == Map[InventoryItemPath, Set[InventoryItemKey]](
      lock.path -> Set(workflow.id),
      board.path -> Set(workflow.id),
      agentRef.path -> Set(subagentRef.id, fileWatch.path, workflow.id),
      subagentRef.id -> Set(agentRef.path, subagentSelection.id),
      subagentSelection.id -> Set(workflow.id),
      jobResource.path -> Set(workflow.id),
      workflow.path -> Set(fileWatch.path)))
  }

  "pathToReferencingItemKeys does not return hidden workflows without orders" in {
    val v = VersionId("x")
    val changedWorkflowId = workflow.path ~ v
    val controllerState = ControllerStateTest.controllerState
      .applyEvents(Seq(
        VersionAdded(v),
        VersionedItemChanged(itemSigner.sign(workflow.copy(id = changedWorkflowId)))))
      .orThrow
      .copy(idToOrder = Map.empty)
    // The original workflow is still in use by an order and not deleted
    val x = controllerState.pathToReferencingItemKeys.view.mapValues(_.toSet).toMap
    assert(x == Map[InventoryItemPath, Set[InventoryItemKey]](
        lock.path -> Set(workflow.id, changedWorkflowId),
        board.path -> Set(workflow.id, changedWorkflowId),
        agentRef.path -> Set(subagentRef.id, fileWatch.path, workflow.id, changedWorkflowId),
        subagentRef.id -> Set(agentRef.path, subagentSelection.id),
        subagentSelection.id -> Set(workflow.id, changedWorkflowId),
        jobResource.path -> Set(workflow.id, changedWorkflowId),
        workflow.path -> Set(fileWatch.path)))
  }

  "fromIterator is the reverse of toSnapshotObservable" in {
    ControllerState
      .fromObservable(controllerState.toSnapshotObservable)
      .map(expectedState => assertEqual(controllerState, expectedState))
      .runToFuture
  }

  private val expectedSnapshotJsonArray =
    json"""[
      {
        "TYPE": "SnapshotEventId",
        "eventId": 1001
      }, {
        "TYPE": "JournalState",
        "userIdToReleasedEventId": {
          "A": 1000
        }
      }, {
        "TYPE": "ClusterStateSnapshot",
        "clusterState": {
          "TYPE": "Coupled",
          "setting": {
            "idToUri": {
              "A": "https://A",
              "B": "https://B"
            },
            "activeId": "A",
            "clusterWatches": [ { "uri": "https://CLUSTER-WATCH" } ],
            "timing": {
              "heartbeat": 10,
              "heartbeatTimeout": 20
            }
          }
        }
      }, {
        "TYPE": "ControllerMetaState",
        "controllerId": "CONTROLLER-ID",
        "initiallyStartedAt": 1558699200000,
        "timezone": "Europe/Berlin"
      }, {
        "TYPE": "AgentRefState",
        "agentRef": {
          "path": "AGENT",
          "directors": [ "SUBAGENT" ],
          "itemRevision": 0
        },
        "couplingState": {
          "TYPE": "Reset"
        },
        "eventId": 7
      }, {
        "TYPE": "SubagentRefState",
        "subagentRef": {
          "id": "SUBAGENT",
          "agentPath": "AGENT",
          "uri": "https://SUBAGENT",
          "disabled": false,
          "itemRevision": 7
        },
        "couplingState": {
          "TYPE": "Reset"
        },
        "eventId": 0
      }, {
        "TYPE": "SubagentSelection",
        "id": "SELECTION",
        "subagentToPriority": {
          "SUBAGENT": 1
        },
        "itemRevision": 7
      }, {
        "TYPE": "LockState",
        "lock": {
          "path": "LOCK",
          "limit": 1,
          "itemRevision": 7
        },
        "acquired": {
          "TYPE": "Available"
        },
        "queue": []
      }, {
        "TYPE": "Board",
        "path": "BOARD",
        "postOrderToNoticeId": "$$orderId",
        "expectOrderToNoticeId": "$$orderId",
        "endOfLife": "$$js7EpochMilli + 24 * 3600 * 1000",
        "itemRevision": 7
      }, {
        "TYPE": "Notice",
        "boardPath": "BOARD",
        "id": "NOTICE-1",
        "endOfLife": 10086400000
      }, {
        "TYPE": "Calendar",
        "path": "Calendar",
        "timezone": "Europe/Mariehamn",
        "dateOffset": 21600,
        "orderIdPattern": "#([^#]+)#.*",
        "periodDatePattern": "yyyy-MM-dd",
        "itemRevision": 1
      }, {
        "TYPE": "UnsignedSimpleItemAdded",
        "item": {
          "TYPE": "FileWatch",
          "path": "WATCH",
          "workflowPath": "WORKFLOW",
          "agentPath": "AGENT",
          "directoryExpr": "'/tmp/directory'",
          "delay": 0,
          "itemRevision": 7
        }
      }, {
        "TYPE": "ExternalOrder",
        "orderWatchPath": "WATCH",
        "externalOrderName": "ORDER-NAME",
        "state": {
          "TYPE": "HasOrder",
          "orderId": "ORDER",
          "queued": {
            "TYPE": "Vanished"
          }
        }
      }, {
        "TYPE": "SignedItemAdded",
        "signed": {
          "string": "{\"TYPE\":\"JobResource\",\"path\":\"JOB-RESOURCE\",\"variables\":{},\"env\":{}}",
          "signature": {
            "TYPE": "Silly",
            "signatureString": "SILLY-SIGNATURE"
          }
        }
      }, {
        "TYPE": "VersionAdded",
        "versionId": "1.0"
      }, {
        "TYPE": "VersionedItemAdded",
        "signed": {
          "signature": {
            "TYPE": "Silly",
            "signatureString": "SILLY-SIGNATURE"
          },
          "string": "{\"TYPE\":\"Workflow\",\"path\":\"WORKFLOW\",\"versionId\":\"1.0\",\"instructions\":[{\"TYPE\":\"Lock\",\"lockPath\":\"LOCK\",\"lockedWorkflow\":{\"instructions\":[{\"TYPE\":\"Execute.Anonymous\",\"job\":{\"agentPath\":\"AGENT\",\"subagentSelectionId\":\"SELECTION\",\"executable\":{\"TYPE\":\"ShellScriptExecutable\",\"script\":\"\"},\"jobResourcePaths\":[\"JOB-RESOURCE\"],\"parallelism\":1}}]}},{\"TYPE\":\"ExpectNotice\",\"boardPath\":\"BOARD\"}]}"
        }
      }, {
        "TYPE": "ItemAttachable",
        "delegateId": "Agent:AGENT",
        "key": "JobResource:JOB-RESOURCE"
      }, {
        "TYPE": "ItemDeletionMarked",
        "key": "OrderWatch:WATCH"
      }, {
        "TYPE": "Order",
        "id": "ORDER",
        "state": {
          "TYPE": "Fresh"
        },
        "externalOrderKey": {
          "orderWatchPath": "WATCH",
          "name": "ORDER-NAME"
        },
        "workflowPosition": {
          "position": [ 0 ],
          "workflowId": {
            "path": "WORKFLOW",
            "versionId": "1.0"
          }
        }
      }, {
        "TYPE": "Order",
        "id": "ORDER-EXPECTING-NOTICE",
        "state": {
          "TYPE": "ExpectingNotice",
          "noticeId": "NOTICE-2"
        },
        "workflowPosition": {
          "position": [ 1 ],
          "workflowId": {
            "path": "WORKFLOW",
            "versionId": "1.0"
          }
        }
      }
    ]"""

  "toSnapshotObservable JSON" in {
    implicit val x = ControllerState.snapshotObjectJsonCodec
    for {
      jsonArray <- controllerState.toSnapshotObservable.toListL.runToFuture
      assertion <- testJson(jsonArray, expectedSnapshotJsonArray)
    } yield assertion
  }

  "ControllerStateBuilder.addSnapshotObject" in {
    ControllerState.snapshotObjectJsonCodec
    val builder = new ControllerStateBuilder
    expectedSnapshotJsonArray.asArray.get
      .map(json => ControllerState.snapshotObjectJsonCodec.decodeJson(json).toChecked.orThrow)
      .foreach(builder.addSnapshotObject)
    builder.onAllSnapshotsAdded()
    assert(builder.result() == controllerState)
  }

  "keyToItem" in {
    assert(!controllerState.keyToItem.contains(WorkflowPath("UNKNOWN") ~ "1"))

    assert(controllerState.keyToItem.get(jobResource.path) == Some(jobResource))
    assert(controllerState.keyToItem.get(agentRef.path) == Some(agentRef))
    assert(controllerState.keyToItem.get(lock.path) == Some(lock))
    assert(controllerState.keyToItem.get(fileWatch.path) == Some(fileWatch))
    assert(controllerState.keyToItem.get(workflow.id) == Some(workflow))

    assert(controllerState.keyToItem.keySet == Set(
      jobResource.path, calendar.path,
      agentRef.path, subagentRef.id, subagentSelection.id,
      lock.path, fileWatch.path,
      workflow.id))
  }

  "v2.2 compatiblity" - {
    // COMPATIBLE with v2.2
    var cs = ControllerState.empty
    val builder = ControllerState.newBuilder()
    val eventIds = Iterator.from(1)
    val agentPath = AgentPath("v2.2")
    val uri = Uri("https://localhost")
    val agentRef = AgentRef(agentPath, directors = Nil, Some(uri),
      itemRevision = Some(ItemRevision(7)))

    val subagentId = SubagentId("v2.2-1")
    val generatedSubagentRef = SubagentRef(subagentId, agentPath, uri,
      itemRevision = Some(ItemRevision(1)))

    def applyEvent(event: UnsignedSimpleItemEvent): Unit = {
      val eventId = eventIds.next()
      cs = cs.applyEvent(event).orThrow
      cs = cs.withEventId(eventId)
      builder.addEvent(Stamped(eventId, event))
      assert(builder.result() == cs)
    }

    "UnsignedSimpleItemAdded" in {
      applyEvent(UnsignedSimpleItemAdded(agentRef))

      assert(cs.pathToAgentRefState(agentRef.path).agentRef == agentRef.copy(
        directors = Seq(subagentId),
        uri = None))
      assert(cs.idToSubagentRefState(subagentId).subagentRef == generatedSubagentRef)
    }

    "AgentRefState snapshot object" in {
      val b = ControllerState.newBuilder()
      b.addSnapshotObject(AgentRefState(agentRef))
      b.onAllSnapshotsAdded()
      val x = b.result()
      assert(b.result() == cs.withEventId(0))
    }

    "UnsignedSimpleItemChanged" in {
      val changedUri = Uri("https://example.com")
      val changedAgentRef = agentRef.copy(
        uri = Some(changedUri),
        itemRevision = Some(ItemRevision(8)))
      applyEvent(UnsignedSimpleItemChanged(changedAgentRef))
      assert(cs.pathToAgentRefState(agentRef.path).agentRef == agentRef.copy(
        directors = Seq(subagentId),
        uri = None,
        itemRevision = Some(ItemRevision(8))))
      assert(cs.idToSubagentRefState(subagentId).subagentRef == generatedSubagentRef.copy(
        uri = changedUri))
    }
  }
}

object ControllerStateTest
{
  private val jobResource = JobResource(JobResourcePath("JOB-RESOURCE"))
  private lazy val itemSigner = new ItemSigner(SillySigner.Default, ControllerState.signableItemJsonCodec)
  private lazy val signedJobResource = itemSigner.sign(jobResource)
  private val orderId = OrderId("ORDER")
  private val expectingNoticeOrderId = OrderId("ORDER-EXPECTING-NOTICE")
  private val agentRef = AgentRef(AgentPath("AGENT"), directors = Seq(SubagentId("SUBAGENT")),
    itemRevision = Some(ItemRevision(0)))

  private val subagentRef = SubagentRef(
    SubagentId("SUBAGENT"),
    AgentPath("AGENT"),
    Uri("https://SUBAGENT"),
    disabled = false,
    Some(ItemRevision(7)))
  private val subagentRefState = SubagentRefState.initial(subagentRef)
  private val subagentSelection = SubagentSelection(
    SubagentSelectionId("SELECTION"),
    Map(subagentRef.id -> 1),
    Some(ItemRevision(7)))

  private val lock = Lock(LockPath("LOCK"), itemRevision = Some(ItemRevision(7)))
  private val notice = Notice(NoticeId("NOTICE-1"), Timestamp.ofEpochMilli(10_000_000_000L + 24*3600*1000))
  private val noticeExpectation = NoticeExpectation(NoticeId("NOTICE-2"), Set(expectingNoticeOrderId))

  private val board = Board(
    BoardPath("BOARD"),
    postOrderToNoticeId = expr("$orderId"),
    expectOrderToNoticeId = expr("$orderId"),
    endOfLife = expr("$js7EpochMilli + 24*3600*1000"),
    itemRevision = Some(ItemRevision(7)))

  private val boardState = BoardState(
    board,
    Map(
      notice.id -> notice,
      noticeExpectation.id -> noticeExpectation))

  private val calendar = Calendar(
    CalendarPath("Calendar"),
    Timezone("Europe/Mariehamn"),
    orderIdToDatePattern = "#([^#]+)#.*",
    periodDatePattern = "yyyy-MM-dd",
    dateOffset = 6.h,
    itemRevision = Some(ItemRevision(1)))

  private val versionId = VersionId("1.0")
  private[controller] val workflow = Workflow(WorkflowPath("WORKFLOW") ~ versionId, Seq(
    LockInstruction(lock.path, None, Workflow.of(
      Execute(WorkflowJob(agentRef.path, ShellScriptExecutable(""),
        subagentSelectionId = Some(subagentSelection.id),
        jobResourcePaths = Seq(jobResource.path))))),
    ExpectNotice(board.path)))
  private val signedWorkflow = itemSigner.sign(workflow)
  private[controller] val fileWatch = FileWatch(
    OrderWatchPath("WATCH"),
    workflow.path,
    AgentPath("AGENT"),
    expr("'/tmp/directory'"),
    itemRevision = Some(ItemRevision(7)))

  // Also used by ControllerStateExecutorTest
  private[controller] lazy val controllerState = ControllerState(
    EventId(1001),
    SnapshotableState.Standards(
      JournalState(Map(UserId("A") -> EventId(1000))),
      ClusterState.Coupled(
        ClusterSetting(
          Map(
            NodeId("A") -> Uri("https://A"),
            NodeId("B") -> Uri("https://B")),
          activeId = NodeId("A"),
          Seq(ClusterSetting.Watch(Uri("https://CLUSTER-WATCH"))),
          ClusterTiming(10.s, 20.s)))),
    ControllerMetaState(ControllerId("CONTROLLER-ID"), Timestamp("2019-05-24T12:00:00Z"),
      Timezone("Europe/Berlin")),
    Map(
      agentRef.path -> AgentRefState(
        agentRef, None, None, DelegateCouplingState.Reset, EventId(7), None)),
    Map(
      subagentRef.id -> subagentRefState),
    Map(
      subagentSelection.id -> subagentSelection),
    Map(
      lock.path -> LockState(lock)),
    Map(
      boardState.path -> boardState),
    Map(
      calendar.path -> calendar),
    AllOrderWatchesState(Map(
      fileWatch.path -> OrderWatchState(
        fileWatch,
        Map(agentRef.path -> Attached(Some(ItemRevision(7)))),
        Map(
          ExternalOrderName("ORDER-NAME") -> HasOrder(OrderId("ORDER"), Some(Vanished)))))),
    Repo.empty.applyEvents(Seq(
      VersionAdded(versionId),
      VersionedItemAdded(signedWorkflow))).orThrow,
    Map(
      jobResource.path -> signedJobResource),
    ClientAttachments(Map(
      jobResource.path -> Map(agentRef.path -> Attachable))),
    deletionMarkedItems = Set(fileWatch.path),
    Seq(
      Order(orderId, workflow.id /: Position(0), Order.Fresh,
        externalOrderKey = Some(ExternalOrderKey(fileWatch.path, ExternalOrderName("ORDER-NAME")))),
      Order(expectingNoticeOrderId, workflow.id /: Position(1), Order.ExpectingNotice(noticeExpectation.id))
    ).toKeyedMap(_.id))
}
