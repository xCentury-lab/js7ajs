package js7.tests.controller.commands

import akka.http.scaladsl.model.StatusCodes.{InternalServerError, ServiceUnavailable}
import js7.base.problem.Checked.Ops
import js7.base.time.ScalaTime._
import js7.common.configutils.Configs._
import js7.common.http.AkkaHttpClient.HttpException
import js7.common.scalautil.MonixUtils.syntax._
import js7.data.order.{FreshOrder, OrderId}
import js7.data.workflow.WorkflowPath
import js7.data.workflow.parser.WorkflowParser
import js7.tests.controller.commands.AddOrderTimeoutTest._
import js7.tests.testenv.ControllerAgentForScalaTest
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

final class AddOrderTimeoutTest extends AnyFreeSpec with ControllerAgentForScalaTest
{
  protected val agentRefPaths = Nil
  protected val inventoryItems = workflow :: Nil
  override protected val controllerConfig = config"""
    js7.web.server.auth.public = on
    js7.akka.ask-timeout = 1s
    js7.TEST-ONLY.add-order-delay = 10s
    """

  "AddOrder timeout is returned as 403 Service Unavailable" in {
    val status = intercept[HttpException] {
      controller.httpApi.addOrder(FreshOrder(OrderId("ORDER"), workflow.path)).await(99.s)
    }.status
    // Despite error, addOrder may be successfully completed, so ServiceUnavailable is inappropriate:
    // assert(status == ServiceUnavailable)
    assert(status == InternalServerError)
  }
}

object AddOrderTimeoutTest
{
  private val workflow = WorkflowParser.parse(
    WorkflowPath("/WORKFLOW") ~ "1","""
      define workflow {
        execute executable="/SCRIPT1.cmd", agent="/AGENT";
      }"""
  ).orThrow
}
