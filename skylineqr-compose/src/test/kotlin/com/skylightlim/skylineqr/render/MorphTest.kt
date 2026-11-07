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
      assertEquals("inverse at ${i / 100f}", i / 100f, Morph.curveInverse(y), 1e-4f)
      last = y
    }
  }

  @Test
  fun aFullMorphTakesUnderASecondAndSettles() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    assertEquals(0f, morph.fold(0), 1e-6f)
    assertTrue(!morph.settled(800_000_000))
    assertEquals(1f, morph.fold(850_000_000), 1e-6f)
    assertEquals(1f, morph.swing(850_000_000), 1e-6f)
    assertTrue(morph.settled(850_000_000))
  }

  @Test
  fun aVsyncJustBeforeTheTapDoesNotRunBackwards() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 100_000_000)
    assertEquals(0f, morph.swing(90_000_000), 0f)
    assertEquals(0f, morph.fold(90_000_000), 0f)
  }

  @Test
  fun theBlocksLandBeforeTheCameraSettles() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    // The fold starts at once and every block has landed by 60 % of the morph...
    assertTrue(morph.fold(60_000_000) > 0f)
    assertTrue(morph.fold(450_000_000) < 1f)
    assertEquals(1f, morph.fold(515_000_000), 1e-6f)
    // ...while the camera, most of the way there by a third of a second, eases out over the whole.
    assertTrue(morph.swing(120_000_000) > 0.45f)
    assertTrue(morph.swing(300_000_000) > 0.75f)
    assertTrue(morph.swing(650_000_000) < 1f)
  }

  @Test
  fun theWayBackIsTheRaiseAtOneSteadyPace() {
    val morph = Morph()
    morph.finish(flat = true)
    morph.aim(flat = false, nowNanos = 0)
    // Leaving the code takes the raise, whose timing the shader keeps: the fold just runs down.
    assertTrue(morph.raising)
    assertEquals(0.5f, morph.fold(425_000_000), 1e-4f)
    // The camera still leaves at once, and everything is home when the morph ends.
    assertTrue(morph.swing(100_000_000) < 0.6f)
    assertTrue(morph.fold(800_000_000) > 0f)
    assertEquals(0f, morph.fold(850_000_000), 0f)
    assertTrue(morph.settled(850_000_000))
    // Leaving the city again takes the fold.
    morph.aim(flat = true, nowNanos = 900_000_000)
    assertTrue(!morph.raising)
    assertEquals(1f, morph.fold(1_415_000_000), 1e-6f)
  }

  @Test
  fun aTapOnceTheBlocksHaveLandedRaisesThoughTheCameraStillSettles() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    assertEquals(1f, morph.fold(600_000_000), 0f)
    val swing = morph.swing(600_000_000)
    morph.aim(flat = false, nowNanos = 600_000_000)
    assertTrue(morph.raising)
    assertEquals(1f, morph.fold(600_000_000), 1e-6f)
    assertEquals(swing, morph.swing(600_000_000), 1e-4f)
    assertTrue(morph.fold(700_000_000) < 1f)
  }

  @Test
  fun reversingMidRaiseKeepsToTheRaise() {
    val morph = Morph()
    morph.finish(flat = true)
    morph.aim(flat = false, nowNanos = 0)
    val fold = morph.fold(300_000_000)
    morph.aim(flat = true, nowNanos = 300_000_000)
    assertTrue(morph.raising)
    assertEquals(fold, morph.fold(300_000_000), 1e-6f)
    // Back down the same way, at the same pace.
    assertEquals(1f, morph.fold(600_000_000), 1e-6f)
  }

  @Test
  fun reversingMidWayContinuesFromWhereItIs() {
    val morph = Morph()
    morph.aim(flat = true, nowNanos = 0)
    val swing = morph.swing(300_000_000)
    val fold = morph.fold(300_000_000)
    morph.aim(flat = false, nowNanos = 300_000_000)
    // No jump on the reversal frame, although each way reads its position through its own curve.
    assertEquals(swing, morph.swing(300_000_000), 1e-4f)
    assertEquals(fold, morph.fold(300_000_000), 1e-4f)
    assertTrue(!morph.settled(300_000_000))
    // Back to the city, and never longer than a whole morph.
    assertTrue(morph.settled(1_150_000_000))
    assertEquals(0f, morph.swing(1_150_000_000), 0f)
    assertEquals(0f, morph.fold(1_150_000_000), 0f)
  }

  @Test
  fun finishJumpsStraightToTheCode() {
    val morph = Morph()
    morph.finish(flat = true)
    assertEquals(1f, morph.fold(0), 0f)
    assertEquals(1f, morph.swing(0), 0f)
    assertTrue(morph.settled(0))
  }
}
