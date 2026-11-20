package com.skylightlim.skylineqr.render.tour

import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import com.skylightlim.skylineqr.render.Camera
import com.skylightlim.skylineqr.render.Morph
import com.skylightlim.skylineqr.render.View
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What any edit of TourScript.kt must keep true (see README.md beside it): under twenty seconds with
 * the fold, landing exactly on the city's own view, never inside the city, no jumps, the same
 * every time.
 */
class TourTest {

  private val aspect = 0.45f

  private fun city(url: String, preset: CityPreset) =
    CityIdentity.of(url).let { CityPlanner.plan(QrEncoder.encode(it.link.payload), it, preset) }

  private val cities by lazy {
    listOf("https://github.com/skylightlim/skyline-qr", "https://um.edu.my/", "https://a.co/").flatMap { url -> CityPreset.entries.map { city(url, it) } }
  }

  @Test
  fun everySightTheCityHasIsVisitedAndThePlaneComesLast() {
    for (city in cities) {
      val sights = Tour(city, aspect).sights
      if (city.preset == CityPreset.KUALA_LUMPUR) {
        for (s in listOf(Sight.SUPERTALL, Sight.TELECOM_TOWER, Sight.TWIN_TOWERS, Sight.GARDEN, Sight.SQUARE, Sight.FOUNTAIN)) {
          assertTrue("$s missing", s in sights)
        }
        // A version 1 code has no crossing where the longer monorail (2026-11-12) passes over every lit junction.
        assertEquals(city.crossing != null, Sight.CROSSING in sights)
        assertTrue(Sight.TALLEST_TOWER !in sights)
        if (city.movers.any { it.kind == com.skylightlim.skylineqr.city.Mover.MONORAIL }) assertTrue(Sight.MONORAIL in sights)
      } else {
        assertTrue(Sight.TALLEST_TOWER in sights && Sight.SUPERTALL !in sights)
      }
      assertEquals(Sight.PLANE, sights.last())
      assertEquals(sights.size, sights.distinct().size)
    }
  }

  @Test
  fun theTourAndItsFoldTakeUnderTwentySeconds() {
    for (city in cities) {
      val tour = Tour(city, aspect)
      assertTrue("${tour.length} s", tour.length + Morph.DURATION_NANOS / 1e9f < 20f)
    }
  }

  @Test
  fun itLeavesAndLandsExactlyWhereTheCitysOwnCameraIs() {
    for (city in cities) {
      val tour = Tour(city, aspect)
      for ((t, own) in listOf(0f to Camera.pose(city, aspect, 0f, 0f, View()), tour.length to Camera.pose(city, aspect, 0f, tour.length, View()))) {
        val pose = tour.pose(t)
        for (i in 0..2) {
          assertTrue("eye at $t", abs(pose.eye[i] - own.eye[i]) < 1e-3f)
          assertTrue("looking at $t", abs(pose.fwd[i] - own.fwd[i]) < 1e-3f)
        }
        assertTrue(abs(pose.shiftY - own.shiftY) < 1e-4f)
      }
    }
  }

  @Test
  fun theCameraGoesRoundTheCityNeverThroughItAndMovesSmoothly() {
    for (city in cities) {
      val tour = Tour(city, aspect)
      var last = tour.pose(0f).eye
      var t = 0.02f
      while (t <= tour.length) {
        val eye = tour.pose(t).eye
        assertTrue("eye inside the city at $t", hypot(eye[0], eye[2]) > city.modules / 2f)
        assertTrue("eye under ground at $t", eye[1] > 1f)
        // No jumps: even diving in from the city's own view, far out, it moves under 150 units a second.
        assertTrue("jumped at $t", hypot(hypot(eye[0] - last[0], eye[1] - last[1]), eye[2] - last[2]) < 3f)
        last = eye
        t += 0.02f
      }
      // The same city always gets the same tour.
      val again = Tour(city, aspect)
      for (k in 0..20) assertTrue(tour.pose(k * 0.7f).eye.contentEquals(again.pose(k * 0.7f).eye))
    }
  }
}
