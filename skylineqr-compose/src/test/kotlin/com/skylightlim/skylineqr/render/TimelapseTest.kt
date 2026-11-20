package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelapseTest {

  private val frames = (Timelapse.LENGTH * FPS).toInt()

  private fun at(frame: Int): Atmosphere = Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, Timelapse.minute(frame / FPS))

  @Test
  fun runsFromDayToNightAndNeverBack() {
    assertEquals(Atmospheres.minuteOf(TimeOfDay.DAY, 0f), Timelapse.minute(0f), 1e-3f)
    assertEquals(Atmospheres.minuteOf(TimeOfDay.NIGHT, 0f), Timelapse.minute(Timelapse.LENGTH), 1e-3f)
    var last = Timelapse.minute(0f)
    for (frame in 1..frames) {
      val minute = Timelapse.minute(frame / FPS)
      assertTrue("the clock runs back at frame $frame", minute >= last)
      // Never faster than about two hours a second, so the shadows sweep rather than jump.
      assertTrue("the clock jumps at frame $frame", minute - last < 120f / FPS)
      last = minute
    }
    // It starts and ends at rest.
    assertTrue(Timelapse.minute(1f / FPS) - Timelapse.minute(0f) < 1f)
    assertTrue(Timelapse.minute(Timelapse.LENGTH) - Timelapse.minute(Timelapse.LENGTH - 1f / FPS) < 1f)
  }

  @Test
  fun nothingInTheSkyJumps() {
    var last = at(0)
    for (frame in 1..frames) {
      val now = at(frame)
      for (i in 0..2) {
        assertTrue("sky jumps at frame $frame", abs(now.skyTop[i] - last.skyTop[i]) < 0.01f)
        assertTrue("light jumps at frame $frame", abs(now.sunDir[i] - last.sunDir[i]) < 0.02f)
      }
      assertTrue("windows jump at frame $frame", abs(now.night - last.night) < 0.01f)
      assertTrue("sun jumps at frame $frame", abs(now.sun.altitude - last.sun.altitude) < 0.02f)
      // The moon rises as the sky darkens; it used to appear all at once, high up.
      assertTrue("moon jumps at frame $frame", abs(now.moon.altitude - last.moon.altitude) < 0.02f)
      last = now
    }
  }

  @Test
  fun itEndsOnNightsSky() {
    val end = at(frames)
    val night = Atmospheres.at(TimeOfDay.NIGHT, Weather.CLEAR, 0f)
    assertArrayEquals(night.skyTop, end.skyTop, 1e-5f)
    assertArrayEquals(night.sunDir, end.sunDir, 1e-5f)
    assertArrayEquals(night.paper, end.paper, 1e-5f)
    assertEquals(night.night, end.night, 1e-5f)
    assertEquals(night.moon.altitude, end.moon.altitude, 1e-5f)
    assertEquals(night.moon.azimuth, end.moon.azimuth, 1e-5f)
  }

  private companion object {
    const val FPS = 60f
  }
}
