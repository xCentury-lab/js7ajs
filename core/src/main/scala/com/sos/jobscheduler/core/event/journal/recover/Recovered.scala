package com.sos.jobscheduler.core.event.journal.recover

import akka.actor.{ActorRef, ActorRefFactory}
import com.sos.jobscheduler.core.event.journal.BabyJournaledState
import com.sos.jobscheduler.core.event.journal.data.{JournalMeta, RecoveredJournalingActors}
import com.sos.jobscheduler.core.event.journal.watch.JournalEventWatch
import com.sos.jobscheduler.core.event.state.JournaledStateBuilder
import com.sos.jobscheduler.data.cluster.ClusterState
import com.sos.jobscheduler.data.event.{Event, EventId, JournalId, JournaledState}
import com.typesafe.config.Config
import scala.concurrent.duration.Deadline
import scala.language.higherKinds

final case class Recovered[S <: JournaledState[S, E], E <: Event](
  journalMeta: JournalMeta,
  recoveredJournalFile: Option[RecoveredJournalFile[S, E]],
  totalRunningSince: Deadline,
  /** The recovered state */
  newStateBuilder: () => JournaledStateBuilder[S, E],
  eventWatch: JournalEventWatch,
  config: Config)
extends AutoCloseable
{
  def close() =
    eventWatch.close()

  def eventId: EventId =
    recoveredJournalFile.fold(EventId.BeforeFirst)(_.eventId)

  def journalId: Option[JournalId] = recoveredJournalFile.map(_.journalId)

  def babyJournaledState: BabyJournaledState =
    recoveredJournalFile.fold(BabyJournaledState.empty)(o =>
      BabyJournaledState(o.eventId, o.state.journalState, o.state.clusterState))

  def clusterState: ClusterState =
    babyJournaledState.clusterState

  def recoveredState: Option[S] =
    recoveredJournalFile.map(_.state)

  // Suppresses Config (which may contain secrets)
  override def toString = s"Recovered($journalMeta,$recoveredJournalFile,$eventWatch,Config)"

  def startJournalAndFinishRecovery(
    journalActor: ActorRef,
    recoveredActors: RecoveredJournalingActors = RecoveredJournalingActors.Empty)
    (implicit actorRefFactory: ActorRefFactory)
  =
    JournalRecoverer.startJournalAndFinishRecovery[Event](
      journalActor,
      babyJournaledState,
      recoveredActors,
      Some(eventWatch),
      recoveredJournalFile.map(_.journalId),
      recoveredJournalFile.map(_.calculatedJournalHeader),
      totalRunningSince)
}
