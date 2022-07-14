package js7.base.log

import js7.base.log.CorrelIdLog4JThreadContextMap.CorrelIdKey
import js7.base.log.CorrelIdLog4jStringMap.*
import js7.base.utils.Tests.isTest
import org.apache.logging.log4j.util.{BiConsumer, ReadOnlyStringMap, StringMap, TriConsumer}

final class CorrelIdLog4jStringMap(protected val correlId: CorrelId)
extends StringMap
{
  private def correlIdString =
    if (correlId != null)
      correlId.fixedWidthString
    else if (isTest)
      throw new NullPointerException("CorrelIdLog4jStringMap: correlId is null")
    else
      nullString

  def toMap = toJavaMap

  lazy val toJavaMap: java.util.Map[String, String] =
    java.util.Collections.singletonMap(CorrelIdKey, correlIdString)

  def isEmpty = false

  def size = 1

  def isFrozen = true

  def freeze() = {}

  def containsKey(key: String) =
    key == CorrelIdKey

  def getValue[V](key: String): V =
    key match {
      case CorrelIdKey =>
        CorrelId.onCorrelIdLogged()
        correlIdString.asInstanceOf[V]
      case _ => null.asInstanceOf[V]
    }

  def forEach[V](action: BiConsumer[String, _ >: V]): Unit =
    action.accept(CorrelIdKey, correlIdString.asInstanceOf[V])

  def forEach[V, S](action: TriConsumer[String, _ >: V, S], state: S): Unit = {
    _forEachCount += 1
    action.accept(CorrelIdKey, correlIdString.asInstanceOf[V], state)
  }

  def clear() = throwFrozen()

  def putAll(source: ReadOnlyStringMap) = throwFrozen()

  def putValue(key: String, value: Any) = throwFrozen()

  def remove(key: String) = throwFrozen()

  private def throwFrozen() =
    throw new IllegalStateException("CorrelId StringMap is frozen")
}

object CorrelIdLog4jStringMap
{
  private val nullString = "❗️null❗️  ".take(CorrelId.width + 2 * ("❗️".length - 1))
  private var _forEachCount = 0L

  def forEachCount = _forEachCount
}
