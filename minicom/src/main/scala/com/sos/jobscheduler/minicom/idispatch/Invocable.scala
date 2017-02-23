package com.sos.jobscheduler.minicom.idispatch

import com.sos.jobscheduler.minicom.types.IUnknown
import java.lang.reflect.Method
import scala.collection.immutable.Seq

/**
 * Public methods annotated with @[[com.sos.jobscheduler.minicom.idispatch.annotation.invocable]] are callable via [[OverridingInvocableIDispatch]].
 * Nearly a [[IDispatch]], but without need to implement the `IDispatch`s methods.
 *
 * @author Joacim Zschimmer
 */
trait Invocable extends IUnknown {
  def invocableMethods: Seq[(Method, Option[DISPID])]
}

object Invocable {
  trait Empty extends Invocable {
    final def invocableMethods = Nil
  }
}
