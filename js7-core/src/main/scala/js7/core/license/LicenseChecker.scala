package js7.core.license

import js7.base.log.Logger
import js7.base.problem.{Checked, Problem}
import js7.base.system.ServiceProviders.findServices
import js7.base.utils.ScalaUtils.RightUnit
import js7.base.utils.ScalaUtils.syntax.RichThrowable
import js7.license.{LicenseCheck, LicenseCheckContext}
import scala.util.control.NonFatal

final class LicenseChecker(licenseCheckContext: LicenseCheckContext)
{
  private val logger = Logger[this.type]

  private lazy val licenseChecks: Seq[LicenseCheck] =
    findServices[LicenseCheck] { (logLine, maybeService) =>
      logger.debug(logLine)
      for (service <- maybeService) service.initialize(licenseCheckContext)
    }

  def checkLicense(productName: String): Checked[Unit] =
    try
      if (!hasLicense(licenseChecks, productName))
        Left(Problem(s"No license for $productName"))
      else
        RightUnit
    catch { case NonFatal(t) =>
      logger.error(t.toStringWithCauses, t)
      Left(Problem.fromThrowable(t))
    }

  private[license] def hasLicense(licenseChecks: Seq[LicenseCheck], productName: String): Boolean =
    licenseChecks
      .view
      .dropWhile { o =>
        val hasLicense = o.hasLicense(productName)
        logger.debug(s"${o.getClass.getName} hasLicense($productName) => $hasLicense")
        !hasLicense
      }
      .nonEmpty
}
