package js7.common.scalautil

import js7.common.scalautil.Futures.implicits._
import js7.common.scalautil.IOExecutor.ioFuture
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * @author Joacim Zschimmer
  */
final class IOExecutorTest extends AnyFreeSpec
{
  private implicit val iox = new IOExecutor(Duration.Zero, name = "IOExecutorTest")

  "Success" in {
    assert(ioFuture(7).await(10.seconds) == 7)
  }

  "Failure" in {
    assert(Await.ready(ioFuture { sys.error("FAILED") }, 10.seconds).value.get.failed.get.toString ==
      "java.lang.RuntimeException: FAILED")
  }

  "Thread name" in {
    ioFuture {
      assert(Thread.currentThread.getName startsWith "IOExecutorTest I/O ")
    } await 10.seconds
  }
}
