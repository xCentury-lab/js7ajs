package com.sos.jobscheduler.base.generic

import com.sos.jobscheduler.base.circeutils.CirceUtils.RichJson
import com.sos.jobscheduler.base.convert.As
import io.circe.{Decoder, Encoder, Json, KeyDecoder, KeyEncoder}
import javax.annotation.Nullable
import scala.language.implicitConversions

trait IsString {
  def string: String

  final def isEmpty = string.isEmpty

  final def nonEmpty = string.nonEmpty

  final override def hashCode = string.hashCode ^ getClass.hashCode

  override def toString = string
}

object IsString {

  @Nullable def stringOrNull[A <: IsString](o: Option[A]): String = o match {
    case Some(a) ⇒ a.string
    case None ⇒ null
  }

  implicit def jsonEncoder[A <: IsString]: Encoder[A] =
    o ⇒ Json.fromString(o.string)

  trait HasJsonCodec[A <: IsString] {
    def apply(o: String): A

    implicit val JsonEncoder: Encoder[A] = jsonEncoder[A]
    implicit val JsonDecoder: Decoder[A] = o ⇒ Right(apply(o.value.forceString))
    implicit val keyEncoder: KeyEncoder[A] = _.string
    implicit val keyDecoder: KeyDecoder[A] = o ⇒ Some(apply(o))
  }

  trait Companion[A <: IsString] extends HasJsonCodec[A] {
    implicit val ordering: Ordering[A] = Ordering by { _.string }
    implicit val IsStringAsString: As[String, A] = As(apply)
  }
}
