package com.sos.jobscheduler.data.event

import cats.{Eq, Functor}
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.data.event.Stamped._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json, JsonObject, ObjectEncoder}
import scala.collection.mutable

/**
  * A value with an EventId.
  *
  * @author Joacim Zschimmer
  */
final case class Stamped[+A](eventId: EventId, timestamp: Timestamp, value: A) {

  def map[B](f: A ⇒ B): Stamped[B] = functor.map(this)(f)

  override def toString = s"Stamped(${EventId.toDateTimeString(eventId)} $value)"
}

object Stamped {

  implicit def _eq[A: Eq]: Eq[Stamped[A]] = Eq.fromUniversalEquals

  implicit val functor: Functor[Stamped] = new Functor[Stamped] {
    def map[A,B](fa: Stamped[A])(f: A ⇒ B) =
      fa.copy(value = f(fa.value))
  }

  implicit def jsonEncoder[A: Encoder]: ObjectEncoder[Stamped[A]] =
    stamped ⇒ {
      val fields = mutable.Buffer[(String, Json)]()
      fields += "eventId" → Json.fromLong(stamped.eventId)
      val epochMilli = stamped.timestamp.toEpochMilli
      if (epochMilli != EventId.toEpochMilli(stamped.eventId)) {
        fields += "timestamp" → Json.fromLong(epochMilli)
      }
      val valueJson = stamped.value.asJson
      valueJson.asObject match {
        case Some(o) ⇒
          fields ++= o.toIterable
        case None ⇒
          fields += "value" → valueJson
      }
      JsonObject.fromIterable(fields)
    }

  implicit def jsonDecoder[A: Decoder]: Decoder[Stamped[A]] =
    cursor ⇒
      for {
        eventId ← cursor.get[EventId]("eventId")
        timestamp = cursor.get[Long]("timestamp") map Timestamp.ofEpochMilli getOrElse EventId.toTimestamp(eventId)
        a ← cursor.get[A]("value") match {
          case o if o.isRight ⇒ o
          case _ ⇒ cursor.as[A]
        }
      } yield Stamped(eventId, timestamp, a)
}
