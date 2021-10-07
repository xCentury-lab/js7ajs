package js7.base.time

import java.time.DayOfWeek.{MONDAY, SATURDAY, TUESDAY}
import java.time.LocalTime.MIDNIGHT
import java.time.{LocalDate, LocalDateTime, LocalTime}
import js7.base.time.AdmissionPeriodForJavaTime._
import js7.base.time.ScalaTime._
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.FiniteDuration

final class AdmissionPeriodForJavaTimeTest extends AnyFreeSpec
{
  "WeekdayPeriod" - {
    val weekdayPeriod = WeekdayPeriod(TUESDAY, LocalTime.of(8, 0), 2.h)
    val admissionPeriod: AdmissionPeriod = weekdayPeriod

    "secondOfWeek" in {
      assert(weekdayPeriod.secondOfWeek == 1*24*3600 + 8*3600)
    }

    "dayNumber, dayName" in {
      assert(weekdayPeriod.dayNumber == 2)
      assert(weekdayPeriod.dayName == "Tuesday")
    }

    "secondsSinceStart" in {
      val jwp = JavaWeekdayPeriod(weekdayPeriod)
      assert(jwp.secondsSinceStart(localDT("2021-08-23T00:00")) == -(24+8)*3600)
      assert(jwp.secondsSinceStart(localDT("2021-08-23T01:00")) == -(24+7)*3600)
      assert(jwp.secondsSinceStart(localDT("2021-08-24T00:00")) == -8*3600)
      assert(jwp.secondsSinceStart(localDT("2021-08-24T08:00")) == 0)
      assert(jwp.secondsSinceStart(localDT("2021-08-24T09:00")) == 3600)
      assert(jwp.secondsSinceStart(localDT("2021-08-29T00:00")) == (4*24+16)*3600)
    }

    "toInterval" in {
      assert(admissionPeriod.toInterval(localDT("2021-08-23T00:00")) ==
        Some(LocalInterval(localDT("2021-08-24T08:00"), 2.h)))
    }

    "toInterval may return past period" in {
      // Past periods will be deleted by the caller. For easier code.
      assert(admissionPeriod.toInterval(localDT("2021-08-29T00:00")) ==
        Some(LocalInterval(localDT("2021-08-24T08:00"), 2.h)))
    }

    "calendarStart" in {
      assert(admissionPeriod.calendarStart(localDT("2021-08-23T00:00")) ==
        localDT("2021-08-23T00:00"))
      assert(admissionPeriod.calendarStart(localDT("2021-08-29T23:59")) ==
        localDT("2021-08-23T00:00"))
    }

    "nextCalendarStart" in {
      assert(admissionPeriod.nextCalendarStart(localDT("2021-08-23T00:00")) ==
        localDT("2021-08-30T00:00"))
      assert(admissionPeriod.nextCalendarStart(localDT("2021-08-29T23:59")) ==
        localDT("2021-08-30T00:00"))
    }

    "hasPeriodForDay" - {
      "period within a day" in {
        assert(!admissionPeriod.hasPeriodForDay(LocalDate.parse("2021-08-23")))
        assert(admissionPeriod.hasPeriodForDay(LocalDate.parse("2021-08-24")))
        assert(!admissionPeriod.hasPeriodForDay(LocalDate.parse("2021-08-25")))
      }

      "period starts at midnight" in {
        val weekdayPeriod = WeekdayPeriod(MONDAY, MIDNIGHT, 1.h)
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-29")))  // Sun
        assert(weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-30")))   // Mon
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-31")))  // Tue
      }

      "period ends at midnight" in {
        val weekdayPeriod = WeekdayPeriod(MONDAY, LocalTime.of(23, 0), 1.h)
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-29")))  // Sun
        assert(weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-30")))   // Mon
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-31")))  // Tue
      }

      "period over midnight" in {
        // Saturday 23:00 until Monday 02:00
        val weekdayPeriod = WeekdayPeriod(SATURDAY, LocalTime.of(23, 0), (24 + 3).h)
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-27")))  // Fri
        assert(weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-28")))   // Sat
        assert(weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-29")))   // Sun
        assert(weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-30")))   // Mon
        assert(!weekdayPeriod.hasPeriodForDay(LocalDate.parse("2021-08-31")))  // Tue
      }
    }
  }

  "DailyPeriod" - {
    val dailyPeriod = DailyPeriod(LocalTime.of(8, 0), 2.h)
    val admissionPeriod: AdmissionPeriod = dailyPeriod

    "secondOfDay" in {
      assert(dailyPeriod.secondOfDay == 8*3600)
    }

    "secondsSinceStart" in {
      val period = JavaDailyPeriod(dailyPeriod)
      assert(period.secondsSinceStart(localDT("2021-10-01T00:00")) == -8*3600)
      assert(period.secondsSinceStart(localDT("2021-10-01T01:00")) == -7*3600)
      assert(period.secondsSinceStart(localDT("2021-10-02T00:00")) == -8*3600)
      assert(period.secondsSinceStart(localDT("2021-10-02T08:00")) == 0)
      assert(period.secondsSinceStart(localDT("2021-10-02T09:00")) == 3600)
      assert(period.secondsSinceStart(localDT("2021-08-29T00:00")) == -8*3600)
    }

    "toInterval" in {
      assert(admissionPeriod.toInterval(localDT("2021-10-01T00:00")) ==
        Some(LocalInterval(localDT("2021-10-01T08:00"), 2.h)))
    }

    //"toInterval may return past period" in {
    //  // Past periods will be deleted by the caller. For easier code.
    //  assert(admissionPeriod.toInterval(localDT("2021-10-02T00:00")) ==
    //    Some(LocalInterval(localDT("2021-10-01T08:00"), 2.h)))
    //}

    "calendarStart" in {
      assert(admissionPeriod.calendarStart(localDT("2021-10-01T00:00")) ==
        localDT("2021-10-01T00:00"))
      assert(admissionPeriod.calendarStart(localDT("2021-10-01T23:59")) ==
        localDT("2021-10-01T00:00"))
    }

    "nextCalendarStart" in {
      assert(admissionPeriod.nextCalendarStart(localDT("2021-10-01T00:00")) ==
        localDT("2021-10-02T00:00"))
      assert(admissionPeriod.nextCalendarStart(localDT("2021-10-01T23:59")) ==
        localDT("2021-10-02T00:00"))
    }

    "hasPeriodForDay" - {
      "period within a day" in {
        assert(admissionPeriod.hasPeriodForDay(LocalDate.parse("2021-10-01")))

        val empty = DailyPeriod.checked(1, 0.s)
        assert(empty.isLeft)
      }

      "period starts at midnight" in {
        val dailyPeriod = DailyPeriod(MIDNIGHT, 1.h)
        assert(dailyPeriod.hasPeriodForDay(LocalDate.parse("2021-08-30")))
      }

      "period ends at midnight" in {
        val dailyPeriod = DailyPeriod(LocalTime.of(23, 0), 1.h)
        assert(dailyPeriod.hasPeriodForDay(LocalDate.parse("2021-08-30")))   // Mon
      }

      "period over midnight" in {
        val dailyPeriod = DailyPeriod(LocalTime.of(23, 0), 24.h)
        assert(dailyPeriod.hasPeriodForDay(LocalDate.parse("2021-08-28")))   // Sat
      }
    }
  }

  "AlwaysPeriod" - {
    val admissionPeriod: AdmissionPeriod = AlwaysPeriod

    "toInterval" in {
      assert(admissionPeriod.toInterval(localDT("2021-10-01T00:00")) ==
        Some(LocalInterval(localDT("2021-10-01T00:00"), FiniteDuration.MaxValue)))
    }

    "calendarStart (not used)" in {
      assert(admissionPeriod.calendarStart(localDT("2021-10-01T00:00")) ==
        localDT("2021-10-01T00:00"))
      assert(admissionPeriod.calendarStart(localDT("2021-10-01T23:59")) ==
        localDT("2021-10-01T23:59"))
    }

    "nextCalendarStart (not used)" in {
      assert(admissionPeriod.nextCalendarStart(localDT("2021-10-01T00:00")) ==
        localDT("2021-10-01T00:00:01"))
    }

    "hasPeriodForDay" in {
      assert(admissionPeriod.hasPeriodForDay(LocalDate.parse("2021-10-01")))
    }
  }

  private def localDT(localDateTime: String) =
    LocalDateTime.parse(localDateTime)
}
