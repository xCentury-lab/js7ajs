package js7.data.workflow

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import js7.data.item.{ItemRevision, TrivialItemState, UnsignedSimpleItem, UnsignedSimpleItemState}
import js7.data.workflow.position.Label

final case class WorkflowPathControl(
  path: WorkflowPathControlPath,
  suspended: Boolean = false,
  skip: Set[Label] = Set.empty,
  itemRevision: Option[ItemRevision] = Some(ItemRevision(0)))
extends UnsignedSimpleItem, UnsignedSimpleItemState, TrivialItemState[WorkflowPathControl]:

  protected type Self = WorkflowPathControl
  val companion: WorkflowPathControl.type = WorkflowPathControl
  val item: WorkflowPathControl = this

  def rename(path: WorkflowPathControlPath) =
    copy(path = path)

  def withRevision(revision: Option[ItemRevision]) =
    copy(itemRevision = revision)

  def workflowPath: WorkflowPath =
    path.workflowPath


object WorkflowPathControl
extends UnsignedSimpleItem.Companion[WorkflowPathControl],
  UnsignedSimpleItemState.Companion[WorkflowPathControl],
  TrivialItemState.Companion[WorkflowPathControl]:

  override type Key = WorkflowPathControlPath
  def Key = WorkflowPathControlPath

  override type Path = WorkflowPathControlPath
  val Path: WorkflowPathControlPath.type = WorkflowPathControlPath
  val cls = classOf[WorkflowPathControl]

  override type ItemState = WorkflowPathControl

  implicit val jsonCodec: Codec.AsObject[WorkflowPathControl] =
    deriveCodec[WorkflowPathControl]
