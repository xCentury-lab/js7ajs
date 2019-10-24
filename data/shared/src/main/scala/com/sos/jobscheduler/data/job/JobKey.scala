package com.sos.jobscheduler.data.job

import cats.syntax.either._
import com.sos.jobscheduler.base.utils.ScalazStyle._
import com.sos.jobscheduler.data.workflow.instructions.executable.WorkflowJob
import com.sos.jobscheduler.data.workflow.position.{Position, WorkflowPosition, _}
import com.sos.jobscheduler.data.workflow.{WorkflowId, WorkflowPath}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, JsonObject}

/**
  * @author Joacim Zschimmer
  */
sealed trait JobKey
{
  def keyName: String

  override def toString = s"JobKey($keyName)"
}

object JobKey
{
  def apply(workflowPosition: WorkflowPosition) =
    Anonymous(workflowPosition)

  def apply(workflowBranchPath: WorkflowBranchPath, name: WorkflowJob.Name) =
    Named(workflowBranchPath, name)

  def forTest: JobKey = forTest("TEST")

  def forTest(name: String) = Named(WorkflowBranchPath(WorkflowPath.NoId, Nil), WorkflowJob.Name(name))

  final case class Anonymous(workflowPosition: WorkflowPosition) extends JobKey {
    def keyName = workflowPosition.toString
  }

  final case class Named(workflowBranchPath: WorkflowBranchPath, jobName: WorkflowJob.Name) extends JobKey {
    def keyName = s"$workflowBranchPath:${jobName.string}"
  }

  implicit val jsonEncoder: Encoder.AsObject[JobKey] = {
    case Anonymous(WorkflowPosition(workflowId, position)) =>
      JsonObject(
        "workflowId" -> workflowId.asJson,
        "position" -> position.asJson)

    case Named(WorkflowBranchPath(workflowId, branchPath), name) =>
      JsonObject(
        "workflowId" -> workflowId.asJson,
        "branchPath" -> (branchPath.nonEmpty ? branchPath).asJson,
        "jobName" -> name.asJson)
  }

  implicit val jsonDecoder: Decoder[JobKey] =
    c => for {
      workflowId <- c.get[WorkflowId]("workflowId")
      jobKey <- c.get[Position]("position").map(o => Anonymous(workflowId /: o))
        .orElse(
          for {
            branchPath <- c.get[Option[BranchPath]]("branchPath") map (_ getOrElse Nil)
            name <- c.get[WorkflowJob.Name]("jobName")
          } yield Named(WorkflowBranchPath(workflowId, branchPath), name))
    } yield jobKey
}
