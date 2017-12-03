package com.sos.jobscheduler.data.event

import com.sos.jobscheduler.base.circeutils.CirceUtils.RichJson
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

/**
  * A value with an EventId.
  *
  * @author Joacim Zschimmer
  */
final case class Stamped[+A](eventId: EventId, value: A) {

  def map[B](f: A ⇒ B): Stamped[B] = Stamped(eventId, f(value))

  override def toString = s"Stamped(${EventId.toDateTimeString(eventId)} $value)"
}

object Stamped {
  val EventIdJsonName = "eventId"
  val ElementsJsonName = "elements"

  implicit def jsonEncoder[A: Encoder]: Encoder[Stamped[A]] =
    stamped ⇒ {
      val json = stamped.value.asJson
      val eventIdField = EventIdJsonName → Json.fromLong(stamped.eventId)
      if (json.isArray)
        Json.obj(eventIdField, ElementsJsonName → json)
      else
        Json.fromJsonObject(eventIdField +: json.forceObject)  // slow field prepend
    }

  implicit def jsonDecoder[A: Decoder]: Decoder[Stamped[A]] =
    cursor ⇒
      for {
        eventId ← cursor.get[EventId](EventIdJsonName)
        a ← cursor.get[A]("elements") match {
          case o if o.isRight ⇒ o
          case _ ⇒ cursor.as[A]
        }
      } yield Stamped(eventId, a)
}
