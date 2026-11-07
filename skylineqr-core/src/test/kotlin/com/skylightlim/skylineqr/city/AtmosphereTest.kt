package com.skylightlim.skylineqr.city

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereTest {

  private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

  @Test
  fun theCodesPaperCarriesTheSkyButStaysLight() {
    for (time in TimeOfDay.entries) for (weather in Weather.entries) for (minute in 0 until 1440 step 20) {
      val air = Atmospheres.at(time, weather, minute.toFloat())
      val paper = air.paper
      assertTrue("$time $weather $minute: paper luma ${luma(paper)}", luma(paper) >= 0.8f)
      // Tinted, not white, at every fixed hour. (Auto passes briefly near neutral as one hour's tint
      // blends into the next.)
      if (time != TimeOfDay.AUTO) assertTrue("$time $weather: paper is white", paper.max() - paper.min() > 0.04f)
      // The surround is darker than the paper, so the code stands out from the screen around it.
      assertTrue(luma(Atmospheres.surround(air)) < luma(paper) - 0.1f)
    }
  }

  @Test
  fun autoMovesSmoothlyThroughTheDay() {
    var last = Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, 0f)
    for (minute in 1..1440) {
      val now = Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, minute.toFloat())
      for (i in 0..2) {
        assertTrue("sky jumps at $minute", abs(now.skyTop[i] - last.skyTop[i]) < 0.02f)
        assertTrue("sun jumps at $minute", abs(now.sunDir[i] - last.sunDir[i]) < 0.05f)
      }
      assertTrue("night jumps at $minute", abs(now.night - last.night) < 0.02f)
      last = now
    }
  }

  @Test
  fun fixedTimesAreAutoHeldAtTheirMinute() {
    for (time in listOf(TimeOfDay.DAWN, TimeOfDay.DAY, TimeOfDay.DUSK, TimeOfDay.NIGHT)) {
      val fixed = Atmospheres.at(time, Weather.CLEAR, 0f)
      val auto = Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, Atmospheres.minuteOf(time, 0f))
      assertTrue(fixed.skyTop.contentEquals(auto.skyTop))
      assertEquals(fixed.night, auto.night, 0f)
    }
  }

  @Test
  fun lightComesFromAboveAndWeatherDimsIt() {
    for (minute in 0 until 1440 step 10) {
      val a = Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, minute.toFloat())
      assertTrue("light from below at $minute", a.sunDir[1] > 0.05f)
      assertEquals(1f, sqrt(a.sunDir.sumOf { (it * it).toDouble() }).toFloat(), 1e-4f)
    }
    val clear = Atmospheres.at(TimeOfDay.DAY, Weather.CLEAR, 0f)
    for (weather in listOf(Weather.RAIN, Weather.STORM, Weather.HAZE)) {
      assertTrue(luma(Atmospheres.at(TimeOfDay.DAY, weather, 0f).sunColor) < luma(clear.sunColor))
    }
    assertTrue(Atmospheres.at(TimeOfDay.DAY, Weather.STORM, 0f).storm > 0f)
    assertEquals(0f, clear.rain, 0f)
  }
}
