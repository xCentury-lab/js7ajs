package js7.controller

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import cats.syntax.flatMap._
import com.google.inject.Stage.{DEVELOPMENT, PRODUCTION}
import com.google.inject.util.Modules
import com.google.inject.util.Modules.EMPTY_MODULE
import com.google.inject.{Guice, Injector, Module}
import com.typesafe.config.{Config, ConfigFactory}
import java.nio.file.Files.deleteIfExists
import java.nio.file.Path
import js7.base.auth.{SimpleUser, UserAndPassword}
import js7.base.eventbus.{EventPublisher, StandardEventBus}
import js7.base.generic.Completed
import js7.base.problem.Checked
import js7.base.problem.Checked._
import js7.base.time.ScalaTime._
import js7.base.utils.Assertions.assertThat
import js7.base.utils.AutoClosing.autoClosing
import js7.base.utils.Closer.syntax.RichClosersAutoCloseable
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.{Closer, SetOnce}
import js7.common.akkahttp.web.session.{SessionRegister, SimpleSession}
import js7.common.event.{EventIdGenerator, StrictEventWatch}
import js7.common.guice.GuiceImplicits.RichInjector
import js7.common.scalautil.FileUtils.syntax._
import js7.common.scalautil.Futures.implicits._
import js7.common.scalautil.Logger
import js7.common.scalautil.MonixUtils.syntax._
import js7.common.utils.FreeTcpPortFinder.findFreeTcpPort
import js7.controller.RunningController._
import js7.controller.client.{AkkaHttpControllerApi, HttpControllerApi}
import js7.controller.cluster.{Cluster, ClusterFollowUp}
import js7.controller.command.ControllerCommandExecutor
import js7.controller.configuration.ControllerConfiguration
import js7.controller.configuration.inject.ControllerModule
import js7.controller.data.{ControllerCommand, ControllerState}
import js7.controller.problems.ControllerIsNotYetReadyProblem
import js7.controller.web.ControllerWebServer
import js7.core.command.{CommandExecutor, CommandMeta}
import js7.core.crypt.generic.GenericSignatureVerifier
import js7.core.event.StampedKeyedEventBus
import js7.core.event.journal.JournalActor
import js7.core.event.journal.JournalActor.Output
import js7.core.event.journal.recover.Recovered
import js7.core.event.state.JournaledStatePersistence
import js7.core.problems.{ClusterNodeIsNotActiveProblem, ClusterNodeIsNotYetReadyProblem, JobSchedulerIsShuttingDownProblem}
import js7.data.Problems.PassiveClusterNodeShutdownNotAllowedProblem
import js7.data.cluster.ClusterState
import js7.data.event.{EventRequest, Stamped}
import js7.data.order.OrderEvent.OrderFinished
import js7.data.order.{FreshOrder, OrderEvent}
import monix.eval.Task
import monix.execution.Scheduler
import org.jetbrains.annotations.TestOnly
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, blocking}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}
import shapeless.tag
import shapeless.tag.@@

/**
 * JS7 Controller.
 *
 * Integration test in engine-tests, for example js7.tests.jira.js1291.JS1291AgentIT.
 *
 * @author Joacim Zschimmer
 */
final class RunningController private(
  val eventWatch: StrictEventWatch,
  webServer: ControllerWebServer,
  val fileBasedApi: MainFileBasedApi,
  val orderApi: OrderApi.WithCommands,
  val clusterState: Task[ClusterState],
  commandExecutor: ControllerCommandExecutor,
  whenReady: Future[ControllerOrderKeeper.ControllerReadyTestIncident.type],
  terminated1: Future[ControllerTermination],
  val testEventBus: StandardEventBus[Any],
  closer: Closer,
  val injector: Injector)
extends AutoCloseable
{
  implicit val scheduler = injector.instance[Scheduler]
  val config: Config = injector.instance[Config]
  val sessionRegister: SessionRegister[SimpleSession] = injector.instance[SessionRegister[SimpleSession]]

  @TestOnly
  lazy val actorSystem = injector.instance[ActorSystem]

  val terminated: Future[ControllerTermination] =
    for (o <- terminated1) yield {
      close()
      o
    }

  def terminate(): Task[ControllerTermination] =
    if (terminated.isCompleted)  // Works only if previous termination has been completed
      Task.fromFuture(terminated)
    else
      injector.instance[ActorSystem].whenTerminated.value match {
        case Some(Failure(t)) => Task.raiseError(t)
        case Some(Success(_)) =>
          logger.warn("Controller terminate: Akka has already been terminated")
          Task.pure(ControllerTermination.Terminate(restart = false))
        case None =>
          logger.debug("terminate")
          for {
            _ <- executeCommandAsSystemUser(ControllerCommand.ShutDown()).map(_.orThrow)
            t <- Task.fromFuture(terminated)
          } yield t
      }

  def executeCommandForTest(command: ControllerCommand): Checked[command.Response] =
    executeCommandAsSystemUser(command) await 99.seconds

  def executeCommandAsSystemUser(command: ControllerCommand): Task[Checked[command.Response]] =
    for {
      checkedSession <- sessionRegister.systemSession
      checkedChecked <- checkedSession.map(session => executeCommand(command, CommandMeta(session.currentUser))).evert
    } yield checkedChecked.flatten

  def executeCommand(command: ControllerCommand, meta: CommandMeta): Task[Checked[command.Response]] =
    commandExecutor.executeCommand(command, meta)

  def addOrder(order: FreshOrder): Task[Checked[Boolean]] =
    orderApi.addOrder(order)

  @TestOnly
  def runOrder(order: FreshOrder): Seq[Stamped[OrderEvent]] = {
    val timeout = 99.s
    val eventId = eventWatch.lastAddedEventId
    addOrderBlocking(order)
    eventWatch
      .observe(EventRequest.singleClass[OrderEvent](eventId, Some(timeout + 9.s)))
      .takeWhile(_.value.key == order.id)
      .map(o => o.copy(value = o.value.event))
      .takeWhileInclusive(o => !o.value.isInstanceOf[OrderFinished])
      .toListL
      .await(timeout)
  }

  @TestOnly
  def addOrderBlocking(order: FreshOrder): Boolean =
    orderApi.addOrder(order).runToFuture.await(99.s).orThrow

  @TestOnly
  def waitUntilReady(): Unit =
    whenReady await 99.s

  @TestOnly
  lazy val localUri = webServer.localUri

  private val httpApiUserAndPassword = SetOnce[Option[UserAndPassword]]
  private val _httpApi = SetOnce[AkkaHttpControllerApi]

  @TestOnly
  def httpApiDefaultLogin(userAndPassword: Option[UserAndPassword]): Unit = {
    assertThat(_httpApi.isEmpty)
    httpApiUserAndPassword := userAndPassword
    httpApi
  }

  @TestOnly
  lazy val httpApi: HttpControllerApi = {
    if (_httpApi.isEmpty) {
      httpApiUserAndPassword.trySet(None)
      _httpApi := new AkkaHttpControllerApi(localUri, httpApiUserAndPassword.get, actorSystem = actorSystem, config = config)
    }
    _httpApi.get
  }

  @TestOnly
  def journalActorState: Output.JournalActorState =
    (actorSystem.actorSelection("user/Journal") ? JournalActor.Input.GetJournalActorState)(Timeout(99.s))
      .mapTo[JournalActor.Output.JournalActorState]
      .await(99.s)

  def close() = {
    for (o <- _httpApi) o.close()  // Close before server
    closer.close()
  }
}

object RunningController
{
  private val logger = Logger(getClass)

  def run[A](configuration: ControllerConfiguration, timeout: Option[FiniteDuration] = None)(body: RunningController => Unit)(implicit s: Scheduler): Unit =
    autoClosing(apply(configuration) await timeout) { controller =>
      for (t <- controller.terminated.failed) logger.error(t.toStringWithCauses, t)
      body(controller)
      controller.terminated await timeout
    }

  def newInjectorForTest(directory: Path, module: Module = EMPTY_MODULE,
    config: Config = ConfigFactory.empty,
    httpPort: Option[Int] = Some(findFreeTcpPort()),
    httpsPort: Option[Int] = None,
    mutualHttps: Boolean = false,
    name: String)
  : Injector =
    Guice.createInjector(DEVELOPMENT,
      Modules `override` new ControllerModule(ControllerConfiguration.forTest(
        configAndData = directory,
        config,
        httpPort = httpPort,
        httpsPort = httpsPort,
        mutualHttps = mutualHttps,
        name = name))
      `with` module)

  def apply(configuration: ControllerConfiguration): Future[RunningController] =
    fromInjector(Guice.createInjector(PRODUCTION, new ControllerModule(configuration)))

  def fromInjector(injector: Injector): Future[RunningController] =
    new Starter(injector).start()

  private class Starter(injector: Injector)
  {
    private val controllerConfiguration = injector.instance[ControllerConfiguration]
    private implicit val scheduler = injector.instance[Scheduler]
    private implicit lazy val closer = injector.instance[Closer]
    private implicit lazy val actorSystem = injector.instance[ActorSystem]
    private lazy val signatureVerifier = GenericSignatureVerifier(controllerConfiguration.config).orThrow
    import controllerConfiguration.{akkaAskTimeout, journalMeta}
    @volatile private var clusterStartupTermination = ControllerTermination.Terminate()

    private[RunningController] def start(): Future[RunningController] = {
      val whenRecovered = Future {  // May take minutes !!!
        ControllerJournalRecoverer.recover(journalMeta, controllerConfiguration.config)
      }
      val testEventBus = injector.instance[StandardEventBus[Any]]
      val whenReady = testEventBus.when[ControllerOrderKeeper.ControllerReadyTestIncident.type].runToFuture  // TODO Replace by a new StampedEventBus ?
      // Start-up some stuff while recovering
      val journalActor = tag[JournalActor.type](actorSystem.actorOf(
        JournalActor.props[ControllerState](journalMeta, controllerConfiguration.journalConf,
          injector.instance[StampedKeyedEventBus], scheduler, injector.instance[EventIdGenerator],
          useJournaledStateAsSnapshot = true),
        "Journal"))
      signatureVerifier
      val persistence = new JournaledStatePersistence[ControllerState](journalActor, controllerConfiguration.journalConf).closeWithCloser
      val recovered = Await.result(whenRecovered, Duration.Inf).closeWithCloser
      val cluster = new Cluster(
        journalMeta,
        persistence,
        recovered.eventWatch,
        controllerConfiguration.controllerId,
        controllerConfiguration.journalConf,
        controllerConfiguration.clusterConf,
        controllerConfiguration.httpsConfig,
        controllerConfiguration.config,
        injector.instance[EventIdGenerator],
        testEventBus)

      // clusterFollowUpFuture terminates when this cluster node becomes active or terminates
      // maybePassiveState accesses the current ControllerState while this node is passive, otherwise it is None
      val (currentPassiveControllerState, clusterFollowUpTask) = startCluster(cluster, recovered)
      val clusterFollowUpFuture = clusterFollowUpTask
        .flatTap {
          case Right(ClusterFollowUp.BecomeActive(recovered)) =>
            Task { persistence.start(recovered.state) }
          case _ => Task.unit
        }
        .executeWithOptions(_.enableAutoCancelableRunLoops)
        .runToFuture
      val (orderKeeperStarted, orderKeeperTerminated) = {
        val started = clusterFollowUpFuture.map(_.flatMap(
          startControllerOrderKeeper(journalActor, cluster, _, testEventBus)))
        (started.map(_.map(_.actor)),
          started.flatMap {
            case Left(termination) =>
              Future.successful(termination)
            case Right(o) =>
              o.termination andThen { case tried =>
                for (t <- tried.failed) {
                  logger.error(s"ControllerOrderKeeper failed with ${t.toStringWithCauses}", t)  // Support diagnosis
                }
                clusterFollowUpFuture.cancel()
              }
          })
      }
      for (t <- orderKeeperStarted.failed) logger.debug("orderKeeperStarted => " + t.toStringWithCauses, t)
      //for (t <- orderKeeperTerminated.failed) logger.debug("orderKeeperTerminated => " + t.toStringWithCauses, t)
      val orderKeeperTask = Task.defer {
        orderKeeperStarted.value match {
          case None => Task.raiseError(ControllerIsNotYetReadyProblem.throwable)
          case Some(orderKeeperTry) =>
            orderKeeperTry match {
              case Failure(t) => Task.raiseError(t)
              case Success(Left(_)) => Task.raiseError(JobSchedulerIsShuttingDownProblem.throwable)
              case Success(Right(actor)) => Task.pure(actor)
            }
        }
      }
      val commandExecutor = new ControllerCommandExecutor(
        new MyCommandExecutor(cluster,
          onShutDownPassive = termination => Task {
            clusterStartupTermination = termination
            clusterFollowUpFuture.cancel()
          },
          orderKeeperStarted.map(_.toOption)))
      val controllerState = Task.defer {
        if (persistence.isStarted)
          persistence.currentState map Right.apply
        else
          currentPassiveControllerState.map(_.toChecked(ClusterNodeIsNotYetReadyProblem))
      }
      val fileBasedApi = new MainFileBasedApi(controllerState)
      val orderApi = new MainOrderApi(controllerState, orderKeeperTask)

      val webServer = injector.instance[ControllerWebServer.Factory]
        .apply(fileBasedApi, orderApi, commandExecutor,
          cluster.currentClusterState,
          controllerState,
          recovered.totalRunningSince,  // Maybe different from JournalHeader
          recovered.eventWatch
        ).closeWithCloser

      for (_ <- webServer.start().runToFuture) yield {
        createSessionTokenFile(injector.instance[SessionRegister[SimpleSession]])
        controllerConfiguration.stateDirectory / "http-uri" := webServer.localHttpUri.fold(_ => "", o => s"$o/controller")
        new RunningController(recovered.eventWatch.strict, webServer, fileBasedApi, orderApi, cluster.currentClusterState,
          commandExecutor,
          whenReady, orderKeeperTerminated, testEventBus, closer, injector)
      }
    }

    private def createSessionTokenFile(sessionRegister: SessionRegister[SimpleSession]): Unit = {
      val sessionTokenFile = controllerConfiguration.stateDirectory / "session-token"
      blocking {
        sessionRegister.createSystemSession(SimpleUser.System, sessionTokenFile)
          .runToFuture await controllerConfiguration.akkaAskTimeout.duration
      }
      closer onClose { deleteIfExists(sessionTokenFile) }
    }

    /** @return Task(None) when cancelled. */
    private def startCluster(
      cluster: Cluster[ControllerState],
      recovered: Recovered[ControllerState])
    : (Task[Option[ControllerState]], Task[Either[ControllerTermination.Terminate, ClusterFollowUp[ControllerState]]]) =
    {
      class StartingClusterCancelledException extends NoStackTrace
      val recoveredState = recovered.recoveredState getOrElse ControllerState.Undefined
      val (passiveState, followUpTask) = cluster.start(recovered, recoveredState)
      passiveState ->
        followUpTask
          .doOnCancel(Task { logger.debug("Cancel Cluster") })
          .onCancelRaiseError(new StartingClusterCancelledException)
          .map(_.orThrow)
          .map(Right.apply)
          .onErrorRecoverWith {
            case _: StartingClusterCancelledException => Task { Left(clusterStartupTermination) }
          }
    }

    private def startControllerOrderKeeper(
      journalActor: ActorRef @@ JournalActor.type,
      cluster: Cluster[ControllerState],
      followUp: ClusterFollowUp[ControllerState],
      testEventPublisher: EventPublisher[Any])
    : Either[ControllerTermination.Terminate, OrderKeeperStarted] = {
      logger.debug(s"startControllerOrderKeeper(clusterFollowUp=${followUp.getClass.simpleScalaName})")
      followUp match {
        //case _: ClusterFollowUp.Terminate[ControllerState, Event] =>
        //  Left(ControllerTermination.Terminate(restart = false))

        case ClusterFollowUp.BecomeActive(recovered: Recovered[ControllerState @unchecked]) =>
          val terminationPromise = Promise[ControllerTermination]()
          val actor = actorSystem.actorOf(
            Props {
              new ControllerOrderKeeper(terminationPromise, journalActor, cluster, controllerConfiguration,
                signatureVerifier, testEventPublisher)
            },
            "ControllerOrderKeeper")
          actor ! ControllerOrderKeeper.Input.Start(recovered)
          val termination = terminationPromise.future
            .andThen { case Failure(t) => logger.error(t.toStringWithCauses, t) }
            .andThen { case _ => closer.close() }  // Close automatically after termination
          Right(OrderKeeperStarted(tag[ControllerOrderKeeper](actor), termination))
      }
    }
  }

  private class MyCommandExecutor(
    cluster: Cluster[ControllerState],
    onShutDownPassive: ControllerTermination.Terminate => Task[Unit],
    orderKeeperStarted: Future[Option[ActorRef @@ ControllerOrderKeeper]])
    (implicit timeout: Timeout)
  extends CommandExecutor[ControllerCommand]
  {
    def executeCommand(command: ControllerCommand, meta: CommandMeta): Task[Checked[command.Response]] =
      (command match {
        case command: ControllerCommand.ShutDown =>
          cluster.isActive.flatMap(isActive =>
            if (!isActive)
              if (command.clusterAction.isEmpty)
                onShutDownPassive(ControllerTermination.Terminate(restart = command.restart))
                  .map(_ => Right(ControllerCommand.Response.Accepted))
              else
                Task.pure(Left(PassiveClusterNodeShutdownNotAllowedProblem))
            else
              Task.deferFutureAction(implicit s =>
                orderKeeperStarted flatMap {
                  case None =>  // ControllerOrderKeeper does not start
                    Future.successful(
                      if (command.clusterAction.nonEmpty)
                        Left(PassiveClusterNodeShutdownNotAllowedProblem)
                      else
                        Right(ControllerCommand.Response.Accepted))
                  case Some(actor) =>
                    (actor ? ControllerOrderKeeper.Command.Execute(command, meta))
                      .mapTo[Checked[command.Response]]
                }))

        case ControllerCommand.ClusterAppointNodes(idToUri, activeId) =>
          cluster.appointNodes(idToUri, activeId)
            .map(_.map((_: Completed) => ControllerCommand.Response.Accepted))

        case ControllerCommand.InternalClusterCommand(clusterCommand) =>
          cluster.executeCommand(clusterCommand)
            .map(_.map(ControllerCommand.InternalClusterCommand.Response.apply))

        case _ =>
          orderKeeperStarted.value match {
            case None =>  // Cluster node is still waiting for activation
              Task.pure(Left(ClusterNodeIsNotActiveProblem))
            case Some(Failure(t)) =>
              Task.raiseError(t)
            case Some(Success(None)) =>   // ControllerOrderKeeper does not start
              Task.pure(Left(JobSchedulerIsShuttingDownProblem))
            case Some(Success(Some(actor))) =>
              Task.deferFuture(
                (actor ? ControllerOrderKeeper.Command.Execute(command, meta))
                  .mapTo[Checked[command.Response]])
          }
      }).map(_.map((_: ControllerCommand.Response).asInstanceOf[command.Response]))
  }

  private case class OrderKeeperStarted(actor: ActorRef @@ ControllerOrderKeeper, termination: Future[ControllerTermination])
}
