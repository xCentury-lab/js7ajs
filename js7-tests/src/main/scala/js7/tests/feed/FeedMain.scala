package js7.tests.feed

import cats.effect.{ExitCode, IO, Resource, ResourceIO}
import java.io.InputStream
import js7.base.catsutils.OurApp
import js7.base.log.Logger
import js7.base.problem.Checked

object FeedMain extends OurApp:

  def run(args: List[String]): IO[ExitCode] =
    IO.defer:
      Logger.initialize("JS7 Feed")

      if args.isEmpty || args.sameElements(Array("--help")) then
        println("Usage: testAddOrders --workflow=WORKFLOWPATH --order-count=1 --user=USER:PASSWORD")
        IO.pure(ExitCode.Success)
      else
        run(args, Resource.eval(IO.pure(System.in)))
          .map:
            case Left(problem) =>
              println(problem.toString)
              ExitCode.Error

            case Right(()) =>
              ExitCode.Success

  def run(args: Seq[String], in: ResourceIO[InputStream]): IO[Checked[Unit]] =
    val settings = Settings.parseArguments(args)
    Feed.run(in, settings)
