package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.TimeOfDay

/**
 * The timelapse (2026-11-20, at the user's request): the clock from the middle of the day to night
 * in [LENGTH] seconds. It hurries through the afternoon and the deep of the night and lingers
 * where the light changes, over the sunset and the dusk, on one smooth curve through [KEYS], so
 * the sun and the shadows never lurch. It starts and ends at rest, on Day's minute and on Night's,
 * so it leaves the city exactly at Night.
 */
internal object Timelapse {

  const val LENGTH = 15f

  /** Seconds into the timelapse, and the minute of the day it shows then. */
  private val KEYS =
    arrayOf(
      0f to Atmospheres.minuteOf(TimeOfDay.DAY, 0f), // 12:30
      4.5f to 17 * 60 + 40f, // the afternoon light holds until here
      9f to 19 * 60 + 40f, // the sun has just set, at 19:20
      12f to 20 * 60 + 50f, // night has fallen
      LENGTH to Atmospheres.minuteOf(TimeOfDay.NIGHT, 0f), // 23:00
    )

  /**
   * The curve's speed at each key, in minutes a second: none at either end, and inside, the
   * Fritsch–Butland mean of the speeds either side, which never overshoots, so the clock never
   * runs backward.
   */
  private val SPEEDS =
    FloatArray(KEYS.size) { i ->
      if (i == 0 || i == KEYS.size - 1) return@FloatArray 0f
      val h0 = KEYS[i].first - KEYS[i - 1].first
      val h1 = KEYS[i + 1].first - KEYS[i].first
      val d0 = (KEYS[i].second - KEYS[i - 1].second) / h0
      val d1 = (KEYS[i + 1].second - KEYS[i].second) / h1
      3f * (h0 + h1) / ((2f * h1 + h0) / d0 + (h1 + 2f * h0) / d1)
    }

  /** The minute of the day the timelapse shows [seconds] after it began. */
  fun minute(seconds: Float): Float {
    val t = seconds.coerceIn(0f, LENGTH)
    var i = 0
    while (i < KEYS.size - 2 && t > KEYS[i + 1].first) i++
    val (t0, y0) = KEYS[i]
    val (t1, y1) = KEYS[i + 1]
    val h = t1 - t0
    val s = (t - t0) / h
    val s2 = s * s
    val s3 = s2 * s
    return (2f * s3 - 3f * s2 + 1f) * y0 + (s3 - 2f * s2 + s) * h * SPEEDS[i] + (3f * s2 - 2f * s3) * y1 + (s3 - s2) * h * SPEEDS[i + 1]
  }
}
