package com.skylightlim.skylineqr.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphTest {

  @Test
  fun curveRunsFromZeroToOneWithoutGoingBack() {
    assertEquals(0f, Morph.curve(0f), 0f)
    assertEquals(1f, Morph.curve(1f), 0f)
    var last = 0f
    for (i in 1..100) {
      val y = Morph.curve(i / 100f)
      assertTrue("curve dips at ${i / 100f}", y >= last - 1e-6f)
      last = y
    }
  }

  @Test
  fun aFullMorphTakesUnderASecondAndSettles() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    assertEquals(0f, morph.at(0), 1e-6f)
    assertEquals(0.5f, morph.at(450_000_000), 1e-4f)
    assertTrue(!morph.settled(850_000_000))
    assertEquals(1f, morph.at(900_000_000), 1e-6f)
    assertTrue(morph.settled(900_000_000))
  }

  @Test
  fun theCameraArrivesFirstAndTheBlocksKeepLanding() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    // Overhead in about half a second, as quick as the ICQR tree.
    assertEquals(1f, Morph.swing(morph.at(500_000_000)), 1e-6f)
    // The fold starts almost at once and is still running when the camera is there.
    assertEquals(0f, Morph.fold(morph.at(50_000_000)), 1e-6f)
    assertTrue(Morph.fold(morph.at(150_000_000)) > 0f)
    assertTrue(Morph.fold(morph.at(500_000_000)) in 0.3f..0.7f)
    assertEquals(1f, Morph.fold(morph.at(900_000_000)), 1e-6f)
  }

  @Test
  fun reversingMidWayContinuesFromWhereItIs() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    val partway = morph.at(300_000_000)
    morph.aim(flat = false, nowNanos = 300_000_000)
    assertEquals(partway, morph.at(300_000_000), 1e-6f) // no jump on the reversal frame
    assertTrue(!morph.settled(300_000_000))
    // Back the way it came, in the time it took to get there.
    assertEquals(0f, morph.at(600_000_000), 1e-6f)
    assertTrue(morph.settled(600_000_000))
  }

  @Test
  fun finishJumpsStraightToTheCode() {
    val morph = Morph()
    morph.finish(flat = true)
    assertEquals(1f, morph.at(0), 0f)
    assertTrue(morph.settled(0))
  }
}
