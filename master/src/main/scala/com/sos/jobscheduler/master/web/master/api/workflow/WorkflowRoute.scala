package com.sos.jobscheduler.master.web.master.api.workflow

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.common.akkahttp.CirceJsonOrYamlSupport._
import com.sos.jobscheduler.common.akkahttp.StandardDirectives.remainingSegmentOrPath
import com.sos.jobscheduler.common.akkahttp.StandardMarshallers._
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.master.WorkflowClient
import scala.concurrent.ExecutionContext

/**
  * @author Joacim Zschimmer
  */
trait WorkflowRoute {

  protected def workflowClient: WorkflowClient
  protected implicit def executionContext: ExecutionContext

  def workflowRoute: Route =
    get {
      pathEnd {
        complete(workflowClient.workflowsOverview)
      } ~
      pathSingleSlash {
        parameter("return".?) {
          case Some("WorkflowOverview") | None ⇒
            complete(workflowClient.workflowOverviews)

          case Some("Workflow") | Some("Workflow") | None ⇒
            complete(workflowClient.workflows)

          case _ ⇒
            reject
        }
      } ~
      path(remainingSegmentOrPath[WorkflowPath]) { workflowPath ⇒
        singleWorkflow(workflowPath)
      }
    }

  private def singleWorkflow(path: WorkflowPath): Route =
    parameter("return".?) {
      case Some("Workflow") | Some("Workflow") | None ⇒
        complete {
          workflowClient.workflow(path).map[ToResponseMarshallable] {
            case Some(workflow) ⇒
              workflow
            case None ⇒
              Problem(s"$path does not exist")
          }
        }

      case o ⇒
        complete(Problem(s"Unrecognized parameter return=$o"))
    }
}
