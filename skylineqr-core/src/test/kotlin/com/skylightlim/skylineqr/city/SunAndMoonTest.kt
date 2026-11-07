package com.skylightlim.skylineqr.city

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunAndMoonTest {

  /**
   * Instants, then the sun's and the moon's altitude and azimuth over Kuala Lumpur in degrees, from
   * PyEphem 4.2 (no refraction, sea level). Over a week of instants every 37 minutes the worst
   * differences were 0.006 degrees for the sun and 0.11 (altitude) and 0.37 (azimuth) for the moon.
   */
  private val reference =
    listOf(
      longArrayOf(1794024000000) to doubleArrayOf(66.04, 144.49, 71.33, 216.53), // 7 Nov 2026, noon in KL
      longArrayOf(1794051000000) to doubleArrayOf(-8.82, 253.93, -30.85, 256.47), // 7 Nov, 19:30
      longArrayOf(1794006600000) to doubleArrayOf(2.26, 106.37, 28.61, 103.89), // 7 Nov, 07:10
      longArrayOf(1805520000000) to doubleArrayOf(86.61, 176.35, -59.74, 62.88), // 20 Mar 2027, near noon
      longArrayOf(1794661200000) to doubleArrayOf(-29.96, 250.64, 26.17, 240.01), // 14 Nov 2026, 21:00
    )

  @Test
  fun theSunAndTheMoonStandWhereAnEphemerisPutsThem() {
    for ((at, want) in reference) {
      val sun = SunAndMoon.sun(at[0])
      val moon = SunAndMoon.moon(at[0])
      assertEquals(want[0], degrees(sun.altitude), 0.05)
      assertEquals(0.0, turn(degrees(sun.azimuth), want[1]), if (want[0] > 80) 1.0 else 0.05)
      assertEquals(want[2], degrees(moon.altitude), 0.5)
      assertEquals(0.0, turn(degrees(moon.azimuth), want[3]), 0.6)
    }
  }

  @Test
  fun aLunarEclipseHasTheMoonOppositeTheSun() {
    // Greatest eclipse of 3 March 2026, 11:33 UT. Seen from the city the moon sits up to a degree
    // lower than from the earth's centre.
    val at = 1772537580000
    val sun = SunAndMoon.sun(at)
    val moon = SunAndMoon.moon(at)
    val apart = Math.toDegrees(kotlin.math.acos(dot(direction(sun), direction(moon)).coerceIn(-1.0, 1.0)))
    assertTrue("sun and moon $apart degrees apart", apart > 178.0)
  }

  @Test
  fun theFixedPathRisesAtSunriseAndSetsAtSunset() {
    assertEquals(0.0, degrees(SunAndMoon.sunOnTheClock(Atmospheres.SUNRISE.toFloat()).altitude), 1.0)
    assertEquals(0.0, degrees(SunAndMoon.sunOnTheClock(Atmospheres.SUNSET.toFloat()).altitude), 1.0)
    val noon = SunAndMoon.sunOnTheClock((Atmospheres.SUNRISE + Atmospheres.SUNSET) / 2f)
    assertTrue(degrees(noon.altitude) in 75.0..80.0)
    assertEquals(180.0, degrees(noon.azimuth), 0.5)
    // Morning in the east, evening in the west.
    assertTrue(degrees(SunAndMoon.sunOnTheClock(9 * 60f).azimuth) in 60.0..150.0)
    assertTrue(degrees(SunAndMoon.sunOnTheClock(17 * 60f).azimuth) in 210.0..300.0)
  }

  @Test
  fun theMoonIsLitByHowFarItStandsFromTheSun() {
    // On 7 November 2026 it is a thin crescent near new moon; at the lunar eclipse it is full.
    assertTrue(SunAndMoon.lit(1794024000000) < 0.12f)
    assertTrue(SunAndMoon.lit(1772537580000) > 0.99f)
  }

  private fun degrees(radians: Float) = Math.toDegrees(radians.toDouble())

  private fun turn(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

  private fun direction(p: SunAndMoon.Place): DoubleArray {
    val alt = p.altitude.toDouble()
    val az = p.azimuth.toDouble()
    return doubleArrayOf(kotlin.math.cos(alt) * kotlin.math.sin(az), kotlin.math.sin(alt), kotlin.math.cos(alt) * kotlin.math.cos(az))
  }

  private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
