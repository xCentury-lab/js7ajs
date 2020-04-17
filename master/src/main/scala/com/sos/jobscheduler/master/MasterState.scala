package com.sos.jobscheduler.master

import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.utils.Assertions.assertThat
import com.sos.jobscheduler.base.utils.ScalaUtils.RichPartialFunction
import com.sos.jobscheduler.base.utils.ScalazStyle._
import com.sos.jobscheduler.core.event.journal.BabyJournaledState
import com.sos.jobscheduler.core.filebased.Repo
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.cluster.ClusterEvent
import com.sos.jobscheduler.data.event.KeyedEvent.NoKey
import com.sos.jobscheduler.data.event.{Event, EventId, JournalEvent, JournaledState, KeyedEvent}
import com.sos.jobscheduler.data.filebased.RepoEvent
import com.sos.jobscheduler.data.master.MasterFileBaseds
import com.sos.jobscheduler.data.master.MasterFileBaseds.MasterTypedPathCompanions
import com.sos.jobscheduler.data.order.OrderEvent.{OrderAdded, OrderCancelled, OrderCoreEvent, OrderFinished, OrderForked, OrderJoined, OrderStdWritten}
import com.sos.jobscheduler.data.order.{Order, OrderEvent, OrderId}
import com.sos.jobscheduler.master.data.MasterSnapshots.MasterMetaState
import com.sos.jobscheduler.master.data.agent.{AgentEventIdEvent, AgentSnapshot}
import com.sos.jobscheduler.master.data.events.MasterAgentEvent.{AgentCouplingFailed, AgentReady, AgentRegisteredMaster}
import com.sos.jobscheduler.master.data.events.MasterEvent.MasterTestEvent
import com.sos.jobscheduler.master.data.events.{MasterAgentEvent, MasterEvent}
import monix.reactive.Observable

/**
  * @author Joacim Zschimmer
  */
final case class MasterState(
  eventId: EventId,
  babyJournaledState: BabyJournaledState,
  masterMetaState: MasterMetaState,
  repo: Repo,
  pathToAgentSnapshot: Map[AgentRefPath, AgentSnapshot],
  idToOrder: Map[OrderId, Order[Order.State]])
extends JournaledState[MasterState, Event]
{
  assertThat(eventId == babyJournaledState.eventId)

  def toSnapshotObservable: Observable[Any] =
    babyJournaledState.toSnapshotObservable ++
    Observable.fromIterable(masterMetaState.isDefined ? masterMetaState) ++
    Observable.fromIterable(repo.eventsFor(MasterTypedPathCompanions)) ++
    Observable.fromIterable(pathToAgentSnapshot.values) ++
    Observable.fromIterable(idToOrder.values)

  def journalState = babyJournaledState.journalState

  def clusterState = babyJournaledState.clusterState

  def withEventId(eventId: EventId) =
    copy(
      eventId = eventId,
      babyJournaledState = babyJournaledState.copy(
        eventId = eventId))

  def applyEvent(keyedEvent: KeyedEvent[Event]) = keyedEvent match {
    case KeyedEvent(_: NoKey, MasterEvent.MasterInitialized(masterId, startedAt)) =>
      Right(copy(masterMetaState = masterMetaState.copy(
        masterId = masterId,
        startedAt = startedAt)))

    case KeyedEvent(_: NoKey, _: MasterEvent.MasterReady) =>
      Right(this)  // TODO MasterReady not handled ?

    case KeyedEvent(_: NoKey, event: RepoEvent) =>
      for (o <- repo.applyEvent(event)) yield
        copy(repo = o)

    case KeyedEvent(agentRefPath: AgentRefPath, event: MasterAgentEvent) =>
      event match {
        case AgentRegisteredMaster(agentRunId) =>
          if (pathToAgentSnapshot contains agentRefPath)
            Left(Problem(s"Duplicate '$agentRefPath'"))
          else
            Right(copy(
              pathToAgentSnapshot = pathToAgentSnapshot +
                (agentRefPath -> AgentSnapshot(agentRefPath, Some(agentRunId), eventId = EventId.BeforeFirst))))

        case _: AgentReady | _: AgentCouplingFailed =>
          Right(this)
      }

    case KeyedEvent(a: AgentRefPath, AgentEventIdEvent(agentEventId)) =>
      // Preceding AgentSnapshot is required (see recoverSnapshot)
      for (o <- pathToAgentSnapshot.checked(a)) yield
        copy(pathToAgentSnapshot = pathToAgentSnapshot + (a -> o.copy(eventId = agentEventId)))

    case KeyedEvent(orderId: OrderId, event: OrderEvent) =>
      event match {
        case event: OrderAdded =>
          if (idToOrder contains orderId)
            Left(Problem(s"Duplicate '$orderId'"))
          else
            Right(copy(idToOrder = idToOrder + (orderId -> Order.fromOrderAdded(orderId, event))))

        case OrderFinished | OrderCancelled =>
          Right(copy(idToOrder = idToOrder - orderId))

        case event: OrderCoreEvent =>
          for {
            previous <- idToOrder.checked(orderId)
            updated <- previous.update(event)
            childOrdersAddedOrRemoved <- event match {
              case event: OrderForked =>
                Right(idToOrder ++ (
                  for (childOrder <- idToOrder(orderId).newForkedOrders(event)) yield
                    childOrder.id -> childOrder))

              case event: OrderJoined =>
                idToOrder(orderId).state match {
                  case forked: Order.Forked =>
                    Right(idToOrder -- forked.childOrderIds)

                  case state =>
                    Left(Problem(s"For event $event, $orderId must be in state Forked, not: $state"))
                }

              case _ => Right(idToOrder)
            }
          } yield
            copy(idToOrder = childOrdersAddedOrRemoved + (updated.id -> updated))

        case _: OrderStdWritten =>
          Right(this)
      }

    case KeyedEvent(_, MasterTestEvent) =>
      Right(this)

    case KeyedEvent(_, _: JournalEvent | _: ClusterEvent) =>
      for (o <- babyJournaledState.applyEvent(keyedEvent))
        yield copy(babyJournaledState = o)

    case _ => eventNotApplicable(keyedEvent)
  }

  override def toString =
    s"MasterState(${EventId.toString(eventId)} ${idToOrder.size} orders, Repo(${repo.currentVersion.size} objects, ...))"
}

object MasterState
{
  val Undefined = MasterState(
    EventId.BeforeFirst,
    BabyJournaledState.empty,
    MasterMetaState.Undefined,
    Repo(MasterFileBaseds.jsonCodec),
    Map.empty,
    Map.empty)

  def fromIterator(snapshotObjects: Iterator[Any])
  : MasterState = {
    val builder = new MasterStateBuilder
    snapshotObjects foreach builder.addSnapshot
    builder.state
  }
}
