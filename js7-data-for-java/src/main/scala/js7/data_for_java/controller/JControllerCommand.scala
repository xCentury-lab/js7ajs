package js7.data_for_java.controller

import io.vavr.control.{Either => VEither}
import java.time.Instant
import java.util.Collections.emptyMap
import java.util.Optional
import javax.annotation.Nonnull
import js7.base.annotation.javaApi
import js7.base.problem.Problem
import js7.base.time.JavaTimestamp
import js7.data.board.{BoardPath, NoticeId}
import js7.data.controller.ControllerCommand
import js7.data.controller.ControllerCommand.{AddOrder, ControlWorkflow, ControlWorkflowPath, PostNotice}
import js7.data.workflow.{WorkflowId, WorkflowPath}
import js7.data.workflow.position.Label
import js7.data_for_java.common.JJsonable
import js7.data_for_java.order.JFreshOrder
import js7.data_for_java.workflow.position.JPosition
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional

@javaApi
final case class JControllerCommand(asScala: ControllerCommand)
extends JJsonable[JControllerCommand]
{
  protected type AsScala = ControllerCommand

  protected def companion = JControllerCommand
}

@javaApi
object JControllerCommand extends JJsonable.Companion[JControllerCommand]
{
  @Nonnull
  def addOrder(@Nonnull jFreshOrder: JFreshOrder): JControllerCommand =
    JControllerCommand(AddOrder(jFreshOrder.asScala))

  @Nonnull
  def postNotice(boardPath: BoardPath, noticeId: NoticeId, endOfLife: Optional[Instant])
  : JControllerCommand =
    JControllerCommand(
      PostNotice(
        boardPath,
        noticeId,
        endOfLife.toScala
          .map(JavaTimestamp.ofInstant)))

  @Deprecated
  @deprecated("Use controlWorkflowPath")
  @Nonnull
  def controlWorkflow(workflowPath: WorkflowPath, suspend: Boolean): JControllerCommand =
    controlWorkflowPath(workflowPath, Optional.of(suspend), emptyMap)

  @Nonnull
  def controlWorkflowPath(
    workflowPath: WorkflowPath,
    suspend: Optional[Boolean],
    skip: java.util.Map[Label, java.lang.Boolean])
  : JControllerCommand =
    JControllerCommand(
      ControlWorkflowPath( workflowPath, suspend = suspend.toScala,
        skip.asScala.view.mapValues(_.booleanValue).toMap))

  @Nonnull
  def controlWorkflow(
    workflowId: WorkflowId,
    breakpoints: java.util.Set[JPosition])
  : JControllerCommand =
    JControllerCommand(
      ControlWorkflow(
        workflowId,
        breakpoints.asScala.view.map(_.asScala).toSet))

  @Nonnull
  override def fromJson(@Nonnull jsonString: String): VEither[Problem, JControllerCommand] =
    super.fromJson(jsonString)

  protected def jsonDecoder = ControllerCommand.jsonCodec
  protected def jsonEncoder = ControllerCommand.jsonCodec
}
