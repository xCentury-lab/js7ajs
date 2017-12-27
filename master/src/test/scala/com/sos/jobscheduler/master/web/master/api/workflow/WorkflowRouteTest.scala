package com.sos.jobscheduler.master.web.master.api.workflow

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.sos.jobscheduler.base.utils.Collections.implicits.RichTraversable
import com.sos.jobscheduler.common.CirceJsonSupport._
import com.sos.jobscheduler.common.akkahttp.AkkaHttpUtils.pathSegments
import com.sos.jobscheduler.common.event.EventIdGenerator
import com.sos.jobscheduler.common.event.collector.EventCollector
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.timer.TimerService
import com.sos.jobscheduler.data.event.Stamped
import com.sos.jobscheduler.data.workflow.test.ForkTestSetting.TestWorkflow
import com.sos.jobscheduler.data.workflow.{WorkflowGraph, WorkflowPath, WorkflowScript, WorkflowsOverview}
import com.sos.jobscheduler.master.WorkflowClient
import com.sos.jobscheduler.master.web.master.api.workflow.WorkflowRouteTest._
import org.scalatest.FreeSpec
import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * @author Joacim Zschimmer
  */
final class WorkflowRouteTest extends FreeSpec with ScalatestRouteTest with WorkflowRoute {

  protected implicit def executionContext = system.dispatcher
  private implicit val timerService = new TimerService(idleTimeout = Some(1.s))
  protected val eventCollector = new EventCollector.ForTest
  protected val eventIdGenerator = new EventIdGenerator
  protected val workflowClient = new WorkflowClient {
    def executionContext = WorkflowRouteTest.this.executionContext
    def workflow(path: WorkflowPath) = Future.successful(pathToWorkflow.get(path))
    def workflows = Future.successful(eventIdGenerator.stamp(pathToWorkflow.values.toVector))
    def workflowCount = Future.successful(pathToWorkflow.values.size)
  }

  private def route: Route =
    pathSegments("api/workflow") {
      workflowRoute
    }

  // WorkflowsOverview
  WorkflowUri in {
    Get(WorkflowUri) ~> Accept(`application/json`) ~> route ~> check {
      assert(status == OK)
      assert(responseAs[WorkflowsOverview] == WorkflowsOverview(workflowCount = pathToWorkflow.size))
    }
  }

  // Seq[WorkflowOverview]
  for (uri ← List(
      s"$WorkflowUri/")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        val Stamped(_, workflows) = responseAs[Stamped[Seq[WorkflowPath]]]
        assert(workflows == (pathToWorkflow.keys.toList))
      }
    }
  }

  // Seq[Workflow]
  for (uri ← List(
       s"$WorkflowUri/?return=WorkflowScript")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        val Stamped(_, workflows) = responseAs[Stamped[Seq[WorkflowScript.Named]]]
        assert(workflows == pathToWorkflow.values.flatMap(_.toWorkflowScriptNamed).toList)
      }
    }
  }

  for (uri ← List(
       s"$WorkflowUri/?return=WorkflowGraph")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        val Stamped(_, workflows) = responseAs[Stamped[Seq[WorkflowGraph.Named]]]
        assert(workflows == pathToWorkflow.values.toList)
      }
    }
  }

  // Workflow
  for (uri ← List(
       s"$WorkflowUri/${pathToWorkflow.values.head.path.withoutStartingSlash}",
       s"$WorkflowUri/${pathToWorkflow.values.head.path.withoutStartingSlash.replace("/", "%2F")}")) {
    s"$uri" in {
      Get(uri) ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        assert(responseAs[WorkflowScript] == pathToWorkflow.values.head.graph.originalScript.get)
      }
      Get(s"$uri?return=WorkflowScript") ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        assert(responseAs[WorkflowScript] == pathToWorkflow.values.head.graph.originalScript.get)
      }
      Get(s"$uri?return=WorkflowGraph") ~> Accept(`application/json`) ~> route ~> check {
        assert(status == OK)
        assert(responseAs[WorkflowGraph] == pathToWorkflow.values.head.graph)
      }
    }
  }
}

object WorkflowRouteTest {
  private val WorkflowUri = "/api/workflow"
  private val pathToWorkflow: Map[WorkflowPath, WorkflowGraph.Named] =
    List(WorkflowGraph.Named(WorkflowPath(s"/PATH${TestWorkflow.path.string}"), TestWorkflow.graph))
      .toKeyedMap(_.path)
}
