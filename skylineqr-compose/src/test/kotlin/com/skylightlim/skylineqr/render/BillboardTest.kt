package com.skylightlim.skylineqr.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillboardTest {

  /** Two messages that fit (one a hair over, as drawing to fit can leave it) and one that scrolls. */
  private val board = Billboard(floatArrayOf(0.1462f, 0.12f, 0.31f), 0.1458f)
  private val out = FloatArray(Billboard.FLOATS)

  @Test
  fun everyMessageComesRoundAndTheLongOneScrollsThroughToItsEnd() {
    val seen = HashSet<Int>()
    var lowest = Float.MAX_VALUE
    var highest = -Float.MAX_VALUE
    var t = 0f
    while (t < 40f) {
      board.state(t, calm = false, out)
      val row = out[0].toInt()
      seen += row
      assertTrue(out[8] in 0f..1f)
      assertEquals(1f, out[10], 0f)
      if (row == 2 && out[8] == 0f) {
        lowest = minOf(lowest, out[1])
        highest = maxOf(highest, out[1])
      } else if (row != 2) {
        // A message that fits sits still, centred.
        assertEquals((out[2] - 0.1458f) / 2f, out[1], 1e-6f)
      }
      t += 0.01f
    }
    assertEquals(setOf(0, 1, 2), seen)
    assertEquals(0f, lowest, 1e-6f)
    assertEquals(0.31f - 0.1458f, highest, 1e-4f)
  }

  @Test
  fun calmNeitherScrollsNorFlips() {
    var t = 0f
    while (t < 40f) {
      board.state(t, calm = true, out)
      assertEquals(0f, out[8], 0f)
      if (out[0].toInt() == 2) assertEquals(0f, out[1], 0f)
      t += 0.05f
    }
  }

  @Test
  fun noMessagesNoScreen() {
    Billboard.NONE.state(3f, calm = false, out)
    assertEquals(0f, out[10], 0f)
  }
}
