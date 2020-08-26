package js7.proxy.javaapi.data.event

import js7.base.circeutils.CirceUtils._
import js7.controller.data.ControllerState
import js7.data.event.{Event, KeyedEvent}

object JKeyedEvent
{
  def keyedEventToJson[E <: Event](keyedEvent: KeyedEvent[E]): String =
    ControllerState.keyedEventJsonCodec.encodeObject(keyedEvent).compactPrint
}
