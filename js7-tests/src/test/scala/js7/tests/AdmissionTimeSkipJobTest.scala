package js7.tests

import com.google.inject.{AbstractModule, Provides}
import java.time.DayOfWeek.{FRIDAY, THURSDAY}
import java.time.{LocalTime, ZoneId}
import javax.inject.Singleton
import js7.base.configutils.Configs.HoconStringInterpolator
import js7.base.thread.MonixBlocking.syntax.RichTask
import js7.base.time.ScalaTime._
import js7.base.time.{AdmissionTimeScheme, AlarmClock, JavaTimestamp, TimeZone, WeekdayPeriod}
import js7.base.utils.ScalaUtils.syntax.RichEither
import js7.data.agent.AgentPath
import js7.data.execution.workflow.instructions.ExecuteExecutor.orderIdToDate
import js7.data.order.Order.Fresh
import js7.data.order.OrderEvent.{OrderAdded, OrderAttachable, OrderAttached, OrderDetachable, OrderDetached, OrderFinished, OrderMoved, OrderProcessed, OrderProcessingStarted, OrderStarted}
import js7.data.order.{FreshOrder, OrderId, Outcome}
import js7.data.workflow.instructions.Execute
import js7.data.workflow.instructions.executable.WorkflowJob
import js7.data.workflow.position.Position
import js7.data.workflow.{Workflow, WorkflowPath}
import js7.tests.AdmissionTimeSkipJobTest._
import js7.tests.jobs.EmptyJob
import js7.tests.testenv.ControllerAgentForScalaTest
import monix.execution.Scheduler.Implicits.global
import org.scalatest.freespec.AnyFreeSpec

final class AdmissionTimeSkipJobTest extends AnyFreeSpec with ControllerAgentForScalaTest
{
  override protected val controllerConfig = config"""
    js7.auth.users.TEST-USER.permissions = [ UpdateItem ]
    js7.journal.remove-obsolete-files = false
    js7.controller.agent-driver.command-batch-delay = 0ms
    js7.controller.agent-driver.event-buffer-delay = 0ms"""

  override protected def agentConfig = config"""
    js7.job.execution.signed-script-injection-allowed = on"""

  protected def agentPaths = Seq(agentPath)
  protected def items = Seq(singleJobWorkflow, multipleJobsWorkflow)

  private implicit val timeZone = AdmissionTimeSkipJobTest.timeZone
  private val clock = AlarmClock.forTest(
    ts("2021-09-09T00:00"),
    clockCheckInterval = 100.ms)

  override protected def controllerModule = new AbstractModule {
    @Provides @Singleton def provideAlarmClock(): AlarmClock = clock
  }

  override protected def agentModule = new AbstractModule {
    @Provides @Singleton def provideAlarmClock(): AlarmClock = clock
  }

  "Skip job if it has no admission time for order date" - {
    "Single job" in {
      val orderId = OrderId("#2021-09-02#single-job")
      assert(orderIdToDate(orderId).map(_.getDayOfWeek) == Some(THURSDAY))

      val events = controller.runOrder(FreshOrder(orderId, singleJobWorkflow.path)).map(_.value)
      assert(events == Seq(
        OrderAdded(singleJobWorkflow.id),
        OrderMoved(Position(1)),
        // Order does not start for skipped order (but for OrderFinished)
        OrderStarted,
        OrderFinished))
    }

    "Between other jobs" in {
      val orderId = OrderId("#2021-09-02#multiple-jobs")
      assert(orderIdToDate(orderId).map(_.getDayOfWeek) == Some(THURSDAY))

      val events = controller.runOrder(FreshOrder(orderId, multipleJobsWorkflow.path)).map(_.value)
      assert(events == Seq(
        OrderAdded(multipleJobsWorkflow.id),

        OrderAttachable(agentPath),
        OrderAttached(agentPath),

        OrderStarted,
        OrderProcessingStarted,
        OrderProcessed(Outcome.succeeded),
        OrderMoved(Position(3)),  // Positions 1 and 2 are skipped!

        OrderProcessingStarted,
        OrderProcessed(Outcome.succeeded),
        OrderMoved(Position(4)),

        OrderDetachable,
        OrderDetached,
        OrderFinished))
    }
  }

  "Do not skip if job has a admission time for order date" in {
    clock := ts("2021-09-10T00:00")
    val orderId = OrderId("#2021-09-03#")  // Friday
    assert(orderIdToDate(orderId).map(_.getDayOfWeek) == Some(FRIDAY))

    controllerApi.addOrder(FreshOrder(orderId, singleJobWorkflow.path)).await(99.s).orThrow
    eventWatch.await[OrderAttached](_.key == orderId)
    sleep(100.ms)
    assert(controllerState.idToOrder(orderId).isState[Fresh])

    clock := ts("2021-09-10T18:00")
    eventWatch.await[OrderProcessed](_.key == orderId)
    eventWatch.await[OrderFinished](_.key == orderId)
  }

  "Do not skip if OrderId has no order date" in {
    clock := ts("2021-09-10T00:00")
    val orderId = OrderId("NO-DATE")
    controllerApi.addOrder(FreshOrder(orderId, singleJobWorkflow.path)).await(99.s).orThrow
    eventWatch.await[OrderAttached](_.key == orderId)
    sleep(100.ms)
    assert(controllerState.idToOrder(orderId).isState[Fresh])

    clock := ts("2021-09-10T18:00")
    eventWatch.await[OrderProcessed](_.key == orderId)
    eventWatch.await[OrderFinished](_.key == orderId)
  }

  "Do not skip if OrderId has an invalid order date" in {
    clock := ts("2021-09-10T00:00")
    val orderId = OrderId("#2021-02-29#invalid")
    assert(orderIdToDate(orderId).map(_.getDayOfWeek) == None)

    controllerApi.addOrder(FreshOrder(orderId, singleJobWorkflow.path)).await(99.s).orThrow
    eventWatch.await[OrderAttached](_.key == orderId)
    sleep(100.ms)
    assert(controllerState.idToOrder(orderId).isState[Fresh])

    clock := ts("2021-09-10T18:00")
    eventWatch.await[OrderProcessed](_.key == orderId)
    eventWatch.await[OrderFinished](_.key == orderId)
  }
}

object AdmissionTimeSkipJobTest
{
  private val agentPath = AgentPath("AGENT")
  private val timeZone = ZoneId.of("Europe/Mariehamn")

  private val fridayExecute = Execute(
    WorkflowJob(
      agentPath,
      EmptyJob.executable(),
      admissionTimeScheme = Some(AdmissionTimeScheme(Seq(
        WeekdayPeriod(FRIDAY, LocalTime.of(18, 0), 1.h)))),
      skipIfNoAdmissionForOrderDay = true))

  private val singleJobWorkflow = Workflow(WorkflowPath("SINGLE-JOB") ~ "INITIAL",
    Seq(fridayExecute),
    timeZone = TimeZone(timeZone.getId))

  private val multipleJobsWorkflow = Workflow(WorkflowPath("MULTIPLE-JOBS") ~ "INITIAL",
    Seq(
      EmptyJob.execute(agentPath),
      fridayExecute,
      fridayExecute,
      EmptyJob.execute(agentPath)),
    timeZone = TimeZone(timeZone.getId))

  private def ts(ts: String)(implicit zone: ZoneId) =
    JavaTimestamp.parseLocal(ts, zone)
}
