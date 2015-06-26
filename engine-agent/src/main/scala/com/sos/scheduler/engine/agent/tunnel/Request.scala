package com.sos.scheduler.engine.agent.tunnel

import akka.util.ByteString
import scala.concurrent.Promise

/**
 * @author Joacim Zschimmer
 */
private[agent] final case class Request(message: ByteString, responsePromise: Promise[ByteString]) {
  override def toString = s"Request(${message.size} bytes)"
}
