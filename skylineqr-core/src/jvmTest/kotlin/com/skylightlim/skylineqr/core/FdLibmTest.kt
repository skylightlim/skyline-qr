package com.skylightlim.skylineqr.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** FdLibm must return StrictMath's bits exactly, or the browser builds a different city. */
class FdLibmTest {

  private val rnd = java.util.SplittableRandom(20261121)

  private fun samples(): List<Double> {
    val out = ArrayList<Double>()
    // Every float in the planner's usual range is far too many; a few hundred thousand spread over
    // magnitudes, plus the floats the planner actually passes (angles, ratios, small powers).
    repeat(200_000) { out += (rnd.nextDouble() * 2 - 1) * Math.pow(10.0, rnd.nextInt(-8, 7).toDouble()) }
    repeat(50_000) { out += (rnd.nextDouble() * 20 - 10).toFloat().toDouble() }
    for (i in -4000..4000) out += i * Math.PI / 512
    out += listOf(0.0, -0.0, 1.0, -1.0, Math.PI / 4, Math.PI / 2, Math.PI, 3 * Math.PI / 4, Double.MIN_VALUE, Double.MAX_VALUE,
      Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e-300, 823_549.0, -823_549.0)
    return out
  }

  private fun same(name: String, x: Double, expected: Double, actual: Double) =
    assertEquals("$name($x)", expected.toRawBits(), actual.toRawBits())

  @Test
  fun sinCosTan() {
    for (x in samples()) {
      same("sin", x, StrictMath.sin(x), FdLibm.sin(x))
      same("cos", x, StrictMath.cos(x), FdLibm.cos(x))
      same("tan", x, StrictMath.tan(x), FdLibm.tan(x))
    }
  }

  @Test
  fun atanAndAtan2() {
    val xs = samples()
    for (x in xs) same("atan", x, StrictMath.atan(x), FdLibm.atan(x))
    for (i in xs.indices) {
      val y = xs[i]
      val x = xs[((i.toLong() * 7919 + 13) % xs.size).toInt()]
      assertEquals("atan2($y, $x)", StrictMath.atan2(y, x).toRawBits(), FdLibm.atan2(y, x).toRawBits())
    }
  }

  @Test
  fun hypotAndPow() {
    val xs = samples()
    for (i in xs.indices) {
      val a = xs[i]
      val b = xs[((i.toLong() * 104729 + 7) % xs.size).toInt()]
      assertEquals("hypot($a, $b)", StrictMath.hypot(a, b).toRawBits(), FdLibm.hypot(a, b).toRawBits())
      assertEquals("pow($a, $b)", StrictMath.pow(a, b).toRawBits(), FdLibm.pow(a, b).toRawBits())
      val base = Math.abs(a)
      val e = (rnd.nextDouble() * 8 - 4)
      assertEquals("pow($base, $e)", StrictMath.pow(base, e).toRawBits(), FdLibm.pow(base, e).toRawBits())
    }
    for (x in listOf(-2.0, -0.5, 0.0, -0.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e-310, 1e300)) {
      for (y in listOf(-3.0, -2.0, -0.5, 0.0, 0.5, 1.0, 2.0, 3.0, 1e10, -1e10, 1e20, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1074.5, -1074.5)) {
        assertEquals("pow($x, $y)", StrictMath.pow(x, y).toRawBits(), FdLibm.pow(x, y).toRawBits())
        assertEquals("hypot($x, $y)", StrictMath.hypot(x, y).toRawBits(), FdLibm.hypot(x, y).toRawBits())
      }
    }
  }
}
