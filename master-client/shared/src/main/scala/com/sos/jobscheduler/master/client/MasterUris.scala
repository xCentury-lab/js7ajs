package com.sos.jobscheduler.master.client

import com.sos.jobscheduler.base.utils.ScalaUtils.{RichJavaClass, implicitClass}
import com.sos.jobscheduler.common.http.Uris.{encodePath, encodeQuery}
import com.sos.jobscheduler.data.event.{Event, EventId}
import com.sos.jobscheduler.data.order.OrderId
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.master.client.MasterUris._
import org.scalactic.Requirements._
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
    api("/event") + encodeQuery(
      "return" → encodeClass[E],
      "timeout" → s"${BigDecimal(timeout.toMillis) / 1000}",
      "after" → after.toString)

  object order {
    def overview = api("/order")

    def add = api("/order")

    def list[A: ClassTag]: String =
      api("/" + encodePath("order", ""), "return" → encodeClass[A])

    def apply(orderId: OrderId): String =
      api("/" + encodePath("order", orderId.string))
  }

  object workflow {
    def apply(path: WorkflowPath): String =
      api("/" + encodePath("workflow", path.withoutStartingSlash))

    def list[A: ClassTag]: String =
      api("/" + encodePath("workflow", ""), "return" → encodeClass[A])
  }

  def api(query: (String, String)*): String =
    api("", query: _*)

  def api(path: String, query: (String, String)*): String = {
    require(path.isEmpty || path.startsWith("/"))
    master("api" + path) +
      encodeQuery(query: _*)
  }

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
    cls.simpleScalaName
  }
}
