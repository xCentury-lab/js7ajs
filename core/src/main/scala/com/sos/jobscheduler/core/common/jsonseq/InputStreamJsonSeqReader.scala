package com.sos.jobscheduler.core.common.jsonseq

import akka.util.ByteString
import com.google.common.base.Ascii.{LF, RS}
import com.sos.jobscheduler.base.circeutils.CirceUtils._
import com.sos.jobscheduler.base.utils.ScalazStyle._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.common.utils.UntilNoneIterator
import com.sos.jobscheduler.core.common.jsonseq.InputStreamJsonSeqReader._
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path

/**
  * MIME media type application/json-seq, RFC 7464 "JavaScript Object Notation (JSON) Text Sequences".
  * <p>
  *   This implementation depends on BufferedReader.readLine, which separates the input not only
  *   by LF but also by CR, CR/LF and EOF.
  * <p>
  *    Also, this class does not collapse consecutive RS.
  *
  * @author Joacim Zschimmer
  * @see https://tools.ietf.org/html/rfc7464
  */
class InputStreamJsonSeqReader(in: SeekableInputStream, blockSize: Int = BlockSize, charBufferSize: Int = CharBufferSize)
extends AutoCloseable {

  private val block = new Array[Byte](blockSize)
  private var blockPos = 0L
  private var blockLength = 0
  private var blockRead = 0
  private val byteStringBuilder = ByteString.newBuilder

  private var lineNumber = 0  // -1 for unknown line number after seek
  lazy val iterator: Iterator[PositionAnd[Json]] = UntilNoneIterator(read)

  /** Closes underlying `SeekableInputStream`. */
  def close() = in.close()

  final def read(): Option[PositionAnd[Json]] = {
    val pos = position
    readByteString() map (o ⇒ PositionAnd(pos, o.decodeString(UTF_8).parseJson))
  }

  private def readByteString(): Option[ByteString] = {
    if (lineNumber != -1) lineNumber += 1
    var rsReached = false
    var lfReached = false
    var eof = false
    while (!lfReached && (!eof || blockRead < blockLength)) {
      if (blockRead == blockLength) {
        eof = !fillByteBuffer()
      }
      if (!rsReached && blockRead < blockLength) {
        if (block(blockRead) != RS)
          throwCorrupted("Missing ASCII RS at start of JSON sequence record")
        blockRead += 1
        rsReached = true
      }
      val start = blockRead
      while (blockRead < blockLength && (block(blockRead) != LF || { lfReached = true; false })) { blockRead += 1 }
      byteStringBuilder.putBytes(block, start, blockRead - start)
      if (lfReached) {
        blockRead += 1
      }
    }
    if (rsReached && !lfReached) throwCorrupted("Missing ASCII LF at end of JSON sequence record")
    lfReached ? {
      val result = byteStringBuilder.result()
      byteStringBuilder.clear()
      result
    }
  }

  private def fillByteBuffer(): Boolean = {
    blockPos = position
    blockRead = 0
    val length = in.read(block)
    //logger.trace(s"position=$position: $length bytes read")
    if (length == -1) {
      blockLength = 0
      false  // EOF
    } else {
      blockLength = length
      true
    }
  }

  final def seek(pos: Long): Unit =
    if (pos != position) {
      if (pos >= blockPos && pos <= blockPos + blockLength) {
        blockRead = (pos - blockPos).toInt  // May be == blockLength
      } else {
        logger.trace(s"seek $pos")
        in.seek(pos)
        blockPos = pos
        blockLength = 0
        blockRead = 0
      }
      lineNumber = -1
    }

  final def position = blockPos + blockRead

  private def throwCorrupted(extra: String) = {
    val where = if (lineNumber != -1) s"line $lineNumber" else s"position $position"
    sys.error(s"JSON sequence is corrupted at $where. $extra")
  }
}

object InputStreamJsonSeqReader
{
  private val BlockSize = 4096
  private val CharBufferSize = 1000
  private val logger = Logger(getClass)

  def open(file: Path, blockSize: Int = BlockSize, charBufferSize: Int = CharBufferSize): InputStreamJsonSeqReader =
    new InputStreamJsonSeqReader(SeekableInputStream.openFile(file), blockSize, charBufferSize)
}
