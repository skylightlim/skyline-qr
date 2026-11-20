package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.Vehicles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireworksTest {

  private fun boxes(emit: (Vehicles.Sink) -> Unit): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    emit { x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper -> out += floatArrayOf(x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper) }
    return out
  }

  @Test
  fun aVolleyClimbsBurstsAndBurnsOut() {
    val fireworks = Fireworks()
    fireworks.volley(10f, 1f, 2f, 1f, 12f, 2f, seed = 3)
    // Climbing: only the first shell is up, below where it bursts.
    val climbing = boxes { fireworks.emit(it, 10.4f) }
    assertTrue(climbing.isNotEmpty() && climbing.all { it[1] < 14f })
    // All three have burst by now, high over the city, spread out.
    val burning = boxes { fireworks.emit(it, 10.7f + Fireworks.RISE + 0.5f) }
    assertTrue("${burning.size} boxes", burning.size > 100)
    for (b in burning) {
      assertEquals(CityPlanner.ROLE_SPARK, b[6])
      assertTrue(b.all { it.isFinite() } && b[3] > 0f && b[4] > 0f && b[5] > 0f)
      assertTrue("a spark at ${b[1]}", b[1] > 6f)
      assertTrue(b[8] >= 0.1f)
    }
    assertTrue(boxes { fireworks.emit(it, 20f) }.isEmpty())
    assertFalse(fireworks.busy)
  }

  @Test
  fun goldIsTheMostCommonColour() {
    val colours = (0 until 1000).groupingBy { Fireworks.colourOf(it) }.eachCount()
    assertEquals(0.3f, colours.maxBy { it.value }.key)
    assertEquals(5, colours.size)
  }

  @Test
  fun theFountainsWaterIsFiniteAndStaysOverItsPool() {
    val at = floatArrayOf(5f, 2.5f, -3f)
    for (k in 0 until 50) {
      for (b in boxes { Fountain.emit(it, at, k * 0.13f) }) {
        assertTrue(b.all { it.isFinite() } && b[3] > 0f)
        assertTrue(kotlin.math.hypot(b[0] - at[0], b[2] - at[2]) < 1.0f)
        assertTrue(b[1] > 0f && b[1] < at[1] + 2f)
        assertTrue(b[8] < 0.1f)
      }
    }
  }
}
