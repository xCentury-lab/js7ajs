package com.sos.jobscheduler.master.gui.components.gui

import com.sos.jobscheduler.data.event.{EventId, EventSeq, KeyedEvent, Stamped, TearableEventSeq}
import com.sos.jobscheduler.data.order.{Order, OrderEvent}
import com.sos.jobscheduler.master.gui.components.gui.GuiBackend._
import com.sos.jobscheduler.master.gui.components.state.{GuiState, OrdersState}
import com.sos.jobscheduler.master.gui.services.MasterApi
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{BackendScope, Callback}
import org.scalajs.dom
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * @author Joacim Zschimmer
  */
final class GuiBackend(scope: BackendScope[Unit, GuiState]) {

  private val onDocumentVisibilityChanged = (_: dom.raw.Event) ⇒ sleep.awake().runNow()
  private var isRequestingEvents = false

  def componentDidMount() =
    Callback {
      dom.document.addEventListener("visibilitychange", onDocumentVisibilityChanged)
    } >>
      requestOrders()

  def componentWillUnmount() = Callback {
    dom.document.removeEventListener("visibilitychange", onDocumentVisibilityChanged)
  }

  def requestOrders() = Callback.future {
    MasterApi.orders transform {
      case Success(stamped: Stamped[Seq[Order[Order.State]]]) ⇒
        Try {
          for {
            state ← scope.state
            _ ← scope.setState(state.copy(
                isConnected = true,
                ordersState = state.ordersState.updateOrders(stamped)))
            callback ← requestAndHandleEvents(after = stamped.eventId, forStep = state.ordersState.step + 1)
          } yield callback
        }

      case Failure(err) ⇒
        Try {
          scope.modState(state ⇒ state.copy(
            isConnected = false,
            ordersState = state.ordersState.copy(
              content = InitialFetchedContext,
              error = Some(err.toString),
              step = state.ordersState.step + 1)))
        }
    }
  }

  private def requestAndHandleEvents(
    after: EventId,
    forStep: Int,
    delay: FiniteDuration = 0.seconds,
    timeout: FiniteDuration = EventTimeout,
    afterErrorDelay: Iterator[FiniteDuration] = newAfterErrorDelayIterator)
  : Callback = {
      def fetchEvents() =
        Callback.future {
          isRequestingEvents = true
          MasterApi.orderEvents(after = after, timeout = timeout)
            .andThen { case _ ⇒
              isRequestingEvents = false  // TODO Falls requestOrders() aufgerufen wird, während Events geholt werden, wird isRequestingEvents zu früh zurückgesetzt (wegen doppelter fetchEvents)
            } transform
              handleResponse
        }

      def handleResponse(response: Try[TearableEventSeq[Seq, KeyedEvent[OrderEvent]]]): Try[Callback] =
        Try {
          withProperState(forStep) {
            case state if state.isFreezed ⇒
              scope.modState { _.copy(
                isConnected = false)
              }

            case state  ⇒
              val step = state.ordersState.step
              response match {
                case Failure(_) ⇒
                  scope.setState(state.copy(
                    isConnected = false)
                  ) >>
                    requestAndHandleEvents(after = after, forStep = step, timeout = FirstEventTimeout, afterErrorDelay = afterErrorDelay)
                      .delay(afterErrorDelay.next()).void

                case Success(EventSeq.Empty(lastEventId)) ⇒
                  scope.setState(state.copy(
                    isConnected = true)
                  ) >>
                    requestAndHandleEvents(after = lastEventId, forStep = step, delay = AfterTimeoutDelay)

                case Success(EventSeq.NonEmpty(stampedEvents)) ⇒
                  val nextStep = step + 1
                  scope.setState(state.copy(
                    isConnected = true,
                    ordersState = state.ordersState.copy(
                      content = state.ordersState.content match {
                        case content: OrdersState.FetchedContent ⇒
                          content.handleEvents(stampedEvents)
                        case o ⇒ o  // Ignore the events
                      },
                      step = nextStep))
                  ) >>
                    requestAndHandleEvents(after = stampedEvents.last.eventId, forStep = nextStep, delay = ContinueDelay)

                case Success(EventSeq.Torn) ⇒
                  dom.console.warn("EventSeq.Torn")
                  requestOrders().delay(TornDelay).void  // Request all orders
              }
        }
      }

      for {
        state ← scope.state
        callback ←
          if (state.isFreezed)
            Callback.empty
          else if (dom.document.hidden)
            sleep.start()
          else
            fetchEvents().delay(delay).void
      } yield callback
    }

  private def withProperState(forStep: Int)(body: GuiState ⇒ Callback): Callback =
    for {
      state ← scope.state
      callback ←
        if (state.ordersState.step == forStep)
          body(state)
        else {
          //dom.console.log(s"forStep=$forVersion != state.version=${state.version} - Callback discarded")
          Callback.empty
        }
    } yield callback

  def render(state: GuiState): VdomElement =
    new GuiRenderer(state, toggleFreezed).render

  private def toggleFreezed: Callback =
    scope.modState(state ⇒
      state.copy(
        isFreezed = !state.isFreezed)
    ) >>
      mayReconnect()
        .delay(100.milliseconds).void  // ??? Without delay, change of isFreezed will not have taken effect in mayReconnect

  object sleep {
    private var isSleeping = false

    def start() = Callback {
      if (!isSleeping) {
        dom.console.log(s"$Moon Sleeping...")
        //dom.document.title = originalTitle + Moon
        isSleeping = true
      }
    }

    def awake(): Callback = {
      if (!isSleeping)
        Callback.empty
      else {
        //dom.document.title = originalTitle
        isSleeping = false
        mayReconnect()
      }
    }
  }

  private def mayReconnect(): Callback =
    for {
      state ← scope.state
      callback ← {
        dom.console.log(s"mayReconnect: isFreezed=${state.isFreezed}")
        state.ordersState.content match {
          case content: OrdersState.FetchedContent if !state.isFreezed && !dom.document.hidden && !isRequestingEvents ⇒
            dom.console.log("Continuing requesting events...")
            requestAndHandleEvents(after = content.eventId, forStep = state.ordersState.step)
          case _ ⇒
            Callback.empty
        }
      }
    } yield callback
}

object GuiBackend {
  private val FirstEventTimeout =  0.seconds   // Short timeout to check connection
  private val EventTimeout      = 50.seconds
  private val ContinueDelay     =  250.milliseconds
  private val AfterTimeoutDelay = 1000.milliseconds
  private val TornDelay         = 1000.milliseconds
  private val Moon = "\uD83C\uDF19"

  private def newAfterErrorDelayIterator = (Iterator(1, 2, 4, 6) ++ Iterator.continually(10) ) map (_.seconds)

  private val InitialFetchedContext = OrdersState.FetchedContent(Map(), Nil, eventId = EventId.BeforeFirst, eventCount = 0)
}
