package com.sos.jobscheduler.data.workflow.instructions

import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.data.source.SourcePos
import com.sos.jobscheduler.data.workflow.Instruction
import com.sos.jobscheduler.data.workflow.instructions.Instructions.jsonCodec
import com.sos.jobscheduler.tester.CirceJsonTester.testJson
import org.scalatest.freespec.AnyFreeSpec

/**
  * @author Joacim Zschimmer
  */
final class GapTest extends AnyFreeSpec {

  // For internal JobScheduler use only.

  "JSON" in {
    testJson[Instruction.Labeled](
      Gap(Some(SourcePos(1, 2))),
      json"""{
        "TYPE": "Gap",
        "sourcePos": [ 1, 2 ]
      }""")
  }
}
