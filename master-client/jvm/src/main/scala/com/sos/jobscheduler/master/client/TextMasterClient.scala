package com.sos.jobscheduler.master.client

import akka.http.scaladsl.model.Uri
import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.base.session.SessionApi
import com.sos.jobscheduler.common.akkahttp.https.{Https, KeyStoreRef}
import com.sos.jobscheduler.common.akkautils.ProvideActorSystem
import com.sos.jobscheduler.common.configutils.Configs.parseConfigIfExists
import com.sos.jobscheduler.common.http.{AkkaHttpClient, TextClient}
import com.sos.jobscheduler.common.scalautil.Closers.implicits.RichClosersCloser
import com.sos.jobscheduler.common.scalautil.FileUtils.implicits._
import com.sos.jobscheduler.common.scalautil.HasCloser
import com.sos.jobscheduler.master.client.TextMasterClient._
import com.typesafe.config.{Config, ConfigFactory}
import java.nio.file.Path

/**
  * @author Joacim Zschimmer
  */
private[master] final class TextMasterClient(
  protected val baseUri: Uri,
  protected val print: String ⇒ Unit,
  configDirectory: Option[Path] = None)
extends HasCloser with AkkaHttpClient with ProvideActorSystem with TextClient with SessionApi {

  protected def uriPrefixPath = "/master"

  private val masterUris = MasterUris(s"$baseUri/master")

  protected def httpClient = this

  protected def sessionUri = masterUris.session

  protected def serverName = "JobScheduler Master"

  protected def commandUri = masterUris.command

  protected def apiUri(tail: String) = masterUris.api(tail)

  protected override lazy val httpsConnectionContextOption =
    for (configDir ← configDirectory) yield
      Https.toHttpsConnectionContext(
        KeyStoreRef.fromConfig(configDirectoryConfig(configDir), default = configDir resolve "private/https-keystore.p12").orThrow)

  closer.onClose { super.close() }

  override def close() = closer.close()
}

object TextMasterClient
{
  // Like MasterConfiguration.configDirectoryConfig
  private def configDirectoryConfig(configDirectory: Path): Config =
    ConfigFactory
      .empty
      .withFallback(parseConfigIfExists(configDirectory / "private/private.conf"))
      .withFallback(parseConfigIfExists(configDirectory / "master.conf"))
}
