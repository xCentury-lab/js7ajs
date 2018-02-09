package com.sos.jobscheduler.shared.event.journal

import akka.actor.{ActorContext, ActorRef}
import com.sos.jobscheduler.base.utils.DuplicateKeyException
import com.sos.jobscheduler.base.utils.ScalaUtils.RichPartialFunction
import com.sos.jobscheduler.data.event.{AnyKeyedEvent, Event, KeyedEvent, Stamped}
import com.sos.jobscheduler.shared.event.journal.JournalRecoverer.startJournalAndFinishRecovery
import scala.collection.mutable

/**
  * @author Joacim Zschimmer
  */
trait JournalActorRecoverer[E <: Event] extends JournalRecoverer[E] {

  protected implicit def sender: ActorRef
  protected def recoverNewKey: PartialFunction[Stamped[AnyKeyedEvent], Unit]
  protected def snapshotToKey: Any ⇒ Any
  protected def isDeletedEvent: E ⇒ Boolean

  private val keyToActor = mutable.Map[Any, ActorRef]()

  final def recoverAllAndTransferTo(journalActor: ActorRef)(implicit context: ActorContext): Unit = {
    recoverAll()
    startJournalAndFinishRecovery(journalActor = journalActor, recoveredJournalingActors)
  }

  protected final def recoverEvent = {
    case stamped @ Stamped(_, _, KeyedEvent(key, event)) ⇒
      keyToActor.get(key) match {
        case None ⇒
          recoverNewKey.getOrElse(stamped,
            sys.error(s"Uncoverable event for a new key in journal '$journalFile': $stamped"))
        case Some(a) ⇒
          a ! KeyedJournalingActor.Input.RecoverFromEvent(stamped)   // TODO OutOfMemoryError
          if (isDeletedEvent(event.asInstanceOf[E])) {
            keyToActor -= key
          }
        }
  }

  protected def recoverActorForSnapshot(snapshot: Any, actorRef: ActorRef): Unit = {
    val key = snapshotToKey(snapshot)
    if (keyToActor isDefinedAt key) throw new DuplicateKeyException(s"Duplicate snapshot in journal journalFile: '$key'")
    keyToActor += key → actorRef
    actorRef ! KeyedJournalingActor.Input.RecoverFromSnapshot(snapshot)
  }

  protected def recoverActorForNewKey(stampedEvent: Stamped[AnyKeyedEvent], actorRef: ActorRef): Unit = {
    val keyedEvent = stampedEvent.value
    import keyedEvent.key
    if (keyToActor isDefinedAt key) throw new DuplicateKeyException(s"Duplicate key: '$key'")
    keyToActor += key → actorRef
    actorRef ! KeyedJournalingActor.Input.RecoverFromEvent(stampedEvent)
  }

  private[journal] final def recoveredJournalingActors: RecoveredJournalingActors =
    RecoveredJournalingActors(keyToActor.toMap)
}
