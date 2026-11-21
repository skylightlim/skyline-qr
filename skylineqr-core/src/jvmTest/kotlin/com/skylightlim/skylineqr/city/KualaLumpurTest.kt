package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class KualaLumpurTest {

  private val sites =
    listOf(
      "https://github.com/skylightlim/skyline-qr", "https://um.edu.my/", "https://example.com/",
      "https://www.petronas.com/", "https://malaysia.gov.my/", "https://a.co/", "https://news.ycombinator.com/",
      "https://en.wikipedia.org/wiki/Kuala_Lumpur",
    )

  private fun kl(url: String): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)
  }

  @Test
  fun landmarksStandOnSolidDarkGroundAwayFromTheCorners() {
    for (url in sites) {
      val city = kl(url)
      val plan = assertNotNull(url, city.landmarks).let { city.landmarks!! }
      assertNotNull("$url has no supertall", plan.supertall)
      assertNotNull("$url has no twin towers", plan.twins)
      assertNotNull("$url has no telecom tower", plan.telecom)
      for (f in plan.all) {
        assertTrue("$url: footprint at ${f.x},${f.z} is not solid dark", LandmarkPlan.fits(city.qr, f))
        for (row in f.z until f.z + f.size) for (col in f.x until f.x + f.size) {
          assertTrue(!LandmarkPlan.inCorner(col, row, city.modules))
        }
      }
      val (a, b) = plan.twins!!
      assertEquals("twins are one street apart", a.size + 1, if (a.z == b.z) b.x - a.x else b.z - a.z)
      val singles = listOf(plan.supertall!!, a, plan.telecom!!)
      for (i in singles.indices) for (j in i + 1 until singles.size) {
        val d = hypot(singles[i].centerX - singles[j].centerX, singles[i].centerZ - singles[j].centerZ)
        assertTrue("$url: landmarks only $d apart", d >= LandmarkPlan.SPACING)
      }
    }
  }

  @Test
  fun eachSiteKeepsItsSkylineAndSitesDiffer() {
    val placements = sites.map { url ->
      val plan = kl(url).landmarks!!
      assertEquals(plan.all.map { it.x to it.z }, kl(url).landmarks!!.all.map { it.x to it.z })
      plan.all.map { it.x to it.z }
    }
    assertTrue("every site put its landmarks in the same place", placements.toSet().size > sites.size / 2)
  }

  @Test
  fun landmarksOwnTheSkylineAndTheMonorailStaysOnStreets() {
    for (url in sites) {
      val city = kl(url)
      val n = city.modules
      var tallestTower = 0f
      var landmarkParts = 0
      for (i in 0 until city.count) {
        val o = i * CityPlanner.STRIDE
        when (city.instances[o + 6]) {
          CityPlanner.ROLE_TOWER -> tallestTower = maxOf(tallestTower, city.instances[o + 4])
          CityPlanner.ROLE_LANDMARK -> landmarkParts++
          CityPlanner.ROLE_RAIL -> {
            // Pillars (the narrow rail parts) only ever stand on light modules.
            if (city.instances[o + 3] < 0.2f && city.instances[o + 5] < 0.2f) {
              val col = (city.instances[o] + n / 2f - 0.5f).let { Math.round(it) }
              val row = (city.instances[o + 2] + n / 2f - 0.5f).let { Math.round(it) }
              assertTrue("$url: pillar on a dark module at $col,$row", !city.qr.isDark(col, row))
            }
          }
        }
      }
      assertTrue(tallestTower <= KualaLumpur.towerCap(n) + 1e-4f)
      assertTrue(landmarkParts > 20)
      assertTrue(city.maxHeight >= KualaLumpur.supertallHeight(n))
      assertEquals(KualaLumpur.materials, city.materials)
    }
  }

  @Test
  fun theGenericCityHasNoLandmarks() {
    val identity = CityIdentity.of("https://um.edu.my/")
    val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity)
    assertEquals(null, city.landmarks)
    for (i in 0 until city.count) assertTrue(city.instances[i * CityPlanner.STRIDE + 6] < CityPlanner.ROLE_LANDMARK)
    assertTrue(city.movers.none { it.kind == Mover.MONORAIL })
  }
}
