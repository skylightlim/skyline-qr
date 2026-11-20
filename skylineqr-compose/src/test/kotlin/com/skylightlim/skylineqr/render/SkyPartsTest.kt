package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPartsTest {

  private val identity = CityIdentity.of("https://github.com/skylightlim/skyline-qr")
  private val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)

  @Test
  fun cloudsBlowAwayDownwindAndComeBackFromUpwind() {
    var way = 0f
    repeat(400) { way = SkyParts.travel(way, wanted = false, dt = 0.05f) }
    assertEquals(1f, way)
    assertFalse(SkyParts.shows(way))
    // Wanted again, they come from upwind, never back the way they left.
    way = SkyParts.travel(way, wanted = true, dt = 0.05f)
    assertTrue(way < 0f)
    repeat(400) { way = SkyParts.travel(way, wanted = true, dt = 0.05f) }
    assertEquals(0f, way)
  }

  @Test
  fun aDeckStillArrivingWhenItIsNotWantedBlowsOnThroughWithoutJumping() {
    var way = -0.5f
    var last = way
    repeat(400) {
      way = SkyParts.travel(way, wanted = false, dt = 0.05f)
      assertTrue("way $way after $last", way >= last && way - last < 0.05f)
      last = way
    }
    assertEquals(1f, way)
  }

  @Test
  fun theClearSkyHasFiveToEightClouds() {
    assertTrue(SkyParts.fairCount(city) in 5..8)
  }
}
