package com.sos.jobscheduler.master

import akka.actor.{Actor, ActorRefFactory, Props}
import com.sos.jobscheduler.common.event.collector.EventCollector
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.event.{AnyKeyedEvent, Event, EventId, Stamped}
import com.sos.jobscheduler.master.TestEventCollector._
import com.sos.jobscheduler.shared.event.StampedKeyedEventBus
import scala.concurrent.ExecutionContext

/**
  * @author Joacim Zschimmer
  */
final class TestEventCollector
extends EventCollector(
  initialOldestEventId = EventId.BeforeFirst,
  EventCollector.Configuration.ForTest)(
  TimerService(idleTimeout = Some(1.s)),
  ExecutionContext.global)
{
  def start(actorRefFactory: ActorRefFactory, keyedEventBus: StampedKeyedEventBus): Unit = {
    actorRefFactory.actorOf(
      Props {
        new Actor {
          override def preStart() = {
            super.preStart()
            keyedEventBus.subscribe(self, classOf[Event])
            logger.debug("Ready")
          }

          override def postStop() = {
            keyedEventBus.unsubscribe(self)
            logger.debug("Stopped")
            super.postStop()
          }

          def receive = {
            case event: Stamped[AnyKeyedEvent] ⇒
              logger.debug(event.toString)
              addStamped(event)
          }
        }
      },
      name = "TestEventCollector")
  }
}

object TestEventCollector {
  private val logger = Logger(getClass)
}
