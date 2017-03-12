package com.sos.jobscheduler.minicom.remoting.dialog

import akka.util.ByteString
import com.sos.jobscheduler.common.tcp.BlockingMessageConnection
import org.scalactic.Requirements._
import scala.concurrent.{ExecutionContext, Future, blocking}

/**
 * Enforces a ping-pong-style dialog, where each call of `sendMessage` is paired with a call of `receiveMessage`.
 * After a initial message has been received, each pair of send and receive is synchronized.
 * So each `receiveMessage` returns the response to the corresponding `sendMessage`.
 *
 * @author Joacim Zschimmer
 */
final class StandardServerDialogConnection(protected val connection: BlockingMessageConnection)
(implicit protected val executionContext: ExecutionContext)
extends ServerDialogConnection with StandardClientDialogConnection {

  private var firstMessageReceived = false
  private var lastMessageSent = false

  def receiveFirstMessage(): Future[Option[ByteString]] = {
    Future {
      blocking {
        requireState(!firstMessageReceived)
        val r = connection.receiveMessage()
        firstMessageReceived = true
        r
      }
    }
  }

  override def sendAndReceive(data: ByteString): Future[Option[ByteString]] = {
    requireState(firstMessageReceived && !lastMessageSent)
    super.sendAndReceive(data)
  }

  override def blockingSendAndReceive(data: ByteString): Option[ByteString] = {
    requireState(firstMessageReceived && !lastMessageSent)
    super.blockingSendAndReceive(data)
  }

  def blockingSendLastMessage(data: ByteString): Unit = {
    requireState(firstMessageReceived && !lastMessageSent)
    lastMessageSent = true
    exclusive {
      connection.sendMessage(data)
    }
  }
}
