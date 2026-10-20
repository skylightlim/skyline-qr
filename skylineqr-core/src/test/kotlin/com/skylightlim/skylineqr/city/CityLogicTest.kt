package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityLogicTest {

  private val identity = CityIdentity.of("https://github.com/skylightlim/skyline-qr")
  private val matrix = QrEncoder.encode(identity.link.payload)

  @Test
  fun cityIsDeterministicAndSeeded() {
    val city = CityPlanner.plan(matrix, identity)
    val again = CityPlanner.plan(matrix, identity)

    assertEquals(city.count, city.instances.size / CityPlanner.STRIDE)
    val cells = matrix.size * matrix.size
    assertTrue(city.count >= 1 + cells) // plate, one box per module
    assertTrue(city.count <= 1 + cells + matrix.darkCount) // plus beacons on the tallest towers
    assertTrue(city.instances.contentEquals(again.instances))
    assertTrue(city.instances.all { it.isFinite() })

    val otherWorld = CityPlanner.plan(matrix, CityIdentity.of("https://example.org/"))
    assertTrue(!city.instances.contentEquals(otherWorld.instances))
  }

  @Test
  fun trafficFollowsTheSeed() {
    val city = CityPlanner.plan(matrix, identity)
    val again = CityPlanner.plan(matrix, identity)
    assertEquals(city.movers.size, again.movers.size)
    for ((a, b) in city.movers.zip(again.movers)) {
      assertEquals(a.kind, b.kind)
      assertTrue(a.route.points.contentEquals(b.route.points))
    }
    assertEquals(1, city.movers.count { it.kind == Mover.PLANE })
    assertEquals(1, city.movers.count { it.kind == Mover.HELICOPTER })
  }
}
