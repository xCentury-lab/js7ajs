package com.sos.jobscheduler.core.crypt.generic

import com.sos.jobscheduler.base.problem.Checked.Ops
import com.sos.jobscheduler.common.scalautil.FileUtils.syntax._
import com.sos.jobscheduler.common.scalautil.FileUtils.withTemporaryDirectory
import com.sos.jobscheduler.common.scalautil.FileUtils.withTemporaryFile
import com.sos.jobscheduler.core.crypt.pgp.PgpSigner.readSecretKey
import com.sos.jobscheduler.core.crypt.pgp.{PgpSigner, PgpTest}
import com.sos.jobscheduler.core.problems.TamperedWithSignedMessageProblem
import com.typesafe.config.ConfigFactory
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class GenericSignatureVerifierTest extends FreeSpec
{
  "Directory of public keys (recommended usage)" in {
    withTemporaryDirectory("GenericSignatureVerifierTest") { directory =>
      val messages = List("MESSAGE-1", "Message-2")
      val signers = List(
        PgpSigner(readSecretKey(PgpTest.secretKeyResource), PgpTest.secretKeyPassword).orThrow,
        PgpSigner(readSecretKey(PgpTest.secretKeyResource2), PgpTest.secretKeyPassword2).orThrow)
      val signatures = for ((message, signer) <- messages zip signers)
        yield signer.sign(message).toGenericSignature
      for (signature <- signatures) assert(signature.typeName == "PGP")

      directory / "test.asc" := PgpTest.publicKeyResource.contentBytes
      directory / "test-2.asc" := PgpTest.publicKeyResource2.contentBytes

      val verifier = GenericSignatureVerifier(ConfigFactory.parseString(
        s"""jobscheduler.configuration.trusted-signature-keys.PGP = "$directory" """)
      ).orThrow
      assert(verifier.verify(messages(0), signatures(0)) == Right(PgpTest.signerIds))
      assert(verifier.verify("TAMPERED", signatures(0)) == Left(TamperedWithSignedMessageProblem))
      assert(verifier.verify(messages(1), signatures(1)) == Right(PgpTest.signerIds2))
    }
  }

  "Single public key file" in {
    withTemporaryFile { file =>
      val message = "MESSAGE"
      val signer = PgpSigner(readSecretKey(PgpTest.secretKeyResource), PgpTest.secretKeyPassword).orThrow
      val signature = signer.sign(message).toGenericSignature
      assert(signature.typeName == "PGP")

      file := PgpTest.publicKeyResource.contentBytes

      val verifier = GenericSignatureVerifier(ConfigFactory.parseString(
        s"""jobscheduler.configuration.trusted-signature-keys.PGP = "${file.toString.replace("""\""", """\\""")}"""")
      ).orThrow
      assert(verifier.verify(message, signature) == Right(PgpTest.signerIds))
      assert(verifier.verify("TAMPERED", signature) == Left(TamperedWithSignedMessageProblem))
    }
  }
}
