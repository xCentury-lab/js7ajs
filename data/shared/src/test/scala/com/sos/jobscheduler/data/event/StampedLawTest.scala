package com.sos.jobscheduler.data.event

import cats.instances.int._
import cats.instances.string._
import cats.laws.discipline.FunctorTests
import com.sos.jobscheduler.base.time.Timestamp
import com.sos.jobscheduler.data.event.Stamped._
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.prop.Configuration
import org.typelevel.discipline.scalatest.FunSuiteDiscipline
import org.scalatest.funsuite.AnyFunSuite

/**
  * @author Joacim Zschimmer
  */
final class StampedLawTest extends AnyFunSuite with Configuration with FunSuiteDiscipline
{
  private implicit def arbitraryStamped[A: Arbitrary]: Arbitrary[Stamped[A]] =
    Arbitrary(arbitrary[A] map (o => Stamped(EventId(111), Timestamp.ofEpochMilli(222), o)))

  //?private implicit val isomorphisms: Isomorphisms[Stamped] = Isomorphisms.invariant(Stamped.functor)

  checkAll("Stamped[A]", FunctorTests[Stamped].functor[Int, Int, String])
}
