package com.sos.jobscheduler.base.auth

import org.scalatest.FreeSpec

/**
  * @author Joacim Zschimmer
  */
final class UserIdTest extends FreeSpec {

  "Invalid UserId" in {
    intercept[IllegalArgumentException] { UserId("") }
    intercept[IllegalArgumentException] { UserId("a?") }
    intercept[IllegalArgumentException] { UserId("a?b") }
    intercept[IllegalArgumentException] { UserId("a/b") }
    intercept[IllegalArgumentException] { UserId("/root") }
    intercept[IllegalArgumentException] { UserId(".") }
    intercept[IllegalArgumentException] { UserId("..") }
    intercept[IllegalArgumentException] { UserId(".hidden") }
  }

  "Valid UserId" in {
    UserId("a")
    UserId("å")
    UserId("0")
    UserId("ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÅÜabcdefghijklmnopqrstuvwxyzäöåüß0123456789.-_")
    UserId("a.")
  }
}
