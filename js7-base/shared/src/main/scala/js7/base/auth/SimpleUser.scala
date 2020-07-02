package js7.base.auth

import js7.base.utils.ScalaUtils._
import js7.base.utils.ScalaUtils.syntax._

/**
  * @author Joacim Zschimmer
  */
final case class SimpleUser private(
  id: UserId,
  hashedPassword: HashedPassword,
  grantedPermissions: Set[Permission],
  distinguishedName: Option[DistinguishedName] = None)
extends User
{
  if (id.isAnonymous && grantedPermissions.contains(ValidUserPermission))
    throw new IllegalArgumentException("Anonymous must not have ValidUserPermission")
  // SuperPermission is allowed for empowered Anonymous (public = on | loopback-is-public = on)
}

object SimpleUser extends User.Companion[SimpleUser]
{
  /** The unauthenticated, anonymous user without permissions, for testing. */
  val TestAnonymous = SimpleUser(UserId.Anonymous, HashedPassword.newEmpty(), grantedPermissions = Set.empty)
  val System = SimpleUser(UserId("System"), HashedPassword.MatchesNothing, Set(SuperPermission))
  implicit val companion = this

  def addPermissions(user: SimpleUser, permissions: Set[Permission]): SimpleUser =
    reuseIfEqual(user, user.copy(
      grantedPermissions = user.grantedPermissions ++ permissions))

  def apply(
    id: UserId,
    hashedPassword: HashedPassword = HashedPassword.MatchesNothing,
    grantedPermissions: Set[Permission] = Set.empty,
    distinguishedName: Option[DistinguishedName] = None)
  : SimpleUser = new SimpleUser(
    id,
    hashedPassword,
    grantedPermissions ++ (!id.isAnonymous thenSet ValidUserPermission),
    distinguishedName)
}
