package com.sos.scheduler.engine.shared.event.journal.tests

import com.sos.scheduler.engine.base.sprayjson.typed.{Subtype, TypedJsonFormat}
import com.sos.scheduler.engine.data.event.Event
import spray.json.DefaultJsonProtocol._

/**
  * @author Joacim Zschimmer
  */
private[tests] sealed trait TestEvent extends Event {
  type Key = String
}

private[tests] object TestEvent {
  final case class Added(string: String) extends TestEvent

  final case class Appended(string: String) extends TestEvent

  final case object Removed extends TestEvent

  implicit val OrderEventJsonFormat = TypedJsonFormat[TestEvent](
    Subtype(jsonFormat1(Added)),
    Subtype(jsonFormat1(Appended)),
    Subtype(jsonFormat0(() ⇒ Removed)))
}
