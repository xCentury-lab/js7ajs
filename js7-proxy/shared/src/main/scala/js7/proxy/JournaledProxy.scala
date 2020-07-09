package js7.proxy

import cats.effect.Resource
import io.circe.Decoder
import js7.base.problem.Checked.Ops
import js7.base.problem.Problem
import js7.base.time.ScalaTime._
import js7.base.utils.Assertions.assertThat
import js7.base.utils.ScalaUtils.syntax._
import js7.base.utils.SetOnce
import js7.base.web.HttpClient
import js7.common.http.RecouplingStreamReader
import js7.common.http.configuration.RecouplingStreamReaderConf
import js7.data.event.{AnyKeyedEvent, Event, EventApi, EventId, EventRequest, JournaledState, KeyedEvent, Stamped}
import js7.proxy.ProxyEvent.ProxyStarted
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.reactive.Observable
import scala.concurrent.Promise
import scala.util.{Failure, Success}

final class JournaledProxy[S <: JournaledState[S]] private[proxy](
  observable: Observable[EventAndState[Event, S]],
  onEvent: EventAndState[Event, S] => Unit)
{
  private val currentStateFilled = Promise[Unit]()
  @volatile private var _currentState: (EventId, S) = null
  private val observing = SetOnce[CancelableFuture[Unit]]("observing")
  private val observingStopped = Promise[Unit]()

  private def start(): Task[Unit] =
    Task.deferFutureAction { implicit scheduler =>
      assertThat(observing.isEmpty)
      val obs = observe.completedL.runToFuture
      observing := obs
      obs.onComplete {
        case Success(()) =>
          scribe.error("Observable has terminated")
          // ???
        case Failure(t) =>
          scribe.error(t.toStringWithCauses, t.nullIfNoStackTrace)
          // ???
      }
      currentStateFilled.future
    }

  def stop: Task[Unit] =
    Task { cleanUp() }
      .flatMap(_ => Task.fromFuture(observingStopped.future))

  private def cleanUp(): Unit =
    for (o <- observing) o.cancel()

  private def observe: Observable[EventAndState[Event, S]] =
    subscriber =>
      observable
        .map { eventAndState =>
          _currentState = eventAndState.stampedEvent.eventId -> eventAndState.state
          currentStateFilled.trySuccess(())
          onEvent(eventAndState)
          eventAndState
        }
        .guarantee(Task {
          observingStopped.success(())
        })
        .subscribe(subscriber)

  def currentState: (EventId, S) =
    _currentState
}

object JournaledProxy
{
  type ApiResource = Resource[Task, EventApi]

  private val recouplingStreamReaderConf = RecouplingStreamReaderConf(timeout = 55.s, delay = 1.s)

  def start[S <: JournaledState[S]](apiResource: ApiResource, onEvent: EventAndState[Event, S] => Unit)
    (implicit S: JournaledState.Companion[S])
  : Task[JournaledProxy[S]] = {
    val proxy = JournaledProxy.prepare[S](apiResource, onEvent)
    proxy.start().map(_ => proxy)
  }

  private def prepare[S <: JournaledState[S]](
    apiResource: ApiResource,
    onEvent: EventAndState[Event, S] => Unit)
    (implicit S: JournaledState.Companion[S])
  : JournaledProxy[S] =
    new JournaledProxy[S](observe(apiResource), onEvent)

  private def observe[S <: JournaledState[S]](apiResource: ApiResource)
    (implicit S: JournaledState.Companion[S])
  : Observable[EventAndState[Event, S]] =
    Observable.fromResource(apiResource)
      .flatMap(api =>
        Observable.tailRecM(())(_ =>
          Observable
            .fromTask(
              api.loginUntilReachable() >>
                api.retryUntilReachable(api.snapshot)
                  .map(_.map(snapshot => snapshot.eventId -> S.fromIterable(snapshot.value).withEventId(snapshot.eventId))))
            .map(_.orThrow/*???*/)
            .flatMap { case (eventId, snapshotState) =>
              val seed = EventAndState(Stamped(eventId, KeyedEvent(ProxyStarted): AnyKeyedEvent), snapshotState)
              import S.keyedEventJsonDecoder
              observeEvents(api, after = eventId)
                .scan0(seed)((s, stampedEvent) =>
                  EventAndState(stampedEvent, s.state.applyEvent(stampedEvent.value).orThrow/*TODO Restart*/))
                .map(Right.apply)
                .onErrorHandleWith { t =>
                  scribe.error(t.toStringWithCauses, t.nullIfNoStackTrace)
                  scribe.warn("Restarting observation from a new snapshot, loosing some events")
                  Observable.pure(Left(())/*TODO Observable.tailRecM: Left leaks memory, https://github.com/monix/monix/issues/791*/)
                    .delayExecution(1.s/*TODO*/)
                }
            }))

  private def observeEvents(api: EventApi, after: EventId)(implicit kd: Decoder[KeyedEvent[Event]])
  : Observable[Stamped[AnyKeyedEvent]] =
    new RecouplingStreamReader[EventId, Stamped[AnyKeyedEvent], EventApi](_.eventId, recouplingStreamReaderConf)
    {
      def getObservable(api: EventApi, after: EventId) =
        HttpClient.liftProblem(
          api.eventObservable(
            EventRequest.singleClass[Event](after = after, delay = 50.ms, timeout = Some(55.s/*TODO*/))))

      //override def onCoupled(api: EventApi, after: EventId) =
      //  super.onCoupled(api, after)
      //
      //override protected def onCouplingFailed(api: EventApi, problem: Problem) =
      //  super.onCouplingFailed(api, problem)
      //
      //override protected def onDecoupled =
      //  super.onDecoupled

      override def eof(index: EventId) = false
      def stopRequested = false
    }.observe(api, after)
}
