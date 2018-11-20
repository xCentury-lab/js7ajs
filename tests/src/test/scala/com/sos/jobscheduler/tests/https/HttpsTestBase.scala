package com.sos.jobscheduler.tests.https

import akka.actor.ActorRefFactory
import com.sos.jobscheduler.base.generic.SecretString
import com.sos.jobscheduler.base.utils.ScalazStyle._
import com.sos.jobscheduler.common.akkahttp.https.{KeyStoreRef, TrustStoreRef}
import com.sos.jobscheduler.common.guice.GuiceImplicits.RichInjector
import com.sos.jobscheduler.common.process.Processes.{ShellFileExtension ⇒ sh}
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits.RichPath
import com.sos.jobscheduler.common.system.OperatingSystem.operatingSystem
import com.sos.jobscheduler.common.utils.FreeTcpPortFinder.findRandomFreeTcpPort
import com.sos.jobscheduler.common.utils.JavaResource
import com.sos.jobscheduler.core.event.StampedKeyedEventBus
import com.sos.jobscheduler.data.agent.AgentPath
import com.sos.jobscheduler.data.job.ExecutablePath
import com.sos.jobscheduler.data.workflow.WorkflowPath
import com.sos.jobscheduler.master.client.AkkaHttpMasterApi
import com.sos.jobscheduler.master.tests.TestEventCollector
import com.sos.jobscheduler.tests.DirectoryProvider
import com.sos.jobscheduler.tests.https.HttpsTestBase._
import com.typesafe.config.ConfigUtil.quoteString
import java.nio.file.Files.{createTempFile, delete}
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import scala.concurrent.duration._

/**
  * Master and Agent with server-side HTTPS.
  *
  * @author Joacim Zschimmer
  */
private[https] trait HttpsTestBase extends FreeSpec with BeforeAndAfterAll with DirectoryProvider.ForScalaTest {

  override protected final def provideAgentHttpsCertificate = true
  protected def provideMasterClientCertificate = false

  override protected final lazy val masterHttpPort = None
  override protected final lazy val masterHttpsPort = Some(findRandomFreeTcpPort())
  override protected final def masterClientCertificate = provideMasterClientCertificate ? ClientTrustStoreResource

  private lazy val keyStore = createTempFile(getClass.getSimpleName + "-keystore-", ".p12")
  private lazy val trustStore = createTempFile(getClass.getSimpleName + "-truststore-", ".p12")

  protected lazy val masterApi = new AkkaHttpMasterApi(master.localUri,
    keyStoreRef = Some(KeyStoreRef(keyStore.toUri.toURL, storePassword = SecretString("jobscheduler"), keyPassword = SecretString("jobscheduler"))),
    trustStoreRef = Some(TrustStoreRef(trustStore.toUri.toURL, storePassword = SecretString("jobscheduler"))))

  protected val agentPaths = AgentPath("/TEST-AGENT") :: Nil
  override protected def agentHttps = true

  protected lazy val eventCollector = new TestEventCollector

  override def beforeAll() = {
    // Reference to agents implicitly starts them (before master)
    provideAgentConfiguration(directoryProvider.agents(0))
    assert(agents.forall(_.localUri.scheme == "https"))

    directoryProvider.master.provideHttpsCertificate()
    provideMasterConfiguration(directoryProvider.master)
    assert(master.localUri.scheme == "https")

    keyStore.contentBytes = ClientKeyStoreResource.contentBytes
    trustStore.contentBytes = DirectoryProvider.MasterTrustStoreResource.contentBytes

    eventCollector.start(master.injector.instance[ActorRefFactory], master.injector.instance[StampedKeyedEventBus])
    super.beforeAll()
  }

  override def afterAll() = {
    super.afterAll()
    delete(keyStore)
  }
}

private[https] object HttpsTestBase
{
  // Following resources have been generated with the command line:
  // common/src/main/resources/com/sos/jobscheduler/common/akkahttp/https/generate-self-signed-ssl-certificate-test-keystore.sh -host=localhost -alias=client -config-directory=tests/src/test/resources/com/sos/jobscheduler/tests/client
  private val ClientKeyStoreResource = JavaResource("com/sos/jobscheduler/tests/client/private/https-keystore.p12")
  private val ClientTrustStoreResource = JavaResource("com/sos/jobscheduler/tests/client/export/https-truststore.p12")

  private def provideAgentConfiguration(agent: DirectoryProvider.AgentTree): Unit = {
    (agent.config / "private/private.conf").append(
      s"""jobscheduler.auth.users {
         |  Master = ${quoteString("plain:" + agent.password.string)}
         |}
         |""".stripMargin)
    agent.writeExecutable(ExecutablePath(s"/TEST$sh"), operatingSystem.sleepingShellScript(0.seconds))
  }

  private def provideMasterConfiguration(master: DirectoryProvider.MasterTree): Unit = {
    (master.config / "private/private.conf").append("""
      |jobscheduler.auth.users {
      |  TEST-USER: "plain:TEST-PASSWORD"
      |}
      |""".stripMargin)
    master.writeTxt(WorkflowPath("/TEST-WORKFLOW"), s"""
      define workflow {
        execute executable="/TEST$sh", agent="/TEST-AGENT";
      }""")
  }
}
