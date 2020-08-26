package js7.controller.web.controller.api

import akka.http.scaladsl.testkit.RouteTestTimeout
import java.nio.file.Files.{createTempDirectory, size}
import java.util.UUID
import js7.base.auth.SessionToken
import js7.base.data.ByteSequence.ops._
import js7.base.time.ScalaTime._
import js7.base.utils.AutoClosing.autoClosing
import js7.base.utils.ScodecUtils.syntax._
import js7.base.web.Uri
import js7.common.akkahttp.AkkaHttpServerUtils.pathSegments
import js7.common.akkahttp.web.AkkaWebServer
import js7.common.event.PositionAnd
import js7.common.http.AkkaHttpClient
import js7.common.scalautil.FileUtils.deleteDirectoryRecursively
import js7.common.scalautil.FileUtils.syntax._
import js7.common.scalautil.Futures.implicits._
import js7.common.scalautil.MonixUtils.syntax._
import js7.common.time.WaitForCondition.waitForCondition
import js7.controller.data.ControllerState
import js7.controller.web.controller.api.test.RouteTester
import js7.core.event.journal.data.JournalMeta
import js7.core.event.journal.files.JournalFiles._
import js7.core.event.journal.watch.JournalEventWatch
import js7.core.event.journal.write.{EventJournalWriter, SnapshotJournalWriter}
import js7.data.event.JournalEvent.SnapshotTaken
import js7.data.event.JournalSeparators.EndOfJournalFileMarker
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.{EventId, JournalHeader, JournalId, Stamped}
import js7.data.order.OrderEvent.OrderAdded
import js7.data.order.OrderId
import js7.data.workflow.WorkflowPath
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class JournalRouteTest extends AnyFreeSpec with RouteTester with JournalRoute
{
  private implicit val timeout = 99.seconds
  private implicit val routeTestTimeout = RouteTestTimeout(timeout)
  protected def whenShuttingDown = Future.never
  protected implicit def scheduler: Scheduler = Scheduler.global
  private lazy val directory = createTempDirectory("JournalRouteTest-")
  private lazy val journalMeta = JournalMeta(ControllerState, directory / "test")
  override protected def config = JournalEventWatch.TestConfig.withFallback(super.config)
  protected var eventWatch = new JournalEventWatch(journalMeta, config)
  private val journalId = JournalId(UUID.fromString("00112233-4455-6677-8899-AABBCCDDEEFF"))
  private var eventWriter: EventJournalWriter = null

  private lazy val webServer = AkkaWebServer.forTest(pathSegments("journal")(journalRoute))
  private lazy val uri = webServer.localUri
  private lazy val client = new AkkaHttpClient.Standard(uri, uriPrefixPath = "", actorSystem = system, name = "JournalRouteTest")

  override def beforeAll() = {
    super.beforeAll()
    eventWatch = new JournalEventWatch(journalMeta, config)
    webServer.start() await 99.s
    writeSnapshot(EventId.BeforeFirst)
    eventWriter = newEventJournalWriter(EventId.BeforeFirst)
    eventWriter.onJournalingStarted()
  }

  override def afterAll() = {
    webServer.close()
    eventWriter.close()
    deleteDirectoryRecursively(directory)
    super.afterAll()
  }

  implicit private val sessionToken: Task[Option[SessionToken]] = Task.pure(None)
  private lazy val file0 = journalMeta.file(0L)

  "/journal from start" in {
    val lines = client.getRawLinesObservable(Uri(s"$uri/journal?timeout=0&file=0&position=0"))
      .await(99.s).toListL.await(99.s)
    assert(lines.map(_.utf8String).mkString == file0.contentString)
  }

  "/journal from end of file" in {
    val fileLength = size(file0)
    val lines = client.getRawLinesObservable(Uri(s"$uri/journal?timeout=0&file=0&position=$fileLength"))
      .await(99.s).toListL.await(99.s)
    assert(lines.map(_.utf8String).isEmpty)
  }

  "New data" - {
    val observed = mutable.Buffer[String]()
    var observing: CancelableFuture[Unit] = null

    "Nothing yet written" in {
      val initialFileLength = size(journalMeta.file(0L))
      observing = client.getRawLinesObservable(Uri(s"$uri/journal?timeout=9&markEOF=true&file=0&position=$initialFileLength"))
        .await(99.s).foreach(observed += _.utf8String)
      sleep(100.ms)
      assert(observed.isEmpty)
    }

    "Written but not flushed" in {
      eventWriter.writeEvent(Stamped(1000L, OrderId("1") <-: OrderAdded(WorkflowPath("/TEST") ~ "VERSION", None, Map.empty)))
      sleep(100.ms)
      assert(observed.isEmpty)
    }

    "flushed" in {
      assert(observed.isEmpty)
      eventWriter.flush(false)
      waitForCondition(9.s, 10.ms)(observed.nonEmpty)
      assert(observed.mkString ==
         """{"eventId":1000,"key":"1","TYPE":"OrderAdded","workflowId":{"path":"/TEST","versionId":"VERSION"}}
           |""".stripMargin)
    }

    "committed" in {
      // Journal web service reads uncommitted !!!
      eventWriter.onCommitted(eventWriter.fileLengthAndEventId, n = 1)
    }

    "Next file" in {
      eventWriter.endEventSection(sync = false)
      eventWriter.close()
      observing await 99.s
      assert(observed.mkString ==
         """{"eventId":1000,"key":"1","TYPE":"OrderAdded","workflowId":{"path":"/TEST","versionId":"VERSION"}}
           |""".stripMargin ++
           EndOfJournalFileMarker.utf8String)

      writeSnapshot(1000L)
      eventWriter = newEventJournalWriter(1000L)
      eventWriter.onJournalingStarted()
    }
  }

  "Truncated record is ignored" in {
    eventWriter.endEventSection(sync = false)
    eventWriter.close()
    eventWatch.close()

    writeSnapshot(2000L)
    val file2 = journalMeta.file(2000L)
    val file2size = size(file2)
    file2 ++= "{"  // Truncated record

    val file3 = journalMeta.file(3000L)
    writeSnapshot(3000L)

    eventWatch = new JournalEventWatch(journalMeta, config)
    eventWatch.onJournalingStarted(file3, journalId, PositionAnd(size(file3), 3000L), PositionAnd(size(file3), 3000L))

    val lines = client.getRawLinesObservable(Uri(s"$uri/journal?timeout=0&markEOF=true&file=2000&position=$file2size"))
      .await(99.s).toListL.await(99.s)
    assert(lines == List(EndOfJournalFileMarker))
  }

  private def writeSnapshot(eventId: EventId): Unit =
    autoClosing(newSnapshotJournalWriter(eventId)) { writer =>
      writer.writeHeader(JournalHeader.forTest(journalId))
      writer.beginSnapshotSection()
      writer.endSnapshotSection()
      writer.beginEventSection(sync = false)
      writer.writeEvent(Stamped(eventId + 1, NoKey <-: SnapshotTaken))
    }

  private def newSnapshotJournalWriter(eventId: EventId) =
    new SnapshotJournalWriter(journalMeta, journalMeta.file(eventId), after = eventId, simulateSync = None)

  private def newEventJournalWriter(eventId: EventId) =
    new EventJournalWriter(journalMeta, journalMeta.file(eventId), after = eventId, journalId, Some(eventWatch), simulateSync = None)
}
