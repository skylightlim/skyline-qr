package com.skylightlim.skylineqr.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The city-to-code transition: 0 is the city, 1 the flat code. Progress runs at one steady pace,
 * so a reversal mid-way starts from where the morph is and takes only as long as the distance left,
 * and a quick double tap never jumps (that timing follows every-qrcode's tree morph, MIT, see NOTICE).
 *
 * Two things follow the progress. The camera [swing]s overhead quickly, in the first half. The
 * city's [fold] starts almost at once but runs to the very end, and each block takes only part of
 * it, on its own beat, so blocks keep dropping into their modules after the camera has arrived.
 */
internal class Morph {

  private var from = 0f
  private var target = 0f
  private var startNanos = 0L

  /** Linear progress at [nowNanos]. */
  fun at(nowNanos: Long): Float {
    val moved = (nowNanos - startNanos).toFloat() / DURATION_NANOS
    return if (target >= from) min(target, from + moved) else max(target, from - moved)
  }

  fun settled(nowNanos: Long) = at(nowNanos) == target

  /** Where the morph is heading: 1 toward the flat code, 0 toward the city. */
  val heading: Float
    get() = target

  fun aim(flat: Boolean, nowNanos: Long) {
    val next = if (flat) 1f else 0f
    if (next == target) return
    from = at(nowNanos)
    target = next
    startNanos = nowNanos
  }

  /** Jumps straight to the end, for a snapshot that must show the finished code. */
  fun finish(flat: Boolean) {
    target = if (flat) 1f else 0f
    from = target
  }

  companion object {
    /** The whole morph, the last block landing. */
    const val DURATION_NANOS = 900_000_000L

    /** Where in the progress the camera is overhead. */
    private const val SWING_END = 0.55f

    /** Where in the progress the first blocks start to fold. */
    private const val FOLD_FROM = 0.08f

    /** Sine ease in and out. */
    fun curve(x: Float): Float {
      if (x <= 0f) return 0f
      if (x >= 1f) return 1f
      return 0.5f - 0.5f * cos(PI.toFloat() * x)
    }

    /** The camera's eased swing at [progress]: overhead and square by [SWING_END]. */
    fun swing(progress: Float): Float = curve(progress / SWING_END)

    /** How far the fold has run at [progress]. Each block eases through its own part of it. */
    fun fold(progress: Float): Float = ((progress - FOLD_FROM) / (1f - FOLD_FROM)).coerceIn(0f, 1f)
  }
}
