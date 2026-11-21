package com.skylightlim.skylineqr.city

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A path through or over the city, as dense points: closed into a loop, or run end to end. Corners
 * are rounded when a route is built, so following one is only interpolation, and the heading turns
 * smoothly through a corner instead of snapping at each point.
 */
class Route(
  /** x, y, z triples. */
  val points: FloatArray,
  val closed: Boolean,
) {
  val count = points.size / 3
  private val segments = if (closed) count else count - 1
  private val cumulative = FloatArray(segments + 1)
  private val headings = FloatArray(count)

  /** Total length in world units. */
  val length: Float

  init {
    require(count >= 2) { "a route needs two points" }
    for (i in 0 until segments) {
      cumulative[i + 1] = cumulative[i] + distance(i, (i + 1) % count)
    }
    length = cumulative[segments]
    // Each point faces along the average of the two segments that meet there.
    for (i in 0 until count) {
      val before = if (i > 0) i - 1 else if (closed) count - 1 else 0
      val after = if (i < count - 1) i + 1 else if (closed) 0 else count - 1
      headings[i] = atan2(z(after) - z(before), x(after) - x(before))
    }
  }

  fun x(i: Int) = points[i * 3]

  fun y(i: Int) = points[i * 3 + 1]

  fun z(i: Int) = points[i * 3 + 2]

  /**
   * The position [s] world units along the route into out[0..2] and the heading into out[3], as
   * a yaw where 0 faces +x. A loop wraps; an open route stops at its ends.
   */
  fun at(s: Float, out: FloatArray) {
    val d = if (closed) ((s % length) + length) % length else s.coerceIn(0f, length)
    var lo = 0
    var hi = segments - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (cumulative[mid] <= d) lo = mid else hi = mid - 1
    }
    val a = lo
    val b = (lo + 1) % count
    val span = cumulative[lo + 1] - cumulative[lo]
    val t = if (span > 1e-6f) (d - cumulative[lo]) / span else 0f
    out[0] = x(a) + (x(b) - x(a)) * t
    out[1] = y(a) + (y(b) - y(a)) * t
    out[2] = z(a) + (z(b) - z(a)) * t
    out[3] = turnBetween(headings[a], headings[b], t)
  }

  private fun distance(i: Int, j: Int): Float {
    val dx = x(j) - x(i)
    val dy = y(j) - y(i)
    val dz = z(j) - z(i)
    return sqrt(dx * dx + dy * dy + dz * dz)
  }

  companion object {
    private const val PI = kotlin.math.PI.toFloat()

    /** Turns from heading [a] toward [b] by [t], the short way round. */
    fun turnBetween(a: Float, b: Float, t: Float): Float {
      var d = b - a
      while (d > PI) d -= 2 * PI
      while (d < -PI) d += 2 * PI
      return a + d * t
    }

    /**
     * Rounds the corners of a path through cell centres: each turn becomes a quadratic curve of
     * [radius] cut into the corner, sampled into [steps] points. Straight runs keep their ends only.
     */
    fun rounded(cells: List<Pair<Float, Float>>, closed: Boolean, radius: Float, y: Float, steps: Int = 5): Route {
      val out = ArrayList<Float>()
      fun add(x: Float, z: Float) {
        out += x
        out += y
        out += z
      }
      val n = cells.size
      for (i in 0 until n) {
        val (cx, cz) = cells[i]
        val atEnd = !closed && (i == 0 || i == n - 1)
        val prev = cells[(i - 1 + n) % n]
        val next = cells[(i + 1) % n]
        val inX = cx - prev.first
        val inZ = cz - prev.second
        val outX = next.first - cx
        val outZ = next.second - cz
        val straight = inX * outZ - inZ * outX == 0f && inX * outX + inZ * outZ > 0f
        if (atEnd) {
          add(cx, cz)
          continue
        }
        if (straight) {
          // Only the first and last cell of a straight run need a point.
          continue
        }
        val inLen = sqrt(inX * inX + inZ * inZ)
        val outLen = sqrt(outX * outX + outZ * outZ)
        val ax = cx - inX / inLen * radius
        val az = cz - inZ / inLen * radius
        val bx = cx + outX / outLen * radius
        val bz = cz + outZ / outLen * radius
        for (k in 0..steps) {
          val t = k.toFloat() / steps
          val u = 1f - t
          add(u * u * ax + 2f * u * t * cx + t * t * bx, u * u * az + 2f * u * t * cz + t * t * bz)
        }
      }
      return Route(out.toFloatArray(), closed)
    }
  }
}
