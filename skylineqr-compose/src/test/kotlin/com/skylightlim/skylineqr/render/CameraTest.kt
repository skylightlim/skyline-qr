package com.skylightlim.skylineqr.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraTest {

  /** Projects a world point to the screen fractions a touch would report. */
  private fun screen(pose: Pose, p: FloatArray): Pair<Float, Float> {
    val m = pose.viewProjection
    val x = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12]
    val y = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13]
    val w = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15]
    return (x / w + 1f) / 2f to (1f - y / w) / 2f
  }

  @Test
  fun aTouchLandsWhereThatPointOfTheCityIsDrawn() {
    val pose = Camera.pose(null, 0.45f, 0f, 0f)
    for (target in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(6f, 0f, -4f), floatArrayOf(-9f, 0f, 7f))) {
      val (u, v) = screen(pose, target)
      val hit = assertNotNull(pose.ground(u, v)).let { pose.ground(u, v)!! }
      assertEquals(target[0], hit[0], 0.01f)
      assertEquals(target[2], hit[2], 0.01f)
    }
  }

  @Test
  fun theSkyIsNotTheGround() {
    // Even tilted lowest the camera still looks down at the top edge, so aim well above the screen.
    val pose = Camera.pose(null, 0.45f, 0f, 0f, View(elevation = View.MIN_ELEVATION))
    assertNull(pose.ground(0.5f, -1f))
  }
}
