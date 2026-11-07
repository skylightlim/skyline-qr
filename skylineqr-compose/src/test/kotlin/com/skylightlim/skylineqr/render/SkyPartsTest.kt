package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Vehicles
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.SkyPart
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

  @Test
  fun aLongPressFindsTheCloudOrTheSunUnderIt() {
    val boxes = ArrayList<Float>()
    val sink = Vehicles.Sink { x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper -> boxes += listOf(x, y, z, sx, sy, sz, role, glow, 0f, rnd, yaw, taper) }
    val air = Atmospheres.at(TimeOfDay.DAY, Weather.CLEAR, 0f)
    SkyParts.emit(sink, city, 12f, false, SkyParts.Amounts(fair = 0f, deck = 1f, shine = 1f), air)
    val data = boxes.toFloatArray()
    val count = boxes.size / CityPlanner.STRIDE
    val pose = Camera.pose(city, 0.45f, 0f, 12f)
    fun toward(i: Int) = FloatArray(3) { data[i * CityPlanner.STRIDE + it] - pose.eye[it] }

    val sun = (0 until count).first { data[it * CityPlanner.STRIDE + 7] > 0.9f }
    assertEquals(SkyPart.SUN, SkyParts.pick(data, count, pose.eye, toward(sun)))
    val cloud = (0 until count).first { data[it * CityPlanner.STRIDE + 7] < 0.5f }
    assertEquals(SkyPart.CLOUD, SkyParts.pick(data, count, pose.eye, toward(cloud)))
    // Straight down onto the middle of the city, clear of the clouds and the sun behind it.
    assertNull(SkyParts.pick(data, count, floatArrayOf(0f, city.maxHeight + 2f, 0f), floatArrayOf(0f, -1f, 0f)))
  }
}
