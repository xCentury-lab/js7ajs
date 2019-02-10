package com.sos.jobscheduler.core.crypt.generic

import cats.Applicative
import cats.data.Validated._
import cats.instances.vector._
import cats.syntax.traverse._
import com.sos.jobscheduler.base.problem.Checked._
import com.sos.jobscheduler.base.problem.{Checked, Problem}
import com.sos.jobscheduler.base.utils.Collections._
import com.sos.jobscheduler.common.scalautil.Logger
import com.sos.jobscheduler.core.crypt.SignatureVerifier
import com.sos.jobscheduler.data.crypt.{GenericSignature, SignerId}
import com.typesafe.config.Config
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq

/** A `SignatureVerifier` that verifies different types of signatures.
  * @author Joacim Zschimmer
  */
final class GenericSignatureVerifier private(typeToVerifier: Map[String, Checked[SignatureVerifier]]) extends SignatureVerifier
{
  protected type MySignature = GenericSignature

  def companion = GenericSignatureVerifier

  def keyOrigin = "(GenericSignatureVerifier)"

  def verify(message: String, signature: GenericSignature): Checked[Seq[SignerId]] =
    typeToVerifier(signature.typeName)
      .flatMap(verifier => verifier.verify(message, verifier.companion.genericSignatureToSignature(signature)))

  def isEmpty = typeToVerifier.isEmpty
}

object GenericSignatureVerifier extends SignatureVerifier.Companion
{
  private val configPath = "jobscheduler.configuration.trusted-signature-keys"
  private val logger = Logger(getClass)

  protected type MySignature = GenericSignature
  protected type MySignatureVerifier = GenericSignatureVerifier

  def typeName = "(generic)"

  implicitly[Applicative[Checked]]

  def apply(config: Config): Checked[GenericSignatureVerifier] =
    config.getObject(configPath).asScala.toMap  // All Config key-values
      .map { case (typeName, v) =>
        typeName -> (v.unwrapped match {
          case o: String =>
            val file = Paths.get(o)
            if (!Files.exists(file))
              Invalid(Problem.pure(s"Signature key file '$file' for '$typeName' does not exists"))
            else
              SignatureVerifiers.typeToSignatureVerifierCompanion(typeName)
                .flatMap(_.checked(Files.readAllBytes(file).toVector, keyOrigin = file.toString))
          case _ =>
            Invalid(Problem.pure(s"String expected as value for configuration key $configPath.$typeName"))
        })
      }
      .toVector.map {
        case (k, Valid(v)) => Valid(k -> v)
        case (_, Invalid(p)) => invalid(p): Checked[(String, SignatureVerifier)]
      }
      .sequence
      .map(_.toMap)
      .flatMap { typeToVerifier =>
        if (typeToVerifier.isEmpty)
          Invalid(Problem.pure(s"No trusted signature keys - Configure one with $configPath!"))
        else {
          for (verifier <- typeToVerifier.values.toVector.sortBy(_.companion.typeName)) {
            logger.info("Using trusted key for the signature type " +
              s"${verifier.companion.typeName}: $verifier")
          }
          Valid(
            new GenericSignatureVerifier(
              typeToVerifier.toChecked(key => Problem(s"No trusted public key for signature type '$key'"))))
        }
      }

  @deprecated("Not implemented", "")
  def checked(publicKey: Seq[Byte], keyOrigin: String) = throw new NotImplementedError("GenericSignatureVerifier.checked?")

  def genericSignatureToSignature(signature: GenericSignature) = signature
}
