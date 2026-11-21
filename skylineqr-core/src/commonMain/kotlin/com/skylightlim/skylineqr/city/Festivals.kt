package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.Platform

/**
 * The nights Kuala Lumpur lets off fireworks, for Now (2026-11-09, at the user's request): the eve
 * of independence day (into 31 August) and New Year's Eve, from 20:00 until half past midnight
 * with the finale at midnight, and Federal Territory Day (1 February) from 20:00 to midnight. The
 * real shows are shorter; these last the evening so they can be caught.
 */
object Festivals {

  const val NONE = 0
  const val SHOW = 1
  const val FINALE = 2

  /** [NONE], [SHOW] or [FINALE] at [millis], [offsetMillis] ahead of UTC (the phone's zone by default). */
  fun at(millis: Long, offsetMillis: Int = Platform.zoneOffsetMillis(millis)): Int {
    val local = millis + offsetMillis
    val (month, day) = monthDay(local.floorDiv(86_400_000L))
    val minute = (local.mod(86_400_000L) / 60_000L).toInt()
    val evening = minute >= 20 * 60
    val afterMidnight = minute < 30
    return when {
      // The eve, then the first minutes of the day itself, the first ten of them the finale.
      (month == 8 && day == 30 || month == 12 && day == 31) && evening -> SHOW
      (month == 8 && day == 31 || month == 1 && day == 1) && afterMidnight -> if (minute < 10) FINALE else SHOW
      month == 2 && day == 1 && evening -> SHOW
      else -> NONE
    }
  }

  /** The month (1 to 12) and day of the month [days] after 1970-01-01, in the Gregorian calendar. */
  internal fun monthDay(days: Long): Pair<Int, Int> {
    // Howard Hinnant's civil_from_days.
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return month to day
  }
}
