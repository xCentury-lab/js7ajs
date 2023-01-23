package js7.cluster

import cats.effect.concurrent.Deferred
import cats.effect.{ExitCase, Resource}
import cats.syntax.flatMap.*
import js7.base.fs2utils.Fs2PubSub
import js7.base.generic.Completed
import js7.base.log.Logger.syntax.*
import js7.base.log.{CorrelId, Logger}
import js7.base.monixutils.MonixBase.syntax.*
import js7.base.monixutils.MonixDeadline
import js7.base.monixutils.MonixDeadline.now
import js7.base.problem.Checked
import js7.base.service.Service
import js7.base.time.ScalaTime.{DurationRichInt, RichDuration}
import js7.base.utils.AsyncLock
import js7.base.utils.ScalaUtils.syntax.*
import js7.base.utils.Tests.isTest
import js7.cluster.ClusterWatchCounterpart.*
import js7.cluster.watch.api.AnyClusterWatch
import js7.cluster.watch.api.ClusterWatchProblems.{ClusterWatchIdDoesNotMatchProblem, ClusterWatchRequestDoesNotMatchProblem, NoClusterWatchProblem, OtherClusterWatchStillAliveProblem}
import js7.data.cluster.ClusterEvent.{ClusterCouplingPrepared, ClusterNodesAppointed, ClusterWatchRegistered}
import js7.data.cluster.ClusterState.{Coupled, FailedOver, HasNodes, PassiveLost}
import js7.data.cluster.ClusterWatchMessage.RequestId
import js7.data.cluster.ClusterWatchingCommand.ClusterWatchConfirm
import js7.data.cluster.{ClusterEvent, ClusterTiming, ClusterWatchCheckEvent, ClusterWatchCheckState, ClusterWatchId, ClusterWatchMessage, ClusterWatchRequest}
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import scala.annotation.tailrec
import scala.util.Random

final class ClusterWatchCounterpart private(
  clusterConf: ClusterConf,
  timing: ClusterTiming)
  (implicit scheduler: Scheduler)
extends Service.StoppableByRequest with AnyClusterWatch
{
  import clusterConf.ownId

  private val nextRequestId = Atomic(if (isTest) 1 else Random.nextLong(
    (Long.MaxValue - (3 * 32_000_000/*a year*/) / timing.heartbeat.toSeconds) / 1000 * 1000))
  private val lock = AsyncLock()
  private val request = Atomic(None: Option[Request])
  private val pubsub = new Fs2PubSub[Task, ClusterWatchMessage]

  private val clusterWatchUniquenessChecker = new ClusterWatchUniquenessChecker(
    clusterConf.clusterWatchUniquenessMemorySize)
  @volatile private var currentClusterWatchId: Option[CurrentClusterWatchId] = None

  protected def start =
    startService(
      untilStopRequested *> pubsub.complete)

  def tryLogout = Task.pure(Completed)

  def checkClusterState(clusterState: HasNodes, clusterWatchIdChangeAllowed: Boolean)
  : Task[Checked[Option[ClusterWatchConfirm]]] =
    if (!clusterState.setting.clusterWatchId.isDefined
      && !clusterWatchIdChangeAllowed
      && !clusterState.isInstanceOf[Coupled]
      && !clusterState.isInstanceOf[PassiveLost]
      && !clusterState.isInstanceOf[FailedOver])
      Task.right(None)
    else
      initializeCurrentClusterWatchId(clusterState) *>
        check(
          clusterState.setting.clusterWatchId,
          ClusterWatchCheckState(_, CorrelId.current, ownId, clusterState),
          clusterWatchIdChangeAllowed = clusterWatchIdChangeAllowed
        ).map(_.map(Some(_)))

  private def initializeCurrentClusterWatchId(clusterState: HasNodes): Task[Unit] =
    Task {
      if (currentClusterWatchId.isEmpty) {
        for (clusterWatchId <- clusterState.setting.clusterWatchId) {
          // Set expiration time on start to inhibit change of registered ClusterWatchId when
          // another ClusterWatch tries to confirm, too.
          currentClusterWatchId = Some(CurrentClusterWatchId(clusterWatchId))
        }
      }
    }

  def applyEvent(event: ClusterEvent, clusterState: HasNodes)
  : Task[Checked[Option[ClusterWatchConfirm]]] =
    event match {
      case _: ClusterNodesAppointed | _: ClusterCouplingPrepared
        if !clusterState.setting.clusterWatchId.isDefined =>
        Task.right(None)

      case _ =>
        check(
          clusterState.setting.clusterWatchId,
          ClusterWatchCheckEvent(_, CorrelId.current, ownId, event, clusterState),
          clusterWatchIdChangeAllowed = event.isInstanceOf[ClusterWatchRegistered]
        ).map(_.map(Some(_)))
    }

  private def check(
    clusterWatchId: Option[ClusterWatchId],
    toMessage: RequestId => ClusterWatchRequest,
    clusterWatchIdChangeAllowed: Boolean = false)
  : Task[Checked[ClusterWatchConfirm]] =
    if (!clusterWatchIdChangeAllowed && !clusterWatchId.isDefined)
      Task.left(NoClusterWatchProblem)
    else
      Task.defer {
        val reqId = RequestId(nextRequestId.getAndIncrement())
        val msg = toMessage(reqId)
        lock.lock(
          logger.debugTask("check", msg)(Task.defer {
            val req = new Request(clusterWatchId, reqId,
              clusterWatchIdChangeAllowed = clusterWatchIdChangeAllowed)
            request.set(Some(req))
            val t = now
            var warned = false
            send(msg)
              .*>(req
                .untilConfirmed
                .timeoutTo(
                  timing.clusterWatchReactionTimeout,
                  Task.raiseError(RequestTimeoutException)))
              .onErrorRestartLoop(()) {
                case (RequestTimeoutException, _, retry) =>
                  warned = true
                  logger.warn(
                    s"⭕ Still trying to get a confirmation from ${
                      clusterWatchId getOrElse "unknown ClusterWatch"} for ${
                      msg.toShortString} for ${t.elapsed.pretty}...")
                  retry(()).delayExecution(1.s)

                case (t, _, _) => Task.raiseError(t)
              }
              .flatTap {
                case Left(problem) =>
                  Task(logger.warn(s"ClusterWatch rejected ${msg.toShortString}: $problem"))

                case Right(ClusterWatchConfirm(_, clusterWatchId, _, Some(problem))) =>
                  // Just in case, the caller does not warn ???
                  Task(logger.warn(s"$clusterWatchId rejected ${msg.toShortString}: $problem"))

                case Right(ClusterWatchConfirm(_, clusterWatchId, _, None)) =>
                  Task {
                    if (warned) logger.info(
                      s"🟢 $clusterWatchId finally confirmed ${
                        msg.toShortString} after ${t.elapsed.pretty}")
                  }
              }
              .guaranteeCase(exitCase => Task {
                request.set(None)
                if (warned && exitCase != ExitCase.Completed) logger.warn(
                  s"${msg.toShortString} => $exitCase · after ${t.elapsed.pretty}")
              })
          }))
      }

  def executeClusterWatchConfirm(confirm: ClusterWatchConfirm): Task[Checked[Unit]] =
    logger.traceTask("executeClusterWatchConfirm", confirm.argString)(
      Task(clusterWatchUniquenessChecker.check(confirm.clusterWatchId, confirm.clusterWatchRunId))
        .flatMapT(_ => Task(takeRequest(confirm)))
        .flatMapT(_.confirm(confirm))
        .flatMapT(_ => Task {
          for (o <- currentClusterWatchId) o.touched(confirm.clusterWatchId)
          Checked.unit
        }))

  // Recursive in (wrong) case of concurrent access to this.request
  @tailrec private def takeRequest(confirm: ClusterWatchConfirm): Checked[Request] = {
    request.get() match {
      case None =>
        currentClusterWatchId match {
          case Some(o) if o.clusterWatchId != confirm.clusterWatchId =>
            // Try to return the same problem when ClusterWatchId does not match,
            // whether request.get() contains a Request or not.
            // So a second ClusterWatch gets always the same problem.
            Left(OtherClusterWatchStillAliveProblem/*?*/(
              rejectedClusterWatchId = confirm.clusterWatchId,
              requestedClusterWatchId = o.clusterWatchId))

          case _ =>
            Left(ClusterWatchRequestDoesNotMatchProblem)
        }

      case value @ Some(req) =>
        req.clusterWatchId match {
          case Some(o) if o != confirm.clusterWatchId
            && currentClusterWatchId.exists(_.isStillAlive) =>
            Left(OtherClusterWatchStillAliveProblem(
              rejectedClusterWatchId = confirm.clusterWatchId,
              requestedClusterWatchId = o))

          case Some(o) if o != confirm.clusterWatchId && !req.clusterWatchIdChangeAllowed =>
            Left(ClusterWatchIdDoesNotMatchProblem(
              rejectedClusterWatchId = confirm.clusterWatchId,
              requestedClusterWatchId = o))

          case _ =>
            if (confirm.requestId != req.id) {
              val problem = ClusterWatchRequestDoesNotMatchProblem
              logger.debug(s"$problem id=${confirm.requestId} but request=${req.id}")
              Left(problem)
            } else if (!request.compareAndSet(value, None))
              takeRequest(confirm)
            else {
              // Log when ActiveClusterNode will detect and register a changed ClusterWatchId.
              req.clusterWatchId match {
                case None => logger.info(s"${confirm.clusterWatchId} will be registered")
                case Some(o) if confirm.clusterWatchId != o =>
                  logger.info(s"${confirm.clusterWatchId} will replace registered $o")
                case _ =>
              }
              Right(req)
            }
        }
    }
  }

  def onClusterWatchRegistered(clusterWatchId: ClusterWatchId): Task[Unit] =
    Task {
      currentClusterWatchId = Some(CurrentClusterWatchId(clusterWatchId))
    }

  private def send(msg: ClusterWatchMessage): Task[Unit] =
    logger
      .traceTask("send", msg)(
        pubsub.publish(msg))
      .logWhenItTakesLonger("ClusterWatch sender")

  def newStream: Task[fs2.Stream[Task, ClusterWatchMessage]] =
    pubsub.newStream // TODO Delete all but the last message at a time. At push-side?

  override def toString = "ClusterWatchCounterpart"

  private final case class CurrentClusterWatchId(
    // This field is only to return a proper Problem if no Request is pending.
    clusterWatchId: ClusterWatchId)
  {
    private var expires: MonixDeadline =
      now + timing.clusterWatchIdTimeout

    def touched(clusterWatchId: ClusterWatchId): Unit =
      if (clusterWatchId == this.clusterWatchId) {
        expires = now + timing.clusterWatchIdTimeout
      }

    def isStillAlive: Boolean =
      expires.hasTimeLeft

    override def toString = s"$clusterWatchId($expires)"
  }
}

object ClusterWatchCounterpart
{
  private val logger = Logger[this.type]

  def resource(clusterConf: ClusterConf, timing: ClusterTiming): Resource[Task, ClusterWatchCounterpart] =
    Service.resource(Task.deferAction(scheduler => Task(
      new ClusterWatchCounterpart(clusterConf, timing)(scheduler))))

  /** A running request to ClusterWatch. */
  private final class Request(
    val clusterWatchId: Option[ClusterWatchId],
    val id: RequestId,
    val clusterWatchIdChangeAllowed: Boolean)
  {
    private val confirmation = Deferred.unsafe[Task, Checked[ClusterWatchConfirm]]

    def untilConfirmed: Task[Checked[ClusterWatchConfirm]] =
      confirmation.get

    def confirm(confirm: ClusterWatchConfirm): Task[Checked[Unit]] =
      confirmation.complete(confirm.problem.toLeft(confirm))
        .materialize/*Ignore duplicate complete*/.as(Checked.unit)

    override def toString = s"Request($id,$clusterWatchId)"
  }

  private object RequestTimeoutException extends Exception
}
