package js7.common.akkautils

import akka.util.ByteString
import js7.base.data.ByteArray
import js7.base.data.ByteSequence.ops.*
import js7.common.akkautils.ByteStrings.syntax.*
import org.scalatest.freespec.AnyFreeSpec

final class ByteStringsTest extends AnyFreeSpec
{
  "ByteString and ByteArray" in {
    assert((ByteString('A', 'B')).toByteArray == ByteArray('A', 'B'))
    assert((ByteString('A', 'B') ++ ByteString('C')).toByteArray == ByteArray('A', 'B', 'C'))

    assert((ByteArray('A', 'B')).toByteString == ByteString('A', 'B'))
    assert((ByteArray('A', 'B') ++ ByteArray('C')).toByteString == ByteString('A', 'B', 'C'))

    assert((ByteArray('A', 'B')).toByteString == ByteString('A', 'B'))
    assert((ByteArray('A', 'B') ++ ByteArray('C')).toByteString == ByteString('A', 'B', 'C'))
  }
}
