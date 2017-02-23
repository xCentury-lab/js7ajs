package com.sos.jobscheduler.common.sprayutils

import com.sos.jobscheduler.common.sprayutils.JsObjectMarshallers._
import java.nio.charset.StandardCharsets._
import org.scalatest.FreeSpec
import spray.http.HttpEntity
import spray.http.MediaTypes._
import spray.httpx.marshalling._
import spray.httpx.unmarshalling._
import spray.json._

/**
 * @author Joacim Zschimmer
 */
final class JsObjectMarshallersTest extends FreeSpec {

  private val jsObject = JsObject("a" → JsString("Ä"))
  private val entity = HttpEntity(`application/json`, jsObject.compactPrint.getBytes(UTF_8))

  "Marshal application/json" in {
    assert(marshal(jsObject) == Right(entity))
  }

  "Unmarshal application/json" in {
    assert(entity.as[JsObject] == Right(jsObject))
  }
}
