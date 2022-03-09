package js7.agent.subagent

import akka.actor.ActorSystem
import cats.effect.Resource
import cats.syntax.foldable._
import cats.syntax.parallel._
import cats.syntax.traverse._
import js7.agent.configuration.AgentConfiguration
import js7.agent.data.AgentState
import js7.agent.subagent.SubagentKeeper._
import js7.base.io.process.ProcessSignal
import js7.base.io.process.ProcessSignal.SIGKILL
import js7.base.log.Logger
import js7.base.log.Logger.syntax._
import js7.base.monixutils.MonixBase.syntax.{RichCheckedTask, RichMonixTask}
import js7.base.monixutils.{AsyncMap, AsyncVariable}
import js7.base.problem.Checked._
import js7.base.problem.{Checked, Problem}
import js7.base.time.{DelayIterator, DelayIterators}
import js7.base.utils.Collections.RichMap
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.SetOnce
import js7.data.agent.AgentPath
import js7.data.controller.ControllerId
import js7.data.event.{KeyedEvent, Stamped}
import js7.data.order.OrderEvent.{OrderCoreEvent, OrderProcessed, OrderProcessingStarted, OrderStarted}
import js7.data.order.{Order, OrderId, Outcome}
import js7.data.subagent.{SubagentId, SubagentRef, SubagentRefState, SubagentSelection, SubagentSelectionId}
import js7.journal.state.StatePersistence
import js7.launcher.configuration.JobLauncherConf
import js7.subagent.LocalSubagentDriver
import js7.subagent.client.SubagentDriver
import monix.eval.{Coeval, Task}
import monix.reactive.Observable
import scala.concurrent.Promise

final class SubagentKeeper(
  agentPath: AgentPath,
  persistence: StatePersistence[AgentState],
  jobLauncherConf: JobLauncherConf,
  agentConf: AgentConfiguration,
  actorSystem: ActorSystem)
{
  private var reconnectDelayer: DelayIterator = null
  private val legacyLocalSubagentId = SubagentId.legacyLocalFromAgentPath(agentPath) // COMPATIBLE with v2.2
  private val driverConf = SubagentDriver.Conf.fromConfig(agentConf.config,
    commitDelay = agentConf.journalConf.delay)
  /** defaultPrioritized is used when no SubagentSelectionId is given. */
  private val defaultPrioritized = Prioritized.empty[SubagentId](
    toPriority = _ => 0/*same priority for each entry, round-robin*/)
  private val initialized = SetOnce[Initialized]
  private val state = AsyncVariable[State](State(Map.empty, Map(None -> defaultPrioritized)))
  private val orderToWaitForSubagent = AsyncMap.empty[OrderId, Promise[Unit]]
  private val orderToSubagent = AsyncMap.empty[OrderId, SubagentDriver]

  def initialize(localSubagentId: Option[SubagentId], controllerId: ControllerId): Task[Unit] =
    Task.deferAction { scheduler =>
      reconnectDelayer = DelayIterators
        .fromConfig(agentConf.config, "js7.subagent-driver.reconnect-delays")(scheduler)
        .orThrow

      val initialized = Initialized(agentPath, localSubagentId, controllerId)
      this.initialized := initialized
      if (localSubagentId.isDefined)
        Task.unit
      else // COMPATIBLE with v2.2 which does not know Subagents
        state
          .update(state => Task(state
            .insertSubagentDriver(newLocalSubagentDriver(legacyLocalSubagentId, initialized))
            .orThrow))
          .void
    }

  def start: Task[Unit] =
    logger.debugTask(Task.defer {
      initialized.orThrow
      state.get.idToDriver.values
        .toVector
        .parUnorderedTraverse(_.start)
        .map(_.combineAll)
    })

  def stop: Task[Unit] =
    state
      .updateWithResult(state => Task(
        state.clear -> state.idToDriver.values))
      .flatMap(drivers =>
        drivers.toVector
          .parUnorderedTraverse(_.stop(Some(SIGKILL)))
          .map(_.combineAll))

  def orderIsLocal(orderId: OrderId): Boolean =
    orderToSubagent.toMap.get(orderId)
      .exists(_.isInstanceOf[LocalSubagentDriver[_]])

  def processOrder(
    order: Order[Order.IsFreshOrReady],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[Unit]] =
    selectSubagentDriverCancelable(order.id).flatMapT {
      case None =>
        logger.debug(s"${order.id} has been canceled while selecting a Subagent")
        Task.right(())

      case Some(driver) =>
        processOrderAndForwardEvents(order, onEvents, driver)
    }

  private def processOrderAndForwardEvents(
    order: Order[Order.IsFreshOrReady],
    onEvents: Seq[OrderCoreEvent] => Unit,
    subagentDriver: SubagentDriver)
  : Task[Checked[Unit]] = {
    // TODO Race with CancelOrders ?
    val startedEvents = order.isState[Order.Fresh].thenList(OrderStarted) :::
      OrderProcessingStarted(subagentDriver.subagentId) :: Nil
    persist(order.id, startedEvents, onEvents)
      .map(_.map { case (_, s) =>
        s.idToOrder
          .checked(order.id)
          .flatMap(_.checkedState[Order.Processing])
          .orThrow
      })
    .flatMapT(order =>
      forProcessingOrder(order.id, subagentDriver, onEvents)(
        subagentDriver.processOrder(order)))
    .onErrorHandle { t =>
      logger.error(s"processOrder ${order.id} => ${t.toStringWithCauses}", t.nullIfNoStackTrace)
      Left(Problem.fromThrowable(t))
    }
  }

  def continueProcessingOrder(
    order: Order[Order.Processing],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[Unit]] =
    logger.traceTask("continueProcessingOrder", order.id)(Task.defer {
      val subagentId = order.state.subagentId getOrElse legacyLocalSubagentId
      state.get.idToDriver.get(subagentId)
        .match_ {
          case None =>
            val event = OrderProcessed(Outcome.Disrupted(Problem.pure(
              s"Missing $subagentId SubagentDriver for processing ${ order.id }")))
            persist(order.id, event :: Nil, onEvents)
              .map(_.map { case (_, s) => event -> s })

          case Some(subagentDriver) =>
            forProcessingOrder(order.id, subagentDriver, onEvents)(
              subagentDriver.continueProcessingOrder(order)
            ).materializeIntoChecked
        }
    })

  private def persist(
    orderId: OrderId,
    events: Seq[OrderCoreEvent],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[(Seq[Stamped[KeyedEvent[OrderCoreEvent]]], AgentState)]] =
    persistence
      .persistKeyedEvents(events.map(orderId <-: _))
      .map(_.map { o =>
        onEvents(events)
        o
      })

  private def forProcessingOrder(orderId: OrderId, subagentDriver: SubagentDriver, onEvents: Seq[OrderCoreEvent] => Unit)
    (body: Task[Checked[OrderProcessed]])
  : Task[Checked[Unit]] =
    Resource.make(
      acquire = orderToSubagent.put(orderId, subagentDriver).void)(
      release = _ => orderToSubagent.remove(orderId).void
    ).use(_ =>
      body.map(_.map(processed => onEvents(processed :: Nil)))
    )

  private def selectSubagentDriverCancelable(orderId: OrderId)
  : Task[Checked[Option[SubagentDriver]]] =
    orderIdSubagentSelectionId(orderId)
      .flatMapT(maybeSelectionId =>
        cancelableWhileWaitingForSubagent(orderId)
          .use(canceledPromise =>
            Task.race(
              Task.fromFuture(canceledPromise.future),
              selectSubagentDriver(maybeSelectionId)))
          .map(_.toOption)
          .map(Right(_)))

  private def orderIdSubagentSelectionId(orderId: OrderId)
  : Task[Checked[Option[SubagentSelectionId]]] =
    for (agentState <- persistence.state) yield
      for {
        order <- agentState.idToOrder.checked(orderId)
        job <- agentState.workflowJob(order.workflowPosition)
      } yield job.subagentSelectionId

  /** While waiting for a Subagent, the Order is cancelable. */
  private def cancelableWhileWaitingForSubagent(orderId: OrderId): Resource[Task, Promise[Unit]] =
    Resource
      .eval(Task(Promise[Unit]()))
      .flatMap(canceledPromise =>
        Resource.make(
          acquire = orderToWaitForSubagent.put(orderId, canceledPromise))(
          release = _ => orderToWaitForSubagent.remove(orderId).void))

  private def selectSubagentDriver(maybeSelectionId: Option[SubagentSelectionId])
  : Task[SubagentDriver] =
    Observable
      .repeatEvalF(Coeval {
        state.get.selectNext(maybeSelectionId)
      })
      .delayOnNextBySelector {
        case None => Observable.unit.delayExecution(reconnectDelayer.next())
        case Some(_) =>
          reconnectDelayer.reset()
          Observable.empty
      }
      .flatMap(Observable.fromIterable(_))
      .headL

  def killProcess(orderId: OrderId, signal: ProcessSignal): Task[Unit] =
    Task.defer {
      // TODO Race condition?
      orderToWaitForSubagent
        .get(orderId)
        .fold(Task.unit)(promise => Task(promise.success(())))
        .*>(orderToSubagent
          .get(orderId)
          .match_ {
            case None => Task(logger.warn(s"killProcess($orderId): Unknown OrderId"))
            case Some(driver) => driver.killProcess(orderId, signal)
          })
    }

  def removeSubagent(subagentId: SubagentId): Task[Checked[Unit]] =
    logger.debugTask("removeSubagent", subagentId)(Task.defer {
      state.get.idToDriver
        .get(subagentId)
        .fold(Task.right(()))(subagentDriver =>
          subagentDriver.shutdown
            .*>(state
              .update(state => Task(
                state.removeSubagent(subagentId))))
            .*>(subagentDriver.stop(signal = None))
            .map(Right(_)))
    })

  def recoverSubagents(subagentRefStates: Seq[SubagentRefState]): Task[Checked[Unit]] =
    subagentRefStates
      .traverse(addOrChange)
      .map(_
        .collectFirst { case Left(problem) => Left(problem) }
        .getOrElse(Checked.unit))

  // TODO Kann SubagentRef gelöscht werden während proceed hängt wegen unerreichbarem Subagenten?
  def proceedWithSubagent(subagentRefState: SubagentRefState): Task[Checked[Unit]] =
    logger.traceTask("proceedWithSubagent", subagentRefState.subagentId)(
      addOrChange(subagentRefState)
        .flatMapT(_
          .fold(Task.unit)(_
            .start
            .onErrorHandle(t => logger.error( // TODO Emit event ?
              s"proceedWithSubagent(${subagentRefState.subagentId}) => ${t.toStringWithCauses}"))
            .startAndForget)
          .map(Right(_))))

  // Returns a SubagentDriver if created
  private def addOrChange(subagentRefState: SubagentRefState)
  : Task[Checked[Option[SubagentDriver]]] =
    logger.traceTask("addOrChange", subagentRefState.subagentId) {
      val subagentRef = subagentRefState.subagentRef
      initialized.task
        .logWhenItTakesLonger("SubagentKeeper.initialized?")
        .flatMap(initialized =>
          state
            .updateCheckedWithResult(state =>
              state.idToDriver
                .get(subagentRef.id)
                .match_ {
                  case Some(_: LocalSubagentDriver[_]) =>
                    Task.pure(
                      for (updated <- state.disable(subagentRef.id, subagentRef.disabled)) yield
                        updated -> None)

                  case Some(existing: RemoteSubagentDriver) =>
                    Task
                      .when(subagentRef.uri != existing.subagentRef.uri)(
                        existing
                          .stop(Some(SIGKILL))
                          .onErrorHandle(t => logger.error("After change of URI of " +
                            existing.subagentId + " " + existing.subagentRef.uri + ": " +
                            t.toStringWithCauses))
                          .startAndForget) // Do not block the `state` here, so startAndForget ???
                      .map { _ =>
                        // TODO subagentRef.disable!
                        // Replace and forget the existing driver
                        val driver = newRemoteSubagentDriver(subagentRef, initialized)
                        for (updated <- state.insertSubagentDriver(driver, subagentRef)) yield
                          updated -> Some(driver)
                      }

                  case None =>
                    Task {
                      val subagentDriver = newSubagentDriver(subagentRef, initialized)
                      for (s <- state.insertSubagentDriver(subagentDriver, subagentRef)) yield
                        s -> Some(subagentDriver)
                    }
                }))
    }

  private def newSubagentDriver(subagentRef: SubagentRef, initialized: Initialized) =
    if (initialized.localSubagentId contains subagentRef.id)
      newLocalSubagentDriver(subagentRef.id, initialized)
    else
      newRemoteSubagentDriver(subagentRef, initialized)

  private def newLocalSubagentDriver(subagentId: SubagentId, initialized: Initialized) =
    new LocalSubagentDriver(
      subagentId,
      persistence,
      initialized.agentPath,
      initialized.controllerId,
      jobLauncherConf,
      driverConf,
      agentConf.subagentConf)

  private def newRemoteSubagentDriver(subagentRef: SubagentRef, initialized: Initialized) =
    new RemoteSubagentDriver(
      subagentRef,
      agentConf.httpsConfig,
      persistence,
      initialized.controllerId,
      driverConf,
      agentConf.subagentConf,
      agentConf.recouplingStreamReaderConf,
      actorSystem)

  def addOrReplaceSubagentSelection(selection: SubagentSelection): Task[Checked[Unit]] =
    state
      .updateChecked(state => Task(state.insertSelection(selection)))
      .rightAs(())

  def removeSubagentSelection(subagentSelectionId: SubagentSelectionId): Task[Unit] =
    state
      .update(state => Task(state.removeSelection(subagentSelectionId)))
      .void

  override def toString = s"SubagentKeeper(${orderToSubagent.size} processing orders)"
}

object SubagentKeeper
{
  private val logger = Logger[this.type]
  private val DefaultPriority = 0

  private case class Initialized(
    agentPath: AgentPath,
    localSubagentId: Option[SubagentId],
    controllerId: ControllerId)

  private final case class State(
    subagentToEntry: Map[SubagentId, Entry],
    selectionToPrioritized: Map[Option[SubagentSelectionId], Prioritized[SubagentId]])
  {
    def idToDriver = subagentToEntry.view.mapValues(_.driver)

    def insertSubagentDriver(driver: SubagentDriver, subagentRef: SubagentRef): Checked[State] =
      insertSubagentDriver(driver, subagentRef.disabled)

    def insertSubagentDriver(driver: SubagentDriver, disabled: Boolean = false): Checked[State] =
      for {
        idToE <- subagentToEntry.insert(driver.subagentId -> Entry(driver, disabled))
        selectionToPrioritized <-
          if (disabled)
            Right(selectionToPrioritized)
          else
            // Add SubagentId to default SubagentSelection
            for (o <- selectionToPrioritized(None).add(driver.subagentId)) yield
              selectionToPrioritized.updated(None, o)
      } yield copy(
        subagentToEntry = idToE,
        selectionToPrioritized = selectionToPrioritized)

    def removeSubagent(subagentId: SubagentId): State =
      copy(
        subagentToEntry = subagentToEntry.removed(subagentId),
        // Remove SubagentId from default SubagentSelection
        selectionToPrioritized =
          selectionToPrioritized.updated(None, selectionToPrioritized(None).remove(subagentId)))

    def insertSelection(selection: SubagentSelection): Checked[State] =
      selectionToPrioritized
        .insert(
          Some(selection.id) ->
            Prioritized[SubagentId](
              selection.subagentToPriority.keys,
              id => selection.subagentToPriority.getOrElse(id, {
                logger.error(s"${selection.id} uses unknown $id. Assuming priority=$DefaultPriority")
                DefaultPriority
              })))
        .map(o => copy(
          selectionToPrioritized = o))

    def removeSelection(selectionId: SubagentSelectionId): State =
      copy(selectionToPrioritized = selectionToPrioritized - Some(selectionId))

    def clear: State =
      copy(
        subagentToEntry = Map.empty,
        selectionToPrioritized = Map.empty)

    def disable(id: SubagentId, disabled: Boolean): Checked[State] =
      subagentToEntry
        .get(id)
        .filter(_.disabled != disabled)
        .fold(Checked(this)) { entry =>
          val checkedSelToPrio =
            if (disabled)
              Right(selectionToPrioritized.view.mapValues(_.remove(id)).toMap)
            else
              selectionToPrioritized.toVector
                .traverse { case (k, v) => v.add(id).map(k -> _) }
                .map(_.toMap)
          for (selToPrio <- checkedSelToPrio) yield
            copy(
              subagentToEntry = subagentToEntry.updated(id, entry.copy(disabled = disabled)),
              selectionToPrioritized = selToPrio)
        }

    def selectNext(selectionId: Option[SubagentSelectionId]): Option[SubagentDriver] =
      selectionToPrioritized(selectionId)
        .selectNext(subagentId => subagentToEntry.get(subagentId).fold(false)(_.driver.isAcceptingOrder))
        .flatMap(subagentId => subagentToEntry.get(subagentId).map(_.driver))
  }

  private final case class Entry(
    driver: SubagentDriver,
    disabled: Boolean = false)

  private final case class SelectionEntry(
    subagentSelectionId: SubagentSelectionId,
    fixedPriority: FixedPriority/*mutable*/)
}
