package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kuala Lumpur as the camera sees it from where it usually stands: the three landmarks apart
 * across the screen in their triangle, and the monorail in plain view. Checked against the built
 * city itself, every tower and landmark part a box, with rays to the camera's eye as the renderer
 * places it on a phone held upright.
 */
class ViewTest {

  private val sites =
    listOf(
      "https://www.instagram.com/chenyuulim/", "https://github.com/skylightlim/skyline-qr", "https://um.edu.my/",
      "https://example.com/", "https://www.petronas.com/", "https://malaysia.gov.my/", "https://a.co/",
      "https://news.ycombinator.com/", "https://en.wikipedia.org/wiki/Kuala_Lumpur",
    )

  private fun kl(url: String): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)
  }

  /** The renderer's default eye (Camera.pose with no orbit or zoom) for a screen [aspect] wide. */
  private fun eye(city: City, aspect: Float): FloatArray {
    val half = city.plateHalf * 1.34f
    val tanHalf = tan(Math.toRadians(22.5)).toFloat()
    val distance = max(half / tanHalf, half / (tanHalf * aspect)) * 1.18f
    val e = CityPlanner.VIEW_ELEVATION
    val y = CityPlanner.VIEW_YAW
    return floatArrayOf(distance * cos(e) * sin(y), city.maxHeight * 0.4f + distance * sin(e), distance * cos(e) * cos(y))
  }

  /** Whether the segment from [p] to [q] passes through any tower or landmark part. */
  private fun blocked(city: City, p: FloatArray, q: FloatArray): Boolean {
    val d = FloatArray(3) { q[it] - p[it] }
    for (i in 0 until city.count) {
      val o = i * CityPlanner.STRIDE
      val role = city.instances[o + 6]
      if (role != CityPlanner.ROLE_TOWER && role != CityPlanner.ROLE_LANDMARK) continue
      // Spires, masts and flagpoles only slice the view.
      if (min(city.instances[o + 3], city.instances[o + 5]) < 0.35f) continue
      // A turned box within its square of reach; tapering ignored, so this errs toward hidden.
      val yaw = city.instances[o + 10]
      val reach = (abs(cos(yaw)) + abs(sin(yaw)))
      val hx = city.instances[o + 3] / 2f * reach
      val hz = city.instances[o + 5] / 2f * reach
      val hy = city.instances[o + 4] / 2f
      val lo = floatArrayOf(city.instances[o] - hx, city.instances[o + 1] - hy, city.instances[o + 2] - hz)
      val hi = floatArrayOf(city.instances[o] + hx, city.instances[o + 1] + hy, city.instances[o + 2] + hz)
      var t0 = 0.002f
      var t1 = 1f
      var hit = true
      for (k in 0 until 3) {
        if (abs(d[k]) < 1e-9f) {
          if (p[k] < lo[k] || p[k] > hi[k]) hit = false
        } else {
          val a = (lo[k] - p[k]) / d[k]
          val b = (hi[k] - p[k]) / d[k]
          t0 = max(t0, min(a, b))
          t1 = min(t1, max(a, b))
        }
        if (!hit || t0 > t1) {
          hit = false
          break
        }
      }
      if (hit) return true
    }
    return false
  }

  /** How much of the monorail's beam the camera sees, sampled along it. */
  private fun railInView(city: City, aspect: Float): Float {
    val train = city.movers.first { it.kind == Mover.MONORAIL }
    val eye = eye(city, aspect)
    val at = FloatArray(4)
    var seen = 0
    var all = 0
    var s = 0f
    while (s <= train.route.length) {
      train.route.at(s, at)
      if (!blocked(city, floatArrayOf(at[0], KualaLumpur.RAIL_Y + 0.11f, at[2]), eye)) seen++
      all++
      s += 0.25f
    }
    return seen.toFloat() / all
  }

  /** Where a landmark spans across the screen; the twin towers as one. */
  private fun spans(city: City): List<FloatArray> {
    val plan = city.landmarks!!
    val n = city.modules
    val (a, b) = plan.twins!!.let { LandmarkPlan.span(it.first, n) to LandmarkPlan.span(it.second, n) }
    return listOf(
      LandmarkPlan.span(plan.supertall!!, n),
      LandmarkPlan.span(plan.telecom!!, n, LandmarkPlan.TELECOM_PAD),
      floatArrayOf(min(a[0], b[0]), max(a[1], b[1])),
    )
  }

  @Test
  fun theLandmarksStandApartLeftToRightSupertallTelecomTwins() {
    for (url in sites) {
      val (supertall, telecom, twins) = spans(kl(url))
      assertTrue("$url: the telecom tower is not clear right of the supertall", telecom[0] >= supertall[1] + LandmarkPlan.APART - 1e-4f)
      assertTrue("$url: the twin towers are not clear right of the telecom tower", twins[0] >= telecom[1] + LandmarkPlan.APART - 1e-4f)
    }
  }

  @Test
  fun theMonorailIsInPlainView() {
    for (url in sites) {
      val city = kl(url)
      val train = city.movers.first { it.kind == Mover.MONORAIL }
      assertTrue("$url: a monorail of only ${train.route.length}", train.route.length >= 8f)
      // On a phone held upright, the city filling the screen.
      val seen = railInView(city, 0.45f)
      println("rail $url length ${train.route.length} stations ${train.stations.size} seen $seen")
      // Most of it: since 2026-11-12 the line runs on behind towers to reach more of the city.
      assertTrue("$url: only $seen of the monorail in view", seen >= 0.6f)
    }
  }
}
