package js7.data.controller

import cats.syntax.flatMap._
import cats.syntax.traverse._
import js7.base.problem.Checked
import js7.base.utils.Collections.implicits.RichIterable
import js7.base.utils.ScalaUtils.syntax.{RichBoolean, RichEither}
import js7.data.Problems.AgentResetProblem
import js7.data.agent.AgentPath
import js7.data.agent.AgentRefStateEvent.AgentResetStarted
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.{AnyKeyedEvent, KeyedEvent}
import js7.data.execution.workflow.OrderEventSource
import js7.data.execution.workflow.instructions.InstructionExecutorService
import js7.data.item.BasicItemEvent.{ItemAttachable, ItemAttachedStateEvent, ItemDeleted, ItemDetachable, ItemDetached}
import js7.data.item.ItemAttachedState.{Attachable, Attached, Detachable}
import js7.data.item.VersionedEvent.VersionedItemEvent
import js7.data.item.{InventoryItem, InventoryItemEvent, InventoryItemKey, SimpleItemPath, VersionedItemId_}
import js7.data.job.JobResource
import js7.data.order.Order.State
import js7.data.order.OrderEvent.{OrderAdded, OrderAwoke, OrderBroken, OrderCoreEvent, OrderDeleted, OrderDeletionMarked, OrderDetached, OrderForked, OrderLockEvent, OrderOrderAdded, OrderProcessed}
import js7.data.order.{FreshOrder, Order, OrderEvent, OrderId, Outcome}
import js7.data.orderwatch.ExternalOrderKey
import js7.data.value.expression.scopes.NowScope
import js7.data.workflow.{Workflow, WorkflowId}
import scala.annotation.tailrec
import scala.collection.{View, mutable}
import scala.language.implicitConversions

final case class ControllerStateExecutor private(
  keyedEvents: Seq[AnyKeyedEvent],
  controllerState: ControllerState)
  (implicit instructionExecutorService: InstructionExecutorService)
{
  import ControllerStateExecutor.convertImplicitly
  import controllerState.controllerId

  // Same clock time for a chunk of operations
  private lazy val nowScope = NowScope()

  def addOrders(freshOrders: Seq[FreshOrder], suppressOrderIdCheckFor: Option[String] = None)
  : Checked[Seq[KeyedEvent[OrderAdded]]] =
    freshOrders.checkUniqueness(_.id) >>
      freshOrders
        .traverse(addOrder(_, suppressOrderIdCheckFor = suppressOrderIdCheckFor))
        .map(_.flatten)

  def addOrder(
    order: FreshOrder,
    externalOrderKey: Option[ExternalOrderKey] = None,
    suppressOrderIdCheckFor: Option[String] = None)
  : Checked[Option[KeyedEvent[OrderAdded]]] =
    ( if (suppressOrderIdCheckFor.contains(order.id.string)) Checked.unit
      else order.id.checkedNameSyntax
    ) >>
      addOrderWithPrecheckedId(order, externalOrderKey)

  private def addOrderWithPrecheckedId(
    order: FreshOrder,
    externalOrderKey: Option[ExternalOrderKey] = None)
  : Checked[Option[KeyedEvent[OrderAdded]]] =
    if (controllerState.idToOrder.contains(order.id))
      Right(None) // Ignore known orders  — TODO Fail as duplicate if deleteWhenTerminated ?
    else
      for {
        workflow <- controllerState.repo.pathTo[Workflow](order.workflowPath)
        preparedArguments <- workflow.orderParameterList.prepareOrderArguments(
          order, controllerId, controllerState.keyTo(JobResource), nowScope)
      } yield Some(
        order.toOrderAdded(workflow.id.versionId, preparedArguments, externalOrderKey))

  def resetAgent(agentPath: AgentPath, force: Boolean): Checked[Seq[AnyKeyedEvent]] = {
    val agentResetStarted = View(agentPath <-: AgentResetStarted(force = force))
    val itemsDetached = controllerState.itemToAgentToAttachedState.to(View)
      .filter(_._2.contains(agentPath))
      .map(_._1)
      .map(itemKey => NoKey <-: ItemDetached(itemKey, agentPath))
    controllerState
      .idToOrder.values.view
      .filter(_.isAtAgent(agentPath))
      .map(forciblyDetachOrder(_, agentPath))
      .toVector.sequence
      .map(_.view.flatten)
      .map(ordersDetached => (agentResetStarted ++ ordersDetached ++ itemsDetached))
      .map(_.toVector)
  }

  private[controller] def forciblyDetachOrder(order: Order[Order.State], agentPath: AgentPath)
  : Checked[Seq[KeyedEvent[OrderCoreEvent]]] = {
    val outcome = Outcome.Disrupted(AgentResetProblem(agentPath))
    val stateEvents = (order.state: State) match {
      case _: Order.Processing =>
        Vector(order.id <-: OrderProcessed(outcome))

      case _: Order.DelayedAfterError =>
        Vector(order.id <-: OrderAwoke)

      case _ => Vector.empty
    }
    val detached = stateEvents :+ (order.id <-: OrderDetached)
    for {
      detachedState <- controllerState.applyEvents(detached)
      fail <- new OrderEventSource(detachedState)
        .failOrDetach(detachedState.idToOrder(order.id), Some(outcome), uncatchable = true)
    } yield detached ++ fail.view.map(order.id <-: _)
  }

  def applyEventsAndReturnSubsequentEvents(keyedEvents: Iterable[AnyKeyedEvent])
  : Checked[ControllerStateExecutor] =
    for {
      tuple <- applyEvents(keyedEvents)
      result <- (subsequentEvents _).tupled(tuple)
    } yield result

  private def applyEvents(keyedEvents: Iterable[AnyKeyedEvent])
  : Checked[(
    collection.Set[InventoryItemKey],
    collection.Set[OrderId],
    collection.Set[WorkflowId],
    collection.Set[InventoryItemKey],
    ControllerState)] =
  {
    val touchedItemKeys = mutable.Set.empty[InventoryItemKey]
    val touchedOrderIds = mutable.Set.empty[OrderId]
    val detachWorkflowCandidates = mutable.Set.empty[WorkflowId]
    val detachedItems = mutable.Set.empty[InventoryItemKey]

    var controllerState = this.controllerState
    var checked: Checked[Unit] = Checked.unit
    val iterator = keyedEvents.iterator

    while (iterator.hasNext && checked.isRight) {
      val keyedEvent = iterator.next()
      val previous = controllerState

      controllerState.applyEvent(keyedEvent) match {
        case Left(problem) =>
          checked = Left(problem)

        case Right(updated) =>
          controllerState = updated
          keyedEvent match {
            case KeyedEvent(_, event: VersionedItemEvent) =>
              for (previousItem <- previous.repo.pathToItem(event.path)) {
                touchedItemKeys += previousItem.id
                previousItem match {
                  case previousItem: Workflow =>
                    detachWorkflowCandidates += previousItem.id
                  case _ =>
                }
              }

            case KeyedEvent(_: NoKey, event: InventoryItemEvent) =>
              touchedItemKeys += event.key

              event match {
                case ItemDetached(itemKey, _) =>
                  detachedItems += itemKey
                case _ =>
              }

            case KeyedEvent(orderId: OrderId, event: OrderEvent) =>
              touchedOrderIds += orderId
              event match {
                case OrderDeleted =>
                  detachWorkflowCandidates += previous.idToOrder(orderId).workflowId
                case _ =>
              }

            case _ =>
          }
      }
    }

    for (_ <- checked) yield
      (touchedItemKeys, touchedOrderIds, detachWorkflowCandidates, detachedItems, controllerState)
  }

  private def subsequentEvents(
    touchedItemKeys: collection.Set[InventoryItemKey],
    touchedOrderIds: collection.Set[OrderId],
    detachWorkflowCandidates: collection.Set[WorkflowId],
    detachedItems1: collection.Set[InventoryItemKey],
    controllerState: ControllerState)
  : Checked[ControllerStateExecutor] = {
    // Slow ???
    val detachedItems = mutable.Set.empty[InventoryItemKey] ++ detachedItems1
    val controllerStateBeforeSubsequentEvents = controllerState
    val itemEvents = ControllerStateExecutor(controllerState)
      .nextItemAttachedStateEvents(touchedItemKeys)
      .toVector
    controllerState
      .applyEvents(itemEvents)
      .flatMap { controllerState_ =>
        var controllerState = controllerState_
        val makeWorkflowsDetachable = detachWorkflowCandidates
          .view
          .filter(controllerState.keyToItem.keySet.contains)
          .filter(controllerState.isObsoleteItem)
          .flatMap(workflowId =>
            controllerState.itemToAgentToAttachedState
              .get(workflowId)
              .view
              .flatMap(_.collect {
                // Workflows are never Attachable, only Attached or Detachable
                case (agentPath, Attached(_)) =>
                  detachedItems += workflowId
                  NoKey <-: ItemDetachable(workflowId, agentPath)
              }))
          .toVector

        val deleteItemKeys = (detachWorkflowCandidates.view ++ detachedItems)
          .filter(controllerState.keyToItem.contains)
          .filterNot(controllerState.itemToAgentToAttachedState.contains)
          .filter {
            case WorkflowId.as(workflowId) =>
              controllerState.isObsoleteItem(workflowId)
            case path: SimpleItemPath =>
              !controllerState.isReferenced(path)
            case _ =>
              false
          }
          .map(itemKey => NoKey <-: ItemDeleted(itemKey))
          .toVector
        controllerState = controllerState.applyEvents(makeWorkflowsDetachable.view ++ deleteItemKeys)
          .orThrow

        val eventsAndState = controllerState.nextOrderEvents(touchedOrderIds)
        controllerState = eventsAndState.controllerState
        val orderEvents = eventsAndState.keyedEvents

        val orderWatchEvents = controllerState.nextOrderWatchOrderEvents
        controllerState = controllerState.applyEvents(orderWatchEvents)
          .orThrow

        val subsequentKeyedEvents = Vector.concat(
          itemEvents.view,
          makeWorkflowsDetachable,
          deleteItemKeys,
          orderEvents,
          orderWatchEvents)

        // Loop to derive further events from the just derived events
        if (subsequentKeyedEvents.nonEmpty)
          controllerStateBeforeSubsequentEvents
            .applyEventsAndReturnSubsequentEvents(subsequentKeyedEvents)
            .map(o => ControllerStateExecutor(subsequentKeyedEvents ++ o.keyedEvents, o.controllerState))
        else
          Right(new ControllerStateExecutor(subsequentKeyedEvents, controllerState))
    }
  }

  private def nextItemAttachedStateEvents(itemKeys: Iterable[InventoryItemKey])
  : View[KeyedEvent[ItemAttachedStateEvent]] =
    itemKeys
      .view
      .flatMap(controllerState.keyToItem.get)
      .flatMap(nextItemAttachedStateEventsForItem)
      .map(NoKey <-: _)

  private def nextItemAttachedStateEventsForItem(item: InventoryItem)
  : Iterable[ItemAttachedStateEvent] =
    controllerState.itemToAgentToAttachedState.get(item.key) match {
      case Some(agentPathToAttached) =>
        agentPathToAttached.view.flatMap {
          case (agentPath, Attached(revision)) =>
            def detachable =
              if (controllerState.pathToAgentRefState.contains(agentPath))
                ItemDetachable(item.key, agentPath)
              else // shortcut in case, the Agent has been deleted (reset)
                ItemDetached(item.key, agentPath)

            item.key match {
              case itemId: VersionedItemId_ =>
                controllerState.isObsoleteItem(itemId) ? detachable

              case path: SimpleItemPath =>
                if (controllerState.deletionMarkedItems.contains(path))
                  Some(detachable)
                else
                  (item.itemRevision != revision) ?
                    (item.dedicatedAgentPath match {
                      case Some(`agentPath`) | None =>
                        // Item is dedicated to this Agent or is required by an Order.
                        // Attach again without detaching, and let Agent change the item while in flight
                        ItemAttachable(path, agentPath)
                      case Some(_) =>
                        // Item's Agent dedication has changed, so we detach it
                        detachable
                    })
            }

          case (_, Attachable | Detachable) =>
            None
        }

      case None =>
        if (controllerState.deletionMarkedItems.contains(item.key))
          Nil
        else
          item.dedicatedAgentPath.map(ItemAttachable(item.key, _))
    }

  def nextOrderWatchOrderEvents: View[KeyedEvent[OrderCoreEvent]] =
    controllerState.allOrderWatchesState
      .nextEvents(addOrder(_, _))
      .filter {
        case KeyedEvent(orderId: OrderId, OrderDeletionMarked) =>
          // OrderWatchState emits OrderDeletionMarked without knowledge of the order
          controllerState.idToOrder.get(orderId).exists(o => !o.deleteWhenTerminated)
        case _ => true
      }

  def nextOrderEvents(orderIds: Iterable[OrderId]): ControllerStateExecutor = {
    var controllerState = this.controllerState
    val queue = mutable.Queue.empty[OrderId] ++= orderIds
    val _keyedEvents = Vector.newBuilder[KeyedEvent[OrderCoreEvent]]
    @tailrec def loop(): Unit = {
      queue.removeHeadOption() match {
        case Some(orderId) =>
          if (controllerState.idToOrder contains orderId) {
            val keyedEvents = new OrderEventSource(controllerState).nextEvents(orderId)
            for (KeyedEvent(orderId, OrderBroken(problem)) <- keyedEvents) {
              scribe.error(s"Order '${orderId.string}' is broken: $problem") // ???
            }
            controllerState.applyEvents(keyedEvents) match {
              case Left(problem) =>
                scribe.error(s"$orderId: $problem")  // Should not happen
              case Right(state) =>
                controllerState = state
                _keyedEvents ++= keyedEvents
                queue ++= keyedEvents.view
                  .flatMap(ControllerStateExecutor(controllerState).keyedEventToOrderIds)
                  .toSeq.distinct
            }
          }
          loop()
        case None =>
      }
    }
    loop()
    new ControllerStateExecutor(_keyedEvents.result(), controllerState)
  }

  private def keyedEventToOrderIds(keyedEvent: KeyedEvent[OrderEvent]): View[OrderId] =
    View(keyedEvent.key) ++ (keyedEvent.event match {
      case OrderLockEvent(lockPaths) =>
        lockPaths.view
          .flatMap(controllerState.pathToLockState.get)
          .flatMap(_.firstQueuedOrderId)

      case OrderForked(children) =>
        children.view.map(_.orderId)

      case orderOrderAdded: OrderOrderAdded =>
        new View.Single(orderOrderAdded.orderId)

      case _ => View.empty
    })
}

object ControllerStateExecutor
{
  def apply(controllerState: ControllerState)(implicit service: InstructionExecutorService)
  : ControllerStateExecutor =
    new ControllerStateExecutor(Nil, controllerState)(service)

  implicit def convertImplicitly(controllerState: ControllerState)
    (implicit ies: InstructionExecutorService)
  : ControllerStateExecutor =
    apply(controllerState)
}
