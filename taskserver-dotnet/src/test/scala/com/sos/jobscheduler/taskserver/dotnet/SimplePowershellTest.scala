package com.sos.jobscheduler.taskserver.dotnet

import com.sos.jobscheduler.common.system.OperatingSystem._
import com.sos.jobscheduler.taskserver.dotnet.SimpleDotnetTest.TestErrorMessage
import com.sos.jobscheduler.taskserver.dotnet.api.DotnetModuleReference

/**
  * @author Joacim Zschimmer
  */
final class SimplePowershellTest extends SimpleDotnetTest {

  protected def language = "PowerShell"

  if (isWindows) {
    addScriptErrorTest(DotnetModuleReference.Powershell(s"""
      function spooler_process() {
        throw "$TestErrorMessage"
      }"""))

    addStandardTest(DotnetModuleReference.Powershell(
       """
    function spooler_process() {
      $orderVariables = $spooler_task.order().params()
      $value = $orderVariables.value("TEST")
      $orderVariables.set_value("TEST", "TEST-CHANGED")
      $spooler_log.log(0, $value)
      return $true
    }"""))
  }
}
