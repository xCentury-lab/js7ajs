package com.sos.jobscheduler.common.files

import java.nio.file.{Files, Path}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import scala.concurrent.duration.FiniteDuration
import scodec.bits.ByteVector

final class GrowingFileObservable(file: Path, pollDuration: Option[FiniteDuration] = None)(implicit scheduler: Scheduler)
extends Observable[ByteVector]
{
  def unsafeSubscribeFn(subscriber: Subscriber[ByteVector]): Cancelable = {
    @volatile var cancelled = false
    val reader = new ByteVectorReader(file, fromEnd = pollDuration.isDefined)

    def continue(): Unit =
      if (cancelled) {
        reader.close()
      } else {
        val chunk = reader.read()
        if (chunk.isEmpty) {
          // End of file reached
          pollDuration match {
            case Some(delay) if chunk.isEmpty =>
              if (Files.exists(file)) {
                scheduler.scheduleOnce(delay) {
                  continue()
                }
              }
            case _  =>
              complete()
          }
        } else {
          subscriber.onNext(chunk) map {
            case Continue => continue()
            case Stop => complete()
          }
        }
      }

    def complete(): Unit = {
      try reader.close()
      finally subscriber.onComplete()
    }

    continue()

    () => cancelled = true
  }
}
