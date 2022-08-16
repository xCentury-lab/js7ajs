package js7.launcher.forjava.internal

import js7.base.test.Test
import js7.base.thread.Futures.implicits.SuccessFuture
import js7.base.time.ScalaTime.*
import monix.execution.Scheduler.Implicits.traced
import monix.reactive.subjects.PublishSubject

final class ObserverWriterTest extends Test
{
  "ObserverWriter" in {
    val subject = PublishSubject[String]()
    val result = subject.toListL.runToFuture
    val w = new ObserverWriter(subject)
    w.write("EINS")
    w.write('-')
    w.write("ZWEI")
    w.close()
    assert(result.await(99.s) == List("EINS", "-", "ZWEI") )
  }
}
