package js7.agent.scheduler.order

import java.io.{IOException, Writer}
import js7.base.generic.Accepted
import js7.base.thread.Futures.implicits.SuccessFuture
import monix.execution.Scheduler
import org.jetbrains.annotations.TestOnly
import scala.concurrent.{Future, Promise}

/**
  * Combines several `write` calls to one `onFlush`.
  * Tries to free the buffer (`StringBuilder`) when not needed.
  *
  * @author Joacim Zschimmer
  */
private[order] trait BufferedStringWriter extends Writer {

  require(passThroughSize <= size)

  protected def size: Int

  protected def passThroughSize: Int

  implicit protected def scheduler: Scheduler

  /** Do not block here! */
  protected def onFlush(string: String): Future[Accepted]

  protected def onBufferingStarted(): Unit

  private val closed = Promise[Unit]()
  private var whenReady = Future.successful[Accepted](Accepted)
  private var stringBuilder: StringBuilder = null

  final def close() = {
    closed.trySuccess(())
    flush()
  }

  final def write(chars: Array[Char], offset: Int, length: Int) =
    if (length > 0) {
      Future.firstCompletedOf(whenReady :: closed.future :: Nil).awaitInfinite
      if (closed.future.isCompleted) throw new IOException("stdout/stderr receiver has been closed")
      whenReady = writeSynchronized(chars, offset, length)
    }

  private def writeSynchronized(chars: Array[Char], offset: Int, length: Int): Future[Accepted] =
    synchronized {
      val startWritten =
        if (bufferLength + length > size)
          flushBuffer()
        else
          Future.successful(Accepted)
      if (isEmpty && length >= passThroughSize) {
        val accepted = onFlush(new String(chars, offset, length))
        stringBuilder = null
        accepted
      } else {
        if (isEmpty) {
          onBufferingStarted()
          if (stringBuilder == null) {
            stringBuilder = new StringBuilder(size)  // Reserve now (StringBuilder itself would reserve up to twice as much, see StringBuilder#newCapacity)
          }
        }
        stringBuilder.appendAll(chars, offset, length)
        if (bufferLength >= size) {
          val flushed = flushBuffer()
          Future.sequence(flushed :: startWritten :: Nil).map(_ reduce ((_, _) => Accepted))
        }
        else
          startWritten
      }
    }

  final def flush(): Unit =
    synchronized {  // `flush` may be called by the actor thread, while the pipe thread is calling `write`
      flushBuffer()
      stringBuilder = null
    } // Don't wait here for flushBuffer() to be completed

  private def flushBuffer(): Future[Accepted] = {
    if (isEmpty)
      Future.successful(Accepted)
    else {
      val string = stringBuilder.toString
      stringBuilder.clear()
      onFlush(string)  // Send to JournalActor for persisting (persistAcceptEarly)
    }
  }

  private[order] def isEmpty = bufferLength == 0

  @TestOnly
  private[order] def reservedSize = if (stringBuilder == null) 0 else stringBuilder.capacity

  private def bufferLength: Int =
    if (stringBuilder == null) 0 else stringBuilder.length
}
