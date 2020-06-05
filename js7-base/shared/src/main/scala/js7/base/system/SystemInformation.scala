package js7.base.system

import java.lang.management.ManagementFactory.getOperatingSystemMXBean
import js7.base.circeutils.AnyJsonCodecs.implicits._
import js7.base.circeutils.CirceUtils.deriveCodec

final case class SystemInformation(
  hostname: String,
  distribution: Option[String] = None,
  cpuModel: Option[String] = None,
  mxBeans: Map[String, Any] = Map())

object SystemInformation {
  (MapJsonDecoder, MapJsonEncoder)  // Force import usage for IntelliJ (hidden usage by @JsonCocec)

  def totalPhysicalMemory: Option[Long] =
    getOperatingSystemMXBean match {
      case o: com.sun.management.OperatingSystemMXBean => Some(o.getTotalPhysicalMemorySize)
      case _ => None
  }

  val ForTest = SystemInformation(hostname = "HOSTNAME")

  implicit val jsonCodec = deriveCodec[SystemInformation]
}
