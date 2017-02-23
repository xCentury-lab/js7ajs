package com.sos.scheduler.engine.shared.event.journal

import com.sos.scheduler.engine.base.sprayjson.typed.TypedJsonFormat
import com.sos.scheduler.engine.common.BuildInfo
import com.sos.scheduler.engine.data.event.{Event, KeyedTypedEventJsonFormat}
import java.io.{InputStream, OutputStream}
import spray.json.{JsObject, JsString}

/**
  * @author Joacim Zschimmer
  */
final class JsonJournalMeta(
  val snapshotJsonFormat: TypedJsonFormat[Any],
  implicit val eventJsonFormat: KeyedTypedEventJsonFormat[Event],
  val snapshotToKey: Any ⇒ Any,
  val isDeletedEvent: Event ⇒ Boolean)
{
  def convertOutputStream(out: OutputStream): OutputStream = out

  def convertInputStream(in: InputStream): InputStream = in
}

object JsonJournalMeta {
  val Header = JsObject(
    "TYPE" → JsString("JobScheduler.Journal"),
    "version" → JsString(BuildInfo.version))
}
