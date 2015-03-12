package com.sos.scheduler.engine.agent.xmlcommand

import com.sos.scheduler.engine.agent.commands.{StartRemoteDedicatedProcessTask, StartRemoteInProcessTask, StartRemoteTask, StartRemoteTaskResponse}
import com.sos.scheduler.engine.common.scalautil.Collections.implicits._
import com.sos.scheduler.engine.common.scalautil.xmls.ScalaXMLEventReader
import com.sos.scheduler.engine.common.utils.TcpUtils.parseTcpPort
import java.net.InetAddress
import org.scalactic.Requirements._

/**
 * @author Joacim Zschimmer
 */
object StartRemoteTaskXml {

  def parseXml(inetAddress: InetAddress, eventReader: ScalaXMLEventReader): StartRemoteTask = {
    import eventReader._
    parseElement() {
      val host: String = inetAddress.getHostAddress
      requireNonNull(host)
      val port = attributeMap.convert("tcp_port")(parseTcpPort)
      val controller = s"$host:$port"
      val kindProcess = attributeMap.get("kind") match {
        case Some("process") ⇒ true
        case None ⇒ false
        case x ⇒ throw new IllegalArgumentException(s"kind=$x")
      }
      if (kindProcess) {
        attributeMap.ignore("java_options")
        attributeMap.ignore("java_classpath")
        StartRemoteInProcessTask(controllerAddress = controller)
      } else
        StartRemoteDedicatedProcessTask(
          controllerAddress = controller,
          javaOptions = attributeMap.getOrElse("java_options", ""),
          javaClasspath = attributeMap.getOrElse("java_classpath", ""))
    }
  }

  def responseToXmlElem(response: StartRemoteTaskResponse): xml.Elem = <process process_id={response.remoteTaskId.value.toString}/>
}
