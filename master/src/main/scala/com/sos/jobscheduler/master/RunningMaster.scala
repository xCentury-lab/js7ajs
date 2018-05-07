package com.sos.jobscheduler.master

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import com.google.common.io.Closer
import com.google.inject.Stage.{DEVELOPMENT, PRODUCTION}
import com.google.inject.util.Modules
import com.google.inject.util.Modules.EMPTY_MODULE
import com.google.inject.{Guice, Injector, Module}
import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.problem.Checked
import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.utils.Collections.implicits.RichTraversableOnce
import com.sos.jobscheduler.base.utils.ScalaUtils.{RichPartialFunction, RichThrowable}
import com.sos.jobscheduler.common.akkautils.CatchingActor
import com.sos.jobscheduler.common.event.{EventIdClock, EventReader}
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.log.Log4j
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.core.StartUp
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.core.event.journal.{EventReaderProvider, JournalEventReaderProvider}
import com.sos.jobscheduler.core.filebased.{FileBasedApi, Repo}
import com.sos.jobscheduler.data.event.{Event, Stamped}
import com.sos.jobscheduler.data.filebased.{FileBased, FileBasedId, FileBasedsOverview, TypedPath}
import com.sos.jobscheduler.data.order.{FreshOrder, Order, OrderId}
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import com.sos.jobscheduler.master.configuration.inject.MasterModule
import com.sos.jobscheduler.master.data.MasterCommand
import com.sos.jobscheduler.master.tests.TestEventCollector
import com.sos.jobscheduler.master.web.MasterWebServer
import java.nio.file.Files.{createDirectory, exists}
import java.nio.file.Path
import java.time.{Duration, Instant}
import monix.eval.Task
import monix.execution.Scheduler
import org.jetbrains.annotations.TestOnly
import scala.collection.immutable.Seq
import scala.concurrent.{Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * JobScheduler Agent.
 *
 * Integration test in engine-tests, for example com.sos.jobscheduler.tests.jira.js1291.JS1291AgentIT.
 *
 * @author Joacim Zschimmer
 */
abstract class RunningMaster private(
  val webServer: MasterWebServer,
  val orderApi: OrderApi.WithCommands,
  val orderKeeper: ActorRef,
  val terminated: Future[Completed],
  closer: Closer,
  @TestOnly val injector: Injector)
extends AutoCloseable
{
  def executeCommand(command: MasterCommand): Task[command.MyResponse]

  def addOrder(order: FreshOrder): Task[Checked[Boolean]] =
    orderApi.addOrder(order)

  def addOrderBlocking(order: FreshOrder)(implicit s: Scheduler): Boolean =
    orderApi.addOrder(order).runAsync.await(99.s).orThrow

  val localUri: Uri = webServer.localUri
  val eventReader: EventReader[Event] = injector.instance[EventReaderProvider[Event]]

  def close() = closer.close()
}

object RunningMaster {
  val StartedAt = Instant.now()
  private val logger = Logger(getClass)

  def run[A](configuration: MasterConfiguration, timeout: Option[Duration] = None)(body: RunningMaster ⇒ Unit)(implicit s: Scheduler): Unit = {
    autoClosing(apply(configuration) await timeout) { master ⇒
      for (t ← master.terminated.failed) logger.error(t.toStringWithCauses, t)
      body(master)
      master.terminated await timeout
    }
  }

  def runForTest(directory: Path, eventCollector: Option[TestEventCollector] = None)(body: RunningMaster ⇒ Unit)(implicit s: Scheduler): Unit = {
    val injector = newInjector(directory)
    eventCollector foreach (_.start(injector.instance[ActorSystem], injector.instance[StampedKeyedEventBus]))
    runForTest(injector)(body)
  }

  def runForTest(injector: Injector)(body: RunningMaster ⇒ Unit)(implicit s: Scheduler): Unit =
    autoClosing(RunningMaster(injector) await 99.s) { master ⇒
      try {
        body(master)
        master.executeCommand(MasterCommand.Terminate) await 99.s
        master.terminated await 99.s
      } catch { case NonFatal(t) if master.terminated.failed.isCompleted ⇒
        t.addSuppressed(master.terminated.failed.successValue)
        throw t
      }
    }

  def newInjector(directory: Path, module: Module = EMPTY_MODULE): Injector =
    Guice.createInjector(DEVELOPMENT, Modules `override` newModule(directory) `with` module)

  private def newModule(directory: Path): Module =
    new MasterModule(MasterConfiguration.forTest(configAndData = directory / "master"))

  def apply(configuration: MasterConfiguration): Task[RunningMaster] =
    apply(new MasterModule(configuration))

  private def apply(module: Module): Task[RunningMaster] =
    apply(Guice.createInjector(PRODUCTION, module))

  def apply(injector: Injector): Task[RunningMaster] =
    Task.deferAction { implicit scheduler ⇒
      val masterConfiguration = injector.instance[MasterConfiguration]

      StartUp.logStartUp(masterConfiguration.configDirectory, masterConfiguration.dataDirectory)
      masterConfiguration.stateDirectory match {
        case o if !exists(o) ⇒ createDirectory(o)
        case _ ⇒
      }

      val actorSystem = injector.instance[ActorSystem]
      val eventIdClock = injector.instance[EventIdClock]
      val eventReaderProvider = injector.instance[JournalEventReaderProvider[Event]]
      val closer = injector.instance[Closer]
      val webServer = injector.instance[MasterWebServer]

      val (orderKeeper, actorStopped) = CatchingActor.actorOf[Completed](
          _ ⇒ Props {
            new MasterOrderKeeper(
              masterConfiguration,
              eventIdClock)(
              injector.instance[TimerService],
              eventReaderProvider,
              injector.instance[StampedKeyedEventBus]) },
          onStopped = _ ⇒ Success(Completed)
        )(actorSystem)

      val fileBasedApi: FileBasedApi = new FileBasedApi {
        def overview[A <: FileBased: FileBased.Companion](implicit O: FileBasedsOverview.Companion[A]): Task[Stamped[O.Overview]] =
          for (stamped ← getRepo) yield
            for (repo ← stamped) yield
              O.fileBasedsToOverview(repo.currentTyped[A].values.toImmutableSeq)

        def idTo[A <: FileBased: FileBased.Companion](id: A#Id) =
          for (stamped ← getRepo) yield
            for (repo ← stamped) yield
              repo.idTo[A](id)

        def fileBaseds[A <: FileBased: FileBased.Companion]: Task[Stamped[Seq[A]]] =
          for (stamped ← getRepo) yield
            for (repo ← stamped) yield
              repo.currentTyped[A].values.toImmutableSeq.sortBy/*for determinstic tests*/(_.id: FileBasedId[TypedPath])

        def pathToCurrentFileBased[A <: FileBased: FileBased.Companion](path: A#Path): Task[Checked[Stamped[A]]] =
          for (stamped ← getRepo; repo = stamped.value) yield
            for (a ← repo.currentTyped[A].checked(path)) yield
              stamped.copy(value = a)

        private def getRepo: Task[Stamped[Repo]] = {
          import masterConfiguration.akkaAskTimeout  // TODO May timeout while Master recovers
          Task.deferFuture(
            (orderKeeper ? MasterOrderKeeper.Command.GetRepo).mapTo[Stamped[Repo]])
        }
      }

      val orderApi = new OrderApi.WithCommands {
        import masterConfiguration.akkaAskTimeout

        def addOrder(order: FreshOrder) =
          Task.deferFuture(
            (orderKeeper ? MasterOrderKeeper.Command.AddOrder(order)).mapTo[MasterOrderKeeper.Response.ForAddOrder])
            .map(_.created)

        def order(orderId: OrderId): Task[Option[Order[Order.State]]] =
          Task.deferFuture(
            (orderKeeper ? MasterOrderKeeper.Command.GetOrder(orderId)).mapTo[Option[Order[Order.State]]])

        def orders: Task[Stamped[Seq[Order[Order.State]]]] =
          Task.deferFuture(
            (orderKeeper ? MasterOrderKeeper.Command.GetOrders).mapTo[Stamped[Seq[Order[Order.State]]]])

        def orderCount =
          Task.deferFuture(
            (orderKeeper ? MasterOrderKeeper.Command.GetOrderCount).mapTo[Int])
      }

      webServer.setClients(fileBasedApi, orderApi)
      val webServerReady = webServer.start()

      val terminated = actorStopped
        .andThen { case Failure(t) ⇒
          logger.error(t.toStringWithCauses, t)
        }
        .andThen { case _ ⇒
          blocking {
            logger.debug("Delaying close to let HTTP server respond open requests")
            sleep(500.ms)
          }
          closer.close()  // Close automatically after termination
        }
      for (_ ← Task.fromFuture(webServerReady)) yield {
        def execCmd(command: MasterCommand): Task[command.MyResponse] = {
          import masterConfiguration.akkaAskTimeout
          (command match {
            case MasterCommand.EmergencyStop ⇒
              val msg = "Command EmergencyStop received: JOBSCHEDULER MASTER STOPS NOW"
              logger.error(msg)
              Log4j.shutdown()
              sys.runtime.halt(99)
              Task.pure(MasterCommand.Response.Accepted)  // unreachable

            case _ ⇒
              Task.deferFuture(orderKeeper ? command)
          }) map (_.asInstanceOf[command.MyResponse])
        }
        val master = new RunningMaster(webServer, orderApi, orderKeeper, terminated, closer, injector) {
          def executeCommand(command: MasterCommand): Task[command.MyResponse] =
            execCmd(command)
        }
        webServer.setExecuteCommand(command ⇒ master.executeCommand(command))
        master
      }
    }
}
