package com.sos.jobscheduler.agent.tests

import com.sos.jobscheduler.agent.Agent
import com.sos.jobscheduler.agent.client.AgentClient
import com.sos.jobscheduler.agent.configuration.AgentConfiguration
import com.sos.jobscheduler.agent.configuration.Akkas.newActorSystem
import com.sos.jobscheduler.agent.data.commandresponses.EmptyResponse
import com.sos.jobscheduler.agent.data.commands.{AddJobnet, AddOrder, DetachOrder, RegisterAsMaster}
import com.sos.jobscheduler.agent.tests.OrderAgentIT._
import com.sos.jobscheduler.common.scalautil.AutoClosing.autoClosing
import com.sos.jobscheduler.common.scalautil.Closers.implicits._
import com.sos.jobscheduler.common.scalautil.Closers.withCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.deleteDirectoryRecursively
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.Futures.implicits._
import com.sos.jobscheduler.common.scalautil.xmls.ScalaXmls.implicits.RichXmlPath
import com.sos.jobscheduler.common.system.OperatingSystem.isWindows
import com.sos.jobscheduler.common.time.ScalaTime._
import com.sos.jobscheduler.common.time.WaitForCondition.waitForCondition
import com.sos.jobscheduler.data.engine2.agent.AgentPath
import com.sos.jobscheduler.data.engine2.order.Jobnet.{EndNode, JobNode}
import com.sos.jobscheduler.data.engine2.order.OrderEvent.OrderReady
import com.sos.jobscheduler.data.engine2.order.{JobPath, Jobnet, JobnetPath, NodeId, NodeKey, Order, OrderEvent}
import com.sos.jobscheduler.data.event.{EventId, EventRequest, EventSeq, KeyedEvent}
import com.sos.jobscheduler.data.order.OrderId
import java.nio.file.Files.{createDirectories, createTempDirectory}
import org.scalatest.FreeSpec
import org.scalatest.Matchers._

/**
  * @author Joacim Zschimmer
  */
final class OrderAgentIT extends FreeSpec {

  "Command AddOrder" in {
    val dataDir = createTempDirectory("test-")
    val jobDir = dataDir / "config" / "live"
    createDirectories(jobDir)
    (jobDir / "a.job.xml").xml = AJobXml
    (jobDir / "b.job.xml").xml = BJobXml
    val persistenceDir = dataDir / "persistence"
    createDirectories(persistenceDir)
    try {
      val agentConf = AgentConfiguration.forTest()
        //config = ConfigFactory.parseMap(Map(
            //"akka.persistence.journal.plugin" → "dummy-journal",
            //"dummy-journal.class" → classOf[org.dmonix.akka.persistence.JournalPlugin].getName,
            //"dummy-journal.plugin-dispatcher" → "akka.actor.default-dispatcher")))
            //"akka.persistence.journal.plugin" → "akka.persistence.journal.leveldb",
            //"akka.persistence.journal.leveldb.dir" → s"$persistenceDir/persistence",
            //"akka.persistence.snapshot-store.plugin" → "akka.persistence.snapshot-store.local",
            //"akka.persistence.snapshot-store.local.dir" → s"$persistenceDir/snapshots")))
          .copy(
            dataDirectory = Some(dataDir),
            experimentalOrdersEnabled = true)
      autoClosing(new Agent(agentConf)) { agent ⇒
        agent.start() await 5.s
        withCloser { implicit closer ⇒
          implicit val actorSystem = newActorSystem(getClass.getSimpleName) withCloser { _.terminate() await 99.s }
          val agentClient = AgentClient(agent.localUri.toString)

          agentClient.executeCommand(RegisterAsMaster) await 99.s shouldEqual EmptyResponse  // Without Login, this registers all anonymous clients
          agentClient.executeCommand(AddJobnet(TestJobnet)) await 99.s shouldEqual EmptyResponse

          val order = Order(
            OrderId("TEST-ORDER"),
            NodeKey(TestJobnet.path, ANodeId),
            Order.Waiting,
            Map("x" → "X"))
          agentClient.executeCommand(AddOrder(order)) await 99.s shouldEqual EmptyResponse

          waitForCondition(10.s, 100.ms) {
            agentClient.mastersEvents(EventRequest.singleClass[OrderEvent](after = EventId.BeforeFirst, timeout = 10.s)) await 99.s match {
              case EventSeq.NonEmpty(stampeds) if stampeds map { _.value } contains KeyedEvent(OrderReady)(order.id) ⇒
                true
              case _ ⇒
                false
            }
          }

          val processedOrder = agentClient.order(order.id) await 99.s
          assert(processedOrder == order.copy(
            nodeKey = order.nodeKey.copy(nodeId = EndNodeId),
            state = Order.Ready,
            outcome = Order.Good(true),
            variables = Map("x" → "X", "result" → "TEST-RESULT-BBB")))

          agentClient.executeCommand(DetachOrder(order.id)) await 99.s shouldEqual EmptyResponse
        }
      }
    }
    finally
      deleteDirectoryRecursively(dataDir)
  }
}

private object OrderAgentIT {
  private val TestAgentId = AgentPath("/TEST-AGENT")
  private val TestScript =
    if (isWindows) """
      |@echo off
      |echo result=TEST-RESULT-%SCHEDULER_PARAM_VAR1% >>"%SCHEDULER_RETURN_VALUES%"
      |""".stripMargin
    else """
      |echo "result=TEST-RESULT-$SCHEDULER_PARAM_VAR1" >>"$SCHEDULER_RETURN_VALUES"
      |""".stripMargin

  private val AJobXml =
    <job>
      <params>
        <param name="var1" value="AAA"/>
      </params>
      <script language="shell">{TestScript}</script>
    </job>

  private val BJobXml =
    <job>
      <params>
        <param name="var1" value="BBB"/>
      </params>
      <script language="shell">{TestScript}</script>
    </job>

  private val AJobPath = JobPath("/a")
  private val BJobPath = JobPath("/b")
  private val ANodeId = NodeId("AAA")
  private val BNodeId = NodeId("BBB")
  private val EndNodeId = NodeId("END")
  private val FailedNodeId = NodeId("FAILED")
  private val TestJobnet = Jobnet(
    JobnetPath("/TEST"),
    ANodeId,
    List(
      JobNode(ANodeId, TestAgentId, AJobPath, onSuccess = BNodeId, onFailure = FailedNodeId),
      JobNode(BNodeId, TestAgentId, BJobPath, onSuccess = EndNodeId, onFailure = FailedNodeId),
      EndNode(EndNodeId),
      EndNode(FailedNodeId)))
}
