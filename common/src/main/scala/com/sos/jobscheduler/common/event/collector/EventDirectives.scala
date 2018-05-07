package com.sos.jobscheduler.common.event.collector

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route, ValidationRejection}
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import com.google.common.base.Splitter
import com.sos.jobscheduler.base.utils.ScalaUtils.implicitClass
import com.sos.jobscheduler.data.event._
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * @author Joacim Zschimmer
  */
object EventDirectives {

  private val DefaultEventRequestDelay = 500.milliseconds
  private val ReturnSplitter = Splitter.on(',')

  def eventRequest[E <: Event: KeyedEventTypedJsonCodec: ClassTag]: Directive1[SomeEventRequest[E]] =
    eventRequest[E](None)

  def eventRequest[E <: Event: KeyedEventTypedJsonCodec: ClassTag](defaultReturnType: Option[String] = None): Directive1[SomeEventRequest[E]] =
    new Directive1[SomeEventRequest[E]] {
      def tapply(inner: Tuple1[SomeEventRequest[E]] ⇒ Route) =
        parameter("return".?) {
          _ orElse defaultReturnType match {
            case None ⇒ reject(ValidationRejection("Missing parameter return="))
            case Some(returnType) ⇒
              val eventSuperclass = implicitClass[E]
              val returnTypeNames = ReturnSplitter.split(returnType).asScala.toSet
              val eventClasses = returnTypeNames flatMap { t ⇒
                implicitly[KeyedEventTypedJsonCodec[E]].typenameToClassOption(t)
              } collect {
                case eventClass_ if eventSuperclass isAssignableFrom eventClass_ ⇒ eventClass_
                case eventClass_ if eventClass_ isAssignableFrom eventSuperclass ⇒ eventSuperclass
              }
              if (eventClasses.size != returnTypeNames.size)
                reject(ValidationRejection(s"Unrecognized event type: return=$returnType"))
              else
                eventRequestRoute[E](eventClasses, inner)
          }
        }
    }

  private implicit val finiteDurationParamMarshaller: FromStringUnmarshaller[FiniteDuration] =
    Unmarshaller.strict((o: String) ⇒ new FiniteDuration(o.toInt, TimeUnit.SECONDS))  // Whole seconds only !!!

  private def eventRequestRoute[E <: Event](eventClasses: Set[Class[_ <: E]], inner: Tuple1[SomeEventRequest[E]] ⇒ Route): Route = {
    parameter("limit" ? Int.MaxValue) {

      case 0 ⇒
        reject(ValidationRejection("Invalid limit=0"))

      case limit if limit > 0 ⇒
        parameter("after".as[EventId]) { after ⇒
          parameter("timeout" ? Duration.Zero) { timeout ⇒
            parameter("delay" ? DefaultEventRequestDelay) { delay ⇒
              inner(Tuple1(EventRequest[E](eventClasses, after = after, timeout = timeout, delay = delay, limit = limit)))
            }
          }
        }

      case limit if limit < 0 ⇒
        parameter("after" ? EventId.BeforeFirst) { after ⇒
          inner(Tuple1(ReverseEventRequest[E](eventClasses, after = after, limit = -limit)))
        }
    }
  }
}
