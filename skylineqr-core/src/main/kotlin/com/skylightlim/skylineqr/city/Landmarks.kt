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
 * finder patterns own; leaning toward the middle, the way a skyline gathers downtown; and spread
 * out, so three landmarks never merge into one blob. The seed breaks ties, so different sites get
 * different arrangements while each site always gets the same one.
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

  companion object {
    /** Corner zones, a module wider than the finder patterns and their separators. */
    const val CORNER = 9
    /** Centres of different landmarks stay at least this many modules apart. */
    const val SPACING = 5f

    fun plan(matrix: QrMatrix, seed: Int): LandmarkPlan {
      val taken = ArrayList<Footprint>()
      fun farFromOthers(f: Footprint) =
        taken.all { other ->
          val dx = f.centerX - other.centerX
          val dz = f.centerZ - other.centerZ
          dx * dx + dz * dz >= SPACING * SPACING
        }

      val supertall = sequenceOf(3, 2, 1).firstNotNullOfOrNull { best(matrix, seed + 1, it, ::farFromOthers) }
      supertall?.let(taken::add)

      val twins = sequenceOf(2, 1).firstNotNullOfOrNull { bestPair(matrix, seed + 2, it, ::farFromOthers) }
      twins?.let {
        taken += it.first
        taken += it.second
      }

      val telecom = sequenceOf(2, 1).firstNotNullOfOrNull { best(matrix, seed + 3, it, ::farFromOthers) }
      return LandmarkPlan(supertall, twins, telecom)
    }

    /** A footprint every module of which is dark and none of which is in a corner zone. */
    fun fits(matrix: QrMatrix, f: Footprint): Boolean {
      val n = matrix.size
      if (f.x < 0 || f.z < 0 || f.x + f.size > n || f.z + f.size > n) return false
      for (row in f.z until f.z + f.size) {
        for (col in f.x until f.x + f.size) {
          if (!matrix.isDark(col, row) || inCorner(col, row, n)) return false
          if (matrix.role(col, row) == QrRole.FINDER || matrix.role(col, row) == QrRole.SEPARATOR) return false
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

    /** Lower is better: near the middle, plus a seeded nudge so equal spots do not always tie. */
    private fun score(matrix: QrMatrix, seed: Int, cx: Float, cz: Float, x: Int, z: Int): Float {
      val half = matrix.size / 2f
      val dx = (cx - half) / half
      val dz = (cz - half) / half
      return dx * dx + dz * dz + 0.45f * unit(seed, x, z)
    }

    private fun best(matrix: QrMatrix, seed: Int, size: Int, allowed: (Footprint) -> Boolean): Footprint? {
      var best: Footprint? = null
      var bestScore = Float.MAX_VALUE
      for (z in 0..matrix.size - size) {
        for (x in 0..matrix.size - size) {
          val f = Footprint(x, z, size)
          if (!fits(matrix, f) || !allowed(f)) continue
          val s = score(matrix, seed, f.centerX, f.centerZ, x, z)
          if (s < bestScore) {
            bestScore = s
            best = f
          }
        }
      }
      return best
    }

    /** Two squares with exactly one module of street between them, so a skybridge can span it. */
    private fun bestPair(
      matrix: QrMatrix,
      seed: Int,
      size: Int,
      allowed: (Footprint) -> Boolean,
    ): Pair<Footprint, Footprint>? {
      var best: Pair<Footprint, Footprint>? = null
      var bestScore = Float.MAX_VALUE
      for (z in 0..matrix.size - size) {
        for (x in 0..matrix.size - size) {
          val a = Footprint(x, z, size)
          if (!fits(matrix, a) || !allowed(a)) continue
          for (alongX in booleanArrayOf(true, false)) {
            val b = if (alongX) Footprint(x + size + 1, z, size) else Footprint(x, z + size + 1, size)
            if (!fits(matrix, b) || !allowed(b)) continue
            // A real street between them reads best; a dark gap still works.
            var gapLight = 0
            for (i in 0 until size) {
              val col = if (alongX) x + size else x + i
              val row = if (alongX) z + i else z + size
              if (!matrix.isDark(col, row)) gapLight++
            }
            val cx = (a.centerX + b.centerX) / 2f
            val cz = (a.centerZ + b.centerZ) / 2f
            val s = score(matrix, seed, cx, cz, x, z) - 0.2f * gapLight / size
            if (s < bestScore) {
              bestScore = s
              best = a to b
            }
          }
        }
      }
      return best
    }

    private fun unit(seed: Int, x: Int, z: Int): Float {
      var h = seed * 374761393 + x * 668265263 + z * 1274126177
      h = (h xor (h ushr 13)) * 1274126177
      h = h xor (h ushr 16)
      return (h and 0x7fffffff) / 2147483647f
    }
  }
}
