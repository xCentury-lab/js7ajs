package com.sos.jobscheduler.agent.scheduler.job

import akka.pattern.ask
import com.google.inject.Guice
import com.sos.jobscheduler.agent.configuration.AgentConfiguration
import com.sos.jobscheduler.agent.configuration.Akkas.newActorSystem
import com.sos.jobscheduler.agent.configuration.inject.AgentModule
import com.sos.jobscheduler.agent.scheduler.AgentActorIT.provideAgentDirectory
import com.sos.jobscheduler.agent.scheduler.job.JobKeeperTest._
import com.sos.jobscheduler.agent.task.AgentTaskFactory
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.scalautil.Closers.implicits.RichClosersAny
import com.sos.jobscheduler.common.scalautil.Closers.withCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.Stopwatch
import org.scalatest.FreeSpec
import scala.concurrent.duration.DurationInt

/**
  * @author Joacim Zschimmer
  */
final class JobKeeperTest extends FreeSpec {

  if (sys.props contains "test.speed") "Speed" in {
    logger.info("START")
    provideAgentDirectory { directory ⇒
      val jobDir = directory / "config" / "live"
      for (i ← (1 to N - 2).par) (jobDir / s"test-$i.job.xml").contentString = TestJobXmlString
      logger.info(s"$N files created, ${TestJobXmlString.length} bytes each")
      withCloser { implicit closer ⇒
        implicit val actorSystem = newActorSystem(getClass.getSimpleName) withCloser { _.shutdown() }
        import actorSystem.dispatcher

        val injector = Guice.createInjector(new AgentModule(AgentConfiguration.forTest(Some(directory))))
        implicit val newTask = injector.instance[AgentTaskFactory]

        for (_ ← 1 to 5) {
          val stopwatch = new Stopwatch
          val jobKeeper = actorSystem.actorOf(JobKeeper(jobDir))
          val JobKeeper.Started(jobs) = jobKeeper.ask(JobKeeper.Start)(600.seconds).mapTo[JobKeeper.Started] await 600.s
          logger.info(stopwatch.itemsPerSecondString(N, "jobs"))
          assert(jobs.size == N)
        }
        actorSystem.shutdown()
      }
    }
  }
}

object JobKeeperTest {
  private val logger = Logger(getClass)
  private val N = 10000
  private val TestJobXmlString =
    <job>
      <params>{
        for (i ← 1 to 10) yield <param name={s"NAME-$i"} value={"*" * 100}/>
      }</params>
      <script language="shell">SCRIPT</script>
    </job>.toString
}
