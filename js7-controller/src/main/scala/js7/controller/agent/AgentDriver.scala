package js7.controller.agent

import akka.actor.{ActorRef, DeadLetterSuppression, Props}
import cats.data.EitherT
import com.typesafe.config.ConfigUtil
import js7.agent.client.AgentClient
import js7.agent.data.commands.AgentCommand
import js7.agent.data.commands.AgentCommand.{CoupleController, RegisterAsController}
import js7.agent.data.event.AgentControllerEvent
import js7.base.auth.UserAndPassword
import js7.base.crypt.Signed
import js7.base.generic.{Completed, SecretString}
import js7.base.problem.Checked._
import js7.base.problem.Problems.InvalidSessionTokenProblem
import js7.base.problem.{Checked, Problem}
import js7.base.time.ScalaTime._
import js7.base.time.Timestamp
import js7.base.utils.Assertions.assertThat
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.SetOnce
import js7.base.web.Uri
import js7.common.akkautils.ReceiveLoggingActor
import js7.common.configutils.Configs.ConvertibleConfig
import js7.common.http.RecouplingStreamReader
import js7.common.scalautil.Futures.promiseFuture
import js7.common.scalautil.Logger
import js7.common.scalautil.MonixUtils.promiseTask
import js7.controller.agent.AgentDriver._
import js7.controller.agent.CommandQueue.QueuedInputResponse
import js7.controller.configuration.ControllerConfiguration
import js7.controller.data.ControllerState
import js7.controller.data.events.ControllerAgentEvent
import js7.controller.data.events.ControllerAgentEvent.{AgentCouplingFailed, AgentRegisteredController}
import js7.core.event.journal.{JournalActor, KeyedJournalingActor}
import js7.data.agent.{AgentRefPath, AgentRunId}
import js7.data.command.CancelMode
import js7.data.event.{AnyKeyedEvent, Event, EventId, EventRequest, Stamped}
import js7.data.order.{Order, OrderEvent, OrderId}
import js7.data.workflow.Workflow
import monix.eval.Task
import monix.execution.atomic.{AtomicInt, AtomicLong}
import monix.execution.{Cancelable, CancelableFuture, Scheduler}
import scala.concurrent.Promise
import scala.concurrent.duration.Deadline.now
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success, Try}
import shapeless.tag.@@

/**
  * Couples to an Agent, sends orders, and fetches events.
  *
  * @author Joacim Zschimmer
  */
final class AgentDriver private(agentRefPath: AgentRefPath,
  initialUri: Uri,
  initialAgentRunId: Option[AgentRunId],
  initialEventId: EventId,
  conf: AgentDriverConfiguration,
  controllerConfiguration: ControllerConfiguration,
  protected val journalActor: ActorRef @@ JournalActor.type)
  (implicit protected val scheduler: Scheduler)
extends KeyedJournalingActor[ControllerState, ControllerAgentEvent]
with ReceiveLoggingActor.WithStash
{
  protected def journalConf = controllerConfiguration.journalConf

  private val logger = Logger.withPrefix[this.type](agentRefPath.string)
  private val agentUserAndPassword: Option[UserAndPassword] =
    controllerConfiguration.config.optionAs[SecretString]("js7.auth.agents." + ConfigUtil.joinPath(agentRefPath.string))
      .map(password => UserAndPassword(controllerConfiguration.controllerId.toUserId, password))

  private val agentRunIdOnce = SetOnce.fromOption(initialAgentRunId)
  private var client = newAgentClient(initialUri)
  /** Only filled when coupled */
  private var lastFetchedEventId = initialEventId
  private var lastCommittedEventId = initialEventId
  @volatile
  private var lastProblem: Option[Problem] = None
  private var currentFetchedFuture: Option[CancelableFuture[Completed]] = None
  private var releaseEventsCancelable: Option[Cancelable] = None
  private var delayNextReleaseEvents = false
  private var delayCommandExecutionAfterErrorUntil = now
  private var isTerminating = false
  private var changingUri: Option[Uri] = None
  private val sessionNumber = AtomicInt(0)
  private val eventFetcherTerminated = Promise[Completed]()
  private var noJournal = false

  private val eventFetcher = new RecouplingStreamReader[EventId, Stamped[AnyKeyedEvent], AgentClient](
    _.eventId, conf.recouplingStreamReader)
  {
    private var attachedOrderIds: Set[OrderId] = null

    override protected def couple(eventId: EventId) =
      (for {
        _ <- EitherT(registerAsControllerIfNeeded)
        completed <- EitherT(
          client.commandExecute(CoupleController(agentRefPath, agentRunIdOnce.orThrow, eventId = eventId))
            .map(_.map { case CoupleController.Response(orderIds) =>
              logger.trace(s"CoupleController returned attached OrderIds={${orderIds.toSeq.sorted.mkString(" ")}}")
              attachedOrderIds = orderIds
              Completed
            }))
      } yield completed).value

    protected def getObservable(api: AgentClient, after: EventId) =
      Task { logger.debug(s"getObservable(after=$after)") } >>
        api.controllersEventObservable(EventRequest[Event](EventClasses, after = after, timeout = Some(idleTimeout)))

    override protected def onCouplingFailed(api: AgentClient, problem: Problem) =
      Task.defer {
        if (lastProblem.contains(problem)) {
          logger.debug(s"Coupling failed: $problem")
          Task.pure(Completed)
        } else {
          lastProblem = Some(problem)
          logger.warn(s"Coupling failed: $problem")
          for (t <- problem.throwableOption if t.getStackTrace.nonEmpty) logger.debug(s"Coupling failed: $problem", t)
          if (noJournal)
            Task.pure(Completed)
          else
            persistTask(AgentCouplingFailed(problem), async = true) { (_, _) =>
              Completed
            }.map(_.orThrow)
        }
      }

    override protected def onCoupled(api: AgentClient, after: EventId) =
      promiseTask[Completed] { promise =>
        logger.info(s"Coupled with $api after=${EventId.toString(after)}")
        sessionNumber += 1
        assertThat(attachedOrderIds != null)
        self ! Internal.OnCoupled(promise, attachedOrderIds)
        attachedOrderIds = null
      }

    override protected def onDecoupled = Task {
      logger.debug("onDecoupled")
      sessionNumber += 1
      self ! Internal.OnDecoupled
      Completed
    }

    protected def stopRequested = false
  }

  private val commandQueue = new CommandQueue(logger, batchSize = conf.commandBatchSize) {
    protected def commandParallelism = conf.commandParallelism

    protected def executeCommand(command: AgentCommand.Batch) = {
      val expectedSessionNumber: Int = sessionNumber.get()
      for {
        _ <- Task.defer {
          val delay = delayCommandExecutionAfterErrorUntil.timeLeft
          if (delay >= Duration.Zero) logger.debug(s"${AgentDriver.this.toString}: Delay command after error until ${Timestamp.now + delay}")
          Task.sleep(delay)
        }
        checkedApi <- eventFetcher.coupledApi.map(_.toRight(DecoupledProblem))
        response <-
          (for {
            // Fail on recoupling, later read restarted Agent's attached OrderIds before issuing again AttachOrder
            api <- EitherT(Task.pure(checkedApi))
            response <- EitherT(
              if (sessionNumber.get() != expectedSessionNumber)
                Task.pure(Left(DecoupledProblem))
              else
                // TODO Still a small possibility for race-condition? May log a AgentDuplicateOrder
                api.commandExecute(command))
          } yield response).value
      } yield response
    }

    protected def asyncOnBatchSucceeded(queuedInputResponses: Seq[QueuedInputResponse]) =
      self ! Internal.BatchSucceeded(queuedInputResponses)

    protected def asyncOnBatchFailed(inputs: Vector[Queueable], problem: Problem) =
      if (problem == DecoupledProblem) {  // Avoid loop
        self ! Internal.BatchFailed(inputs, problem)
      } else
        (eventFetcher.invalidateCoupledApi >>
          Task { currentFetchedFuture.foreach(_.cancel()) } >>
          cancelObservationAndAwaitTermination >>
          eventFetcher.decouple >>
          Task {
            self ! Internal.BatchFailed(inputs, problem)
          }
        ).runToFuture
  }

  protected def key = agentRefPath  // Only one version is active at any time
  protected def recoverFromSnapshot(snapshot: Any) = throw new NotImplementedError
  protected def recoverFromEvent(event: ControllerAgentEvent) = throw new NotImplementedError
  protected def snapshot = None  // ControllerOrderKeeper provides the AgentSnapshot

  private def newAgentClient(uri: Uri): AgentClient =
    AgentClient(uri, agentUserAndPassword,
      controllerConfiguration.keyStoreRefOption, controllerConfiguration.trustStoreRefs)(context.system)

  def receive = {
    case input: Input with Queueable if sender() == context.parent && !isTerminating =>
      commandQueue.enqueue(input)
      scheduler.scheduleOnce(conf.commandBatchDelay) {
        self ! Internal.CommandQueueReady  // (Even with commandBatchDelay == 0) delay maySend() such that Queueable pending in actor's mailbox can be queued
      }

    case Input.ChangeUri(uri) =>
      if (uri != client.baseUri) {
        logger.debug(s"ChangeUri $uri")
        // TODO Changing URI in quick succession is not properly solved
        for (u <- changingUri) logger.warn(s"Already changing URI to $u ?")
        changingUri = Some(uri)
        (cancelObservationAndAwaitTermination >>
          eventFetcher.decouple
        ).runToFuture
          .onComplete {
            case Success(_) =>
            case Failure(t) => logger.error(t.toStringWithCauses)
          }
      }

    case Internal.UriChanged =>
      for (uri <- changingUri) {
        client.close()
        logger.debug(s"new AgentClient($uri)")
        client = newAgentClient(uri)
        self ! Internal.FetchEvents
        changingUri = None
      }

    case Input.Terminate(emergency) =>
      this.noJournal = emergency
      // Wait until all pending Agent commands are responded, and do not accept further commands
      if (!isTerminating) {
        logger.debug("Terminate")
        isTerminating = true
        currentFetchedFuture foreach (_.cancel())
        commandQueue.terminate()
        eventFetcherTerminated.completeWith(
          eventFetcher.terminate.runToFuture)  // Rejects current commands waiting for coupling
        stopIfTerminated()
      }

    case Input.StartFetchingEvents | Internal.FetchEvents =>
      if (changingUri.isEmpty && !isTerminating) {
        assertThat(currentFetchedFuture.isEmpty, "Duplicate fetchEvents")
        currentFetchedFuture = Some(
          observeAndConsumeEvents
            .onCancelRaiseError(CancelledMarker)
            .executeWithOptions(_.enableAutoCancelableRunLoops)
            .runToFuture
            .andThen {
              case tried =>
                logger.trace(s"self ! Internal.FetchFinished($tried)")
                self ! Internal.FetchFinished(tried)
            })
      }

    case Internal.OnCoupled(promise, agentOrderIds) =>
      lastProblem = None
      delayNextReleaseEvents = false
      commandQueue.onCoupled(agentOrderIds)
      promise.success(Completed)

    case Internal.OnDecoupled =>
      commandQueue.onDecoupled()

    case Internal.FetchedEvents(stampedEvents, promise) =>
      assertThat(stampedEvents.nonEmpty)
      val reducedStampedEvents = stampedEvents dropWhile { stamped =>
        val drop = stamped.eventId <= lastFetchedEventId
        if (drop) logger.debug(s"Drop duplicate received event: $stamped")
        drop
      }

      // The events must be journaled and handled by ControllerOrderKeeper
      val lastEventId = stampedEvents.last.eventId
      lastFetchedEventId = lastEventId

      promiseFuture[Completed] { p =>
        context.parent ! Output.EventsFromAgent(reducedStampedEvents, p)
      } onComplete {
        case Success(Completed) =>
          self ! Internal.EventsAccepted(lastEventId, promise)
        case o => promise.complete(o)
      }

    case Internal.EventsAccepted(lastEventId, promise) =>
      lastCommittedEventId = lastEventId
      if (releaseEventsCancelable.isEmpty) {
        val delay = if (delayNextReleaseEvents) conf.releaseEventsPeriod else Duration.Zero
        releaseEventsCancelable = Some(scheduler.scheduleOnce(delay) {
          self ! Internal.ReleaseEvents
        })
        delayNextReleaseEvents = true
      }
      promise.success(Completed)

    case Internal.FetchFinished(tried) =>
      // Message is expected only after ChangeUri or after InvalidSessionTokenProblem while executing a command
      logger.debug(s"FetchFinished $tried")
      currentFetchedFuture = None
      (eventFetcher.decouple >>
        eventFetcher.pauseBeforeNextTry(conf.recouplingStreamReader.delay)
      ).runToFuture
        .onComplete { _ =>
          if (!isTerminating) {
            if (changingUri.isDefined) {
              logger.trace("self ! Internal.UriChanged")
              self ! Internal.UriChanged
            } else {
              logger.trace("self ! Internal.FetchEvents")
              self ! Internal.FetchEvents
            }
          }
        }

    case Internal.ReleaseEvents =>
      if (!isTerminating) {
        commandQueue.enqueue(ReleaseEventsQueueable(lastCommittedEventId))
      }

    case Internal.CommandQueueReady =>
      commandQueue.maySend()

    case Internal.BatchSucceeded(responses) =>
      lastProblem = None
      val succeededInputs = commandQueue.handleBatchSucceeded(responses)

      val detachedOrderIds = succeededInputs collect { case Input.DetachOrder(orderId) => orderId }
      if (detachedOrderIds.nonEmpty) {
        context.parent ! Output.OrdersDetached(detachedOrderIds.toSet)
      }

      val cancelledOrderIds = succeededInputs collect { case o: Input.CancelOrder => o.orderId }
      if (cancelledOrderIds.nonEmpty) {
        context.parent ! Output.OrdersCancellationMarked(cancelledOrderIds.toSet)
      }

      val releaseEvents = succeededInputs collect { case o: ReleaseEventsQueueable => o }
      if (releaseEvents.nonEmpty) {
        releaseEventsCancelable foreach (_.cancel())
        releaseEventsCancelable = None
      }
      stopIfTerminated()

    case Internal.BatchFailed(inputs, problem) =>
      problem match {
        case DecoupledProblem |
             InvalidSessionTokenProblem |
             RecouplingStreamReader.TerminatedProblem =>
          logger.debug(s"Command batch failed: $problem")
        case _ =>
          logger.warn(s"Command batch failed: $problem")
      }
      delayCommandExecutionAfterErrorUntil = now + conf.commandErrorDelay
      logger.trace(s"delayCommandExecutionAfterErrorUntil=${Timestamp.ofDeadline(delayCommandExecutionAfterErrorUntil)}")
      commandQueue.handleBatchFailed(inputs)
      stopIfTerminated()
  }

  private def cancelObservationAndAwaitTermination: Task[Completed] =
    currentFetchedFuture match {
      case None => Task.pure(Completed)
      case Some(fetchedFuture) =>
        fetchedFuture.cancel()
        Task.fromFuture(fetchedFuture)
          .onErrorRecover { case t =>
            if (t ne CancelledMarker) logger.warn(t.toStringWithCauses)
            Completed
          }
    }

  protected def observeAndConsumeEvents: Task[Completed] =
    eventFetcher.observe(client, after = lastFetchedEventId)
      //.map { a => logEvent(a); a }
      .bufferTimedAndCounted(
        conf.eventBufferDelay max conf.commitDelay,
        maxCount = conf.eventBufferSize)  // ticks
      .filter(_.nonEmpty)   // Ignore empty ticks
      .mapEval(stampedEvents =>
        promiseTask[Completed] { promise =>
          self ! Internal.FetchedEvents(stampedEvents, promise)
        })
      .completedL
      .map(_ => Completed)

  private def registerAsControllerIfNeeded: Task[Checked[Completed]] =
    Task.defer {
      if (agentRunIdOnce.nonEmpty)
        Task.pure(Right(Completed))
      else
        (for {
          agentRunId <- EitherT(
            client.commandExecute(RegisterAsController(agentRefPath)).map(_.map(_.agentRunId)))
          completed <- EitherT(
            if (noJournal)
              Task.pure(Right(Completed))
            else
              persistTask(AgentRegisteredController(agentRunId), async = true) { (_, _) =>
                // asynchronous
                agentRunIdOnce := agentRunId
                Completed
              })
        } yield completed).value
    }

  private object logEvent {
    // This object is used asynchronously
    private val logEventCount = AtomicLong(0)
    def apply(stampedEvent: Stamped[AnyKeyedEvent]): Unit =
      logger.whenTraceEnabled {
        val i = logEventCount.incrementAndGet()
        logger.trace(s"#$i $stampedEvent")
      }
  }

  private def stopIfTerminated() =
    if (commandQueue.isTerminated) {
      logger.debug("Stop")
      currentFetchedFuture.foreach(_.cancel())
      releaseEventsCancelable.foreach(_.cancel())
      Task.fromFuture(eventFetcherTerminated.future)
        .onErrorRecover { case t => logger.debug(t.toStringWithCauses) }
        .flatMap(_ => closeClient)
        .onErrorRecover { case t => logger.debug(t.toStringWithCauses) }
        .foreach(_ => self ! Internal.Stop)
      become("stopping") {
        case Internal.Stop => context.stop(self)
      }
    }

  private def closeClient: Task[Completed] =
    eventFetcher.invalidateCoupledApi
      .materialize
      .flatMap(_ => client.logoutOrTimeout(10.s/*TODO*/))
      .guarantee(Task {
        client.close()
      })

  override def toString = s"AgentDriver($agentRefPath)"
}

private[controller] object AgentDriver
{
  private val EventClasses = Set[Class[_ <: Event]](classOf[OrderEvent], classOf[AgentControllerEvent.AgentReadyForController])
  private val DecoupledProblem = Problem.pure("Agent has been decoupled")

  def props(agentRefPath: AgentRefPath, uri: Uri, agentRunId: Option[AgentRunId], eventId: EventId,
    agentDriverConfiguration: AgentDriverConfiguration, controllerConfiguration: ControllerConfiguration,
    journalActor: ActorRef @@ JournalActor.type)(implicit s: Scheduler)
  =
    Props { new AgentDriver(agentRefPath, uri, agentRunId, eventId, agentDriverConfiguration, controllerConfiguration, journalActor) }

  sealed trait Queueable extends Input {
    def toShortString = toString
  }

  private[agent] final case class ReleaseEventsQueueable(agentEventId: EventId) extends Queueable

  sealed trait Input
  object Input {
    case object StartFetchingEvents

    final case class ChangeUri(uri: Uri)

    final case class AttachOrder(order: Order[Order.IsFreshOrReady], agentRefPath: AgentRefPath, signedWorkflow: Signed[Workflow]/*TODO Separate this*/)
    extends Input with Queueable {
      def orderId = order.id
      override lazy val hashCode = 31 * order.id.hashCode + signedWorkflow.value.id.hashCode  // Accelerate CommandQueue
      override def toShortString = s"AttachOrder(${orderId.string}, ${order.workflowPosition}, ${order.state.getClass.simpleScalaName})"
    }

    final case class DetachOrder(orderId: OrderId) extends Input with Queueable

    final case class CancelOrder(orderId: OrderId, mode: CancelMode) extends Input with Queueable

    final case class Terminate(noJournal: Boolean = false) extends DeadLetterSuppression
  }

  object Output {
    final case class EventsFromAgent(stamped: Seq[Stamped[AnyKeyedEvent]], promise: Promise[Completed])
    final case class OrdersDetached(orderIds: Set[OrderId])
    final case class OrdersCancellationMarked(orderIds: Set[OrderId])
  }

  private object Internal {
    final case object CommandQueueReady extends DeadLetterSuppression
    final case class BatchSucceeded(responses: Seq[QueuedInputResponse]) extends DeadLetterSuppression
    final case class BatchFailed(inputs: Seq[Queueable], problem: Problem) extends DeadLetterSuppression
    final case object FetchEvents extends DeadLetterSuppression
    final case class FetchedEvents(events: Seq[Stamped[AnyKeyedEvent]], promise: Promise[Completed])
      extends DeadLetterSuppression
    final case class FetchFinished(tried: Try[Completed]) extends DeadLetterSuppression
    final case class OnCoupled(promise: Promise[Completed], orderIds: Set[OrderId]) extends DeadLetterSuppression
    final case object OnDecoupled extends DeadLetterSuppression
    final case class EventsAccepted(lastEventId: EventId, promise: Promise[Completed]) extends DeadLetterSuppression
    final case object ReleaseEvents extends DeadLetterSuppression
    final case object UriChanged extends DeadLetterSuppression
    final case object Stop
  }

  private object CancelledMarker extends Exception with NoStackTrace
}
