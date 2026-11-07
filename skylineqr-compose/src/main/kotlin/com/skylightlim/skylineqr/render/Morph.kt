package com.skylightlim.skylineqr.render

import kotlin.math.max
import kotlin.math.min

/**
 * The city-to-code transition: 0 is the city, 1 the flat code. Each of its two quantities runs on
 * a linear position that moves at one steady pace, so a reversal mid-way starts from where the
 * morph is and a quick double tap never jumps (that timing follows every-qrcode's tree morph, MIT,
 * see NOTICE).
 *
 * Both read that position through a curve chosen by the heading (2026-11-01 revision, measured
 * from a screen recording of the ICQR tree), so each way responds at once and settles gently. The
 * camera [swing]s across the whole morph, most of the way in the first third of a second and the
 * rest as a long soft tail. The city's [fold] starts at once and has landed by about 60 % of the
 * morph: in the shader, its colours wash early and all together, what is not part of the code
 * shrinks away, and the towers widen and sink. On a reversal each position is moved to where its
 * new curve gives the value it has now, so nothing jumps.
 *
 * The way back from the code is not the fold played backwards (2026-11-06 revision: the towers
 * shot up as the code's slabs and the landmarks popped in late). Leaving the code once its blocks
 * are home, the morph is [raising]: the fold then runs at its steady pace both ways, and the
 * shader times the raise itself, the city's colours and footprints first and then everything
 * growing up from the ground, the taller the slower. A reversal mid-way keeps the way it is on.
 */
internal class Morph {

  private var target = 0f

  /** Whether the fold is on the raise, from the code back to the city, rather than the fold. */
  var raising = false
    private set
  private val foldChannel = Channel(::foldToward, ::foldAway, ::foldTowardInverse, ::foldAwayInverse)
  private val swingChannel = Channel(::curve, ::uncurve, ::curveInverse, ::uncurveInverse)

  /** How far the fold has run at [nowNanos]. Each block eases through its own part of it. */
  fun fold(nowNanos: Long): Float = foldChannel.value(target, nowNanos)

  /** The camera's eased swing at [nowNanos]: overhead and square at 1. */
  fun swing(nowNanos: Long): Float = swingChannel.value(target, nowNanos)

  fun settled(nowNanos: Long) = foldChannel.at(target, nowNanos) == target && swingChannel.at(target, nowNanos) == target

  /** Where the morph is heading: 1 toward the flat code, 0 toward the city. */
  val heading: Float
    get() = target

  fun aim(flat: Boolean, nowNanos: Long) {
    val next = if (flat) 1f else 0f
    if (next == target) return
    // Both ways show the same city at 0 and the same code at 1, so the way only changes there.
    if (foldChannel.value(target, nowNanos) == target) raising = !flat
    foldChannel.turn(target, next, nowNanos, straight = raising)
    swingChannel.turn(target, next, nowNanos)
    target = next
  }

  /** Jumps straight to the end, for a snapshot that must show the finished code. */
  fun finish(flat: Boolean) {
    target = if (flat) 1f else 0f
    raising = false
    foldChannel.straight = false
    foldChannel.from = target
    swingChannel.from = target
  }

  /**
   * One quantity the morph drives: a position that runs linearly toward the target, read through
   * [toward] while heading for the code and [away] while heading back to the city, or as it is
   * both ways while [straight].
   */
  private class Channel(
    val toward: (Float) -> Float,
    val away: (Float) -> Float,
    val towardInverse: (Float) -> Float,
    val awayInverse: (Float) -> Float,
  ) {
    var from = 0f
    var straight = false
    private var startNanos = 0L

    fun at(target: Float, nowNanos: Long): Float {
      // A frame's vsync time can fall just before the tap that aimed the morph.
      val moved = max(0L, nowNanos - startNanos).toFloat() / DURATION_NANOS
      return if (target >= from) min(target, from + moved) else max(target, from - moved)
    }

    fun value(target: Float, nowNanos: Long): Float {
      val x = at(target, nowNanos)
      return if (straight) x else (if (target > 0.5f) toward else away)(x)
    }

    /** Turns toward [next], from now on read through its curve, or as it is if [straight]. */
    fun turn(old: Float, next: Float, nowNanos: Long, straight: Boolean = this.straight) {
      val now = value(old, nowNanos)
      this.straight = straight
      from = if (straight) now else (if (next > 0.5f) towardInverse else awayInverse)(now)
      startNanos = nowNanos
    }
  }

  companion object {
    /** The whole morph, the camera settling. */
    const val DURATION_NANOS = 850_000_000L

    /** Where in the position the fold starts, heading for the code. */
    private const val FOLD_FROM = 0.0f

    /** Where in the position the last block has landed; the camera settles over the rest. */
    private const val FOLD_TO = 0.6f

    private fun foldToward(x: Float) = ((x - FOLD_FROM) / (FOLD_TO - FOLD_FROM)).coerceIn(0f, 1f)

    private fun foldTowardInverse(v: Float) = if (v <= 0f) 0f else if (v >= 1f) 1f else FOLD_FROM + v * (FOLD_TO - FOLD_FROM)

    /** Heading back to the city, the same window seen from the other end. */
    private fun foldAway(x: Float) = 1f - foldToward(1f - x)

    private fun foldAwayInverse(v: Float) = 1f - foldTowardInverse(1f - v)

    /**
     * The camera's ease toward the code: a cubic Bézier from (0, 0) to (1, 1) through (0.10, 0.45)
     * and (0.15, 1), fitted to the ICQR tree's yaw frame by frame: half way in 0.12 s, four fifths
     * in 0.3 s, and the last few percent over the rest of the morph.
     */
    fun curve(x: Float): Float {
      if (x <= 0f) return 0f
      if (x >= 1f) return 1f
      return bezier(solve(x) { bezier(it, 0.10f, 0.15f) }, 0.45f, 1f)
    }

    fun curveInverse(y: Float): Float {
      if (y <= 0f) return 0f
      if (y >= 1f) return 1f
      return bezier(solve(y) { bezier(it, 0.45f, 1f) }, 0.10f, 0.15f)
    }

    /** The same ease heading back to the city, so the way back also leaves quickly and settles. */
    private fun uncurve(x: Float) = 1f - curve(1f - x)

    private fun uncurveInverse(y: Float) = 1f - curveInverse(1f - y)

    /** The t at which a monotonic [f] on 0..1 reaches [x], by bisection: 24 halvings pass a float's step. */
    private inline fun solve(x: Float, f: (Float) -> Float): Float {
      var lo = 0f
      var hi = 1f
      repeat(24) {
        val t = (lo + hi) / 2f
        if (f(t) < x) lo = t else hi = t
      }
      return (lo + hi) / 2f
    }

    private fun bezier(t: Float, p1: Float, p2: Float): Float {
      val u = 1f - t
      return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }
  }
}
