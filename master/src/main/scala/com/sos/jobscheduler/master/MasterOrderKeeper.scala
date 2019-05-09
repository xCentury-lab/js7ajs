package com.sos.jobscheduler.master

import akka.actor.{ActorRef, Stash, Status, Terminated}
import akka.pattern.{ask, pipe}
import cats.data.Validated.{Invalid, Valid}
import cats.effect.SyncIO
import cats.instances.future._
import cats.instances.vector._
import cats.syntax.flatMap._
import cats.syntax.option._
import cats.syntax.traverse._
import com.sos.jobscheduler.agent.data.event.AgentMasterEvent
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.problem.Checked._
import com.sos.jobscheduler.base.problem.{Checked, Problem}
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.base.utils.Collections.implicits._
import com.sos.jobscheduler.base.utils.IntelliJUtils.intelliJuseImport
import com.sos.jobscheduler.base.utils.ScalaUtils.RichPartialFunction
import com.sos.jobscheduler.common.akkautils.Akkas.encodeAsActorName
import com.sos.jobscheduler.common.akkautils.SupervisorStrategies
import com.sos.jobscheduler.common.configutils.Configs.ConvertibleConfig
import com.sos.jobscheduler.common.event.EventIdClock
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.scalautil.Logger.ops._
import com.sos.jobscheduler.core.command.CommandMeta
import com.sos.jobscheduler.core.common.ActorRegister
import com.sos.jobscheduler.core.crypt.SignatureVerifier
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.core.event.journal.data.JournalMeta
import com.sos.jobscheduler.core.event.journal.recover.JournalRecoverer
import com.sos.jobscheduler.core.event.journal.watch.JournalEventWatch
import com.sos.jobscheduler.core.event.journal.{JournalActor, MainJournalingActor}
import com.sos.jobscheduler.core.filebased.{FileBasedVerifier, FileBaseds, Repo}
import com.sos.jobscheduler.core.problems.UnknownOrderProblem
import com.sos.jobscheduler.core.workflow.OrderEventHandler.FollowUp
import com.sos.jobscheduler.core.workflow.OrderProcessor
import com.sos.jobscheduler.data.agent.{AgentRef, AgentRefPath}
import com.sos.jobscheduler.data.crypt.Signed
import com.sos.jobscheduler.data.event.KeyedEvent.NoKey
import com.sos.jobscheduler.data.event.{Event, EventId, KeyedEvent, Stamped}
import com.sos.jobscheduler.data.filebased.RepoEvent.{FileBasedAdded, FileBasedChanged, FileBasedDeleted, VersionAdded}
import com.sos.jobscheduler.data.filebased.{FileBased, RepoEvent, TypedPath}
import com.sos.jobscheduler.data.master.MasterFileBaseds
import com.sos.jobscheduler.data.order.OrderEvent.{OrderAdded, OrderAttachable, OrderBroken, OrderCancelationMarked, OrderCoreEvent, OrderStdWritten, OrderTransferredToAgent, OrderTransferredToMaster}
import com.sos.jobscheduler.data.order.{FreshOrder, Order, OrderEvent, OrderId}
import com.sos.jobscheduler.data.workflow.instructions.Execute
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.position.WorkflowPosition
import com.sos.jobscheduler.data.workflow.{Instruction, Workflow}
import com.sos.jobscheduler.master.MasterOrderKeeper._
import com.sos.jobscheduler.master.agent.AgentDriver
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import com.sos.jobscheduler.master.data.MasterCommand
import com.sos.jobscheduler.master.data.agent.AgentEventIdEvent
import com.sos.jobscheduler.master.data.events.MasterAgentEvent.AgentReady
import com.sos.jobscheduler.master.data.events.MasterEvent
import com.sos.jobscheduler.master.data.events.MasterEvent.MasterTestEvent
import com.sos.jobscheduler.master.repo.RepoCommandExecutor
import java.time.ZoneId
import monix.execution.Scheduler
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Deadline.now
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * @author Joacim Zschimmer
  */
final class MasterOrderKeeper(
  masterConfiguration: MasterConfiguration,
  journalMeta: JournalMeta[Event],
  eventWatch: JournalEventWatch[Event],
  eventIdClock: EventIdClock,
  signatureVerifier: SignatureVerifier)
  (implicit
    keyedEventBus: StampedKeyedEventBus,
    scheduler: Scheduler)
extends Stash
with MainJournalingActor[Event]
{
  import context.{actorOf, watch}

  override val supervisorStrategy = SupervisorStrategies.escalate

  protected val journalActor = watch(actorOf(
    JournalActor.props(journalMeta, masterConfiguration.config, keyedEventBus, scheduler, eventIdClock),
    "Journal"))
  private var repo = Repo(MasterFileBaseds.jsonCodec)
  private val repoCommandExecutor = new RepoCommandExecutor(new FileBasedVerifier(signatureVerifier, MasterFileBaseds.jsonCodec))
  private val agentRegister = new AgentRegister
  private object orderRegister extends mutable.HashMap[OrderId, OrderEntry] {
    def checked(orderId: OrderId) = get(orderId).toChecked(UnknownOrderProblem(orderId))
  }
  private val idToOrder = orderRegister mapPartialFunction (_.order)
  private var orderProcessor = new OrderProcessor(PartialFunction.empty, idToOrder)
  private val suppressOrderIdCheckFor = masterConfiguration.config.optionAs[String]("jobscheduler.TEST-ONLY.suppress-order-id-check-for")
  private var terminatingSince: Option[Deadline] = None
  private def terminating = terminatingSince.isDefined

  private object afterProceedEvents {
    private val events = mutable.Buffer[KeyedEvent[OrderEvent]]()

    def persistAndHandleLater(keyedEvent: KeyedEvent[OrderEvent]): Unit = {
      val first = events.isEmpty
      events += keyedEvent
      if (first) {
        self ! Internal.AfterProceedEventsAdded
      }
    }

    def persistThenHandleEvents(): Unit = {
      // Eliminate duplicate events like OrderJoined, which may be issued by parent and child orders when recovering
      persistMultiple(events.distinct)(
        _ foreach handleOrderEvent)
      events.clear()
    }
  }

  override def preStart() = {
    super.preStart()  // First let JournalingActor register itself
    recover()
  }

  override def postStop() = {
    super.postStop()
    for (t <- terminatingSince) {
      val deadline = t + 500.millis
      if (deadline.hasTimeLeft()) {
        logger.debug("Delaying to let HTTP server respond to Terminate command")
        sleepUntil(deadline)
      }
    }
    logger.debug("Stopped")
  }

  protected def snapshots = Future.successful(masterState.toSnapshots)

  private def masterState = MasterState(
    persistedEventId,
    repo,
    orderRegister.values.map(_.order).toImmutableSeq,
    agentRegister.values.map(entry => entry.agentRefPath -> entry.lastAgentEventId).uniqueToMap)

  private def recover() = {
    val recoverer = new MasterJournalRecoverer(journalMeta)
    recoverer.recoverAll()
    for (masterState <- recoverer.masterState) {
      setRepo(masterState.repo)
      for (agentRef <- repo.currentFileBaseds collect { case o: AgentRef => o }) {
        registerAgent(agentRef)
      }
      for ((agentRefPath, eventId) <- masterState.agentToEventId) {
        agentRegister(agentRefPath).lastAgentEventId = eventId
      }
      for (order <- masterState.orders) {
        orderRegister.insert(order.id -> new OrderEntry(order))
      }
      persistedEventId = masterState.eventId
    }
    recoverer.startJournalAndFinishRecovery(journalActor = journalActor, journalingObserver = Some(eventWatch))
  }

  def receive = {
    case JournalRecoverer.Output.JournalIsReady =>
      agentRegister.values foreach { _.start() }

      orderRegister.values.toVector/*copy*/ foreach proceedWithOrder  // Any ordering when continuing orders???
      afterProceedEvents.persistThenHandleEvents()  // Persist and handle before Internal.Ready
      if (persistedEventId > EventId.BeforeFirst) {  // Recovered?
        logger.info(s"${orderRegister.size} Orders, ${repo.typedCount[Workflow]} Workflows and ${repo.typedCount[AgentRef]} AgentRefs recovered")
      }
      persist(MasterEvent.MasterReady(masterConfiguration.masterId, ZoneId.systemDefault.getId))(_ =>
        self ! Internal.Ready
      )

    case Internal.Ready =>
      logger.info("Ready")
      become("Ready")(ready)
      unstashAll()

    case _ => stash()
  }

  private def ready: Receive = {
    case Internal.AfterProceedEventsAdded =>
      afterProceedEvents.persistThenHandleEvents()

    case Command.Execute(command, meta) =>
      val sender = this.sender()
      if (terminating)
        sender ! Invalid(MasterIsTerminatingProblem)
      else
        executeMasterCommand(command, meta) onComplete {
          case Failure(t) => sender ! Status.Failure(t)
          case Success(response) => sender ! response
        }

    case Command.GetRepo =>
      sender() ! Stamped(persistedEventId, repo)

    case Command.AddOrder(order) =>
      if (terminating)
        sender ! Status.Failure(MasterIsTerminatingProblem.throwable)
      else
        addOrder(order) map Response.ForAddOrder.apply pipeTo sender()

    case Command.AddOrders(orders) =>
      if (terminating)
        sender ! Status.Failure(MasterIsTerminatingProblem.throwable)
      else
        addOrders(orders).pipeTo(sender())

    case Command.GetOrder(orderId) =>
      sender() ! (orderRegister.get(orderId) map { _.order })

    case Command.GetOrders =>
      sender() ! Stamped[Vector[Order[Order.State]]](persistedEventId, orderRegister.values.map(_.order).toVector)

    case Command.GetOrderCount =>
      sender() ! (orderRegister.size: Int)

    case Command.GetState =>
      sender() ! masterState

    case AgentDriver.Output.EventsFromAgent(stampeds) =>
      val agentEntry = agentRegister(sender())
      import agentEntry.agentRefPath
      var lastAgentEventId = none[EventId]
      var masterStamped: Seq[Timestamped[Event]] = stampeds.flatMap {
        case stamped @ Stamped(agentEventId, timestamp, keyedEvent) =>
          if (agentEventId <= lastAgentEventId.getOrElse(agentEntry.lastAgentEventId)) {
            logger.error(s"Agent ${agentEntry.agentRefPath} has returned old (<= ${lastAgentEventId.getOrElse(agentEntry.lastAgentEventId)}) event: $stamped")
            None
          } else {
            lastAgentEventId = agentEventId.some
            keyedEvent match {
              case KeyedEvent(_, _: OrderCancelationMarked) =>  // We (the Master) issue our own OrderCancelationMarked
                None

              case KeyedEvent(orderId: OrderId, event: OrderEvent) =>
                val ownEvent = event match {
                  case _: OrderEvent.OrderAttached => OrderTransferredToAgent(agentRefPath) // TODO Das kann schon der Agent machen. Dann wird weniger übertragen.
                  case _ => event
                }
                Some(Timestamped(orderId <-: ownEvent, Some(timestamp)))

              case KeyedEvent(_: NoKey, AgentMasterEvent.AgentReadyForMaster(timezone)) =>
                Some(Timestamped(agentEntry.agentRefPath <-: AgentReady(timezone), Some(timestamp)))

              case _ =>
                logger.warn(s"Unknown event received from ${agentEntry.agentRefPath}: $keyedEvent")
                None
            }
          }
      }
      masterStamped ++= lastAgentEventId.map(agentEventId => Timestamped(agentRefPath <-: AgentEventIdEvent(agentEventId)))

      persistTransactionTimestamped(masterStamped) {
        _ map (_.value) foreach {
          case KeyedEvent(orderId: OrderId, event: OrderEvent) =>
            handleOrderEvent(orderId, event)

          case KeyedEvent(_, AgentEventIdEvent(agentEventId)) =>
            assert(agentEntry.lastAgentEventId < agentEventId)
            agentEntry.lastAgentEventId = agentEventId
            agentEntry.actor ! AgentDriver.Input.EventsAccepted(agentEventId)

          case _ =>
        }
      }

    case AgentDriver.Output.OrdersDetached(orderIds) =>
      val unknown = orderIds -- orderRegister.keySet
      if (unknown.nonEmpty) {
        logger.error(s"Response to AgentCommand.DetachOrder from Agent for unknown orders: "+ unknown.mkString(", "))
      }
      persistMultipleAsync(orderIds -- unknown map (_ <-: OrderTransferredToMaster))(
        _ foreach handleOrderEvent)

    case AgentDriver.Output.OrdersCancelationMarked(orderIds) =>
      val unknown = orderIds -- orderRegister.keySet
      if (unknown.nonEmpty) {
        logger.error(s"Response to AgentCommand.CancelOrder from Agent for unknown orders: "+ unknown.mkString(", "))
      }
      for (orderId <- orderIds) {
        orderRegister(orderId).cancelationMarkedOnAgent = true
      }

    case JournalActor.Output.SnapshotTaken =>
      if (terminating) {
        // TODO termination wie in AgentOrderKeeper, dabei AgentDriver ordentlich beenden
        if (agentRegister.nonEmpty)
          agentRegister.values foreach { _.actor ! AgentDriver.Input.Terminate }
        else
          journalActor ! JournalActor.Input.Terminate
      }

    case Terminated(a) if agentRegister contains a =>
      agentRegister -= a
      if (agentRegister.isEmpty) {
        journalActor ! JournalActor.Input.Terminate
      }

    case Terminated(`journalActor`) =>
      if (!terminating) logger.error("JournalActor terminated")
      logger.info("Stop")
      context.stop(self)
  }

  private def executeMasterCommand(command: MasterCommand, commandMeta: CommandMeta): Future[Checked[MasterCommand.Response]] =
    command match {
      case MasterCommand.CancelOrder(orderId, mode) =>
        orderRegister.checked(orderId) map (_.order) match {
          case Invalid(problem) =>
            Future.successful(Invalid(problem))

          case Valid(order) =>
            orderProcessor.cancel(order.id, mode, isAgent = false) match {
              case invalid @ Invalid(_) =>
                Future.successful(invalid)
              case Valid(None) =>
                Future.successful(Valid(MasterCommand.Response.Accepted))
              case Valid(Some(event)) =>
                persist(orderId <-: event) { stamped =>  // Event may be inserted between events coming from Agent
                  handleOrderEvent(stamped)
                  Valid(MasterCommand.Response.Accepted)
                }
            }
        }

      case MasterCommand.KeepEvents(eventId) =>
        Future {  // asynchronous
          eventWatch.keepEvents(eventId)
            .map (_ => MasterCommand.Response.Accepted)
        }

      case cmd: MasterCommand.ReplaceRepo =>
        intelliJuseImport(catsStdInstancesForFuture)  // For traverse
        repoCommandExecutor.replaceRepoCommandToEvents(repo, cmd, commandMeta)
          .flatMap(readConfiguration)
          .traverse((_: SyncIO[Future[Completed]])
            .unsafeRunSync()  // Persist events!
            .map(_ => MasterCommand.Response.Accepted))

      case cmd: MasterCommand.UpdateRepo =>
        repoCommandExecutor.updateRepoCommandToEvents(repo, cmd, commandMeta)
          .flatMap(readConfiguration)
          .traverse((_: SyncIO[Future[Completed]])
            .unsafeRunSync()  // Persist events!
            .map(_ => MasterCommand.Response.Accepted))

      case MasterCommand.EmergencyStop | MasterCommand.NoOperation | _: MasterCommand.Batch =>       // For completeness. RunningMaster has handled the command already
        Future.successful(Invalid(Problem.pure("THIS SHOULD NOT HAPPEN")))  // Never called

      case MasterCommand.TakeSnapshot =>
        import masterConfiguration.akkaAskTimeout  // We need several seconds or even minutes
          (journalActor ? JournalActor.Input.TakeSnapshot)
            .mapTo[JournalActor.Output.SnapshotTaken.type]
            .map(_ => Valid(MasterCommand.Response.Accepted))

      case MasterCommand.Terminate =>
        logger.info("Command Terminate")
        journalActor ! JournalActor.Input.TakeSnapshot
        terminatingSince = Some(now)
        Future.successful(Valid(MasterCommand.Response.Accepted))

      case MasterCommand.IssueTestEvent =>
        persist(MasterTestEvent, async = true)(_ =>
          Valid(MasterCommand.Response.Accepted))
    }

  private def readConfiguration(events: Seq[RepoEvent]): Checked[SyncIO[Future[Completed]]] = {
    def updateFileBaseds(diff: FileBaseds.Diff[TypedPath, FileBased]): Seq[Checked[SyncIO[Unit]]] =
      updateAgents(diff.select[AgentRefPath, AgentRef])

    def updateAgents(diff: FileBaseds.Diff[AgentRefPath, AgentRef]): Seq[Checked[SyncIO[Unit]]] =
      deletionNotSupported(diff) :+
        Valid(SyncIO {
          for (agentRef <- diff.added) registerAgent(agentRef).start()
          for (agentRef <- diff.updated) {
            agentRegister.update(agentRef)
            agentRegister(agentRef.path).reconnect()
          }
        })

    def deletionNotSupported[P <: TypedPath, A <: FileBased](diff: FileBaseds.Diff[P, A]): Seq[Invalid[Problem]] =
      diff.deleted.map(o => Invalid(Problem(s"Deletion of these configuration objects is not supported: $o")))

    //def changeNotSupported[P <: TypedPath, A <: FileBased](diff: FileBaseds.Diff[P, A]): Seq[Invalid[Problem]] =
    //  diff.updated.map(o => Invalid(Problem(s"Change of these configuration objects is not supported: ${o.path}")))

    for {
      changedRepo <- repo.applyEvents(events)  // May return DuplicateVersionProblem
      realChanges = FileBaseds.diffFileBaseds(changedRepo.currentFileBaseds, repo.currentFileBaseds)  // should be equivalent to events
      checkedSideEffects = updateFileBaseds(FileBaseds.Diff.fromRepoChanges(realChanges) withVersionId changedRepo.versionId)
      foldedSideEffects <- checkedSideEffects.toVector.sequence map (_.fold(SyncIO.unit)(_ >> _))  // One problem invalidates all side effects
    } yield
      SyncIO {
        persistTransaction(events map (e => KeyedEvent(e))) { _ =>
          setRepo(changedRepo)
          events foreach logRepoEvent
          foldedSideEffects.unsafeRunSync()
          Completed
        }
      }
  }

  private def logRepoEvent(event: RepoEvent): Unit =
    event match {
      case VersionAdded(version)     => logger.info(s"Version '${version.string}' added")
      case FileBasedAdded(path, _)   => logger.info(s"Added $path")
      case FileBasedChanged(path, _) => logger.info(s"Changed $path")
      case FileBasedDeleted(path)    => logger.info(s"Deleted $path")
    }

  private def setRepo(o: Repo): Unit = {
    repo = o
    orderProcessor = new OrderProcessor(repo.idTo[Workflow], idToOrder)
  }

  private def registerAgent(agent: AgentRef): AgentEntry = {
    val actor = watch(actorOf(
      AgentDriver.props(agent.path, agent.uri, masterConfiguration, journalActor = journalActor),
      encodeAsActorName("Agent-" + agent.path.withoutStartingSlash)))
    val entry = AgentEntry(agent, actor)
    agentRegister.insert(agent.path -> entry)
    entry
  }

  private def addOrder(order: FreshOrder): Future[Checked[Boolean]] =
    suppressOrderIdCheckFor match {
      case Some(order.id.string) =>  // Test only
        addOrderWithUncheckedId(order)

      case _ =>
        order.id.checkedNameSyntax match {
          case Invalid(problem) => Future.successful(Invalid(problem))
          case Valid(_) => addOrderWithUncheckedId(order)
        }
    }

  private def addOrderWithUncheckedId(freshOrder: FreshOrder): Future[Checked[Boolean]] = {
    val order = freshOrder.toOrder(repo.versionId)
    orderRegister.get(order.id) match {
      case Some(_) =>
        logger.debug(s"Discarding duplicate added Order: $freshOrder")
        Future.successful(Valid(false))

      case None =>
        repo.idTo[Workflow](order.workflowId) match {
          case Invalid(problem) => Future.successful(Invalid(problem))
          case Valid(workflow) =>
            persist/*Async?*/(order.id <-: OrderAdded(workflow.id, order.state.scheduledFor, order.arguments)) { stamped =>
              handleOrderEvent(stamped)
              Valid(true)
            }
        }
    }
  }

  private def addOrders(freshOrders: Seq[FreshOrder]): Future[Checked[Completed]] =
    freshOrders.toVector.traverse[Checked, FreshOrder](order => order.id.checkedNameSyntax.map(_ => order))
      .flatMap(_
        .filterNot(o => orderRegister.contains(o.id))  // Ignore known orders
        .map(_.toOrder(repo.versionId))
        .traverse[Checked, (Order[Order.Fresh], Workflow)](order => repo.idTo[Workflow](order.workflowId).map(order -> _))
        .map { ordersAndWorkflows =>
          val events = for ((order, workflow) <- ordersAndWorkflows) yield
            order.id <-: OrderAdded(workflow.id/*reuse*/, order.state.scheduledFor, order.arguments)
          persistMultiple(events) { stamped =>
            for (o <- stamped) handleOrderEvent(o)
            Completed
          }
        })
      .evert

  private def handleOrderEvent(stamped: Stamped[KeyedEvent[OrderEvent]]): Unit =
    handleOrderEvent(stamped.value.key, stamped.value.event)

  private def handleOrderEvent(orderId: OrderId, event: OrderEvent): Unit = {
    event match {
      case event: OrderAdded =>
        registerOrderAndProceed(Order.fromOrderAdded(orderId, event))

      case _ =>
        orderRegister.get(orderId) match {
          case None =>
            logger.error(s"Unknown OrderId in event ${orderId <-: event}")

          case Some(orderEntry) =>
            val checkedFollowUps = orderProcessor.handleEvent(orderId <-: event)
            for (followUps <- checkedFollowUps onProblem (p => logger.error(p)))  {
              followUps foreach {
                case _: FollowUp.Processed if orderEntry.order.isAttached =>

                case FollowUp.AddChild(childOrder) =>
                  registerOrderAndProceed(childOrder)

                case FollowUp.AddOffered(offeredOrder) =>
                  registerOrderAndProceed(offeredOrder)

                case FollowUp.Remove(removeOrderId) =>
                  orderRegister -= removeOrderId

                case unexpected =>
                  logger.error(s"Order '$orderId': Unexpected FollowUp $unexpected")
              }
            }
            orderEntry.update(event)
            if (orderRegister contains orderId) {  // orderEntry has not been deleted?
              proceedWithOrder(orderEntry)
            }
        }
    }
  }

  private def registerOrderAndProceed(order: Order[Order.State]): Unit = {
    val entry = new OrderEntry(order)
    orderRegister.insert(order.id -> entry)
    proceedWithOrder(entry)
  }

  private def proceedWithOrder(orderEntry: OrderEntry): Unit =
    if (!terminating) {
      val order = orderEntry.order
      for (mode <- order.cancel) {
        if ((order.isAttaching || order.isAttached) && !orderEntry.cancelationMarkedOnAgent) {
          // On Recovery, CancelOrder is sent again, because orderEntry.cancelationMarkedOnAgent is lost
          for ((_, _, agentEntry) <- checkedWorkflowJobAndAgentEntry(order) onProblem (p => logger.error(p))) {
            agentEntry.actor ! AgentDriver.Input.CancelOrder(order.id, mode)
          }
        }
      }
      order.attachedState match {
        case None |
             Some(_: Order.Attaching) => proceedWithOrderOnMaster(orderEntry)
        case Some(_: Order.Attached)  =>
        case Some(_: Order.Detaching) => detachOrderFromAgent(order.id)
      }
    }

  private def proceedWithOrderOnMaster(orderEntry: OrderEntry): Unit = {
    val order = orderEntry.order
    order.state match {
      case _: Order.FreshOrReady =>
        val freshOrReady = order.castState[Order.FreshOrReady]
        instruction(order.workflowPosition) match {
          case _: Execute => tryAttachOrderToAgent(freshOrReady)
          case _ =>
        }

      case _: Order.Offering =>
        for (awaitingOrderId <- orderProcessor.offeredToAwaitingOrder(orderEntry.orderId);
             awaitingOrder <- orderRegister.checked(awaitingOrderId).onProblem(p => logger.warn(p.toString));
             _ <- awaitingOrder.order.checkedState[Order.Awaiting].onProblem(p => logger.error(p.toString)))
        {
          proceedWithOrderOnMaster(awaitingOrder)
        }

      case _ =>
    }

    // When recovering, proceedWithOrderOnMaster may issue the same event multiple times,
    // for example OrderJoined for each parent and child order.
    // These events are collected and with actor message Internal.AfterProceedEventsAdded reduced to one.
    for (keyedEvent <- orderProcessor.nextEvent(order.id)) {
      keyedEvent match {
        case KeyedEvent(orderId, OrderBroken(problem)) =>
          logger.error(s"Order ${orderId.string} is broken: $problem")
        case _ =>
      }
      afterProceedEvents.persistAndHandleLater(keyedEvent)
    }
  }

  private def tryAttachOrderToAgent(order: Order[Order.FreshOrReady]): Unit =
    for ((signedWorkflow, job, agentEntry) <- checkedWorkflowJobAndAgentEntry(order).onProblem(p => logger.error(p))) {
      if (order.isDetached && !orderProcessor.isOrderCancelable(order))
        persist(order.id <-: OrderAttachable(agentEntry.agentRefPath)) { stamped =>
          handleOrderEvent(stamped)
        }
      else if (order.isAttaching) {
        agentEntry.actor ! AgentDriver.Input.AttachOrder(order, agentEntry.agentRefPath, signedWorkflow)  // OutOfMemoryError when Agent is unreachable !!!
      }
    }

  private def checkedWorkflowJobAndAgentEntry(order: Order[Order.State]): Checked[(Signed[Workflow], WorkflowJob, AgentEntry)] =
    for {
      signedWorkflow <- repo.idToSigned[Workflow](order.workflowId)
      job <- signedWorkflow.value.checkedWorkflowJob(order.position)
      agentEntry <- agentRegister.checked(job.agentRefPath)
    } yield (signedWorkflow, job, agentEntry)

  private def detachOrderFromAgent(orderId: OrderId): Unit =
    orderRegister(orderId).order.detaching
      .onProblem(p => logger.error(s"detachOrderFromAgent '$orderId': not Detaching: $p"))
      .foreach { agentRefPath =>
        agentRegister.get(agentRefPath) match {
          case None => logger.error(s"detachOrderFromAgent '$orderId': Unknown $agentRefPath")
          case Some(a) => a.actor ! AgentDriver.Input.DetachOrder(orderId)
        }
      }

  private def instruction(workflowPosition: WorkflowPosition): Instruction =
    repo.idTo[Workflow](workflowPosition.workflowId).orThrow.instruction(workflowPosition.position)

  override def toString = "MasterOrderKeeper"
}

private[master] object MasterOrderKeeper {
  private val MasterIsTerminatingProblem = Problem.pure("Master is terminating")

  private val logger = Logger(getClass)

  sealed trait Command
  object Command {
    final case class Execute(command: MasterCommand, meta: CommandMeta)
    final case object GetRepo extends Command
    final case class AddOrder(order: FreshOrder) extends Command
    final case class AddOrders(order: Seq[FreshOrder]) extends Command
    final case class GetOrder(orderId: OrderId) extends Command
    final case object GetOrders extends Command
    final case object GetOrderCount extends Command
    final case object GetState extends Command
  }

  sealed trait Reponse
  object Response {
    final case class ForAddOrder(created: Checked[Boolean])
  }

  private object Internal {
    case object Ready
    case object AfterProceedEventsAdded
  }

  private class AgentRegister extends ActorRegister[AgentRefPath, AgentEntry](_.actor) {
    override def insert(kv: (AgentRefPath, AgentEntry)) = super.insert(kv)
    override def -=(a: ActorRef) = super.-=(a)

    def update(agentRef: AgentRef): Unit = {
      val oldEntry = apply(agentRef.path)
      val entry = oldEntry.copy(agentRef = agentRef)
      super.update(agentRef.path -> entry)
    }
  }

  private case class AgentEntry(
    agentRef: AgentRef,
    actor: ActorRef,
    var lastAgentEventId: EventId = EventId.BeforeFirst)
  {
    def agentRefPath = agentRef.path

    def start()(implicit sender: ActorRef): Unit =
      actor ! AgentDriver.Input.Start(lastAgentEventId = lastAgentEventId)

    def reconnect()(implicit sender: ActorRef): Unit =
      actor ! AgentDriver.Input.Reconnect(uri = agentRef.uri)
  }

  private class OrderEntry(private var _order: Order[Order.State])
  {
    def order = _order

    var cancelationMarkedOnAgent = false

    def orderId = order.id

    def update(event: OrderEvent): Unit =
      event match {
        case _: OrderStdWritten =>
        case event: OrderCoreEvent =>
          _order.update(event) match {
            case Invalid(problem) => logger.error(problem.toString)  // TODO Invalid event stored and ignored. Should we validate the event before persisting?
              // TODO Mark order as unuseable (and try OrderBroken). No further actions on this order to avoid loop!
            case Valid(o) => _order = o
          }
      }
  }
}
