package com.sos.jobscheduler.http.client.common

import com.sos.jobscheduler.base.utils.ScalazStyle.OptionRichBoolean
import com.sos.jobscheduler.http.client.common.OwnHttpHeaderTest._
import org.scalatest.FreeSpec
import spray.http.HttpHeader
import spray.http.HttpHeaders.RawHeader

/**
  * @author Joacim Zschimmer
  */
final class OwnHttpHeaderTest extends FreeSpec {

  "OwnHttpHeader" in {
    val header = `X-Test`(123)
    assert(header.toString == "X-Test: 123")
    RawHeader("X-Test", "123") match {
      case `X-Test`(number) ⇒ assert(number == 123)
    }

    RawHeader("X-TEST", "123") match {
      case `X-Test`(number) ⇒ assert(number == 123)
    }
  }
}

object OwnHttpHeaderTest {
  private case class `X-Test`(number: Int) extends OwnHttpHeader {
    def companion = `X-Test`
    def value = s"$number"
  }

  private object `X-Test` extends OwnHttpHeaderCompanion {
    def unapply(o: HttpHeader) = o is lowercaseName option  o.value.toInt
  }
}
