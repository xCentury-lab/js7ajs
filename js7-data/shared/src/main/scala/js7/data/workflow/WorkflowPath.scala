package js7.data.workflow

import js7.base.annotation.javaApi
import js7.data.item.{SourceType, ItemPath}

final case class WorkflowPath private(string: String)
extends ItemPath
{
  def companion = WorkflowPath
}

object WorkflowPath extends ItemPath.Companion[WorkflowPath]
{
  val sourceTypeToFilenameExtension = Map(
    SourceType.Json -> ".workflow.json",
    SourceType.Yaml -> ".workflow.yaml",
    SourceType.Txt -> ".workflow.txt",
    SourceType.Xml -> ".job_chain.xml")

  protected def unchecked(string: String) = new WorkflowPath(string)

  @javaApi @throws[RuntimeException]("on invalid syntax")
  def of(validWorkflowPath: String): WorkflowPath =
    apply(validWorkflowPath)
}
