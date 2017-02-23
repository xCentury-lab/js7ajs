package com.sos.jobscheduler.tunnel.client

import akka.actor.ActorSystem
import com.sos.jobscheduler.common.tcp.TcpToRequestResponse
import com.sos.jobscheduler.tunnel.data.TunnelToken
import java.net.InetSocketAddress

/**
 * A remote task started by [[com.sos.jobscheduler.client.agent.HttpRemoteProcessStarter]], with API calls tunnelled via HTTP and Agent.
 *
 * @author Joacim Zschimmer
 */
final class TcpToHttpBridge(actorSystem: ActorSystem, connectTo: InetSocketAddress, tunnelToken: TunnelToken, tunnelClient: WebTunnelClient)
extends AutoCloseable {
  protected def executionContext = actorSystem.dispatcher

  private lazy val tcpHttpBridge = new TcpToRequestResponse(
    actorSystem,
    connectTo = connectTo,
    executeRequest = request ⇒ tunnelClient.tunnelRequest(request),
    name = s"${tunnelClient.tunnelUri}")

  def start() = tcpHttpBridge.start()

  def close() = tcpHttpBridge.close()
}
