package com.sos.jobscheduler.core.startup

import com.sos.jobscheduler.common.log.Log4j
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.core.startup.StartUp.printlnWithClock

/**
  * @author Joacim Zschimmer
  */
object Halt
{
  private val logger = Logger(getClass)

  def haltJava(msg: String, restart: Boolean): Nothing =
    haltJava(msg, exitCode = if (restart) 98 else 99)

  def haltJava(msg: String, exitCode: Int = 99): Nothing = {
    System.err.println()
    printlnWithClock(msg)
    logger.error(msg)
    Log4j.shutdown()
    sys.runtime.halt(exitCode)
    throw new Error("sys.runtime.halt failed")
  }
}
