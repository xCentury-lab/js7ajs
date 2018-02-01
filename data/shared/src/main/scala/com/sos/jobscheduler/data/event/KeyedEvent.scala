package com.sos.jobscheduler.data.event

import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, ObjectEncoder}
import scala.reflect.ClassTag

/**
  * A [[Event]] enriched with a `key` designating the respective object.
  *
  * @author Joacim Zschimmer
  */
final case class KeyedEvent[+E <: Event](key: E#Key, event: E) {
  override def toString = s"$key <-: $event"
}

object KeyedEvent {
  private[event] val KeyFieldName = "key"

  sealed trait NoKey
  case object NoKey extends NoKey {
    implicit val JsonEncoder: Encoder[NoKey] = _ ⇒ sys.error("NoKey Encoder")
    implicit val JsonDecoder: Decoder[NoKey] = _ ⇒ sys.error("NoKey Decoder")

    override def toString = "NoKey"
  }

  def apply[E <: Event](event: E)(key: event.Key) = new KeyedEvent(key, event)

  def apply[E <: Event { type Key = NoKey }](event: E) = new KeyedEvent[E](NoKey, event)

  def of[E <: Event { type Key = NoKey }](event: E) = new KeyedEvent[E](NoKey, event)

  implicit def jsonEncoder[E <: Event](implicit eventEncoder: ObjectEncoder[E], keyEncoder: Encoder[E#Key]): ObjectEncoder[KeyedEvent[E]] =
    keyedEvent ⇒ {
      val jsonObject = keyedEvent.event.asJsonObject
      keyedEvent.key match {
        case _: NoKey.type ⇒ jsonObject
        case key ⇒
          require(!jsonObject.contains(KeyFieldName), s"Serialized ${keyedEvent.getClass} must not contain a field '$KeyFieldName'")
          (KeyFieldName → key.asJson) +: jsonObject
      }
    }

  implicit def jsonDecoder[E <: Event](implicit decoder: Decoder[E], keyDecoder: Decoder[E#Key]): Decoder[KeyedEvent[E]] =
    cursor ⇒ {
      val key = cursor.get[E#Key]("key") getOrElse NoKey.asInstanceOf[E#Key]
      for (event ← cursor.as[E]) yield
        KeyedEvent(key, event)
    }

  def typedJsonCodec[E <: Event: ClassTag](subtypes: KeyedEventTypedJsonCodec.KeyedSubtype[_ <: E]*) =
    KeyedEventTypedJsonCodec[E](subtypes: _*)
}
