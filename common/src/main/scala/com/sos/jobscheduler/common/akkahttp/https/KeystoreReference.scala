package com.sos.jobscheduler.common.akkahttp.https

import com.sos.jobscheduler.base.convert.AsJava.StringAsPath
import com.sos.jobscheduler.base.generic.SecretString
import com.sos.jobscheduler.common.configutils.Configs.ConvertibleConfig
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.typesafe.config.Config
import java.net.URL
import java.nio.file.Path

/**
  * @author Joacim Zschimmer
  */
final case class KeystoreReference(
  url: URL,
  /** Password for .jks file, just to check integrity (may be empty) */
  storePassword: Option[SecretString] = None,
  /** PKCS#12 key password */
  keyPassword: Option[SecretString] = None)
{
  override def toString: String = s"KeystoreReference($url)"
}

object KeystoreReference {
  def fromSubConfig(config: Config, configDirectory: Path) =
    KeystoreReference(
      url = config.as[Path]("file", configDirectory / "private/private-https.jks").toURI.toURL,
      storePassword = config.optionAs[SecretString]("password"),
      keyPassword = Some(config.as[SecretString]("key-password")))
}
