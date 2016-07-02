package com.sos.scheduler.engine.base.generic

/**
  * @author Joacim Zschimmer
  */
trait GenericInt extends SerializableGenericInt {
  def value: Int
}

object GenericInt {
  trait Companion[A <: GenericInt] {
    implicit val ordering: Ordering[A] = Ordering by { _.value }
  }
}
