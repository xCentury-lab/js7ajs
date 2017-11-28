package com.sos.jobscheduler.shared.common.jsonseq

import akka.util.ByteString
import com.google.common.base.Ascii
import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.shared.common.jsonseq.OutputStreamJsonSeqWriter._
import io.circe.Json
import java.io.{BufferedOutputStream, OutputStream}
import org.jetbrains.annotations.TestOnly

/**
  * MIME media type application/json-seq, RFC 7464 "JavaScript Object Notation (JSON) Text Sequences".
  *
  * @author Joacim Zschimmer
  * @see https://tools.ietf.org/html/rfc7464
  */
final class OutputStreamJsonSeqWriter(out: OutputStream) extends AutoCloseable {

  private val buffered = out match {
    case o: BufferedOutputStream ⇒ o
    case o ⇒ new BufferedOutputStream(o)
  }
  private var array: Array[Byte] = null

  def close(): Unit = {
    buffered.close()
  }

  @TestOnly
  private[jsonseq] def writeJson(json: Json): Unit =
    writeJson(ByteString.fromString(json.compactPrint))

  def writeJson(byteString: ByteString): Unit = {
    if (array == null || array.length < byteString.length) {
      array = new Array[Byte](byteString.length + Reserve)
    }
    byteString.copyToArray(array)
    buffered.write(Ascii.RS)
    buffered.write(array, 0, byteString.length)
    buffered.write('\n')
    if (array.length > MaxBufferSize) {
      array = null
    }
  }

  def flush(): Unit = {
    buffered.flush()
  }
}

object OutputStreamJsonSeqWriter {
  private val Reserve = 1000
  private val MaxBufferSize = 100000
}
