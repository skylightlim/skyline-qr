package com.skylightlim.skylineqr.city

import java.util.Calendar
import java.util.TimeZone

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

  /** [NONE], [SHOW] or [FINALE] at [millis] in [zone]. */
  fun at(millis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
    val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
    val month = c.get(Calendar.MONTH)
    val day = c.get(Calendar.DAY_OF_MONTH)
    val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    val evening = minute >= 20 * 60
    val afterMidnight = minute < 30
    return when {
      // The eve, then the first minutes of the day itself, the first ten of them the finale.
      (month == Calendar.AUGUST && day == 30 || month == Calendar.DECEMBER && day == 31) && evening -> SHOW
      (month == Calendar.AUGUST && day == 31 || month == Calendar.JANUARY && day == 1) && afterMidnight -> if (minute < 10) FINALE else SHOW
      month == Calendar.FEBRUARY && day == 1 && evening -> SHOW
      else -> NONE
    }
  }
}
