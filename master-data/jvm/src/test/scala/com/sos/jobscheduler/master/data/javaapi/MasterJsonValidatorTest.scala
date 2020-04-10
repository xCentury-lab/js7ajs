package com.sos.jobscheduler.master.data.javaapi

import com.sos.jobscheduler.master.data.javaapi.MasterJsonValidatorTester._
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class MasterJsonValidatorTest extends AnyFreeSpec {

  "Valid Workflow" in {
    testValidWorkflow()
  }

  "Invalid Workflow" in {
    testInvalidWorkflow()
  }

  "Invalid JSON" in {
    testInvalidJson()
  }

  "Valid Instruction" in {
    testValidInstruction()
  }

  "Invalid Instruction" in {
    testInvalidInstruction()
  }
}
