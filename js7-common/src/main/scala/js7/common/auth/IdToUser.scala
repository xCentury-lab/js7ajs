package js7.common.auth

import com.google.common.hash.Hashing.sha512
import com.typesafe.config.{Config, ConfigObject}
import java.nio.charset.StandardCharsets.UTF_8
import js7.base.auth.{DistinguishedName, HashedPassword, Permission, User, UserId}
import js7.base.generic.SecretString
import js7.base.utils.Collections.implicits._
import js7.base.utils.Memoizer
import js7.base.utils.ScalaUtils.syntax._
import js7.common.auth.IdToUser._
import js7.common.configutils.Configs.{ConvertibleConfig, _}
import js7.common.scalautil.Logger
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
  * Extracts the hashed password and the hashing algorithm from an encoded password.
  * The encoded passwords are prefixed with a hash scheme or `"plain:"`.
  * <pre>
  * tobbe = "plain:PASSWORD"
  * lisbeth = "sha512:130c7809c9e5a8d81347b55f5c82c3a7407f4b41b461eb641887d276b11af4b575c5a32d1cf104e531c700e4b1ddd75b27b9e849576f6dfb8ca42789fbc7ece2"
  * </pre>
  * How to generate SHA512?
  * <ul>
  *   <li>Gnu (Linux): <code>sha512sum <(echo -n "password")</code>.
  *   <li>MacOS: <code>shasum -a 512 <(echo -n "password")</code>.
  * </ul>
  *
  * @author Joacim Zschimmer
  */
final class IdToUser[U <: User](
  userIdToRaw: UserId => Option[RawUserAccount],
  distinguishedNameToUserId: DistinguishedName => Option[UserId],
  toUser: (UserId, HashedPassword, Set[Permission], Seq[DistinguishedName]) => U,
  toPermission: PartialFunction[String, Permission])
extends (UserId => Option[U])
{
  private lazy val someAnonymous = Some(toUser(UserId.Anonymous, HashedPassword.newEmpty(), Set.empty, Nil))
  private val memoizedToUser = Memoizer.strict((userId: UserId) =>
    if (userId.isAnonymous)
      someAnonymous
    else
      userIdToRaw(userId).flatMap(rawToUser))

  def apply(userId: UserId): Option[U] = memoizedToUser(userId)

  def distinguishedNameToUser(distinguishedName: DistinguishedName): Option[U] =
    distinguishedNameToUserId(distinguishedName) flatMap apply

  private def rawToUser(raw: RawUserAccount): Option[U] =
    for (hashedPassword <- toHashedPassword(raw.userId, raw.encodedPassword))
      yield toUser(
        raw.userId,
        hashedPassword.hashAgainRandom,
        raw.permissions.flatMap(toPermission.lift),
        raw.distinguishedNames)
}

object IdToUser
{
  private val logger = Logger(getClass)
  private val UsersConfigPath = "js7.auth.users"
  private val PasswordRegex = "([^:]+):(.*)".r

  def fromConfig[U <: User](
    config: Config,
    toUser: (UserId, HashedPassword, Set[Permission], Seq[DistinguishedName]) => U,
    toPermission: PartialFunction[String, Permission] = PartialFunction.empty)
  : IdToUser[U] = {
    val cfg = config.getConfig(UsersConfigPath)
    val cfgObject = config.getValue(UsersConfigPath).asInstanceOf[ConfigObject]

    def userIdToRaw(userId: UserId): Option[RawUserAccount] =
      if (cfg.hasPath(userId.string))
        existentUserIdToRaw(userId)
      else {
        logger.debug(s"""Configuration files ("private.conf") does not have an entry '$UsersConfigPath.${userId.string}'""")
        None
      }

    def existentUserIdToRaw(userId: UserId): Option[RawUserAccount] =
      Try(cfg.getConfig(userId.string)) match {
        case Failure(_: com.typesafe.config.ConfigException.WrongType) =>  // Entry is not a configuration object {...} but a string (the password)
          cfg.optionAs[SecretString](userId.string) map (o =>
            RawUserAccount(userId, encodedPassword = o))

        case Failure(t) =>
          throw t

        case Success(c) =>
          for {
            encodedPassword <- c.optionAs[SecretString]("password")
            permissions = c.stringSeq("permissions", Nil).toSet
            distinguishedNames = c.seqAs[DistinguishedName]("distinguished-names", Nil)
          } yield RawUserAccount(userId, encodedPassword = encodedPassword, permissions = permissions, distinguishedNames)
      }

    val distinguishedNameToUserId: Map[DistinguishedName, UserId] =
      cfgObject.asScala.view
        .flatMap { case (key, value) =>
          UserId.checked(key)
            .toOption/*ignore error*/
            .view
            .flatMap(userId =>
              value match {
                case value: ConfigObject =>
                  Option(value.get("distinguished-names"))
                    .view
                    .flatMap(_.unwrapped match {
                      case list: java.util.List[_] =>
                        list.asScala.flatMap {
                          case o: String =>
                            DistinguishedName.checked(o)
                              .toOption/*ignore error*/
                              .map(_ -> userId)
                          case o =>
                            println(s"### ? $o")
                            Nil/*ignore error*/
                        }
                    })
                case _=>
                  Nil/*ignore type mismatch*/
              })
        }
        .uniqueToMap/*throws*/

    logger.trace("distinguishedNameToUserId=" + distinguishedNameToUserId.map { case (k, v) => s"\n  $k --> $v" }.mkString)

    new IdToUser(userIdToRaw, distinguishedNameToUserId.lift, toUser, toPermission)
  }

  private val sha512Hasher = { o: String => sha512.hashString(o: String, UTF_8).toString } withToString "sha512"
  private val identityHasher = { o: String => identity(o) } withToString "identity"

  private def toHashedPassword(userId: UserId, encodedPassword: SecretString) =
    encodedPassword.string match {
      case PasswordRegex("plain", pw) =>
        Some(HashedPassword(SecretString(pw), identityHasher))

      case PasswordRegex("sha512", pw) =>
        Some(HashedPassword(SecretString(pw), sha512Hasher))

      case PasswordRegex(_, _) =>
        logger.error(s"Unknown password encoding scheme for User '$userId'")
        None

      case _ =>
        logger.error(s"Missing password encoding scheme for User '$userId'. Try to prefix the configured password with 'plain:' or 'sha512:'")
        None
    }

  private[auth] final case class RawUserAccount(
    userId: UserId,
    encodedPassword: SecretString,
    permissions: Set[String] = Set.empty,
    distinguishedNames: Seq[DistinguishedName] = Nil)
}
