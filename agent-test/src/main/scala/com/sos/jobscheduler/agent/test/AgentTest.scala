package com.sos.jobscheduler.agent.test

import com.sos.jobscheduler.common.scalautil.MonixUtils.ops._
import com.sos.jobscheduler.common.time.ScalaTime._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterAll, Suite}

/**
 * @author Joacim Zschimmer
 */
trait AgentTest extends BeforeAndAfterAll with TestAgentProvider {
  this: Suite ⇒

  private var started = false

  override protected def beforeAll() = {
    super.beforeAll()
    agent
    started = true
  }

  override def afterAll() = {
    if (started) {
      agent.terminate() await 99.s
    }
    onClose { super.afterAll() }
    close()
  }
}
