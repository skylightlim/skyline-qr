package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {

  private val sites =
    listOf("https://github.com/skylightlim/skyline-qr", "https://um.edu.my/", "https://example.com/", "https://a.co/", "https://news.ycombinator.com/")

  private fun city(url: String, preset: CityPreset = CityPreset.KUALA_LUMPUR): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, preset)
  }

  /** Whether a world point stands over road: a light module, or the ring outside the code. */
  private fun onRoad(city: City, x: Float, z: Float): Boolean {
    val n = city.modules
    val col = floor(x + n / 2f).toInt()
    val row = floor(z + n / 2f).toInt()
    if (col < 0 || row < 0 || col >= n || row >= n) return col >= -1 && row >= -1 && col <= n && row <= n
    return !city.qr.isDark(col, row)
  }

  private fun angle(a: Float, b: Float): Float {
    var d = b - a
    while (d > PI) d -= (2 * PI).toFloat()
    while (d < -PI) d += (2 * PI).toFloat()
    return abs(d)
  }

  @Test
  fun carsLoopOnTheRoadKeepingLeftAndNeverTurnRound() {
    for (url in sites) for (preset in CityPreset.entries) {
      val city = city(url, preset)
      val drivers = city.movers.filter { it.kind == Mover.CAR || it.kind == Mover.BUS }
      assertTrue("$url has only ${drivers.size} vehicles on the road", drivers.size >= 4)
      assertEquals(1, drivers.count { it.kind == Mover.BUS })
      val p = FloatArray(4)
      val q = FloatArray(4)
      for (m in drivers) {
        assertTrue(m.route.closed)
        var s = 0f
        while (s < m.route.length) {
          m.route.at(s, p)
          assertTrue("$url: off the road at ${p[0]}, ${p[2]}", onRoad(city, p[0], p[2]))
          m.route.at(s + 0.05f, q)
          // At most a right angle over a short step, and never a reversal.
          assertTrue("$url: turned ${angle(p[3], q[3])} rad in 0.05", angle(p[3], q[3]) < 0.9f)
          s += 0.05f
        }
      }
    }
  }

  @Test
  fun theMonorailRunsOverTheStreetAndBends() {
    var bends = 0
    for (url in sites) {
      val city = city(url)
      val train = city.movers.firstOrNull { it.kind == Mover.MONORAIL } ?: continue
      val p = FloatArray(4)
      var s = 0f
      var turned = 0f
      var last = Float.NaN
      while (s <= train.route.length) {
        train.route.at(s, p)
        assertTrue("$url: track over a building at ${p[0]}, ${p[2]}", onRoad(city, p[0], p[2]))
        if (!last.isNaN()) turned += angle(last, p[3])
        last = p[3]
        s += 0.05f
      }
      if (turned > 1f) bends++
      // The middle of the train never runs off either end.
      for (t in 0 until 600) assertTrue(train.distance(t * 0.1f) in 0.9f..train.route.length - 0.9f)
    }
    assertTrue("no monorail bent at all", bends >= 2)
  }

  @Test
  fun theHelicopterClearsTheLandmarksAndThePlaneClearsEverything() {
    for (url in sites) {
      val city = city(url)
      val plan = city.landmarks!!
      val n = city.modules
      val heli = city.movers.single { it.kind == Mover.HELICOPTER }
      val plane = city.movers.single { it.kind == Mover.PLANE }
      val p = FloatArray(4)
      val tall = listOfNotNull(
        plan.supertall?.let { it to KualaLumpur.supertallHeight(n) },
        plan.twins?.first?.let { it to KualaLumpur.twinHeight(n) },
        plan.twins?.second?.let { it to KualaLumpur.twinHeight(n) },
        plan.telecom?.let { it to KualaLumpur.telecomHeight(n) },
      )
      for (i in 0 until heli.route.count) {
        for ((f, h) in tall) {
          val d = hypot(heli.route.x(i) - (f.centerX - n / 2f), heli.route.z(i) - (f.centerZ - n / 2f))
          assertTrue("$url: helicopter ${d} from a landmark", heli.route.y(i) > h || d > f.size * 0.75f + 1f)
        }
      }
      for (i in 0 until plane.route.count) assertTrue(plane.route.y(i) > city.maxHeight + 3f)
      for (t in 0 until 200) {
        heli.at(t * 0.37f, p)
        assertTrue(p.all { it.isFinite() })
      }
    }
  }

  @Test
  fun everyVehicleIsBuiltFromFiniteBoxes() {
    val city = city("https://um.edu.my/")
    val scratch = FloatArray(4)
    var boxes = 0
    for (m in city.movers) {
      for (t in 0 until 50) {
        Vehicles.emit(m, t * 0.21f, { x, y, z, sx, sy, sz, _, _, _, yaw, taper ->
          boxes++
          assertTrue(floatArrayOf(x, y, z, sx, sy, sz, yaw, taper).all { it.isFinite() } && sx > 0f && sy > 0f && sz > 0f)
        }, scratch)
      }
    }
    assertTrue(boxes > 0)
  }
}
