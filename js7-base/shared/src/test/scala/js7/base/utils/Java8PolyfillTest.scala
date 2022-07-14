package js7.base.utils

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import js7.base.system.Java8Polyfill.*
import org.scalatest.freespec.AnyFreeSpec
import scala.util.Random

final class Java8PolyfillTest extends AnyFreeSpec
{
  "InputStream transferTo" in {
    val bytes = Random.nextString(10001).getBytes(UTF_8)
    val in = new ByteArrayInputStream(bytes)
    val out = new ByteArrayOutputStream
    val n = in.transferTo(out)
    assert(out.toByteArray sameElements bytes)
    assert(n == bytes.length)
  }

  java8Polyfill()
}
