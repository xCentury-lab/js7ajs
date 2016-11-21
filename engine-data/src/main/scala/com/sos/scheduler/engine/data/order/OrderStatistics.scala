package com.sos.scheduler.engine.data.order

import com.sos.scheduler.engine.data.order.OrderStatistics._
import com.sos.scheduler.engine.data.queries.QueryableOrder
import spray.json.DefaultJsonProtocol._

/**
  * @author Joacim Zschimmer
  */
final case class OrderStatistics(
  total: Int,
  notPlanned: Int,
  notSuspendedNotPlanned: Int,
  planned: Int,
  due: Int,
  started: Int,
  inTask: Int,
  inProcess: Int,
  setback: Int,
  waitingForResource: Int,
  notSuspendedWaitingForResource: Int,
  suspended: Int,
  blacklisted: Int,
  permanent: Int,
  fileOrder: Int)
{
  def +(o: OrderStatistics) = OrderStatistics(
    total = total + o.total,
    notPlanned = notPlanned + o.notPlanned,
    notSuspendedNotPlanned = notSuspendedNotPlanned + o.notSuspendedNotPlanned,
    planned = planned + o.planned,
    due = due + o.due,
    started = started + o.started,
    inTask = inTask + o.inTask,
    inProcess = inProcess + o.inProcess,
    setback = setback + o.setback,
    waitingForResource = waitingForResource + o.waitingForResource,
    notSuspendedWaitingForResource = notSuspendedWaitingForResource + o.notSuspendedWaitingForResource,
    suspended = suspended + o.suspended,
    blacklisted = blacklisted + o.blacklisted,
    permanent = permanent + o.permanent,
    fileOrder = fileOrder + o.fileOrder)

  override def toString = List(
      format("total", total),
      format("notPlanned", notPlanned),
      format("notSuspendedNotPlanned", notSuspendedNotPlanned),
      format("planned", planned),
      format("due", due),
      format("started", started),
      format("inTask", inTask),
      format("inProcess", inProcess),
      format("setback", setback),
      format("notSuspendedWaitingForResource", notSuspendedWaitingForResource),
      format("waitingForResource", waitingForResource),
      format("suspended", suspended),
      format("blacklisted", blacklisted),
      format("permanent", permanent),
      format("fileOrder", fileOrder))
    .mkString("OrderStatistics(", " ", ")")
}

object OrderStatistics {
  val Zero = OrderStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  implicit val MyJsonFormat = jsonFormat15(apply)

  private def format(name: String, number: Int) = s"$name=$number"

  final class Mutable(
    var total: Int = 0,
    var notPlanned: Int = 0,
    var nonSuspendedNotPlanned: Int = 0,
    var planned: Int = 0,
    var due: Int = 0,
    var started: Int = 0,
    var inTask: Int = 0,
    var inProcess: Int = 0,
    var setback: Int = 0,
    var waitingForResource: Int = 0,
    var notSuspendedWaitingForResource: Int = 0,
    var suspended: Int = 0,
    var blacklisted: Int = 0,
    var permanent: Int = 0,
    var fileOrder: Int = 0)
  {
    def +=(o: OrderStatistics) = {
      total       += o.total
      notPlanned  += o.notPlanned
      nonSuspendedNotPlanned  += o.notSuspendedNotPlanned
      planned     += o.planned
      due         += o.due
      started     += o.started
      inTask      += o.inTask
      inProcess   += o.inProcess
      setback     += o.setback
      waitingForResource += o.waitingForResource
      notSuspendedWaitingForResource += o.notSuspendedWaitingForResource
      suspended   += o.suspended
      blacklisted += o.blacklisted
      permanent   += o.permanent
      fileOrder   += o.fileOrder
    }

    def +=(order: QueryableOrder): Unit = {
      import OrderProcessingState._
      total       += 1
      val isNotPlanned = order.orderProcessingStateClass == NotPlanned.getClass
      notPlanned  += toInt(isNotPlanned)
      nonSuspendedNotPlanned += toInt(!order.isSuspended && isNotPlanned)
      planned     += toInt(order.orderProcessingStateClass == classOf[Planned])
      due         += toInt(order.orderProcessingStateClass == classOf[Due])
      started     += toInt(classOf[Started] isAssignableFrom order.orderProcessingStateClass)
      inProcess   += toInt(classOf[InTask] isAssignableFrom order.orderProcessingStateClass)
      setback     += toInt(order.orderProcessingStateClass == classOf[Setback])
      inTask      += toInt(classOf[InTask] isAssignableFrom order.orderProcessingStateClass)
      val isWaitingForResource = order.orderProcessingStateClass == WaitingForResource.getClass
      waitingForResource += toInt(isWaitingForResource)
      notSuspendedWaitingForResource += toInt(order.isSuspended && isWaitingForResource)
      suspended   += toInt(order.isSuspended)
      blacklisted += toInt(order.isBlacklisted)
      permanent   += toInt(order.orderSourceType == OrderSourceType.Permanent)
      fileOrder   += toInt(order.orderSourceType == OrderSourceType.FileOrder)
    }

    def toImmutable = OrderStatistics(
      total = total,
      notPlanned = notPlanned,
      notSuspendedNotPlanned = nonSuspendedNotPlanned,
      planned = planned,
      due = due,
      started = started,
      inTask = inTask,
      inProcess = inProcess,
      setback = setback,
      waitingForResource = waitingForResource,
      notSuspendedWaitingForResource = notSuspendedWaitingForResource,
      suspended = suspended,
      blacklisted = blacklisted,
      permanent = permanent,
      fileOrder = fileOrder)
  }

  private def toInt(b: Boolean) = if (b) 1 else 0
}
