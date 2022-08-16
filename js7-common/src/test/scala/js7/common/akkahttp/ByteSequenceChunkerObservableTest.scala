package js7.common.akkahttp

import akka.util.ByteString
import js7.base.monixutils.MonixBase.syntax.RichMonixObservable
import js7.base.test.Test
import js7.base.thread.MonixBlocking.syntax.RichTask
import js7.base.time.ScalaTime.*
import js7.common.akkahttp.ByteSequenceChunkerObservable.syntax.*
import js7.common.akkautils.ByteStrings.syntax.*
import monix.execution.Scheduler.Implicits.traced
import monix.reactive.Observable
import scala.util.Random

final class ByteSequenceChunkerObservableTest extends Test
{
  "ByteSequenceChunkerObservable" in {
    val maxSize = 2
    val strings = Vector.fill(100_000)(
      ('A' + Random.nextInt(26)).toChar.toString * Random.nextInt(maxSize + 1) + "\n")
    val expectedCount = strings.view.map(o => (o.size + maxSize - 1) / maxSize).sum
    val result = Observable
      .fromIterable(strings)
      .map(ByteString(_))
      .chunk(maxSize)
      .tapEach { byteString => assert(byteString.length <= maxSize) }
      .toListL
      .await(99.s)
    assert(result.length == expectedCount)
    assert(result.view.map(_.utf8String).mkString == strings.mkString)
  }
}
