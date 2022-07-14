package js7.common.configuration

import cats.syntax.semigroup.*
import com.typesafe.config.Config
import java.nio.file.Path
import js7.base.configutils.Configs.RichConfig
import js7.base.convert.AsJava.StringAsPath
import js7.base.io.https.{HttpsConfig, KeyStoreRef, TrustStoreRef}
import js7.base.log.Logger
import js7.base.problem.Checked.Ops
import js7.base.problem.{Checked, Problem}
import js7.common.akkahttp.web.data.{WebServerBinding, WebServerPort}
import js7.common.commandline.CommandLineArguments
import js7.common.configuration.CommonConfiguration.*
import js7.common.internet.IP.StringToServerInetSocketAddress

/**
  * @author Joacim Zschimmer
  */
trait CommonConfiguration extends WebServerBinding.HasLocalUris
{
  def config: Config

  def configDirectory: Path

  def webServerPorts: Seq[WebServerPort]

  lazy val httpsConfig =
    HttpsConfig(
      keyStoreRef =
        (if (config.hasPath("js7.web.https.client-keystore"))
          KeyStoreRef.fromSubconfig(config.getConfig("js7.web.https.client-keystore"),
            defaultFile = configDirectory.resolve("private/https-client-keystore.p12"))
        else
          keyStoreRef
        ).onProblem(p => logger.debug(s"No keystore: $p")),
      trustStoreRefs)

  final lazy val trustStoreRefs: Seq[TrustStoreRef] =
    TrustStoreRef.fromConfig(config)

  private lazy val keyStoreRef: Checked[KeyStoreRef] =
    config.checkedPath("js7.web.https.keystore")(path =>
      KeyStoreRef.fromSubconfig(config.getConfig(path),
        defaultFile = configDirectory.resolve("private/https-keystore.p12")))

  final def http: Seq[WebServerBinding.Http] =
    webServerBindings collect { case o: WebServerBinding.Http => o }

  final def https: Seq[WebServerBinding.Https] =
    webServerBindings collect { case o: WebServerBinding.Https => o }

  final def webServerBindings: Seq[WebServerBinding] =
    webServerPorts map {
      case WebServerPort.Http(port) =>
        WebServerBinding.Http(port)

      case WebServerPort.Https(port) =>
        WebServerBinding.Https(port,
          keyStoreRef.mapProblem(Problem("HTTPS requires a key store:") |+| _).orThrow,
          trustStoreRefs)
    }
}

object CommonConfiguration
{
  private val logger = Logger(getClass)

  final case class Common(
    configDirectory: Path,
    dataDirectory: Path,
    webServerPorts: Seq[WebServerPort])

  object Common {
    def fromCommandLineArguments(a: CommandLineArguments): Common =
      Common(
        dataDirectory = a.as[Path]("--data-directory=").toAbsolutePath,
        configDirectory = a.as[Path]("--config-directory=").toAbsolutePath,
        webServerPorts =
          a.seqAs("--http-port=")(StringToServerInetSocketAddress).map(WebServerPort.Http) ++
          a.seqAs("--https-port=")(StringToServerInetSocketAddress).map(WebServerPort.Https)
      )
  }
}
