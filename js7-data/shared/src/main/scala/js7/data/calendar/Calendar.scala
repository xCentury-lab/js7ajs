package js7.data.calendar

import io.circe.Codec
import io.circe.generic.extras.Configuration
import js7.base.circeutils.CirceUtils.deriveConfiguredCodec
import js7.base.circeutils.ScalaJsonCodecs.*
import js7.base.problem.{Checked, Problem}
import js7.base.time.ScalaTime.*
import js7.base.time.Timezone
import js7.base.utils.IntelliJUtils.intelliJuseImport
import js7.base.utils.ScalaUtils.syntax.*
import js7.data.item.{ItemRevision, UnsignedSimpleItem}
import org.jetbrains.annotations.TestOnly
import scala.concurrent.duration.*

final case class Calendar(
  path: CalendarPath,
  timezone: Timezone,
  dateOffset: FiniteDuration = Duration.Zero,
  orderIdPattern: String,
  periodDatePattern: String,
  itemRevision: Option[ItemRevision] = None)
extends UnsignedSimpleItem
{
  protected type Self = Calendar

  val companion = Calendar

  def rename(path: CalendarPath) =
    copy(path = path)

  def toInitialItemState = CalendarState(this)

  def withRevision(revision: Option[ItemRevision]) =
    copy(itemRevision = revision)
}

object Calendar extends UnsignedSimpleItem.Companion[Calendar]
{
  val cls = classOf[Calendar]

  @TestOnly
  private[calendar] val orderIdToDatePatternDefault = "#([^#]+)#.*"

  type Key = CalendarPath
  val Key = CalendarPath

  override type Path = CalendarPath
  override val Path = CalendarPath

  type ItemState = CalendarState

  @TestOnly
  def jocStandard(
    path: CalendarPath,
    timezone: Timezone,
    dateOffset: FiniteDuration = Duration.Zero,
    itemRevision: Option[ItemRevision] = None)
  : Calendar =
    apply(path, timezone, dateOffset,
      orderIdToDatePattern = orderIdToDatePatternDefault,
      periodDatePattern = "yyyy-MM-dd",
      itemRevision = itemRevision)

  def apply(
    path: CalendarPath,
    timezone: Timezone,
    dateOffset: FiniteDuration = Duration.Zero,
    orderIdToDatePattern: String,
    periodDatePattern: String,
    itemRevision: Option[ItemRevision] = None)
  : Calendar =
    checked(path, timezone, dateOffset, orderIdToDatePattern, periodDatePattern, itemRevision)
      .orThrow

  @TestOnly
  def daily(
    path: CalendarPath,
    timezone: Timezone,
    dateOffset: FiniteDuration = Duration.Zero,
    itemRevision: Option[ItemRevision] = None)
  : Calendar =
    checked(
      path, timezone, dateOffset,
      orderIdToDatePattern = orderIdToDatePatternDefault,
      periodDatePattern = "yyyy-MM-dd",
      itemRevision
    ).orThrow

  def checked(
    path: CalendarPath,
    timezone: Timezone,
    dateOffset: FiniteDuration,
    orderIdToDatePattern: String,
    periodDatePattern: String,
    itemRevision: Option[ItemRevision] = None)
  : Checked[Calendar] =
    if (dateOffset.isNegative)
      Left(Problem.pure("Invalid Calender arguments"))
    else
      Right(
        new Calendar(path, timezone, dateOffset, orderIdToDatePattern, periodDatePattern,
          itemRevision))

  implicit val jsonCodec: Codec.AsObject[Calendar] = {
    intelliJuseImport(FiniteDurationJsonEncoder)
    implicit val configuration: Configuration = Configuration.default.withDefaults
    deriveConfiguredCodec[Calendar]
  }
}
