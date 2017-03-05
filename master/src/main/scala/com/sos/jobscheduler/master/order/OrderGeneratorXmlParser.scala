package com.sos.jobscheduler.master.order

import com.sos.jobscheduler.base.convert.As
import com.sos.jobscheduler.common.scalautil.xmls.ScalaXMLEventReader
import com.sos.jobscheduler.data.folder.FolderPath
import com.sos.jobscheduler.data.jobnet.{JobnetPath, NodeId, NodeKey}
import com.sos.jobscheduler.master.oldruntime.{OldSchedule, OldScheduleXmlParser}
import com.sos.jobscheduler.shared.common.VariablesXmlParser
import java.time.ZoneId
import javax.xml.transform.Source

/**
  * @author Joacim Zschimmer
  */
object OrderGeneratorXmlParser {

  def parseXml(path: OrderGeneratorPath, source: Source, timeZone: ZoneId): ScheduledOrderGenerator =
    ScalaXMLEventReader.parseDocument(source) { eventReader ⇒
      import eventReader._
      val folderPath = FolderPath parentOf path
      parseElement("order") {
        val nodeKey = NodeKey(
          attributeMap.as("job_chain")(As(o ⇒ folderPath.resolve[JobnetPath](o))),
          attributeMap.as[NodeId]("state"))
        val elements = forEachStartElement {
          case "params" ⇒ VariablesXmlParser.parse(eventReader)
          case "run_time" ⇒ OldScheduleXmlParser.parse(eventReader, timeZone)
        }
        ScheduledOrderGenerator(
          path,
          nodeKey,
          elements.option[Map[String, String]]("params") getOrElse Map(),
          elements.one[OldSchedule])
      }
    }
}
