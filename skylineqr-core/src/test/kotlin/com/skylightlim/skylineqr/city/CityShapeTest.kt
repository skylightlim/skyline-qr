package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import com.skylightlim.skylineqr.core.QrRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityShapeTest {

  private fun city(url: String): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity)
  }

  /** Height of the box standing on module (x, z): instance 0 is the plate, then row by row. */
  private fun height(city: City, x: Int, z: Int): Float {
    val o = (1 + z * city.modules + x) * CityPlanner.STRIDE
    return if (city.instances[o + 6] == CityPlanner.ROLE_TOWER) city.instances[o + 4] else 0f
  }

  @Test
  fun findersAreLowPodiumsWithASquatCore() {
    val city = city("https://github.com/skylightlim/skyline-qr")
    val n = city.modules
    for ((ox, oz) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
      assertEquals(CityPlanner.FLOOR, height(city, ox, oz), 1e-6f) // ring corner
      assertEquals(3 * CityPlanner.FLOOR, height(city, ox + 3, oz + 3), 1e-6f) // core centre
      assertEquals(0f, height(city, ox + 1, oz + 1), 0f) // the light ring stays a plaza
    }
    // Nothing in a finder rises above its core, and the skyline is taller elsewhere.
    for (z in 0 until n) for (x in 0 until n) {
      if (city.qr.role(x, z) == QrRole.FINDER) assertTrue(height(city, x, z) <= 3 * CityPlanner.FLOOR + 1e-6f)
    }
    assertTrue(city.maxHeight > 3 * CityPlanner.FLOOR)
  }

  @Test
  fun materialsFollowTheSite() {
    assertEquals(city("https://example.com/").materials, city("https://example.com/about").materials)
    val names = listOf("a.com", "b.org", "c.net", "d.io", "e.dev", "f.my", "g.app", "h.xyz", "i.co", "j.edu.my")
      .map { city("https://$it/").materials.name }
      .toSet()
    assertTrue("ten sites shared only ${names.size} recipes", names.size >= 4)
  }
}
