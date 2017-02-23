package com.sos.jobscheduler.taskserver.spoolerapi

import com.sos.jobscheduler.common.xml.VariableSets
import com.sos.jobscheduler.data.message.MessageCode
import com.sos.jobscheduler.minicom.idispatch.IDispatch
import com.sos.jobscheduler.taskserver.spoolerapi.SpoolerTask._

/**
 * @author Joacim Zschimmer
 */
trait SpoolerTask extends IDispatch {

  def setErrorCodeAndText(code: MessageCode, text: String): Unit

  final def parameterMap: Map[String, String] = xmlToParameterMap(paramsXml)

  def paramsXml: String

  def paramsXml_=(o: String): Unit

  final def orderParameterMap: Map[String, String] = xmlToParameterMap(orderParamsXml)

  def orderParamsXml: String

  def orderParamsXml_=(o: String): Unit
}

private object SpoolerTask {
  final def xmlToParameterMap(xmlString: String): Map[String, String] =
    xmlString match {
      case "" ⇒ Map()
      case o ⇒ VariableSets.parseXml(o)
    }
}
