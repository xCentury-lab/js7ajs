package com.sos.jobscheduler.minicom.idispatch

/**
  * An IDispatch using only methods of Invocable.
  * *
  * @author Joacim Zschimmer
  */
trait InvocableIDispatch extends IDispatch.Empty with OverridingInvocableIDispatch
