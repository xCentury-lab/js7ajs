package js7.base.catsutils

import cats.kernel.Monoid
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import cats.{Applicative, ApplicativeError, Functor, MonadError, Parallel, Traverse}
import scala.util.{Failure, Success, Try}

object CatsExtensions:

  extension [F[_], A](underlying: F[A])

    def traverseCombine[G[_], B](f: A => G[B])
      (using Traverse[F], Applicative[G], Monoid[B])
    : G[B] =
      underlying.traverse(f).map(_.combineAll)

    def parTraverseCombine[P[_], B](f: A => P[B])
      (using Traverse[F], Parallel[P], Functor[P], Monoid[B])
    : P[B] =
      underlying.parTraverse(f).map(_.combineAll)

    /** Like attempt, but returns a Try. */
    def tryIt(using ApplicativeError[F, Throwable]): F[Try[A]] =
      underlying.attempt.map(_.toTry)

    /** Like rethrow, but from a Try. */
    def untry[A1](using F: MonadError[F, Throwable], ev: A =:= Try[A1]): F[A1] =
      underlying.asInstanceOf[F[Try[A1]]].flatMap:
        case Failure(t) => F.raiseError(t)
        case Success(a) => F.pure(a)
