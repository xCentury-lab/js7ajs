package js7.base.io.process

import java.io.{InputStream, OutputStream}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.StreamConverters.*

trait Js7Process:

  def pid: Pid

  def isAlive: Boolean

  def stdin: OutputStream

  def stdout: InputStream

  def stderr: InputStream

  def returnCode: Option[ReturnCode]

  def destroy() : Unit

  def destroyForcibly() : Unit

  def waitFor(): ReturnCode

  def waitFor(duration: FiniteDuration): Boolean

  def maybeHandle: Option[ProcessHandle]

  final def descendants: Vector[ProcessHandle] =
    maybeHandle.fold(Vector.empty)(_.descendants.toScala(Vector))
