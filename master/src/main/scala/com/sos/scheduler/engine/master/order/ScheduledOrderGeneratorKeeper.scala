package com.sos.scheduler.engine.master.order

import com.sos.scheduler.engine.common.scalautil.AutoClosing.autoClosing
import com.sos.scheduler.engine.common.scalautil.Collections._
import com.sos.scheduler.engine.common.scalautil.xmls.FileSource
import com.sos.scheduler.engine.data.engine2.order.Order
import com.sos.scheduler.engine.data.order.OrderId
import com.sos.scheduler.engine.master.configuration.MasterConfiguration
import com.sos.scheduler.engine.master.oldruntime.InstantInterval
import com.sos.scheduler.engine.master.order.ScheduledOrderGeneratorKeeper._
import com.sos.scheduler.engine.shared.filebased.TypedPathDirectoryWalker.forEachTypedFile
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.collection.immutable.Seq

/**
  * @author Joacim Zschimmer
  */
@Singleton
final class ScheduledOrderGeneratorKeeper @Inject private(masterConfiguration: MasterConfiguration) {

  private val orderGenerators: Map[OrderGeneratorPath, ScheduledOrderGenerator] =
    Map.build[OrderGeneratorPath, ScheduledOrderGenerator] { builder ⇒
      for (dir ← masterConfiguration.liveDirectoryOption) {
        forEachTypedFile(dir, Set(OrderGeneratorPath)) {
          case (file, orderGeneratorPath: OrderGeneratorPath) ⇒
            val orderGenerator = autoClosing(new FileSource(file)) { src ⇒
              OrderGeneratorXmlParser.parseXml(orderGeneratorPath, src, masterConfiguration.timeZone)
            }
            builder += orderGeneratorPath → orderGenerator
        }
      }
    }

  def generateOrders(instantInterval: InstantInterval): Seq[Order[Order.Scheduled]] =
    (for (orderGenerator ← orderGenerators.values;
         instant ← orderGenerator.schedule.instants(instantInterval)) yield
      Order(
        toOrderId(orderGenerator.path, instant),
        orderGenerator.nodeKey,
        Order.Scheduled(instant),
        orderGenerator.variables))
    .toVector.sortBy { _.state.at }
}

object ScheduledOrderGeneratorKeeper {
  private def toOrderId(path: OrderGeneratorPath, instant: Instant) =
    OrderId(s"${path.withoutStartingSlash}#$instant")
}
