package js7.base.auth

import js7.base.utils.Collections.implicits._
import js7.base.utils.ScalaUtils.syntax._

/**
  * @author Joacim Zschimmer
  */
trait Permission {
  def name: String = getClass.simpleScalaName stripSuffix "Permission"
}

object Permission
{
  def toStringToPermission(permissions: Iterable[Permission]): Map[String, Permission] =
    permissions.toKeyedMap(_.name)
}

/** SuperPermission covering all permissions. */
case object SuperPermission extends Permission

case object GetPermission extends Permission

/**
  * Permission for logged-in (non-anonymous) user or public access.
  * <p>
  * If required, access is permitted if
  * <ul>
  * <li> the user has this permission (user is not Anonymous)
  * <li> or the user is Anonymous and
  *   <ul>
  *   <li> loopbackIsPublic is set and access via a TCP port bound to a loopback interface (like localhost)
  *   <li> getIsPublic is set and access is via GET
  *   </ul>
  * </ul>
  * For implementation, see GateKeeper.isAllowed
  */
case object ValidUserPermission extends Permission

case object UpdateItemPermission extends Permission

/** Permit the Agent Director to control the Subagent. */
case object AgentDirectorPermission extends Permission
