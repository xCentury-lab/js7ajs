package js7.data.cluster

import io.circe.generic.semiauto.deriveCodec
import js7.base.circeutils.typed.{Subtype, TypedJsonCodec}
import js7.base.problem.Checked
import js7.base.utils.ScalaUtils.syntax.*
import js7.base.web.Uri
import js7.data.cluster.ClusterEvent.{ClusterActiveNodeRestarted, ClusterActiveNodeShutDown, ClusterCoupled, ClusterCouplingPrepared, ClusterFailedOver, ClusterNodesAppointed, ClusterPassiveLost, ClusterSettingUpdated, ClusterSwitchedOver, ClusterWatchRegistered}
import js7.data.cluster.ClusterSetting.syntax.*
import js7.data.event.KeyedEvent.NoKey
import js7.data.event.{EventDrivenState, JournalPosition, KeyedEvent}
import js7.data.node.NodeId
import monix.reactive.Observable

sealed trait ClusterState
extends EventDrivenState[ClusterState, ClusterEvent]
{
  import ClusterState.*

  def companion = ClusterState

  final def isActive(nodeId: NodeId, isBackup: Boolean) =
    this == Empty && !isBackup || isNonEmptyActive(nodeId)

  def isNonEmptyActive(id: NodeId): Boolean

  def isEmptyOrActive(id: NodeId): Boolean

  final def applyEvent(keyedEvent: KeyedEvent[ClusterEvent]): Checked[ClusterState] =
    keyedEvent match {
      case KeyedEvent(_: NoKey, event) => applyEvent2(event)
      case _ => eventNotApplicable(keyedEvent)
    }

  private def applyEvent2(event: ClusterEvent): Checked[ClusterState] =
    (this, event) match {
      case (Empty, ClusterNodesAppointed(setting)) =>
        Right(NodesAppointed(setting))

      case (state: IsCoupledOrDecoupled, ClusterSettingUpdated(maybePassiveUri)) =>
        ((state, maybePassiveUri) match {
          case (_, None) => Right(state)
          case (state: IsDecoupled, Some(uri)) => Right(state.withPassiveUri(uri))
          case _ => eventNotApplicable(event)
        })

      case (state: IsDecoupled, ClusterCouplingPrepared(activeId)) if state.activeId == activeId =>
        Right(PreparedToBeCoupled(state.setting))

      case (state: PreparedToBeCoupled, ClusterCoupled(activeId)) if state.activeId == activeId =>
        Right(Coupled(state.setting))

      case (state: Coupled, ClusterPassiveLost(id)) if state.passiveId == id =>
        // Be sure that any event on Coupled leaves this state !!!
        // In case of a passive heartbeat loss, the JournalActor gets a JournalActor.Input.PassiveLost,
        // event if a concurrent ClusterEvent changes the ClusterState.
        // No ClusterEvent should go from Coupled to Coupled.
        Right(PassiveLost(state.setting))

      case (state: Coupled, ClusterSwitchedOver(id)) if state.passiveId == id =>
        Right(SwitchedOver(state.setting.copy(activeId = id)))

      case (state: Coupled, event: ClusterFailedOver)
        if state.activeId == event.failedActiveId && state.passiveId == event.activatedId =>
        Right(FailedOver(state.setting.copy(activeId = event.activatedId), failedAt = event.failedAt))

      case (state: Coupled, ClusterActiveNodeShutDown) =>
        Right(ActiveShutDown(state.setting))

      case (state: ActiveShutDown, ClusterActiveNodeRestarted) =>
        Right(PassiveLost(state.setting))
        // Passive node may recouple now

      case (state: HasNodes, ClusterWatchRegistered(clusterWatchId)) =>
        Right(state.withSetting(setting = state.setting.copy(
          clusterWatchId = Some(clusterWatchId))))

      case (_, keyedEvent) =>
        eventNotApplicable(keyedEvent)
    }

  def estimatedSnapshotSize =
    if (this != Empty) 1 else 0

  def toSnapshotObservable =
    Observable.fromIterable((this != Empty) ? ClusterStateSnapshot(this))

  def toShortString: String =
    this match {
      case Empty => "ClusterState.Empty"
      case hasNodes: HasNodes =>
        s"${hasNodes.getClass.simpleScalaName}(${hasNodes.activeId} is active)"
    }
}

object ClusterState
extends EventDrivenState.Companion[ClusterState, ClusterEvent]
{
  private type Id = NodeId

  /** Cluster has not been initialized.
    * Like ClusterSole but own URI is unknown. Non-permanent state, not stored. */
  case object Empty extends ClusterState
  {
    def isNonEmptyActive(id: Id) = false
    def isEmptyOrActive(id: Id) = true
  }

  sealed trait HasNodes extends ClusterState
  {
    this: Product =>

    val setting: ClusterSetting
    def idToUri = setting.idToUri
    def activeId = setting.activeId
    def timing = setting.timing

    def withSetting(setting: ClusterSetting): HasNodes

    final def isNonEmptyActive(id: Id) = id == activeId
    final def isEmptyOrActive(id: Id) = id == activeId
    final def passiveId = idToUri.peerOf(activeId)
    final def passiveUri = idToUri(passiveId)
    final def peerOf(nodeId: NodeId) = idToUri.peerOf(nodeId)

    protected final def nodesString =
      (for ((id, uri) <- idToUri) yield
        (if (activeId == id) "active " else "passive ") + s"${id.string}: $uri"
      ).mkString(", ")

    override def toString =
      s"$productPrefix($nodesString${setting.clusterWatchId.fold("")(o => ", " + o)})"
  }
  object HasNodes {
    def unapply(clusterState: ClusterState.HasNodes) = Some(clusterState.setting)

    implicit val jsonCodec: TypedJsonCodec[HasNodes] = TypedJsonCodec(
      Subtype(deriveCodec[NodesAppointed]),
      Subtype(deriveCodec[PreparedToBeCoupled]),
      Subtype(deriveCodec[Coupled]),
      Subtype(deriveCodec[ActiveShutDown]),
      Subtype(deriveCodec[PassiveLost]),
      Subtype(deriveCodec[SwitchedOver]),
      Subtype(deriveCodec[FailedOver]))
  }

  sealed trait IsCoupledOrDecoupled extends HasNodes {
    this: Product =>

    def withSetting(setting: ClusterSetting): IsCoupledOrDecoupled
  }

  sealed trait IsDecoupled extends IsCoupledOrDecoupled {
    this: Product =>

    final def withPassiveUri(uri: Uri) =
      withSetting(setting.withPassiveUri(uri))
  }

  final case class NodesAppointed(setting: ClusterSetting)
  extends IsDecoupled {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  /** Intermediate state only, is immediately followed by transition ClusterEvent.Coupled -> Coupled. */
  final case class PreparedToBeCoupled(setting: ClusterSetting)
  extends HasNodes {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  /** An active node is coupled with a passive node. */
  final case class Coupled(setting: ClusterSetting)
  extends IsCoupledOrDecoupled
  {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  /** The active node has shut down while `Coupled` and will continue to be active when restarted.
      The passive node must not fail-over.
      After restart, the active node will be still active.
    */
  final case class ActiveShutDown(setting: ClusterSetting)
  extends IsDecoupled {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  final case class SwitchedOver(setting: ClusterSetting)
  extends IsDecoupled {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  sealed trait IsNodeLost extends IsDecoupled {
    this: Product =>
  }

  final case class PassiveLost(setting: ClusterSetting)
  extends IsNodeLost {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)
  }

  /** Decoupled after failover.
    * @param failedAt the failing node's journal must be truncated at this point. */
  final case class FailedOver(setting: ClusterSetting, failedAt: JournalPosition)
  extends IsNodeLost {
    def withSetting(setting: ClusterSetting) = copy(setting = setting)

    override def toShortString =
      s"FailedOver(${setting.passiveId.string} --> ${setting.activeId.string})"

    override def toString = "FailedOver(" +
      s"${setting.passiveId.string} --> ${setting.activeId.string} at $failedAt)"
  }

  implicit val jsonCodec: TypedJsonCodec[ClusterState] = TypedJsonCodec(
    Subtype(Empty),
    Subtype[HasNodes])
}
