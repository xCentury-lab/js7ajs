package js7.common.akkahttp.https

import java.net.URL
import java.security.KeyStore
import java.security.cert.{Certificate, X509Certificate}
import javax.net.ssl.{KeyManager, KeyManagerFactory, SSLContext, TrustManagerFactory, X509TrustManager}
import js7.base.utils.AutoClosing._
import js7.base.utils.Strings._
import js7.common.scalautil.Logger
import scala.jdk.CollectionConverters._

/**
  * Provides HTTPS keystore and truststore..
  * <p>
  * Another way to use an own keystore may be via Java system properties:
  * <ul>
  *   <li>Server: javax.net.ssl.keyStore=keystore javax.net.ssl.keyStorePassword=password
  *   <li>Client: javax.net.ssl.trustStore=truststore javax.net.ssl.trustStorePassword=trustword
  * </ul>
  *
  * @author Joacim Zschimmer
  * @see http://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#CreateKeystore
  *      https://doc.akka.io/docs/akka-http/current/server-side/server-https-support.html
  *      https://tools.ietf.org/html/rfc5246
  */
object Https
{
  private val logger = Logger(getClass)

  def loadSSLContext(keyStoreRef: Option[KeyStoreRef] = None, trustStoreRefs: Seq[TrustStoreRef] = Nil): SSLContext = {
    val keyManagers = keyStoreRef match {
      case None => Array.empty[KeyManager]
      case Some(ref) =>
        val keyStore = loadKeyStore(ref, "private")
        val factory = KeyManagerFactory.getInstance("SunX509")
        factory.init(keyStore, ref.keyPassword.string.toCharArray)
        factory.getKeyManagers
    }
    val trustManagers = trustStoreRefs.view.flatMap { trustStoreRef =>
      val factory = TrustManagerFactory.getInstance("SunX509")
      factory.init(loadKeyStore(trustStoreRef, "trust"))
      factory.getTrustManagers
    }.collect {
      case o: X509TrustManager => Some(o)
      case o =>
        logger.debug(s"Ignoring unknown TrustManager: ${o.getClass.getName} $o")
        None
    }.flatten
      .toSeq
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagers, Array(CompositeX509TrustManager(trustManagers)), null)
    sslContext
  }

  private def loadKeyStore(storeRef: StoreRef, name: String): KeyStore = {
    val keyStore = KeyStore.getInstance("PKCS12")
    autoClosing(storeRef.url.openStream()) { inputStream =>
      keyStore.load(inputStream, storeRef.storePassword.string.toCharArray)
    }
    log(storeRef.url, keyStore, name)
    keyStore
  }

  private def log(url: URL, keyStore: KeyStore, name: String): Unit = {
    val aliases = keyStore.aliases.asScala
    if (aliases.isEmpty)
      logger.info(s"Loaded empty $name keystore $url")
    else
      for (alias <- aliases) {
        val maybeCert = Option(keyStore.getCertificate(alias))
        logger.info(s"Alias '$alias': " +
          maybeCert.fold("no certificate")(cert =>
            (keyStore.isKeyEntry(alias) ?: "private ") +
            (keyStore.isCertificateEntry(alias) ?: "trusted ") +
            certificateToString(cert)) +
            s" from $name $url")
        for (cert <- maybeCert) {
          logger.debug(s"Alias '$alias': hashCode=${cert.hashCode}")
        }
      }
  }

  private def certificateToString(cert: Certificate): String =
    (cert match {
      case cert: X509Certificate =>
        s"X.509 '${cert.getSubjectX500Principal}'"
      case o =>
        o.getType
    })
}
