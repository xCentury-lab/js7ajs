package com.sos.jobscheduler.core.event.state

import akka.pattern.ask
import com.sos.jobscheduler.base.circeutils.CirceUtils.deriveCodec
import com.sos.jobscheduler.base.circeutils.typed.{Subtype, TypedJsonCodec}
import com.sos.jobscheduler.base.generic.GenericString
import com.sos.jobscheduler.base.problem.Checked._
import com.sos.jobscheduler.base.problem.{Checked, Problem}
import com.sos.jobscheduler.base.time.ScalaTime._
import com.sos.jobscheduler.base.utils.Collections.implicits._
import com.sos.jobscheduler.base.utils.ScalaUtils._
import com.sos.jobscheduler.common.akkautils.ProvideActorSystem
import com.sos.jobscheduler.common.event.EventIdClock
import com.sos.jobscheduler.common.scalautil.FileUtils.deleteDirectoryRecursively
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.core.event.journal.data.JournalMeta
import com.sos.jobscheduler.core.event.journal.recover.JournaledStateRecoverer
import com.sos.jobscheduler.core.event.journal.test.TestData
import com.sos.jobscheduler.core.event.journal.watch.JournalEventWatch
import com.sos.jobscheduler.core.event.journal.{JournalActor, JournalConf}
import com.sos.jobscheduler.core.event.state.JournaledStatePersistenceTest._
import com.sos.jobscheduler.data.event.KeyedEventTypedJsonCodec.KeyedSubtype
import com.sos.jobscheduler.data.event.{Event, EventId, JournalEvent, JournaledState, KeyedEvent, KeyedEventTypedJsonCodec, Stamped}
import com.typesafe.config.ConfigFactory
import io.circe.generic.JsonCodec
import java.nio.file.Files.createTempDirectory
import java.nio.file.Path
import java.util.concurrent.Executors
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import shapeless.tag

/**
  * @author Joacim Zschimmer
  */
final class JournaledStatePersistenceTest extends FreeSpec with BeforeAndAfterAll with ProvideActorSystem
{
  private implicit lazy val scheduler = Scheduler(Executors.newCachedThreadPool())  // Scheduler.Implicits.global blocks on 2-processor machine
  protected def config = TestConfig
  protected lazy val directory = createTempDirectory("JournaledStatePersistenceTest-")
  private lazy val journalMeta = testJournalMeta(fileBase = directory)

  override def afterAll() = {
    close()
    deleteDirectoryRecursively(directory)
    scheduler.shutdown()
    super.afterAll()
  }

  private val n = 1000
  private val keys = for (o <- 'A' to 'D') yield NumberKey(o.toString)
  private val expectedThingCollection = NumberThingCollection(
    Vector(
      NumberThing(NumberKey("ONE"), 0),
      NumberThing(NumberKey("TWO"), 0)
    ).toKeyedMap(_.key) ++
      keys.toVector
        .map(key => key -> NumberThing(key, n * 1000))
        .toMap)

  "First run" - {
    lazy val runningPersistence = new RunningPersistence
    lazy val persistence = runningPersistence.start()

    "Start" in {
       persistence
    }

    "persistKeyedEvent with simple KeyedEvent" in {
      assert(persistence.persistKeyedEvent(NumberKey("ONE") <-: NumberAdded).runSyncUnsafe().isRight)
      assert(persistence.persistKeyedEvent(NumberKey("ONE") <-: NumberAdded).runSyncUnsafe() == Left(Problem("Duplicate NumberThing: ONE")))
      intercept[MatchError] { persistence.persistKeyedEvent(NumberKey("ONE") <-: NumberUnhandled).runSyncUnsafe() }
    }

    "persistEvent" in {
      assert(persistence.persistEvent(_ => Right(NumberAdded))(NumberKey("TWO")).runSyncUnsafe().isRight)
    }

    "Concurrent update" in {
      val updated = keys
        .map(key =>
          persistence.persistEvent(_ =>
            Right(NumberAdded))(key).runToFuture: Future[Checked[(Stamped[KeyedEvent[NumberAdded.type]], TestState)]])
        .await(99.s)
      assert(updated.collectFirst { case Left(problem) => problem }.isEmpty)

      val keyFutures = for (key <- keys) yield
        Future {
          for (i <- 0 until n) yield
            persistence.persistEvent(_ => Right(NumberSlowlyIncremented(i * 1000)))(key)
              .runToFuture.await(99.s)
        }
      assert(keyFutures.await(99.s).flatten.collectFirst { case Left(problem) => problem }.isEmpty)
    }

    "currentState" in {
      assert(persistence.currentState.await(99.s) == TestState(eventId = 1000000 + 2 + keys.size * (1 + n), expectedThingCollection))
    }

    "Stop" in {
      runningPersistence.stop()
    }
  }

  "Second run, with recovered state" - {
    lazy val runningPersistence = new RunningPersistence
    lazy val persistence = runningPersistence.start()

    "Start" in {
      persistence
    }

    "currentState" in {
      assert(persistence.currentState.await(99.s) == TestState(eventId = 1000000 + 3 + keys.size * (1 + n), expectedThingCollection))
    }

    "Stop" in {
      runningPersistence.stop()
    }
  }

  private class RunningPersistence {
    private var journalStatePersistence: JournaledStatePersistence[TestState, TestEvent] = null
    private lazy val journalStopped = Promise[JournalActor.Stopped]()

    private lazy val journalActor = tag[JournalActor.type](
      actorSystem.actorOf(
        JournalActor.props(journalMeta, JournalConf.fromConfig(config), new StampedKeyedEventBus, Scheduler.global,
          new EventIdClock.Fixed(currentTimeMillis = 1000/*EventIds start at 1000000*/),
          journalStopped)))

    def start() = {
      val recovered = JournaledStateRecoverer.recover(journalMeta, new TestStateBuilder, JournalEventWatch.TestConfig)
      recovered.startJournalAndFinishRecovery(journalActor)(actorSystem)
      implicit val a = actorSystem
      journalStatePersistence = new JournaledStatePersistence[TestState, TestEvent](recovered.maybeState getOrElse TestState.empty, journalActor)
      journalStatePersistence
    }

    def stop() = {
      (journalActor ? JournalActor.Input.TakeSnapshot)(99.seconds) await 99.seconds
      if (journalStatePersistence != null) {
        journalStatePersistence.close()
      }
      journalActor ! JournalActor.Input.Terminate
      journalStopped.future await 99.s
    }
  }
}

private object JournaledStatePersistenceTest
{
  private val TestConfig = TestData.TestConfig
    .withFallback(ConfigFactory.parseString("""
     |jobscheduler.journal.dispatcher {
     |  type = PinnedDispatcher
     |}
     |""".stripMargin))

  private class TestStateBuilder extends JournalStateBuilder[TestState, TestEvent]
  {
    private val numberThings = mutable.Map[NumberKey, NumberThing]()
    private var _state = TestState.empty

    protected def onAddSnapshot = {
      case numberThing: NumberThing =>
        if (numberThings.contains(numberThing.key)) throw Problem(s"Duplicate NumberThing: ${numberThing.key}").throwable
        numberThings += numberThing.key -> numberThing
    }

    def onAllSnapshotsAdded(): Unit =
      _state = TestState(EventId.BeforeFirst, NumberThingCollection(numberThings.toMap))

    protected def onAddEvent: PartialFunction[Stamped[KeyedEvent[Event]], Unit] = {
      case Stamped(_, _, KeyedEvent(k: NumberKey, e: NumberEvent)) =>
        _state = _state.applyEvent(k <-: e).orThrow

      case Stamped(_, _, KeyedEvent(_, _: JournalEvent)) =>
    }

    def state =
      _state.copy(eventId = eventId)
  }

  private def testJournalMeta(fileBase: Path) =
    new JournalMeta(SnapshotJsonFormat, TestKeyedEventJsonCodec, fileBase)

  final case class NumberThing(key: NumberKey, number: Int) {
    def update(event: NumberEvent): Checked[NumberThing] =
      event match {
        case NumberIncremented =>
          Right(copy(number = number + 1))

        case e @ NumberSlowlyIncremented(expected) =>
          if (number != expected)
            Left(Problem(s"$e, but number is $toString"))
          else {
            Thread.sleep(1)
            Right(copy(number = number + 1000))
          }

        case _ => throw new MatchError(event)
      }
  }

  final case class TestState(eventId: EventId, numberThingCollection: NumberThingCollection)
  extends JournaledState[TestState, TestEvent]
  {
    protected type Self = NumberThingCollection
    protected type Snapshot = NumberThing
    protected type E = NumberEvent

    def withEventId(eventId: EventId) =
      copy(eventId = eventId)

    def applyEvent: PartialFunction[KeyedEvent[TestEvent], Checked[TestState]] = {
      case KeyedEvent(key: NumberKey, event: NumberEvent) =>
        for (o <- numberThingCollection.applyEvent(key <-: event)) yield
          copy(numberThingCollection = o)
    }

    def toSnapshotObservable = Observable.fromIterable(numberThingCollection.numberThings.values)
  }
  object TestState {
    val empty = TestState(EventId.BeforeFirst, NumberThingCollection(Map.empty))
  }

  final case class NumberThingCollection(numberThings: Map[NumberKey, NumberThing])
  {
    def applyEvent: PartialFunction[KeyedEvent[NumberEvent], Checked[NumberThingCollection]] = {
      case KeyedEvent(key: NumberKey, event: NumberEvent) =>
        event match {
          case NumberAdded =>
            if (numberThings.contains(key))
              Left(Problem(s"Duplicate NumberThing: $key"))
            else
              Right(copy(numberThings = numberThings + (key -> NumberThing(key, 0))))
          case _ =>
            numberThings.checked(key)
              .flatMap(_.update(event))
              .map(thing => copy(numberThings = numberThings + (thing.key -> thing)))
        }
    }
  }


  final case class StringThing(key: StringKey, string: String)

  @JsonCodec
  final case class NumberKey(string: String) extends GenericString

  @JsonCodec
  final case class StringKey(string: String) extends GenericString

  sealed trait TestEvent extends Event

  sealed trait NumberEvent extends TestEvent {
    type Key = NumberKey
  }
  object NumberEvent {
    implicit val jsonFormat = TypedJsonCodec[NumberEvent](
      Subtype(NumberAdded),
      Subtype(NumberRemoved),
      Subtype(NumberIncremented),
      Subtype(deriveCodec[NumberSlowlyIncremented]),
      Subtype(NumberUnhandled))
  }
  case object NumberAdded extends NumberEvent

  case object NumberRemoved extends NumberEvent

  case object NumberIncremented extends NumberEvent

  final case class NumberSlowlyIncremented(expected: Int) extends NumberEvent

  case object NumberUnhandled  extends NumberEvent

  private val SnapshotJsonFormat = TypedJsonCodec[Any](
    Subtype(deriveCodec[NumberThing]),
    Subtype(deriveCodec[StringThing]))

  private implicit val TestKeyedEventJsonCodec = KeyedEventTypedJsonCodec[Event](
    KeyedSubtype[JournalEvent],
    KeyedSubtype[NumberEvent])

  implicit val jsonFormat = TypedJsonCodec[TestEvent](
    Subtype[NumberEvent])
}
