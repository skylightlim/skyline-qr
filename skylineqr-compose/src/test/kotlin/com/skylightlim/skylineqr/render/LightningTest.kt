package com.skylightlim.skylineqr.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightningTest {

  @Test
  fun everyFlashBelongsToOneStrikeAndClearSkiesStayDark() {
    var strikes = 0
    var last = -1
    var t = 0f
    while (t < 120f) {
      val flash = CityRenderer.lightning(t, 1f)
      val strike = CityRenderer.strikeOf(t, 1f)
      assertEquals("flash without a strike at $t", flash > 0f, strike >= 0)
      if (strike >= 0 && strike != last) {
        strikes++
        last = strike
      }
      assertEquals(0f, CityRenderer.lightning(t, 0f), 0f)
      t += 1f / 60f
    }
    // Nearly every 3.4 s spell strikes once: about 31 in two minutes.
    assertTrue("$strikes strikes in two minutes", strikes in 24..36)
  }
}
