package com.sos.jobscheduler.agent.orderprocessing

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.sos.jobscheduler.agent.configuration.AgentConfiguration
import com.sos.jobscheduler.agent.data.commands.Command
import com.sos.jobscheduler.agent.task.AgentTaskFactory
import com.sos.jobscheduler.common.auth.UserId
import com.sos.jobscheduler.common.event.EventIdGenerator
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.engine2.order.OrderEvent
import com.sos.jobscheduler.data.event.{EventRequest, EventSeq, KeyedEvent}
import com.sos.jobscheduler.shared.event.SnapshotKeyedEventBus
import java.nio.file.{Files, Path}
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * @author Joacim Zschimmer
  */
@Singleton
final class OrderHandler @Inject private(
  actorSystem: ActorSystem,
  conf: AgentConfiguration)
  (implicit
    timerService: TimerService,
    eventBus: SnapshotKeyedEventBus,
    newTask: AgentTaskFactory,
    eventIdGenerator: EventIdGenerator,
    actorRefFactory: ActorRefFactory,
    executionContext: ExecutionContext)
{
  private val commandAskTimeout = Timeout(conf.startupTimeout.toFiniteDuration)

  // This is a Future to allow parallel startup.
  // Commands are accepted but execution is delayed until the Future has been completed.
  private val agentActorFutureOption: Option[Future[ActorRef]] =
    for (liveDirectory ← conf.liveDirectoryOption if conf.experimentalOrdersEnabled) yield {
      require(Files.isDirectory(liveDirectory), s"Missing live directory '$liveDirectory'")
      val actorRef = newActor(liveDirectory)
      val askTimeout = Timeout(conf.startupTimeout.toFiniteDuration)
      for (_ ← actorRef.ask(AgentActor.Input.Start)(askTimeout).mapTo[AgentActor.Output.Started.type])
        yield actorRef
    }

  private def newActor(jobConfigurationDirectory: Path) = {
    val stateDirectory = conf.stateDirectoryOption getOrElse sys.error("Missing data directory")
    if (!Files.exists(stateDirectory)) {
      Files.createDirectory(stateDirectory)
    }
    actorSystem.actorOf(
      Props { new AgentActor(
        stateDirectory = stateDirectory,
        jobConfigurationDirectory = jobConfigurationDirectory,
        askTimeout = conf.akkaAskTimeout,
        syncOnCommit = conf.journalSyncOnCommit)
      },
      "JobScheduler-Agent")
  }

  private def agentActorFuture = agentActorFutureOption getOrElse {
    throw new IllegalStateException("Experimental order processing is not enabled")
  }

  def execute(userId: UserId, command: Command): Future[command.Response] =
    for (agentActor ← agentActorFuture;
         response ← agentActor.ask(AgentActor.Input.CommandFromMaster(userId, command))(commandAskTimeout) map { _.asInstanceOf[command.Response] })
      yield response

  def events(userId: UserId, request: EventRequest[OrderEvent]): Future[EventSeq[Seq, KeyedEvent[OrderEvent]]] =
    for (agentActor ← agentActorFuture;
         response ← {
            val promise = Promise[EventSeq[Seq, KeyedEvent[OrderEvent]]]
            agentActor ! AgentActor.Input.RequestEvents(
              userId,
              AgentOrderKeeper.Input.RequestEvents(
                after = request.after,
                timeout = request.timeout,
                limit = request.limit,
                promise))
            promise.future
         })
      yield response
}
