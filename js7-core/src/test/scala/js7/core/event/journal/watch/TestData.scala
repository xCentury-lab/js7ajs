package js7.core.event.journal.watch

import java.nio.file.Path
import java.util.UUID
import js7.base.circeutils.CirceUtils.RichJsonObject
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.base.data.ByteArray
import js7.base.utils.AutoClosing.autoClosing
import js7.core.event.journal.data.JournalMeta
import js7.core.event.journal.write.{EventJournalWriter, SnapshotJournalWriter}
import js7.data.event.JournalEvent.SnapshotTaken
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.KeyedEventTypedJsonCodec.KeyedSubtype
import js7.data.event.{Event, EventId, JournalEvent, JournalHeader, JournalId, KeyedEvent, KeyedEventTypedJsonCodec, Stamped}
import monix.execution.Scheduler.Implicits.global

/**
  * @author Joacim Zschimmer
  */
private[watch] object TestData
{
  val journalId = JournalId(UUID.fromString("00112233-4455-6677-8899-AABBCCDDEEFF"))

  sealed trait TestEvent extends Event {
    type Key = String
  }

  final case object AEvent extends TestEvent
  final case object BEvent extends TestEvent

  implicit val jsonFormat = TypedJsonCodec[TestEvent](
    Subtype(AEvent),
    Subtype(BEvent))

  val TestKeyedEventJsonCodec = KeyedEventTypedJsonCodec[Event](
    KeyedSubtype[JournalEvent],
    KeyedSubtype[TestEvent])

  def writeJournalSnapshot[E <: Event](journalMeta: JournalMeta, after: EventId, snapshotObjects: Seq[Any]): Path =
    autoClosing(SnapshotJournalWriter.forTest(journalMeta, after = after)) { writer =>
      writer.writeHeader(JournalHeader.forTest(journalId, eventId = after))
      writer.beginSnapshotSection()
      for (o <- snapshotObjects) {
        writer.writeSnapshot(ByteArray(journalMeta.snapshotJsonCodec.encodeObject(o).compactPrint))
      }
      writer.endSnapshotSection()
      writer.beginEventSection(sync = false)
      writer.writeEvent(Stamped(after + 1, NoKey <-: SnapshotTaken))
      writer.file
    }

  def writeJournal(journalMeta: JournalMeta, after: EventId, stampedEvents: Seq[Stamped[KeyedEvent[Event]]],
    journalId: JournalId = this.journalId): Path
  =
    autoClosing(EventJournalWriter.forTest(journalMeta, after = after, journalId)) { writer =>
      writer.writeHeader(JournalHeader.forTest(journalId, eventId = after))
      writer.beginEventSection(sync = false)
      writer.writeEvents(stampedEvents take 1)
      writer.writeEvents(stampedEvents drop 1 take 2, transaction = true)
      writer.writeEvents(stampedEvents drop 3)
      writer.endEventSection(sync = false)
      writer.file
    }
}
