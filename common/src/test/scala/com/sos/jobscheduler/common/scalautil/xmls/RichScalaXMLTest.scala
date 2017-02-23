package com.sos.jobscheduler.common.scalautil.xmls

import com.sos.jobscheduler.common.scalautil.xmls.RichScalaXML.RichElem
import org.scalatest.FreeSpec
import org.scalatest.Matchers._

class RichScalaXMLTest extends FreeSpec {

  "attributeText" in {
    val e = <a b="B"/>
    e.attributeText("b") should equal ("B")
    e.attributeText("x") should equal ("")
  }
}
