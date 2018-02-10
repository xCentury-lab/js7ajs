package com.sos.jobscheduler.master.client

import com.sos.jobscheduler.base.utils.ScalaUtils.{RichJavaClass, implicitClass}
import com.sos.jobscheduler.data.event.{Event, EventId}
import com.sos.jobscheduler.data.order.OrderId
import com.sos.jobscheduler.data.workflow.{Workflow, WorkflowPath}
import com.sos.jobscheduler.master.client.MasterUris._
import com.sos.jobscheduler.master.client.Uris.{encodePath, encodeQuery}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

/**
 * URIs of the JobScheduler Master.
 *
 * @author Joacim Zschimmer
 */
final class MasterUris private(masterUri: String) {

  def overview = api()

  val command = api()

  def events[E <: Event: ClassTag](after: EventId, timeout: Duration): String =
    api("event") + encodeQuery(
      "return" → encodeClass[E],
      "timeout" → s"${BigDecimal(timeout.toMillis) / 1000}",
      "after" → after.toString)

  object order {
    def overview = api("order")

    def list[A: ClassTag]: String =
      api(encodePath("order", ""), "return" → encodeClass[A])

    def apply(orderId: OrderId): String =
      api(encodePath("order", orderId.string))
  }

  object workflow {
    def apply(path: WorkflowPath): String =
      api(encodePath("workflow", path.withoutStartingSlash))

    def list[A: ClassTag]: String =
      api(encodePath("workflow", ""), "return" → encodeClass[A])
  }

  def api(query: (String, String)*) =
    master("api") + encodeQuery(query: _*)

  def api(path: String, query: (String, String)*) =
    master(s"api/$path") + encodeQuery(query: _*)

  def master(path: String) = s"$masterUri$path"

  override def toString = masterUri
}

object MasterUris {

  def apply(masterUri: String): MasterUris =
    new MasterUris(
      if (masterUri.isEmpty)
        masterUri
      else
        masterUri.stripSuffix("/") + "/")

  private def encodeClass[A: ClassTag]: String =
    encodeClass(implicitClass[A])

  private def encodeClass(cls: Class[_]): String = {
    require(cls != classOf[Nothing], "Missing return=CLASS")
    if (cls == classOf[Workflow.Named])
      "Workflow"
    else
      cls.simpleScalaName
  }
}
