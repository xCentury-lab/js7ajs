package com.sos.jobscheduler.common.event.collector

import com.sos.jobscheduler.base.generic.Completed
import com.sos.jobscheduler.base.utils.CloseableIterator
import com.sos.jobscheduler.common.event.RealEventWatch
import com.sos.jobscheduler.common.event.collector.EventCollector._
import com.sos.jobscheduler.data.event.{AnyKeyedEvent, Event, EventId, Stamped}
import monix.execution.Scheduler
import scala.concurrent.Future

/**
  * @author Joacim Zschimmer
  */
abstract class EventCollector(configuration: Configuration)(implicit protected val scheduler: Scheduler)
extends RealEventWatch[Event]
{
  protected val started = Future.successful(Completed)
  private[collector] val keyedEventQueue = new MemoryKeyedEventQueue(sizeLimit = configuration.queueSize)

  final def addStamped(stamped: Stamped[AnyKeyedEvent]): Unit = {
    keyedEventQueue.add(stamped)
    onEventsAdded(stamped.eventId)
  }

  def tear(after: EventId): Unit =
    keyedEventQueue.tear(after)

  final def tornEventId: EventId =
    keyedEventQueue.tornEventId

  def eventsAfter(after: EventId) =
    keyedEventQueue.after(after) map CloseableIterator.fromIterator
}

object EventCollector
{
  final case class Configuration(queueSize: Int)

  object Configuration {
    val ForTest = Configuration(queueSize = 1000)
  }

  final class ForTest(
    configuration: Configuration = Configuration.ForTest)
    (implicit scheduler: Scheduler)
    extends EventCollector(configuration)
  {
    def snapshotObjectsFor(after: EventId) = None
  }
}
