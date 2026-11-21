package com.skylightlim.skylineqr.city

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sun and the moon really stand over Kuala Lumpur at an instant (2026-11-07, at the
 * user's request: in Now the sun and the moon stand as high as they really do). The Astronomical
 * Almanac's low-precision formulas, good from 1950 to 2050: the sun to about a hundredth of a
 * degree, the moon from its six largest terms to about a third of a degree, far finer than a disc
 * in the sky can show. The moon's altitude is seen from the city, not the earth's centre, which
 * lowers it by up to a degree.
 *
 * Presentation only, like the rest of [Atmosphere], so it never enters a city's identity and may
 * use kotlin.math.
 */
object SunAndMoon {

  /** Kuala Lumpur, which every city is drawn as. Degrees, north and east. */
  const val LATITUDE = 3.139
  const val LONGITUDE = 101.687

  /** How high over the horizon and which way, in radians: azimuth from north, east at π/2. */
  class Place(val altitude: Float, val azimuth: Float)

  fun sun(millis: Long): Place {
    val n = daysSinceJ2000(millis)
    val l = 280.460 + 0.9856474 * n
    val g = rad(357.528 + 0.9856003 * n)
    val longitude = rad(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
    val obliquity = rad(23.439 - 0.0000004 * n)
    val ra = atan2(cos(obliquity) * sin(longitude), cos(longitude))
    val dec = asin(sin(obliquity) * sin(longitude))
    return place(ra, dec, n, parallax = 0.0)
  }

  fun moon(millis: Long): Place {
    val n = daysSinceJ2000(millis)
    val t = n / 36525.0
    fun s(a: Double, b: Double) = sin(rad(a + b * t))
    fun c(a: Double, b: Double) = cos(rad(a + b * t))
    val longitude = rad(
      218.32 + 481267.881 * t +
        6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) + 0.66 * s(235.7, 890534.22) +
        0.21 * s(269.9, 954397.74) - 0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03)
    )
    val latitude = rad(5.13 * s(93.3, 483202.02) + 0.28 * s(228.2, 960400.89) - 0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21))
    val parallax = rad(0.9508 + 0.0518 * c(135.0, 477198.87) + 0.0095 * c(259.3, -413335.36) + 0.0078 * c(235.7, 890534.22) + 0.0028 * c(269.9, 954397.74))
    // Ecliptic to equatorial, with the obliquity of J2000.
    val x = cos(latitude) * cos(longitude)
    val y = 0.9175 * cos(latitude) * sin(longitude) - 0.3978 * sin(latitude)
    val z = 0.3978 * cos(latitude) * sin(longitude) + 0.9175 * sin(latitude)
    return place(atan2(y, x), asin(z.coerceIn(-1.0, 1.0)), n, parallax)
  }

  /**
   * How much of the moon's face is lit at [millis], from 0 at new moon to 1 when full: half of one
   * minus the cosine of its angle from the sun.
   */
  fun lit(millis: Long): Float {
    val sun = sun(millis)
    val moon = moon(millis)
    val apart =
      sin(sun.altitude.toDouble()) * sin(moon.altitude.toDouble()) +
        cos(sun.altitude.toDouble()) * cos(moon.altitude.toDouble()) * cos((sun.azimuth - moon.azimuth).toDouble())
    return ((1.0 - apart) / 2.0).toFloat()
  }

  /**
   * Where a body at right ascension [ra] and declination [dec] stands from the city [n] days after
   * J2000, lowered by its horizontal [parallax].
   */
  private fun place(ra: Double, dec: Double, n: Double, parallax: Double): Place =
    horizon(rad(280.46061837 + 360.98564736629 * n + LONGITUDE) - ra, dec, parallax)

  /** From the city's horizon: a body at hour angle [hour] and declination [dec], lowered by [parallax]. */
  private fun horizon(hour: Double, dec: Double, parallax: Double): Place {
    val lat = rad(LATITUDE)
    val geocentric = asin((sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hour)).coerceIn(-1.0, 1.0))
    val altitude = geocentric - parallax * cos(geocentric)
    val azimuth = atan2(-cos(dec) * sin(hour), sin(dec) * cos(lat) - cos(dec) * cos(hour) * sin(lat))
    return Place(altitude.toFloat(), (((azimuth % TAU) + TAU) % TAU).toFloat())
  }

  /**
   * Where a sun on a fixed path stands at [minute] of the clock: rising at [Atmospheres.SUNRISE],
   * setting at [Atmospheres.SUNSET], noon between them, passing a little south of overhead as it
   * does in Kuala Lumpur for most of the year. For the fixed hours, which stand for any day.
   */
  fun sunOnTheClock(minute: Float): Place {
    val noon = (Atmospheres.SUNRISE + Atmospheres.SUNSET) / 2f
    val halfDay = (Atmospheres.SUNSET - Atmospheres.SUNRISE) / 2f
    val dec = rad(CLOCK_DECLINATION)
    // The hour angle at which this path meets the horizon, stretched over the clock's own day.
    val setting = kotlin.math.acos(-kotlin.math.tan(rad(LATITUDE)) * kotlin.math.tan(dec))
    return horizon((minute - noon) / halfDay * setting, dec, 0.0)
  }

  /** The fixed path's declination: a little south, as in Kuala Lumpur from September to March. */
  private const val CLOCK_DECLINATION = -10.0
  private const val TAU = 2 * PI

  private fun daysSinceJ2000(millis: Long) = millis / 86_400_000.0 + 2440587.5 - 2451545.0

  private fun rad(degrees: Double) = degrees * PI / 180.0
}
