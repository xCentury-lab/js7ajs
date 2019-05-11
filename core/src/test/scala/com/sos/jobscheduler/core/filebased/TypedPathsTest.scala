package com.sos.jobscheduler.core.filebased

import cats.data.Validated.{Invalid, Valid}
import com.sos.jobscheduler.base.problem.Problem
import com.sos.jobscheduler.base.problem.Problems.InvalidNameProblem
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.time.Stopwatch.measureTime
import com.sos.jobscheduler.core.filebased.TypedPaths._
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.filebased.SourceType
import com.sos.jobscheduler.data.workflow.WorkflowPath
import java.io.File.separator
import java.nio.file.Paths
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class TypedPathsTest extends FreeSpec {

  "fileToTypedPathAndSourceType" in {
    val dir = Paths.get("DIR")
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "folder/test.workflow.json") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Json))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath, AgentRefPath), dir, dir / "folder/test.workflow.json") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Json))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath, AgentRefPath), dir, dir / "folder/test.workflow.yaml") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Yaml))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "folder/test.workflow.txt") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Txt))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "folder/test.job_chain.xml") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Xml))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "folder/test.workflow.wrong") ==
      Invalid(Problem(s"File '...${separator}folder${separator}test.workflow.wrong' is not recognized as a configuration file")))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "folder/test.workflow.json") ==
      Valid(WorkflowPath("/folder/test") -> SourceType.Json))
    assert(fileToTypedPathAndSourceType(Set(WorkflowPath), dir, dir / "a@b.workflow.json") ==
      Invalid(InvalidNameProblem("WorkflowPath", "a@b")))
  }

  if (sys.props contains "test.speed") "speed" in {
    val dir = Paths.get("/TEST/JOBSCHEDULER/PROVIDER/CONFIG/LIVE")
    val path = dir / "folder/test.workflow.json"
    for (_ <- 1 to 5) info(
      measureTime(100000, "fileToTypedPathAndSourceType") {
      fileToTypedPathAndSourceType(Set(WorkflowPath), dir, path)
    }.toString)
  }
}
