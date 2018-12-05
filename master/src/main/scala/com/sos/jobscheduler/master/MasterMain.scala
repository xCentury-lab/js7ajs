package com.sos.jobscheduler.master

import cats.data.Validated.{Invalid, Valid}
import com.sos.jobscheduler.base.utils.ScalaUtils.RichThrowable
import com.sos.jobscheduler.common.BuildInfo
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Futures.implicits.SuccessFuture
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.core.JavaMainSupport.{handleJavaShutdown, runMain}
import com.sos.jobscheduler.core.message.ProblemCodeMessages
import com.sos.jobscheduler.master.configuration.MasterConfiguration
import com.sos.jobscheduler.master.data.MasterCommand
import monix.execution.Scheduler.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/**
  * JobScheduler Master.
  *
  * @author Joacim Zschimmer
  */
object MasterMain {
  private val OrderScheduleDuration = 1 * 60.s
  private val logger = Logger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info(s"Master ${BuildInfo.buildVersion}")  // Log early for early timestamp and propery logger initialization by a single (not-parallel) call
    ProblemCodeMessages.initialize()
    runMain {
      val masterConfiguration = MasterConfiguration.fromCommandLine(args.toVector)
      autoClosing(RunningMaster(masterConfiguration).awaitInfinite) { master ⇒
        master.executeCommandAsSystemUser(MasterCommand.ScheduleOrdersEvery(OrderScheduleDuration.toFiniteDuration))
          .runOnComplete {  // On recovery, executeCommand will delay execution until Agents are started
            case Success(Valid(_)) ⇒
            case Success(Invalid(problem)) ⇒ logger.error(problem.toString)
            case Failure(t) ⇒ logger.error(t.toStringWithCauses, t)
          }
        handleJavaShutdown(masterConfiguration.config, "MasterMain", onJavaShutdown(master)) {
          master.terminated.awaitInfinite
        }
      }
      val msg = "JobScheduler Master terminates"
      logger.info(msg)
      println(msg)
    }
  }

  private def onJavaShutdown(master: RunningMaster)(timeout: Duration): Unit = {
    logger.warn("Trying to terminate Master due to Java shutdown")
    master.terminate().runAsync await timeout
    master.close()
  }
}
