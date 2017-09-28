package com.sos.jobscheduler.minicom.remoting.proxy

import com.google.inject.Guice
import com.sos.jobscheduler.common.guice.GuiceImplicits._
import com.sos.jobscheduler.minicom.idispatch.{IDispatch, Invocable}
import com.sos.jobscheduler.minicom.remoting.calls.ProxyId
import com.sos.jobscheduler.minicom.remoting.proxy.ProxyRegister.DuplicateKeyException
import com.sos.jobscheduler.minicom.types.HRESULT._
import com.sos.jobscheduler.minicom.types.{COMException, IUnknown}
import org.mockito.Mockito._
import org.scalatest.FreeSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar.mock
import scala.util.control.NoStackTrace

/**
 * @author Joacim Zschimmer
 */
final class ProxyRegisterTest extends FreeSpec {

  private val proxyRegister = Guice.createInjector().instance[ProxyRegister]
  private val externalProxyId = ProxyId(0x123456789abcdefL)

  "External ProxyId" in {
    val proxy = newProxy(externalProxyId)
    proxyRegister.registerProxy(proxy)
    proxy.id shouldEqual externalProxyId
    proxyRegister.iUnknownToProxyId(proxy) shouldEqual ((externalProxyId, false))
    proxyRegister.iUnknown(externalProxyId) shouldEqual proxy
    proxyRegister.size shouldEqual 1
    intercept[DuplicateKeyException] { proxyRegister.registerProxy(newProxy(externalProxyId)) }
  }

  "Own IDispatch" in {
    proxyRegister.size shouldEqual 1
    val iDispatch = mock[Invocable]
    val (proxyId, true) = proxyRegister.iUnknownToProxyId(iDispatch)
    proxyId.index shouldEqual 0x40000001
    proxyRegister.iUnknownToProxyId(iDispatch) shouldEqual ((proxyId, false))
    proxyRegister.iUnknown(proxyId) shouldEqual iDispatch
    intercept[DuplicateKeyException] { proxyRegister.registerProxy(newProxy(proxyId)) }

    proxyRegister.size shouldEqual 2
    val (otherProxyId, true) = proxyRegister.iUnknownToProxyId(mock[Invocable])
    otherProxyId.index shouldEqual 0x40000002
    proxyRegister.size shouldEqual 3
  }

  "null" in {
    intercept[COMException] { proxyRegister.iUnknown(ProxyId.Null) } .hResult shouldEqual E_POINTER
    assert(proxyRegister.iUnknownToProxyId(null) == ((ProxyId.Null, false)))
  }

  "removeProxy" in {
    proxyRegister.size shouldEqual 3
    proxyRegister.release(externalProxyId)
    proxyRegister.size shouldEqual 2
    proxyRegister.release(externalProxyId)
    proxyRegister.size shouldEqual 2
  }

  "remoteProxy closes AutoCloseable" in {
    trait A extends IDispatch with AutoCloseable
    val a = mock[A]
    when (a.close()) thenThrow new Exception("SHOULD BE IGNORED, ONLY LOGGED") with NoStackTrace
    val (proxyId, true) = proxyRegister.iUnknownToProxyId(a)
    proxyRegister.release(proxyId)
    verify(a).close()
  }

  "iUnknowns returns Invocables of a type" in {
    class X extends IUnknown
    assert(proxyRegister.iUnknowns[X].isEmpty)
    val xs = Iterator.fill(3) { new X } .toSet
    xs foreach proxyRegister.iUnknownToProxyId
    assert((proxyRegister.iUnknowns[X].toSet: Set[X]) == xs)
  }

  private def newProxy(proxyId: ProxyId, name: String = "") = new SimpleProxyIDispatch(mock[ProxyRemoting], proxyId, name)
}
