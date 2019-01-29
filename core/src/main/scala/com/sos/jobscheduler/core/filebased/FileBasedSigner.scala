package com.sos.jobscheduler.core.filebased

import com.sos.jobscheduler.base.circeutils.CirceUtils.RichJson
import com.sos.jobscheduler.base.generic.SecretString
import com.sos.jobscheduler.common.scalautil.GuavaUtils.stringToInputStreamResource
import com.sos.jobscheduler.core.signature.PgpSigner
import com.sos.jobscheduler.data.crypt.PgpSignature
import com.sos.jobscheduler.data.filebased.{FileBased, SignedRepoObject}
import io.circe.Encoder
import java.util.Base64
import org.bouncycastle.openpgp.PGPSecretKey

/**
  * @author Joacim Zschimmer
  */
final class FileBasedSigner(jsonEncoder: Encoder[FileBased], secretKey: PGPSecretKey, password: SecretString)
{
  val pgpSigner = new PgpSigner(secretKey, password)

  def sign(fileBased: FileBased): SignedRepoObject = {
    val message = jsonEncoder(fileBased).compactPrint
    val signature = pgpSigner.sign(stringToInputStreamResource(message))
    SignedRepoObject(message, PgpSignature(Base64.getMimeEncoder.encodeToString(signature)))
  }
}
