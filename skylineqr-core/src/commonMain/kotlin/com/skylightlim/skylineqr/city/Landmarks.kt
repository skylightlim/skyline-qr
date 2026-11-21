package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole

/** Which city the planner builds. A presentation choice: it never enters the identity. */
enum class CityPreset { GENERIC, KUALA_LUMPUR }

/** A square of modules, [size] on a side, with its top-left module at ([x], [z]). */
class Footprint(val x: Int, val z: Int, val size: Int) {
  val centerX: Float get() = x + size / 2f
  val centerZ: Float get() = z + size / 2f

  fun covers(col: Int, row: Int) = col in x until x + size && row in z until z + size
}

/**
 * Where Kuala Lumpur's three landmarks stand on one QR. Only on solid dark footprints, so each
 * landmark rises from buildings rather than out of a street; never in the four corners, which the
 * finder patterns own; in a triangle as they stand in the real city, the twin towers east of the
 * telecom tower and the supertall south of it; and spread out, so no landmark hides another from
 * the camera's usual side. The seed breaks ties, so different sites get different arrangements
 * while each site always gets the same one.
 */
class LandmarkPlan(
  /** Merdeka 118's place: 3 x 3, or smaller if no solid 3 x 3 exists away from the corners. */
  val supertall: Footprint?,
  /** The twin towers: two squares one street apart, side by side along x or along z. */
  val twins: Pair<Footprint, Footprint>?,
  /** The telecom tower: 2 x 2 or 1 x 1. */
  val telecom: Footprint?,
) {
  val all: List<Footprint>
    get() = listOfNotNull(supertall, twins?.first, twins?.second, telecom)

  fun covers(col: Int, row: Int) = all.any { it.covers(col, row) }

  /** How tall the landmark on [f], one of [all], stands in a code [modules] wide, at its true ratio. */
  fun height(f: Footprint, modules: Int): Float =
    when (f) {
      supertall -> KualaLumpur.supertallHeight(modules)
      telecom -> KualaLumpur.telecomHeight(modules)
      else -> KualaLumpur.twinHeight(modules)
    }

  companion object {
    /** Corner zones, a module wider than the finder patterns and their separators. */
    const val CORNER = 9
    /** Centres of different landmarks stay at least this many modules apart. */
    const val SPACING = 5f
    /**
     * Seen from the camera's usual side, different landmarks keep at least this many modules
     * between them across the screen, so none stands in front of another.
     */
    const val APART = 1.5f

    /**
     * Where each landmark aims, in half-widths from the code's middle, x east and z south (the code
     * is a map with north at the top): as in the real city, the twin towers east of the telecom
     * tower and the supertall south-south-west of it, each out on an arm of the cross the finder
     * corners leave free. From the camera's usual side that puts them left to right across the
     * screen, the supertall, the telecom tower further back, then the twin towers.
     */
    private val SUPERTALL_AT = floatArrayOf(-0.2f, 0.55f)
    private val TELECOM_AT = floatArrayOf(-0.12f, -0.12f)
    private val TWINS_AT = floatArrayOf(0.55f, -0.2f)

    /** Where a landmark spans across the screen from the camera's usual side, widened by [pad]. */
    fun span(f: Footprint, n: Int, pad: Float = 0f): FloatArray {
      val half = n / 2f
      var lo = Float.MAX_VALUE
      var hi = -Float.MAX_VALUE
      for (cx in intArrayOf(f.x, f.x + f.size)) {
        for (cz in intArrayOf(f.z, f.z + f.size)) {
          val a = Sight.across(cx - half, cz - half)
          lo = minOf(lo, a)
          hi = maxOf(hi, a)
        }
      }
      return floatArrayOf(lo - pad, hi + pad)
    }

    /** The telecom tower's head is wider than a one-module footprint. */
    const val TELECOM_PAD = 0.3f

    /** How many of the best places for the supertall and for the twins the search tries together. */
    private const val SEARCH = 24

    fun plan(matrix: QrMatrix, seed: Int): LandmarkPlan {
      val n = matrix.size
      val supertalls = candidates(matrix, seed + 1, SUPERTALL_SIZES, SUPERTALL_AT)
      val pairs = pairCandidates(matrix, seed + 2, TWINS_AT)
      val telecoms = candidates(matrix, seed + 3, TELECOM_SIZES, TELECOM_AT)
      fun spaced(f: Footprint, others: List<Footprint>) =
        others.all { other ->
          val dx = f.centerX - other.centerX
          val dz = f.centerZ - other.centerZ
          dx * dx + dz * dz >= SPACING * SPACING
        }
      fun span(pair: Pair<Footprint, Footprint>): FloatArray {
        val a = span(pair.first, n)
        val b = span(pair.second, n)
        return floatArrayOf(minOf(a[0], b[0]), maxOf(a[1], b[1]))
      }
      fun before(left: FloatArray, right: FloatArray) = right[0] >= left[1] + APART

      // The best three that stand in order across the screen, apart: the supertall and the twins
      // from their best few places each, the telecom tower at its best place between them.
      var best: LandmarkPlan? = null
      var bestScore = Float.MAX_VALUE
      for (s in supertalls.take(SEARCH)) {
        val left = span(s.place, n)
        for (t in pairs.take(SEARCH)) {
          if (s.score + t.score >= bestScore) continue
          val twins = listOf(t.place.first, t.place.second)
          val right = span(t.place)
          if (!before(left, right) || !spaced(s.place, twins)) continue
          val between =
            telecoms.firstOrNull { c ->
              val mid = span(c.place, n, TELECOM_PAD)
              before(left, mid) && before(mid, right) && spaced(c.place, twins + s.place)
            } ?: continue
          val total = s.score + t.score + between.score
          if (total < bestScore) {
            bestScore = total
            best = LandmarkPlan(s.place, t.place, between.place)
          }
        }
      }
      best?.let { return it }

      // Where no three fit in order, each at its best place apart from the others on the screen,
      // else only spaced out from them.
      val taken = ArrayList<Footprint>()
      val spans = ArrayList<FloatArray>()
      fun apart(span: FloatArray) = spans.all { span[0] >= it[1] + APART || span[1] <= it[0] - APART }
      val supertall = supertalls.firstOrNull()?.place
      supertall?.let {
        taken += it
        spans += span(it, n)
      }
      val twins =
        (pairs.firstOrNull { spaced(it.place.first, taken) && spaced(it.place.second, taken) && apart(span(it.place)) }
          ?: pairs.firstOrNull { spaced(it.place.first, taken) && spaced(it.place.second, taken) })?.place
      twins?.let {
        taken += it.first
        taken += it.second
        spans += span(it)
      }
      val telecom =
        (telecoms.firstOrNull { spaced(it.place, taken) && apart(span(it.place, n, TELECOM_PAD)) }
          ?: telecoms.firstOrNull { spaced(it.place, taken) })?.place
      return LandmarkPlan(supertall, twins, telecom)
    }

    /**
     * Footprint sides to try, largest first, and what each step smaller costs in [score]: a
     * supertall on fewer than 3 x 3 modules is a needle, so only a long way from its place is
     * worse; the telecom tower is slender either way.
     */
    private val SUPERTALL_SIZES = listOf(3 to 0f, 2 to 0.12f, 1 to 0.5f)
    private val TWIN_SIZES = listOf(2 to 0f, 1 to 0.4f)
    private val TELECOM_SIZES = listOf(2 to 0f, 1 to 0.03f)

    /** A footprint every module of which is dark and none of which is in a corner zone. */
    fun fits(matrix: QrMatrix, f: Footprint): Boolean {
      val n = matrix.size
      if (f.x < 0 || f.z < 0 || f.x + f.size > n || f.z + f.size > n) return false
      for (row in f.z until f.z + f.size) {
        for (col in f.x until f.x + f.size) {
          if (!matrix.isDark(col, row) || inCorner(col, row, n)) return false
          // Nor on the alignment pattern, which is the crossing's (2026-11-09).
          val role = matrix.role(col, row)
          if (role == QrRole.FINDER || role == QrRole.SEPARATOR || role == QrRole.ALIGNMENT) return false
        }
      }
      return true
    }

    fun inCorner(col: Int, row: Int, n: Int): Boolean {
      val left = col < CORNER
      val right = col >= n - CORNER
      val top = row < CORNER
      val bottom = row >= n - CORNER
      return (left || right) && (top || bottom)
    }

    /**
     * Lower is better: near the landmark's place [at], in half-widths from the code's middle, as
     * the camera sees it, where across the screen counting twice as much as how far back; plus a
     * seeded nudge so equal spots do not always tie.
     */
    private fun score(matrix: QrMatrix, seed: Int, cx: Float, cz: Float, x: Int, z: Int, at: FloatArray): Float {
      val half = matrix.size / 2f
      val dx = (cx - half) / half - at[0]
      val dz = (cz - half) / half - at[1]
      val across = Sight.across(dx, dz)
      val toward = Sight.toward(dx, dz)
      return across * across + 0.5f * toward * toward + 0.02f * unit(seed, x, z)
    }

    /** A place and how good it is there: lower is better. */
    private class Scored<T>(val place: T, val score: Float)

    /** Every solid footprint of the [sizes], best first; see [score]. */
    private fun candidates(matrix: QrMatrix, seed: Int, sizes: List<Pair<Int, Float>>, at: FloatArray): List<Scored<Footprint>> {
      val out = ArrayList<Scored<Footprint>>()
      for ((size, cost) in sizes) {
        for (z in 0..matrix.size - size) {
          for (x in 0..matrix.size - size) {
            val f = Footprint(x, z, size)
            if (fits(matrix, f)) out += Scored(f, score(matrix, seed, f.centerX, f.centerZ, x, z, at) + cost)
          }
        }
      }
      return out.sortedBy { it.score }
    }

    /**
     * Every pair of solid squares with exactly one module of street between them, so a skybridge
     * can span it, best first.
     */
    private fun pairCandidates(matrix: QrMatrix, seed: Int, at: FloatArray): List<Scored<Pair<Footprint, Footprint>>> {
      val out = ArrayList<Scored<Pair<Footprint, Footprint>>>()
      for ((size, cost) in TWIN_SIZES) {
        for (z in 0..matrix.size - size) {
          for (x in 0..matrix.size - size) {
            val a = Footprint(x, z, size)
            if (!fits(matrix, a)) continue
            for (alongX in booleanArrayOf(true, false)) {
              val b = if (alongX) Footprint(x + size + 1, z, size) else Footprint(x, z + size + 1, size)
              if (!fits(matrix, b)) continue
              // A real street between them reads best; a dark gap still works.
              var gapLight = 0
              for (i in 0 until size) {
                val col = if (alongX) x + size else x + i
                val row = if (alongX) z + i else z + size
                if (!matrix.isDark(col, row)) gapLight++
              }
              val cx = (a.centerX + b.centerX) / 2f
              val cz = (a.centerZ + b.centerZ) / 2f
              out += Scored(a to b, score(matrix, seed, cx, cz, x, z, at) + cost - 0.03f * gapLight / size)
            }
          }
        }
      }
      return out.sortedBy { it.score }
    }

    private fun unit(seed: Int, x: Int, z: Int): Float {
      var h = seed * 374761393 + x * 668265263 + z * 1274126177
      h = (h xor (h ushr 13)) * 1274126177
      h = h xor (h ushr 16)
      return (h and 0x7fffffff) / 2147483647f
    }
  }
}
