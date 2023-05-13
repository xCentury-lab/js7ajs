package js7.subagent.director

import akka.actor.ActorSystem
import cats.effect.Resource
import cats.implicits.catsSyntaxParallelUnorderedTraverse
import cats.instances.option.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.typesafe.config.ConfigUtil
import izumi.reflect.Tag
import js7.base.auth.{Admission, UserAndPassword}
import js7.base.configutils.Configs.ConvertibleConfig
import js7.base.eventbus.StandardEventBus
import js7.base.generic.SecretString
import js7.base.io.process.ProcessSignal
import js7.base.io.process.ProcessSignal.SIGKILL
import js7.base.log.Logger.syntax.*
import js7.base.log.{CorrelId, Logger}
import js7.base.monixutils.MonixBase.syntax.*
import js7.base.monixutils.{AsyncMap, AsyncVariable}
import js7.base.problem.Checked.*
import js7.base.problem.{Checked, Problem}
import js7.base.thread.IOExecutor
import js7.base.time.{DelayIterator, DelayIterators}
import js7.base.utils.CatsUtils.syntax.{RichF, RichResource}
import js7.base.utils.ScalaUtils.syntax.*
import js7.base.utils.{Allocated, LockKeeper}
import js7.base.web.Uri
import js7.common.system.PlatformInfos.currentPlatformInfo
import js7.data.agent.AgentPath
import js7.data.controller.ControllerId
import js7.data.delegate.DelegateCouplingState.Coupled
import js7.data.event.{KeyedEvent, Stamped}
import js7.data.item.BasicItemEvent.ItemDetached
import js7.data.order.OrderEvent.{OrderCoreEvent, OrderProcessed, OrderProcessingStarted, OrderStarted}
import js7.data.order.{Order, OrderId, Outcome}
import js7.data.subagent.Problems.ProcessLostDueSubagentUriChangeProblem
import js7.data.subagent.SubagentItemStateEvent.{SubagentCoupled, SubagentDedicated, SubagentResetStarted}
import js7.data.subagent.{SubagentDirectorState, SubagentId, SubagentItem, SubagentItemState, SubagentSelection, SubagentSelectionId}
import js7.journal.state.Journal
import js7.subagent.configuration.DirectorConf
import js7.subagent.director.SubagentKeeper.*
import js7.subagent.{LocalSubagentApi, Subagent}
import monix.eval.{Coeval, Fiber, Task}
import monix.execution.Scheduler
import monix.reactive.Observable
import org.jetbrains.annotations.TestOnly
import scala.concurrent.Promise

final class SubagentKeeper[S <: SubagentDirectorState[S]: Tag](
  localSubagentId: Option[SubagentId],
  agentPath: AgentPath,
  controllerId: ControllerId,
  failedOverSubagentId: Option[SubagentId],
  journal: Journal[S],
  directorConf: DirectorConf,
  iox: IOExecutor,
  actorSystem: ActorSystem,
  testEventBus: StandardEventBus[Any])
  (implicit scheduler: Scheduler)
{
  private val reconnectDelayer: DelayIterator = DelayIterators
    .fromConfig(directorConf.config, "js7.subagent-driver.reconnect-delays")(scheduler)
    .orThrow
  private val legacyLocalSubagentId = SubagentId.legacyLocalFromAgentPath(agentPath) // COMPATIBLE with v2.2
  private val driverConf = RemoteSubagentDriver.Conf.fromConfig(directorConf.config,
    commitDelay = directorConf.journalConf.delay)
  /** defaultPrioritized is used when no SubagentSelectionId is given. */
  private val defaultPrioritized = Prioritized.empty[SubagentId](
    toPriority = _ => 0/*same priority for each entry, round-robin*/)
  private val stateVar = AsyncVariable[DirectorState](DirectorState(Map.empty, Map(
    /*local Subagent*/None -> defaultPrioritized)))
  private val orderToWaitForSubagent = AsyncMap.empty[OrderId, Promise[Unit]]
  private val orderToSubagent = AsyncMap.empty[OrderId, SubagentDriver]
  private val subagentItemLockKeeper = new LockKeeper[SubagentId]

  def start: Task[Unit] =
    logger.debugTask(
      Task.defer {
        if (localSubagentId.isDefined)
          Task.unit
        else // COMPATIBLE with v2.2 which does not know Subagents
          stateVar
            .update { state =>
              val subagentItem = SubagentItem(legacyLocalSubagentId, agentPath,
                uri = Uri("http://127.0.0.1:99999"/*dummy???*/))
              allocateLocalSubagentDriver(subagentItem)
                .map(allocatedDriver =>
                  state.insertSubagentDriver(allocatedDriver))
                .orThrow
            }
            .void
    })

  def stop: Task[Unit] =
    logger.traceTask(
      stateVar
        .updateWithResult(state => Task(
          state.clear -> state.subagentToEntry.values))
        .flatMap(entries =>
          entries.toVector
            .parUnorderedTraverse(_.terminate(Some(SIGKILL)))
            .map(_.combineAll)))

  def orderIsLocal(orderId: OrderId): Boolean =
    orderToSubagent.toMap.get(orderId).exists(_.isLocal)

  def processOrder(
    order: Order[Order.IsFreshOrReady],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[Unit]] =
    selectSubagentDriverCancelable(order)
      .flatMap {
        case Left(problem) =>
          // Maybe suppress when this SubagentKeeper has been stopped ???
          Task.defer {
            // ExecuteExecutor should have prechecked this:
            val events = order.isState[Order.Fresh].thenList(OrderStarted) :::
              // TODO Emit OrderFailedIntermediate_ instead, but this is not handled by this version
              OrderProcessingStarted(None) ::
              OrderProcessed(Outcome.Disrupted(problem)) :: Nil
            persist(order.id, events, onEvents)
              .rightAs(())
          }

        case Right(None) =>
          logger.debug(s"⚠️ ${order.id} has been canceled while selecting a Subagent")
          Task.right(())

        case Right(Some(selectedDriver)) =>
          processOrderAndForwardEvents(order, onEvents, selectedDriver)
    }

  private def processOrderAndForwardEvents(
    order: Order[Order.IsFreshOrReady],
    onEvents: Seq[OrderCoreEvent] => Unit,
    selectedDriver: SelectedDriver)
  : Task[Checked[Unit]] = {
    // TODO Race with CancelOrders ?
    import selectedDriver.{stick, subagentDriver}

    val events = order.isState[Order.Fresh].thenList(OrderStarted) :::
      OrderProcessingStarted(subagentDriver.subagentId, stick = stick) :: Nil
    persist(order.id, events, onEvents)
      .map(_.map { case (_, s) => s
        .idToOrder
        .checked(order.id)
        .flatMap(_.checkedState[Order.Processing])
        .orThrow
      })
      .flatMapT(order =>
        forProcessingOrder(order.id, subagentDriver, onEvents)(
          subagentDriver.startOrderProcessing(order)))
      .onErrorHandle { t =>
        logger.error(s"startOrderProcess ${order.id} => ${t.toStringWithCauses}", t.nullIfNoStackTrace)
        Left(Problem.fromThrowable(t))
      }
      .containsType[Checked[Fiber[OrderProcessed]]]
      .rightAs(())
  }

  def recoverOrderProcessing(
    order: Order[Order.Processing],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[Fiber[OrderProcessed]]] =
    logger.traceTask("recoverOrderProcessing", order.id)(Task.defer {
      val subagentId = order.state.subagentId getOrElse legacyLocalSubagentId
      stateVar.get.idToDriver.get(subagentId)
        .match_ {
          case None =>
            val orderProcessed = OrderProcessed(Outcome.Disrupted(Problem.pure(
              s"$subagentId is missed")))
            persist(order.id, orderProcessed :: Nil, onEvents)
              .flatMapT(_ => Task.pure(orderProcessed).start.map(Right(_)))

            case Some(subagentDriver) =>
              forProcessingOrder(order.id, subagentDriver, onEvents)(
                if (failedOverSubagentId contains subagentDriver.subagentId)
                  subagentDriver.emitOrderProcessLost(order)
                  .flatMap(_.traverse(orderProcessed => Task.pure(orderProcessed).start))
                else
                  subagentDriver.recoverOrderProcessing(order)
              ).materializeIntoChecked
              .flatTap {
                case Left(problem) => Task(logger.error(
                  s"recoverOrderProcessing ${order.id} => $problem"))
                case Right(_) => Task.unit
              }
          }
    })

  private def persist(
    orderId: OrderId,
    events: Seq[OrderCoreEvent],
    onEvents: Seq[OrderCoreEvent] => Unit)
  : Task[Checked[(Seq[Stamped[KeyedEvent[OrderCoreEvent]]], S)]] =
    journal
      .persistKeyedEvents(events.map(orderId <-: _))
      .map(_.map { o =>
        onEvents(events)
        o
      })

  private def forProcessingOrder(
    orderId: OrderId,
    subagentDriver: SubagentDriver,
    onEvents: Seq[OrderCoreEvent] => Unit)
    (body: Task[Checked[Fiber[OrderProcessed]]])
  : Task[Checked[Fiber[OrderProcessed]]] = {
    val release = orderToSubagent.remove(orderId).void
    orderToSubagent
      .put(orderId, subagentDriver)
      .*>(body
        .flatMap {
          case Left(problem) => Task.left(problem)
          case Right(fiber) =>
            // OrderProcessed event has been persisted by RemoteSubagentDriver
            fiber.join
              .map { orderProcessed =>
                onEvents(orderProcessed :: Nil)
                orderProcessed
              }
              .guarantee(release)
              .start
              .map(Right(_))
        })
      .guaranteeExceptWhenRight(release)
  }

  private def selectSubagentDriverCancelable(order: Order[Order.IsFreshOrReady])
  : Task[Checked[Option[SelectedDriver]]] =
    orderToSubagentSelectionId(order)
      .flatMapT { case DeterminedSubagentSelection(subagentSelectionId, stick) =>
        cancelableWhileWaitingForSubagent(order.id)
          .use(canceledPromise =>
            Task.race(
              Task.fromFuture(CorrelId.current.bind(canceledPromise.future)),
              selectSubagentDriver(subagentSelectionId)))
          .map(_
            .toOption
            .sequence
            .map(_.map(SelectedDriver(_, stick))))
      }

  private def orderToSubagentSelectionId(order: Order[Order.IsFreshOrReady])
  : Task[Checked[DeterminedSubagentSelection]] =
    for (agentState <- journal.state) yield
      for {
        job <- agentState.workflowJob(order.workflowPosition)
        scope <- agentState.toPureOrderScope(order)
        maybeJobsSelectionId <- job.subagentSelectionId
          .traverse(_.evalAsString(scope)
          .flatMap(SubagentSelectionId.checked))
      } yield
        determineSubagentSelection(order, agentPath, maybeJobsSelectionId)

  /** While waiting for a Subagent, the Order is cancelable. */
  private def cancelableWhileWaitingForSubagent(orderId: OrderId): Resource[Task, Promise[Unit]] =
    Resource
      .eval(Task(Promise[Unit]()))
      .flatMap(canceledPromise =>
        Resource.make(
          acquire = orderToWaitForSubagent.put(orderId, canceledPromise))(
          release = _ => orderToWaitForSubagent.remove(orderId).void))

  private def selectSubagentDriver(maybeSelectionId: Option[SubagentSelectionId])
  : Task[Checked[SubagentDriver]] =
    Observable
      .repeatEvalF(Coeval { stateVar.get.selectNext(maybeSelectionId) }
        .flatTap(o => Coeval(
          logger.trace(s"selectSubagentDriver($maybeSelectionId) => $o ${stateVar.get}"))))
      .delayOnNextBySelector {
        // TODO Do not poll (for each Order)
        case Right(None) => Observable.unit.delayExecution(reconnectDelayer.next())
        case _ =>
          reconnectDelayer.reset()
          Observable.empty
      }
      .map(_.sequence)
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
            case None => Task(logger.error(
              s"killProcess($orderId): unexpected internal state: orderToSubagent does not contain the OrderId"))

            case Some(driver) => driver.killProcess(orderId, signal)
          })
    }

  def startResetSubagent(subagentId: SubagentId, force: Boolean): Task[Checked[Unit]] =
    stateVar.value
      .flatMap(s => Task(s.idToDriver.checked(subagentId)))
      .flatMapT {
        case driver: RemoteSubagentDriver =>
          journal.persistKeyedEvent(subagentId <-: SubagentResetStarted(force))
            .flatMapT(_ =>
              driver.reset(force)
                .onErrorHandle(t =>
                  logger.error(s"$subagentId reset => ${t.toStringWithCauses}", t.nullIfNoStackTrace))
                .startAndForget
                .map(Right(_)))
        case _ =>
          Task.pure(Problem.pure(s"$subagentId as the Agent Director cannot be reset"))
      }

  def startRemoveSubagent(subagentId: SubagentId): Task[Unit] =
    removeSubagent(subagentId)
      .onErrorHandle[Unit](t => Task(logger.error(s"removeSubagent($subagentId) => $t")))
      .startAndForget

  private def removeSubagent(subagentId: SubagentId): Task[Unit] =
    logger.debugTask("removeSubagent", subagentId)(
      stateVar.value
        .flatMap(_.idToDriver
          .get(subagentId)
          .fold(Task.unit)(subagentDriver =>
            subagentDriver.tryShutdown
              .*>(stateVar.update(state => Task(
                state.removeSubagent(subagentId))))
              .*>(subagentDriver.terminate(signal = None)))))
              .*>(journal
                .persistKeyedEvent(ItemDetached(subagentId, agentPath))
                .orThrow
                .void)

  def recoverSubagents(subagentItemStates: Seq[SubagentItemState]): Task[Checked[Unit]] =
    subagentItemStates
      .traverse(s => addOrChange(s)
        .map(_.map(_.map(_ => s))))
      .map(_.combineProblems)
      .map(_.map(_.flatten))

  def continueDetaching: Task[Unit] =
    journal.state.flatMap(_
      .idToSubagentItemState.values
      .view
      .collect { case o if o.isDetaching => o.subagentId }
      .toVector
      .traverse(startRemoveSubagent)
      .map(_.combineAll))

  def recoverSubagentSelections(subagentSelections: Seq[SubagentSelection]): Task[Checked[Unit]] =
    logger.debugTask(
      subagentSelections
        .traverse(addOrReplaceSubagentSelection)
        .map(_.combineAll))

  // TODO Kann SubagentItem gelöscht werden während proceed hängt wegen unerreichbaren Subagenten?
  def proceedWithSubagent(subagentItemState: SubagentItemState): Task[Checked[Unit]] =
    logger.traceTask("proceedWithSubagent", subagentItemState.pathRev)(
      addOrChange(subagentItemState)
        .rightAs(()))

  // May return a new, non-started RemoteSubagentDriver
  private def addOrChange(subagentItemState: SubagentItemState)
  : Task[Checked[Option[SubagentDriver]]] =
    logger.debugTask("addOrChange", subagentItemState.pathRev) {
      val subagentItem = subagentItemState.subagentItem
      stateVar.value
        .map(_.idToDriver.get(subagentItem.id))
        .flatMap {
          case Some(driver) if driver.isLocal =>
            stateVar
              .updateChecked(state => Task(
                state.setDisabled(subagentItem.id, subagentItem.disabled)))
              .rightAs(None)

          case _ =>
            // Don't use the matched RemoteAgentDriver. We update state with an atomic operation.
            stateVar.updateCheckedWithResult(state =>
              state.idToAllocatedDriver.get(subagentItem.id) match {
                case None =>
                  allocateSubagentDriver(subagentItem)
                    .map(allocatedDriver =>
                      state.insertSubagentDriver(allocatedDriver, subagentItem)
                        .flatMap(_.setDisabled(subagentItem.id, subagentItem.disabled))
                        .map(_ -> Some(None -> allocatedDriver.allocatedThing)))

                case Some(existingAllo) =>
                  val existingAllocated = existingAllo
                    .asInstanceOf[Allocated[Task, SubagentDriver]]
                  val existingDriver = existingAllocated.allocatedThing
                  Task
                    .defer(
                      if (subagentItem.uri == existingDriver.subagentItem.uri)
                        Task.right(state -> None)
                      else {
                        // Subagent moved
                        subagentDriverResource(subagentItem)
                          .toAllocated
                          .map(allocatedDriver =>
                            state.replaceSubagentDriver(allocatedDriver, subagentItem)
                              .map(_ -> Some(Some(existingAllocated) -> allocatedDriver.allocatedThing)))
                        // Continue after locking updateCheckedWithResult
                      })
                    .flatMapT { case (state, result) =>
                      Task(state
                        .setDisabled(subagentItem.id, subagentItem.disabled)
                        .map(_ -> result))
                    }
              })
        }
        .flatMapT {
          case Some((Some(Allocated(oldDriver: RemoteSubagentDriver, stopOld)), newDriver: RemoteSubagentDriver))
            if !newDriver.isLocal =>
            assert(oldDriver.subagentId == newDriver.subagentId)
            val name = "addOrChange " + oldDriver.subagentItem.pathRev
            oldDriver
              .stopDispatcherAndEmitProcessLostEvents(ProcessLostDueSubagentUriChangeProblem, None)
              .*>(stopOld)  // Maybe try to send Shutdown command ???
              .*>(subagentItemLockKeeper
                .lock(oldDriver.subagentId)(
                  newDriver.startMovedSubagent(oldDriver))
                .logWhenItTakesLonger(name)
                  .onErrorHandle(t => logger.error(
                    s"addOrChange $name => ${t.toStringWithCauses}", t.nullIfNoStackTrace))
                .startAndForget
                .as(Right(None)))

          case Some((None, newDriver: RemoteSubagentDriver)) if newDriver.isLocal=>
            emitLocalSubagentCoupled
              .as(Right(Some(newDriver)))

          case maybeNewDriver => Task.right(maybeNewDriver.map(_._2))
        }
    }

  private def emitLocalSubagentCoupled: Task[Unit] =
    localSubagentId.traverse(localSubagentId =>
      journal
        .persist(agentState => Right(agentState
          .idToSubagentItemState.get(localSubagentId)
          .exists(_.couplingState != Coupled)
          .thenList(localSubagentId <-: SubagentCoupled)))
        .orThrow)
      .void

  private def allocateSubagentDriver(subagentItem: SubagentItem) =
    if (localSubagentId contains subagentItem.id)
      allocateLocalSubagentDriver(subagentItem)
    else
      subagentDriverResource(subagentItem).toAllocated

  private def allocateLocalSubagentDriver(subagentItem: SubagentItem)
  : Task[Allocated[Task, SubagentDriver]] =
    emitLocalSubagentCoupled *>
      localSubagentDriverResource(subagentItem).toAllocated

  private def localSubagentDriverResource(subagentItem: SubagentItem)
  : Resource[Task, SubagentDriver] =
    for {
      subagent <- Subagent.resource(directorConf.subagentConf, scheduler, iox, testEventBus)
      driver <- RemoteSubagentDriver.resource(
        subagentItem,
        new LocalSubagentApi(subagent),
        journal,
        controllerId,
        driverConf,
        directorConf.subagentConf,
        directorConf.recouplingStreamReaderConf)
      _ <- Resource.eval(journal
        .persist(state => Right(
          if (state.idToSubagentItemState.get(subagentItem.id).exists(_.subagentRunId.isEmpty))
            List(subagentItem.id <-:
              SubagentDedicated(subagent.subagentRunId, Some(currentPlatformInfo())))
          else
            Nil))
        .map(_.orThrow))
    } yield driver

  private def subagentDriverResource(subagentItem: SubagentItem)
  : Resource[Task, SubagentDriver] =
    for {
      api <- subagentApiResource(subagentItem)
      driver <- subagentDriverResource(subagentItem, api)
    } yield driver

  private def subagentApiResource(subagentItem: SubagentItem): Resource[Task, HttpSubagentApi] =
    HttpSubagentApi.resource(
      Admission(
        subagentItem.uri,
        directorConf.config
          .optionAs[SecretString](
            "js7.auth.subagents." + ConfigUtil.joinPath(subagentItem.id.string))
          .map(UserAndPassword(subagentItem.agentPath.toUserId.orThrow, _))),
      directorConf.httpsConfig,
      name = subagentItem.id.toString,
      actorSystem)

  private def subagentDriverResource(subagentItem: SubagentItem, api: HttpSubagentApi)
  : Resource[Task, SubagentDriver] =
    RemoteSubagentDriver
      .resource(
        subagentItem,
        api,
        journal,
        controllerId,
        driverConf,
        directorConf.subagentConf,
        directorConf.recouplingStreamReaderConf)

  def addOrReplaceSubagentSelection(selection: SubagentSelection): Task[Checked[Unit]] =
    stateVar
      .updateChecked(state => Task(state.insertOrReplaceSelection(selection)))
      .rightAs(())

  def removeSubagentSelection(subagentSelectionId: SubagentSelectionId): Task[Unit] =
    stateVar
      .update(state => Task(state.removeSelection(subagentSelectionId)))
      .void

  def testFailover(): Unit =
    stateVar.get.idToDriver.values.filter(_.isLocal).foreach {
      _.testFailover()
    }

  override def toString = s"SubagentKeeper(${orderToSubagent.size} processing orders)"
}

object SubagentKeeper
{
  private val logger = Logger[this.type]

  private[director] def determineSubagentSelection(
    order: Order[Order.IsFreshOrReady],
    agentPath: AgentPath,
    maybeJobsSelectionId: Option[SubagentSelectionId])
  : DeterminedSubagentSelection =
    order.agentToStickySubagent(agentPath) match {
      case Some(sticky)
        if maybeJobsSelectionId.forall(o => sticky.subagentSelectionId.forall(_ == o)) =>
        // StickySubagent instruction applies
        DeterminedSubagentSelection(
          sticky.stuckSubagentId
            .map(SubagentSelectionId.fromSubagentId)
            .orElse(sticky.subagentSelectionId)
            .orElse(maybeJobsSelectionId),
          stick = sticky.stuckSubagentId.isEmpty)

      case _ =>
        DeterminedSubagentSelection(maybeJobsSelectionId)
    }

  private final case class SelectedDriver(subagentDriver: SubagentDriver, stick: Boolean)

  private[director] final case class DeterminedSubagentSelection(
    maybeSubagentSelectionId: Option[SubagentSelectionId],
    stick: Boolean = false)
  private[director] object DeterminedSubagentSelection {
    @TestOnly
    def stuck(stuckSubagentId: SubagentId): DeterminedSubagentSelection =
      DeterminedSubagentSelection(Some(SubagentSelectionId.fromSubagentId(stuckSubagentId)))
  }
}
