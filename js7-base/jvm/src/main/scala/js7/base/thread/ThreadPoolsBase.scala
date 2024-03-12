package js7.base.thread

import com.typesafe.config.Config
import java.util.concurrent.{ArrayBlockingQueue, ExecutorService, LinkedBlockingQueue, SynchronousQueue, ThreadFactory, ThreadPoolExecutor}
import js7.base.log.Logger
import js7.base.system.Java8Polyfill.*
import js7.base.thread.VirtualThreads.maybeNewVirtualThreadExecutorService
import js7.base.time.JavaTimeConverters.AsScalaDuration
import js7.base.time.ScalaTime.*
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, ExecutionContextExecutorService}

object ThreadPoolsBase:
  private val logger = Logger[this.type]

  def newBlockingExecutorService(name: String, config: Config, virtual: Boolean = false)
  : ExecutorService =
    val keepAlive = config.getDuration("js7.thread-pools.long-blocking.keep-alive").toFiniteDuration
    val virtualAllowed = virtual && config.getBoolean("js7.thread-pools.long-blocking.virtual")
    labeledExecutorService(name):
      if virtualAllowed then
        maybeNewVirtualThreadExecutorService() getOrElse
          newBlockingNonVirtualExecutor(name, keepAlive)
      else
        newBlockingNonVirtualExecutor(name, keepAlive)

  def newBlockingNonVirtualExecutor(name: String, keepAlive: FiniteDuration = 60.s): ExecutorService =
    labeledExecutorService(name):
      newThreadPoolExecutor(name = name, keepAlive = keepAlive,
        maximumPoolSize = Int.MaxValue, queueSize = Some(0))

  private def newThreadPoolExecutor(
    name: String,
    keepAlive: FiniteDuration,
    corePoolSize: Int = 0,
    maximumPoolSize: Int,
    queueSize: Option[Int])
  : ThreadPoolExecutor =
    val result = new ThreadPoolExecutor(
      corePoolSize,
      maximumPoolSize,
      keepAlive.toMillis,
      MILLISECONDS,
      queueSize match {
        case None => new LinkedBlockingQueue[Runnable]
        case Some(0) => new SynchronousQueue[Runnable](false)  // Like Monix Scheduler.io
        case Some(n) => new ArrayBlockingQueue[Runnable](n)
      },
      myThreadFactory(name))
    logger.debug(s"newThreadPoolExecutor => $result")
    result

  private def myThreadFactory(name: String): ThreadFactory =
    runnable => {
      val thread = new Thread(runnable)
      thread.setName(s"$name-${thread.threadId}")
      thread.setDaemon(true)  // Do it like Monix and Pekko
      thread
    }

  def labeledExecutionContext(label: String)(ec: ExecutionContext): ExecutionContext =
    ec match
      case ec: ExecutionContextExecutor => LabeledExecutionContextExecutor(label, ec)
      case ec => LabeledExecutionContext(label, ec)

  def labeledExecutorService(label: String)(ec: ExecutorService): ExecutorService =
    LabeledExecutorService(label, ec)

  def labeledExecutionContextExecutorService(label: String)(ec: ExecutionContextExecutorService)
  : ExecutionContextExecutorService =
    LabeledExecutionContextExecutorService(label, ec)

  private class LabeledExecutionContextExecutor(label: String, ec: ExecutionContextExecutor)
  extends ExecutionContextExecutor:
    export ec.*
    override def toString = label

  private class LabeledExecutionContext(label: String, ec: ExecutionContext)
  extends ExecutionContextExecutor:
    export ec.*
    override def toString = label

  private class LabeledExecutorService(label: String, ec: ExecutorService)
  extends ExecutorService:
    export ec.*
    override def toString = label

  private class LabeledExecutionContextExecutorService(
    label: String, ec: ExecutionContextExecutorService)
  extends ExecutionContextExecutorService:
    export ec.*
    override def toString = label

  java8Polyfill()
