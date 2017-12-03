package com.sos.jobscheduler.master.order

import akka.Done
import akka.actor.{ActorRef, Props, Stash, Status, Terminated}
import com.sos.jobscheduler.base.circeutils.typed.{Subtype, TypedJsonCodec}
import com.sos.jobscheduler.base.utils.Collections.implicits.InsertableMutableMap
import com.sos.jobscheduler.base.utils.IntelliJUtils.intelliJuseImport
import com.sos.jobscheduler.common.akkautils.Akkas.encodeAsActorName
import com.sos.jobscheduler.common.akkautils.SupervisorStrategies
import com.sos.jobscheduler.common.event.EventIdGenerator
import com.sos.jobscheduler.common.event.collector.EventCollector
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.scalautil.xmls.FileSource
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.event.{Event, EventId, KeyedEvent, Stamped}
import com.sos.jobscheduler.data.order.OrderEvent.{OrderAdded, OrderCoreEvent, OrderStdWritten}
import com.sos.jobscheduler.data.order.{Order, OrderEvent, OrderId}
import com.sos.jobscheduler.data.workflow.{Workflow, WorkflowPath}
import com.sos.jobscheduler.master.KeyedEventJsonCodecs.MasterKeyedEventJsonCodec
import com.sos.jobscheduler.master.command.MasterCommand
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import com.sos.jobscheduler.master.order.MasterOrderKeeper._
import com.sos.jobscheduler.master.order.agent.{AgentDriver, AgentXmlParser}
import com.sos.jobscheduler.master.{AgentEventId, AgentEventIdEvent}
import com.sos.jobscheduler.shared.common.ActorRegister
import com.sos.jobscheduler.shared.event.StampedKeyedEventBus
import com.sos.jobscheduler.shared.event.journal.JsonJournalRecoverer.startJournalAndFinishRecovery
import com.sos.jobscheduler.shared.event.journal.{GzipCompression, JsonJournalActor, JsonJournalMeta, JsonJournalRecoverer, KeyedEventJournalingActor, RecoveredJournalingActors}
import com.sos.jobscheduler.shared.filebased.TypedPathDirectoryWalker.forEachTypedFile
import com.sos.jobscheduler.shared.workflow.Transitions
import java.time.Duration
import scala.collection.mutable
import scala.concurrent.Future

/**
  * @author Joacim Zschimmer
  */
final class MasterOrderKeeper(
  masterConfiguration: MasterConfiguration,
  scheduledOrderGeneratorKeeper: ScheduledOrderGeneratorKeeper)
  (implicit
    timerService: TimerService,
    eventIdGenerator: EventIdGenerator,
    eventCollector: EventCollector,
    keyedEventBus: StampedKeyedEventBus)
extends KeyedEventJournalingActor[Event]
with Stash {

  override val supervisorStrategy = SupervisorStrategies.escalate

  import context.{become, dispatcher}
  intelliJuseImport(dispatcher)

  private val journalFile = masterConfiguration.stateDirectory / "journal"
  private val agentRegister = new AgentRegister
  private val pathToWorkflow = mutable.Map[WorkflowPath, Workflow]()
  private val orderRegister = mutable.Map[OrderId, OrderEntry]()
  private var detachingSuspended = false
  protected val journalActor = context.watch(context.actorOf(
    Props {
      new JsonJournalActor(MyJournalMeta, journalFile, syncOnCommit = masterConfiguration.journalSyncOnCommit, eventIdGenerator, keyedEventBus)
    },
    "Journal"))
  private val orderScheduleGenerator = context.actorOf(
    Props { new OrderScheduleGenerator(journalActor = journalActor, masterOrderKeeper = self, scheduledOrderGeneratorKeeper)},
    "OrderScheduleGenerator"
  )
  private var terminating = false

  loadConfiguration()

  private def loadConfiguration(): Unit = {
    for (dir ← masterConfiguration.liveDirectoryOption) {
      forEachTypedFile(dir, Set(WorkflowPath, AgentPath)) {
        case (file, workflowPath: WorkflowPath) ⇒
          logger.info(s"Adding $workflowPath")
          val workflow = autoClosing(new FileSource(file)) { src ⇒
            WorkflowXmlParser.parseXml(workflowPath, src)
          }
          pathToWorkflow += workflowPath → workflow

        case (file, agentPath: AgentPath) ⇒
          logger.info(s"Adding $agentPath")
          val agent = autoClosing(new FileSource(file)) { src ⇒
            AgentXmlParser.parseXml(agentPath, src)
          }
          val actor = context.actorOf(
            Props { new AgentDriver(agent.path, agent.uri, masterConfiguration.config) },
            name = encodeAsActorName("Agent-" + agentPath.withoutStartingSlash))
          agentRegister.insert(agentPath → AgentEntry(agentPath, actor))
      }
    }
  }

  override def preStart() = {
    super.preStart()  // First let JournalingActor register itself
    //keyedEventBus.subscribe(self, classOf[OrderEvent])
    recover()
  }

  override def postStop() = {
    //keyedEventBus.unsubscribe(self)
    logger.debug("Stopped")
    super.postStop()
  }

  protected def snapshots = Future.successful(
    (for (entry ← agentRegister.values) yield AgentEventId(entry.agentPath, entry.lastAgentEventId)) ++
      //??? pathToWorkflow.values ++
      (orderRegister.values map { _ .order }))

  private def recover() = {
    val recoverer = new MasterJournalRecoverer(journalFile = journalFile, orderScheduleGenerator = orderScheduleGenerator)
    recoverer.recoverAll()
    for (order ← recoverer.orders) {
      orderRegister.insert(order.id → OrderEntry(order))
    }
    for ((agentPath, eventId) ← recoverer.agentToEventId) {
      agentRegister(agentPath).lastAgentEventId = eventId
    }
    startJournalAndFinishRecovery(journalActor = journalActor,
      RecoveredJournalingActors(Map(OrderScheduleGenerator.Key → orderScheduleGenerator)))
  }

  def receive = journaling orElse {
    case JsonJournalRecoverer.Output.JournalIsReady ⇒
      for (agentEntry ← agentRegister.values) {
        agentEntry.actor ! AgentDriver.Input.Start(lastAgentEventId = agentEntry.lastAgentEventId)
      }
      orderRegister.valuesIterator foreach proceedWithOrder
      logger.info(s"${orderRegister.size} Orders recovered, ready")
      become(ready)
      unstashAll()

    case _ ⇒ stash()
  }

  private def ready: Receive = journaling orElse {
    case command: MasterCommand ⇒
      executeMasterCommand(command)

    case Command.AddOrderSchedule(orders) ⇒
      for (order ← orders) {
        val logMsg = s"Order scheduled for ${order.state.at}: ${order.id}"
        orderRegister.get(order.id) match {
          case Some(_) ⇒
            logger.info(s"$logMsg is duplicate and discarded")
          case None if !pathToWorkflow.isDefinedAt(order.nodeKey.workflowPath) ⇒
            logger.error(s"$logMsg: Unknown '${order.nodeKey.workflowPath}'")
          case _ ⇒
            persistAsync(KeyedEvent(OrderAdded(order.nodeKey, order.state, order.payload))(order.id)) { stamped ⇒
              handleOrderEvent(stamped)
            }
        }
      }
      deferAsync {
        sender() ! Done
      }

    case Command.GetOrder(orderId) ⇒
      sender() ! (orderRegister.get(orderId) map { _.order })

    case Command.GetOrders ⇒
      sender() ! eventIdGenerator.stamp((orderRegister.values map { _.order }).toVector: Vector[Order[Order.State]])

    case Command.GetOrderCount ⇒
      sender() ! (orderRegister.size: Int)

    case Command.Remove(orderId) ⇒
      orderRegister.get(orderId) match {
        case None ⇒ sender() ! Status.Failure(new NoSuchElementException(s"Unknown $orderId"))
        case Some(orderEntry) ⇒
          if (orderEntry.toBeRemoved)
            sender() ! Done
          else {
            orderEntry.toBeRemoved = true
            orderEntry.order.attachedTo match {
              case None ⇒
                //orderEntry.order = orderEntry.order.update(OrderRemoved)  // TODO Persist
                sender() ! Done

              case Some(Order.AttachedTo.AgentOrDetachable(agentPath)) ⇒
                sender() ! Status.Failure(new IllegalStateException(s"Order cannot be removed because it is attached to Agent '$agentPath'"))
            }
          }
      }

    case AgentDriver.Output.EventFromAgent(Stamped(_, KeyedEvent(_, OrderEvent.OrderDetached))) if detachingSuspended ⇒
      stash()

    case msg @ AgentDriver.Output.EventFromAgent(Stamped(agentEventId, KeyedEvent(orderId: OrderId, event: OrderEvent))) ⇒
      val agentEntry = agentRegister(sender())
      import agentEntry.agentPath
      if (orderRegister contains orderId) {
        val ownEvent = event match {
          case _: OrderEvent.OrderAttached ⇒ OrderEvent.OrderMovedToAgent(agentPath)  // TODO Das kann schon der Agent machen. Dann wird weniger übertragen.
          case _ ⇒ event
        }
        persistAsync(KeyedEvent(ownEvent)(orderId))(handleOrderEvent)
        persistAsync(KeyedEvent(AgentEventIdEvent(agentEventId))(agentPath)) { e ⇒
          agentEntry.lastAgentEventId = e.eventId
        }
      } else {
        logger.warn(s"Event for unknown $orderId received from $agentPath: $msg")
      }

    case AgentDriver.Output.OrdersDetached(orderIds) ⇒
      val unknown = orderIds -- orderRegister.keySet
      if (unknown.nonEmpty) {
        logger.error(s"Received OrdersDetached from Agent for unknown orders:" + unknown.mkString(", "))
      }
      for (orderId ← orderIds -- unknown) {
        persistAsync(KeyedEvent(OrderEvent.OrderMovedToMaster)(orderId))(handleOrderEvent)
      }

    case msg @ JsonJournalActor.Output.SerializationFailure(throwable) ⇒
      logger.error(msg.toString, throwable)
      // Ignore this ???

    case Input.SuspendDetaching ⇒
      if (!detachingSuspended) {
        logger.warn("❗ SuspendDetaching")
        detachingSuspended = true
      }

    case Input.ContinueDetaching ⇒
      if (detachingSuspended) {
        logger.info("❗ ContinueDetaching")
        detachingSuspended = false
        unstashAll()
      }

    case Internal.Execute(callback) ⇒
      callback()

    case Terminated(`journalActor`) if terminating ⇒
      logger.info("Stop")
      context.stop(self)
  }

  def executeMasterCommand(command: MasterCommand): Unit = command match {
    case MasterCommand.ScheduleOrdersEvery(every) ⇒
      orderScheduleGenerator ! OrderScheduleGenerator.Input.ScheduleEvery(every.toJavaDuration)
      sender() ! MasterCommand.Response.Accepted

    case MasterCommand.AddOrderIfNew(order) ⇒
      orderRegister.get(order.id) match {
        case None if pathToWorkflow.isDefinedAt(order.nodeKey.workflowPath) ⇒
          persistAsync(KeyedEvent(OrderAdded(order.nodeKey, order.state, order.payload))(order.id)) { stamped ⇒
            handleOrderEvent(stamped)
            sender() ! MasterCommand.Response.Accepted
          }
        case None if !pathToWorkflow.isDefinedAt(order.nodeKey.workflowPath) ⇒
          sender() ! Status.Failure(new NoSuchElementException(s"Unknown Workflow '${order.nodeKey.workflowPath.string}'"))
        case Some(_) ⇒
          logger.debug(s"Discarding duplicate AddOrderIfNew: ${order.id}")
          sender() ! MasterCommand.Response.Accepted //Status.Failure(new IllegalStateException(s"Duplicate OrderId '${order.taskId}'"))
      }

    case MasterCommand.Terminate ⇒
      terminating = true
      journalActor ! JsonJournalActor.Input.Terminate
      sender() ! MasterCommand.Response.Accepted
  }

  private def handleOrderEvent(stamped: Stamped[KeyedEvent[OrderEvent]]): Unit =
    handleOrderEvent(stamped.value.key, stamped.value.event)

  private def handleOrderEvent(orderId: OrderId, event: OrderEvent): Unit = {
    logNotableEvent(orderId, event)
    event match {
      case event: OrderAdded ⇒
        val order = Order.fromOrderAdded(orderId, event)
        orderRegister.insert(order.id → OrderEntry(order))
        tryAttachOrderToAgent(order)

      case event: OrderEvent ⇒
        val orderEntry = orderRegister(orderId)
        orderEntry.update(event)
        proceedWithOrder(orderEntry)
    }
  }

  private def proceedWithOrder(orderEntry: OrderEntry): Unit = {
    val order = orderEntry.order
    (order.attachedTo, order.state) match {
      case (None, _: Order.Idle) ⇒
        val idleOrder = order.castState[Order.Idle]
        for (workflow ← pathToWorkflow.get(orderEntry.order.workflowPath);
             node ← workflow.idToNode.get(orderEntry.order.nodeId)) {
          node match {
            case _: Workflow.JobNode ⇒
              tryAttachOrderToAgent(idleOrder)

            case _: Workflow.EndNode ⇒
              persistAsync(KeyedEvent(OrderEvent.OrderFinished)(order.id))(handleOrderEvent)
          }
        }

      case (None, Order.Processed) ⇒
        for (workflow ← pathToWorkflow.get(orderEntry.order.workflowPath);
             event ← Transitions.switch(order.castState[Order.Processed.type], workflow)) {
          persistAsync(KeyedEvent(event)(order.id))(handleOrderEvent)
        }

      case (Some(_: Order.AttachedTo.Detachable), _) ⇒
        detachOrderFromAgent(order.id)

      case _ ⇒
    }
  }

  private def tryAttachOrderToAgent(order: Order[Order.Idle]): Unit =
    for (workflow ← pathToWorkflow.get(order.nodeKey.workflowPath);
         agentPath ← workflow.agentPathOption(order.nodeKey.nodeId);
         agentEntry ← agentRegister.get(agentPath))
    {
      agentEntry.actor ! AgentDriver.Input.AttachOrder(order, agentPath, workflow.reduceForAgent(agentPath))
    }

  private def detachOrderFromAgent(orderId: OrderId): Unit =
    orderRegister(orderId).order.detachableFromAgent match {
      case Left(t) ⇒
        logger.error(s"detachOrderFromAgent '$orderId': not AttachedTo.Detachable: $t")
      case Right(agentPath) ⇒
        agentRegister(agentPath).actor ! AgentDriver.Input.DetachOrder(orderId)
    }

  override def toString = "MasterOrderKeeper"
}

object MasterOrderKeeper {
  private val SnapshotJsonCodec = TypedJsonCodec[Any](
    Subtype[OrderScheduleEndedAt],
    Subtype[Order[Order.State]],
    Subtype[AgentEventId])
  //Subtype[Workflow])

  private[order] val MyJournalMeta = new JsonJournalMeta(SnapshotJsonCodec, MasterKeyedEventJsonCodec) with GzipCompression

  private val logger = Logger(getClass)

  sealed trait Input
  object Input {
    case object SuspendDetaching extends Input    // For testing
    case object ContinueDetaching extends Input   // For testing
  }

  sealed trait Command
  object Command {
    final case class AddOrderSchedule(orders: Seq[Order[Order.Scheduled]]) extends Command
    final case class GetOrder(orderId: OrderId) extends Command
    final case object GetOrders extends Command
    final case object GetOrderCount extends Command
    final case class Remove(orderId: OrderId) extends Command
  }

  private object Internal {
    final case class Execute(callback: () ⇒ Unit)
    final case class GenerateNextOrders(every: Duration)
  }

  private class AgentRegister extends ActorRegister[AgentPath, AgentEntry](_.actor) {
    override def insert(kv: (AgentPath, AgentEntry)) = super.insert(kv)
  }

  private case class AgentEntry(
    agentPath: AgentPath,
    actor: ActorRef,
    var lastAgentEventId: EventId = EventId.BeforeFirst)

  private case class OrderEntry(
    var order: Order[Order.State],
    var toBeRemoved: Boolean = false)
  {
    def orderId = order.id

    def update(event: OrderEvent): Unit =
      event match {
        case _: OrderStdWritten ⇒
          logEvent(orderId, event)

        case event: OrderCoreEvent ⇒
          order = order.update(event)
      }
  }

  private def logNotableEvent(orderId: OrderId, event: OrderEvent): Unit =
    event match {
      case _ @  (_: OrderEvent.OrderAdded | _: OrderEvent.OrderMovedToAgent | OrderEvent.OrderMovedToMaster | OrderEvent.OrderFinished) ⇒
        logEvent(orderId, event)
      case _ ⇒
    }

  private def logEvent(orderId: OrderId, event: OrderEvent): Unit =
    logger.info(s"$orderId 🔶 $event")
}
