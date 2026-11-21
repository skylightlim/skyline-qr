package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CornersTest {

  /** Links from the smallest code to a version 6 one, in both identity scopes. */
  private val cities: List<City> by lazy {
    listOf(
      "https://a.co",
      "https://github.com/skylightlim/skyline-qr",
      "https://um.edu.my/",
      "https://en.wikipedia.org/wiki/Kuala_Lumpur_Tower?section=history-and-design",
    ).flatMap { url ->
      IdentityScope.entries.map { scope ->
        val identity = CityIdentity.of(url, scope)
        CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)
      }
    }
  }

  /** The finders' top-left modules: garden, mall, square. */
  private fun finders(n: Int) = listOf(0 to 0, n - 7 to 0, 0 to n - 7)

  @Test
  fun everyKualaLumpurCodeHasItsThreeCorners() {
    for (city in cities) {
      val n = city.modules
      for ((fx, fz) in finders(n)) {
        val parts = cornerParts(city, fx, fz)
        assertTrue("v${city.qr.symbolVersion} finder at ($fx, $fz) has ${parts.size} parts", parts.size >= 10)
      }
    }
  }

  @Test
  fun everyCornerPartStaysOnItsFinder() {
    for (city in cities) {
      val n = city.modules
      val d = city.instances
      for ((fx, fz) in finders(n)) for (o in cornerParts(city, fx, fz)) {
        // A box turned about its axis reaches at most half its diagonal from its middle.
        val turned = d[o + 10] != 0f
        val diagonal = sqrt(d[o + 3] * d[o + 3] + d[o + 5] * d[o + 5]) / 2f
        val reachU = if (turned) diagonal else d[o + 3] / 2f
        val reachV = if (turned) diagonal else d[o + 5] / 2f
        val u = d[o] + n / 2f - fx
        val v = d[o + 2] + n / 2f - fz
        val where = "v${city.qr.symbolVersion} part at ($u, $v) reaching $reachU, $reachV"
        assertTrue(where, u - reachU >= -0.05f && u + reachU <= 7.05f)
        assertTrue(where, v - reachV >= -0.05f && v + reachV <= 7.05f)
      }
    }
  }

  @Test
  fun theMallStandsBehindItsPlazaAndTheGardenAndSquareLieLow() {
    for (city in cities) {
      val n = city.modules
      val d = city.instances
      for (i in 0 until city.count) {
        val o = i * CityPlanner.STRIDE
        if (d[o + 6] != CityPlanner.ROLE_TOWER) continue
        val col = (d[o] + n / 2f).toInt()
        val row = (d[o + 2] + n / 2f).toInt()
        val inFinder = (col < 7 || col >= n - 7) && (row < 7 || row >= n - 7) && !(col >= n - 7 && row >= n - 7)
        if (!inFinder) continue
        val height = d[o + 4]
        val plaza = col - (n - 7) in 2..4 && row == 6
        if (col >= n - 7 && !plaza) assertTrue("mall module ($col, $row) is $height", height >= Corners.MALL - 1e-4f)
        else assertEquals("lawn or plaza module ($col, $row)", Corners.UNDER_LAWN, height, 1e-4f)
      }
    }
  }

  @Test
  fun noCarDrivesInsideAFinder() {
    for (city in cities) {
      val n = city.modules
      val point = FloatArray(4)
      for (m in city.movers.filter { it.kind == Mover.CAR }) {
        var s = 0f
        while (s < m.route.length) {
          m.route.at(s, point)
          val u = point[0] + n / 2f
          val v = point[2] + n / 2f
          for ((fx, fz) in finders(n)) {
            val inside = u > fx + 1f && u < fx + 6f && v > fz + 1f && v < fz + 6f
            assertTrue("a car at ($u, $v) inside the finder at ($fx, $fz)", !inside)
          }
          s += 0.25f
        }
      }
    }
  }

  /** Landmark parts whose middle lies on the finder whose top-left module is ([fx], [fz]). */
  private fun cornerParts(city: City, fx: Int, fz: Int): List<Int> {
    val n = city.modules
    val d = city.instances
    return (0 until city.count).map { it * CityPlanner.STRIDE }.filter { o ->
      val u = d[o] + n / 2f - fx
      val v = d[o + 2] + n / 2f - fz
      d[o + 6] == CityPlanner.ROLE_LANDMARK && u in 0f..7f && v in 0f..7f
    }
  }
}
