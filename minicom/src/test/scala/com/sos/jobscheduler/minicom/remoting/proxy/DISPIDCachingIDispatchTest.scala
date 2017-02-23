package com.sos.jobscheduler.minicom.remoting.proxy

import com.sos.jobscheduler.minicom.idispatch.{DISPID, DispatchType, IDispatch}
import com.sos.jobscheduler.minicom.remoting.proxy.DISPIDCachingIDispatchTest._
import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class DISPIDCachingIDispatchTest extends FreeSpec {

  "test" in {
    val iDispatch = new MyIDispatch with DISPIDCachingIDispatch
    for (_ ← 1 to 3) {
      assert(iDispatch.getIdOfName("aa") == DISPID(1))
      assert(iDispatch.getIdOfName("Aa") == DISPID(1))
      assert(iDispatch.getIdOfName("bb") == DISPID(2))
      assert(iDispatch.getIdOfName("BB") == DISPID(2))
    }
    assert(iDispatch.idCalls == 2)
  }

}

private object DISPIDCachingIDispatchTest {
  private trait MyIDispatch extends IDispatch {
    var idCalls = 0

    def getIdOfName(name: String) = {
      idCalls += 1
      name match {
        case "aa" ⇒ DISPID(1)
        case "bb" ⇒ DISPID(2)
      }
    }

    def invoke(dispId: DISPID, dispatchTypes: Set[DispatchType], arguments: Seq[Any], namedArguments: Seq[(DISPID, Any)]) = ???
  }
}
