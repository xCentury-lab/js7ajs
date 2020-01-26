package com.sos.jobscheduler.tests.master.cluster

import com.sos.jobscheduler.base.problem.Checked._
import com.sos.jobscheduler.common.log.ScribeUtils
import com.sos.jobscheduler.common.scalautil.Closer.ops._
import com.sos.jobscheduler.common.scalautil.Closer.withCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.system.OperatingSystem.isWindows
import com.sos.jobscheduler.core.message.ProblemCodeMessages
import com.sos.jobscheduler.data.agent.AgentRefPath
import com.sos.jobscheduler.data.job.ExecutablePath
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.data.workflow.parser.WorkflowParser
import com.sos.jobscheduler.tests.master.cluster.MasterClusterTester.{shellScript, _}
import com.sos.jobscheduler.tests.testenv.DirectoryProvider
import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.scalatest.FreeSpec

private[cluster] trait MasterClusterTester extends FreeSpec
{
  ScribeUtils.coupleScribeWithSlf4j()
  ProblemCodeMessages.initialize()

  protected[cluster] final def withMasterAndBackup(primaryHttpPort: Int, backupHttpPort: Int)(body: (DirectoryProvider, DirectoryProvider) => Unit): Unit =
    withCloser { implicit closer =>
      val testName = MasterClusterTester.this.getClass.getSimpleName
      val primary = new DirectoryProvider(agentRefPath :: Nil, TestWorkflow :: Nil, testName = Some(s"$testName-Primary"),
        masterConfig = ConfigFactory.parseString(s"""
          jobscheduler.master.cluster.this-node.role = Primary
          jobscheduler.master.cluster.this-node.uri = "http://127.0.0.1:$primaryHttpPort"
          jobscheduler.master.cluster.other-node.uri = "http://127.0.0.1:$backupHttpPort"
          jobscheduler.master.cluster.heartbeat = 3s
          jobscheduler.master.cluster.fail-after = 5s
          jobscheduler.master.cluster.idle-get-timeout = 9s
          jobscheduler.auth.users.Master.password = "plain:BACKUP-MASTER-PASSWORD"
          jobscheduler.auth.users.TEST.password = "plain:TEST-PASSWORD"
          jobscheduler.auth.cluster.password = "PRIMARY-MASTER-PASSWORD" """)
      ).closeWithCloser

      val backup = new DirectoryProvider(Nil, Nil, testName = Some(s"$testName-Backup"),
        masterConfig = ConfigFactory.parseString(s"""
          jobscheduler.master.cluster.this-node.role = Backup
          jobscheduler.master.cluster.this-node.uri = "http://127.0.0.1:$backupHttpPort"
          jobscheduler.master.cluster.other-node.uri = "http://127.0.0.1:$primaryHttpPort"
          jobscheduler.master.cluster.heartbeat = 3s
          jobscheduler.master.cluster.fail-after = 5s
          jobscheduler.master.cluster.idle-get-timeout = 9s
          jobscheduler.auth.users.Master.password = "plain:PRIMARY-MASTER-PASSWORD"
          jobscheduler.auth.cluster.password = "BACKUP-MASTER-PASSWORD" """)
      ).closeWithCloser

      // Replicate credentials required for agents
      Files.copy(
        primary.master.configDir / "private" / "private.conf",
        backup.master.configDir / "private" / "private.conf",
        REPLACE_EXISTING)

      primary.agents.head.writeExecutable(ExecutablePath("/TEST.cmd"), shellScript)

      primary.runAgents() { _ =>
        body(primary, backup)
      }
    }
}

object MasterClusterTester
{
  private val agentRefPath = AgentRefPath("/AGENT")
  private[cluster] val TestWorkflow = WorkflowParser.parse(
    WorkflowPath("/WORKFLOW"),
    """define workflow {
      |  execute executable="/TEST.cmd", agent="/AGENT";
      |}""".stripMargin).orThrow

  private val shellScript = {
    val stdoutSize = 1*1000*1000
    val line = "." * 999
    (if (isWindows)
      """@echo off
        |if not "%SCHEDULER_PARAM_SLEEP" == "" ping -n 2 127.0.0.1 >nul
        |if not "%SCHEDULER_PARAM_SLEEP" == "" ping -n %SCHEDULER_PARAM_SLEEP 127.0.0.1 >nul
        |""".stripMargin
     else
      """[ -z "$SCHEDULER_PARAM_SLEEP" ] || sleep $SCHEDULER_PARAM_SLEEP
        |""".stripMargin
    ) +
      (1 to stdoutSize / line.length)
        .map(i => "echo " + s"$i $line".take(line.length) + "\n")
        .mkString
  }
}
