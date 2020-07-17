package js7.base.auth

import js7.base.circeutils.CirceUtils.deriveCodec
import js7.base.generic.SecretString
import scala.language.implicitConversions

/**
  * @author Joacim Zschimmer
  */
final case class UserAndPassword(userId: UserId, password: SecretString)

object UserAndPassword
{
  implicit def apply(userAndPassword: (UserId, SecretString)): UserAndPassword =
    new UserAndPassword(userAndPassword._1, userAndPassword._2)

  val jsonCodec = {
    implicit val x = SecretString.jsonCodec
    deriveCodec[UserAndPassword]
  }
}
