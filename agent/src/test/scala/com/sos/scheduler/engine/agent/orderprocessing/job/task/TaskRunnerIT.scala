package com.sos.scheduler.engine.agent.orderprocessing.job.task

import com.google.common.io.Closer
import com.google.inject.Guice
import com.sos.scheduler.engine.agent.configuration.AgentConfiguration
import com.sos.scheduler.engine.agent.configuration.inject.AgentModule
import com.sos.scheduler.engine.agent.orderprocessing.job.JobConfiguration
import com.sos.scheduler.engine.agent.orderprocessing.job.task.TaskRunnerIT._
import com.sos.scheduler.engine.agent.task.StandardAgentTaskFactory
import com.sos.scheduler.engine.base.utils.MapDiff
import com.sos.scheduler.engine.common.guice.GuiceImplicits.RichInjector
import com.sos.scheduler.engine.common.scalautil.Futures.implicits.SuccessFuture
import com.sos.scheduler.engine.common.scalautil.xmls.XmlSources.xmlElemToSource
import com.sos.scheduler.engine.common.system.OperatingSystem.isWindows
import com.sos.scheduler.engine.common.time.ScalaTime._
import com.sos.scheduler.engine.common.time.Stopwatch.measureTime
import com.sos.scheduler.engine.data.engine2.order.{JobChainPath, JobPath, NodeId, NodeKey, Order}
import com.sos.scheduler.engine.data.engine2.order.OrderEvent.OrderStepSucceeded
import com.sos.scheduler.engine.data.order.OrderId
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * @author Joacim Zschimmer
  */
final class TaskRunnerIT extends FreeSpec with BeforeAndAfterAll {

  private lazy val injector = Guice.createInjector(new AgentModule(AgentConfiguration.forTest()))

  override protected def afterAll() = {
    injector.instance[Closer].close()
    super.afterAll()
  }

  "test" in {
    val jobConfiguration = JobConfiguration.parseXml(
      JobPath("/TEST"),
      xmlElemToSource(
        <job>
          <params>
            <param name="var1" value="VALUE1"/>
          </params>
          <script language="shell">{TestScript}</script>
        </job>))
    measureTime(10, "TaskRunner") {
      val order = Order(
        OrderId("TEST"),
        NodeKey(JobChainPath("/JOBCHAIN"), NodeId("NODE")),
        Order.InProcess,
        Map("a" → "A"))
      implicit val x = injector.instance[StandardAgentTaskFactory]
      val ended = TaskRunner.stepOne(jobConfiguration, order) await 30.s
      assert(ended == OrderStepSucceeded(
        variablesDiff = MapDiff.addedOrUpdated(Map("result" → "TEST-RESULT-VALUE1")),
        returnValue = true))
    }
  }
}

object TaskRunnerIT {
  private val TestScript =
    if (isWindows) """
        |@echo off
        |echo Hej!
        |echo var1=%SCHEDULER_PARAM_VAR1%
        |echo result=TEST-RESULT-%SCHEDULER_PARAM_VAR1% >>"%SCHEDULER_RETURN_VALUES%"
        |""".stripMargin
    else """
        |echo "Hej!"
        |echo "var1=$SCHEDULER_PARAM_VAR1"
        |echo "result=TEST-RESULT-$SCHEDULER_PARAM_VAR1" >>"$SCHEDULER_RETURN_VALUES"
        |""".stripMargin
}
