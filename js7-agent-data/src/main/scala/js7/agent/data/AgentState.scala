package js7.agent.data

import js7.agent.data.AgentState.AgentMetaState
import js7.agent.data.event.AgentEvent
import js7.agent.data.event.AgentEvent.AgentDedicated
import js7.agent.data.orderwatch.{AllFileWatchesState, FileWatchState}
import js7.agent.data.subagent.SubagentRefState
import js7.base.circeutils.CirceUtils.deriveCodec
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.base.problem.{Checked, Problem}
import js7.base.utils.Collections.RichMap
import js7.base.utils.ScalaUtils.syntax._
import js7.data.agent.{AgentPath, AgentRunId}
import js7.data.calendar.{Calendar, CalendarPath}
import js7.data.controller.ControllerId
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.KeyedEventTypedJsonCodec.KeyedSubtype
import js7.data.event.{Event, EventId, ItemContainer, JournalEvent, JournalState, KeyedEvent, KeyedEventTypedJsonCodec, SnapshotableState}
import js7.data.item.BasicItemEvent.{ItemAttachedToMe, ItemDetached}
import js7.data.item.{BasicItemEvent, InventoryItem, InventoryItemEvent, InventoryItemKey}
import js7.data.job.{JobResource, JobResourcePath}
import js7.data.order.OrderEvent.{OrderCoreEvent, OrderForked, OrderJoined, OrderStdWritten}
import js7.data.order.{Order, OrderEvent, OrderId}
import js7.data.orderwatch.{FileWatch, OrderWatchEvent, OrderWatchPath}
import js7.data.state.{AgentStateView, StateView}
import js7.data.subagent.{SubagentId, SubagentRef}
import js7.data.workflow.{Workflow, WorkflowId, WorkflowPath}
import monix.reactive.Observable
import scala.collection.MapView

/**
  * @author Joacim Zschimmer
  */
final case class AgentState(
  eventId: EventId,
  standards: SnapshotableState.Standards,
  meta: AgentMetaState,
  idToSubagentRefState: Map[SubagentId, SubagentRefState],
  idToOrder: Map[OrderId, Order[Order.State]],
  idToWorkflow: Map[WorkflowId, Workflow],
  allFileWatchesState: AllFileWatchesState,
  pathToJobResource: Map[JobResourcePath, JobResource],
  pathToCalendar: Map[CalendarPath, Calendar])
extends StateView
with AgentStateView
with SnapshotableState[AgentState]
{
  def isAgent = true

  def controllerId = meta.controllerId

  def companion = AgentState

  /** A Controller has initialized this Agent? */
  def isDedicated =
    agentPath.nonEmpty/*shortcut*/ ||
      copy(eventId = EventId.BeforeFirst) != AgentState.empty

  def isFreshlyDedicated: Boolean =
    isDedicated &&
      this == AgentState.empty.copy(
        eventId = eventId,
        standards = standards,
        meta = meta)

  def estimatedSnapshotSize =
    standards.snapshotSize +
      1 +
      idToSubagentRefState.values.view.map(_.estimatedSnapshotSize).sum +
      idToWorkflow.size +
      idToOrder.size +
      allFileWatchesState.estimatedSnapshotSize +
      pathToJobResource.size +
      pathToCalendar.size

  def toSnapshotObservable =
    standards.toSnapshotObservable ++
      Observable.fromIterable((meta != AgentMetaState.empty) thenList meta) ++
      Observable.fromIterable(idToSubagentRefState.values).flatMap(_.toSnapshotObservable) ++
      Observable.fromIterable(idToWorkflow.values) ++
      Observable.fromIterable(idToOrder.values) ++
      allFileWatchesState.toSnapshot ++
      Observable.fromIterable(pathToJobResource.values) ++
      Observable.fromIterable(pathToCalendar.values)

  def withEventId(eventId: EventId) =
    copy(eventId = eventId)

  def withStandards(standards: SnapshotableState.Standards) =
    copy(standards = standards)

  def applyEvent(keyedEvent: KeyedEvent[Event]): Checked[AgentState] =
    keyedEvent match {
      case KeyedEvent(orderId: OrderId, event: OrderEvent) =>
        applyOrderEvent(orderId, event)

      case KeyedEvent(_, _: AgentEvent.AgentReady) =>
        Right(this)

      case KeyedEvent(_, AgentEvent.AgentShutDown) =>
        Right(this)

      case KeyedEvent(orderWatchPath: OrderWatchPath, event: OrderWatchEvent) =>
        allFileWatchesState.applyEvent(orderWatchPath <-: event)
          .map(o => copy(allFileWatchesState = o))

      case KeyedEvent(_: NoKey, event: BasicItemEvent.ForDelegate) =>
        event match {
          case ItemAttachedToMe(workflow: Workflow) =>
            for (o <- idToWorkflow.insert(workflow.id -> workflow)) yield
              copy(
                idToWorkflow = o)

          case ItemAttachedToMe(fileWatch: FileWatch) =>
            // May replace an existing JobResource
            Right(copy(
              allFileWatchesState = allFileWatchesState.attach(fileWatch)))

          case ItemAttachedToMe(jobResource: JobResource) =>
            // May replace an existing JobResource
            Right(copy(
              pathToJobResource = pathToJobResource + (jobResource.path -> jobResource)))

          case ItemAttachedToMe(calendar: Calendar) =>
            // May replace an existing Calendar
            Right(copy(
              pathToCalendar = pathToCalendar + (calendar.path -> calendar)))

          case ItemAttachedToMe(subagentRef: SubagentRef) =>
            // May replace an existing SubagentRef
            Right(copy(
              idToSubagentRefState = idToSubagentRefState + (subagentRef.id ->
                idToSubagentRefState
                  .get(subagentRef.id)
                  .match_ {
                    case None => SubagentRefState.initial(subagentRef)
                    case Some(subagentState) => subagentState.copy(subagentRef = subagentRef)
                  })))

          case ItemDetached(WorkflowId.as(workflowId), _) =>
            for (_ <- idToWorkflow.checked(workflowId)) yield
              copy(
                idToWorkflow = idToWorkflow - workflowId)

          case ItemDetached(path: OrderWatchPath, meta.agentPath) =>
            for (_ <- allFileWatchesState.pathToFileWatchState.checked(path)) yield
              copy(
                allFileWatchesState = allFileWatchesState.detach(path))

          case ItemDetached(path: JobResourcePath, meta.agentPath) =>
            for (_ <- pathToJobResource.checked(path)) yield
              copy(
                pathToJobResource = pathToJobResource - path)

          case ItemDetached(path: CalendarPath, meta.agentPath) =>
            for (_ <- pathToCalendar.checked(path)) yield
              copy(
                pathToCalendar = pathToCalendar - path)

          case ItemDetached(id: SubagentId, meta.agentPath) =>
            for (_ <- idToSubagentRefState.checked(id)) yield
              copy(
                idToSubagentRefState = idToSubagentRefState - id)

          case _ => applyStandardEvent(keyedEvent)
        }

      case KeyedEvent(_: NoKey, AgentDedicated(subagentId, agentPath, agentRunId, controllerId)) =>
        Right(copy(meta = meta.copy(
          agentPath = agentPath,
          agentRunId = agentRunId,
          controllerId = controllerId,
          subagentId = subagentId)))

      case _ => applyStandardEvent(keyedEvent)
    }

  private def applyOrderEvent(orderId: OrderId, event: OrderEvent) =
    event match {
      case event: OrderEvent.OrderAttachedToAgent =>
        if (idToOrder.contains(orderId))
          Left(Problem.pure(s"Duplicate order attached: $orderId"))
        else
          Right(copy(
            idToOrder = idToOrder + (orderId -> Order.fromOrderAttached(orderId, event))))

      case OrderEvent.OrderDetached =>
        Right(copy(
          idToOrder = idToOrder - orderId))

      case event: OrderCoreEvent =>
        // See also OrderActor#update
        idToOrder.checked(orderId)
          .flatMap(_.applyEvent(event))
          .flatMap(order =>
            event match {
              case event: OrderForked =>
                // TODO Check duplicate child OrderIds
                Right(copy(
                  idToOrder = idToOrder +
                    (order.id -> order) ++
                    idToOrder(orderId).newForkedOrders(event).map(o => o.id -> o)))

              case _: OrderJoined =>
                //order.checkedState[Order.Forked]
                //  .map(order => copy(
                //    idToOrder = idToOrder +
                //      (order.id -> order) --
                //      order.state.childOrderIds))
                Left(Problem.pure("OrderJoined not applicable on AgentState"))

              case _: OrderCoreEvent =>
                Right(copy(
                  idToOrder = idToOrder + (order.id -> order)))
            })

      case _: OrderStdWritten =>
        // OrderStdWritten is not applied (but forwarded to Controller)
        Right(this)
    }

  def agentPath = meta.agentPath

  lazy val keyToItem: MapView[InventoryItemKey, InventoryItem] =
    new MapView[InventoryItemKey, InventoryItem] {
      def get(itemKey: InventoryItemKey): Option[InventoryItem] =
        itemKey match {
          case path: JobResourcePath => pathToJobResource.get(path)
          case path: CalendarPath => pathToCalendar.get(path)
          case path: OrderWatchPath => allFileWatchesState.pathToFileWatchState.get(path).map(_.fileWatch)
          case WorkflowId.as(id) => idToWorkflow.get(id)
          case id: SubagentId => idToSubagentRefState.get(id).map(_.subagentRef)
        }

      def iterator: Iterator[(InventoryItemKey, InventoryItem)] =
        pathToJobResource.iterator ++
          pathToCalendar.iterator ++
          allFileWatchesState.pathToFileWatchState.view.mapValues(_.fileWatch).iterator ++
          idToWorkflow.iterator ++
          idToSubagentRefState.view.mapValues(_.item).iterator
    }

  def workflowPathToId(workflowPath: WorkflowPath) =
    Left(Problem.pure("workflowPathToId is not available at Agent"))

  def pathToLockState = Map.empty
  def pathToBoardState = Map.empty

  def orders = idToOrder.values
}

object AgentState
extends SnapshotableState.Companion[AgentState]
with ItemContainer.Companion[AgentState]
{
  type StateEvent = Event

  val empty = AgentState(EventId.BeforeFirst, SnapshotableState.Standards.empty,
    AgentMetaState.empty,
    Map.empty, Map.empty, Map.empty, AllFileWatchesState.empty, Map.empty, Map.empty)

  def newBuilder() = new AgentStateBuilder

  protected val inventoryItems = Vector(
    FileWatch, JobResource, Calendar, Workflow, SubagentRef)

  override lazy val itemPaths =
    inventoryItems.map(_.Path) :+ AgentPath

  final case class AgentMetaState(
    subagentId: Option[SubagentId],
    agentPath: AgentPath,
    agentRunId: AgentRunId,
    controllerId: ControllerId)
  object AgentMetaState
  {
    val empty = AgentMetaState(
      None,
      AgentPath.empty,
      AgentRunId.empty,
      ControllerId("NOT-YET-INITIALIZED"))

    implicit val jsonCodec = deriveCodec[AgentMetaState]
  }

  val snapshotObjectJsonCodec: TypedJsonCodec[Any] =
    TypedJsonCodec.named("AgentState.Snapshot",
      Subtype[JournalState],
      Subtype[AgentMetaState],
      Workflow.subtype,
      Subtype[Order[Order.State]],
      Subtype[FileWatchState.Snapshot],
      Subtype(signableSimpleItemJsonCodec),
      Subtype(unsignedSimpleItemJsonCodec),
      Subtype[BasicItemEvent])

  implicit val keyedEventJsonCodec: KeyedEventTypedJsonCodec[Event] =
    KeyedEventTypedJsonCodec.named("AgentState.Event",
      KeyedSubtype[JournalEvent],
      KeyedSubtype[OrderEvent],
      KeyedSubtype[AgentEvent],
      KeyedSubtype[InventoryItemEvent],
      KeyedSubtype[OrderWatchEvent])
}
