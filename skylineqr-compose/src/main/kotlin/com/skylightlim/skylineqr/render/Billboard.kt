package com.skylightlim.skylineqr.render

/**
 * What a billboard shows when (2026-10-21): its messages one after another, each held while it
 * fits the screen, or, too long to fit at a readable size, held at its start, scrolled through to
 * its end and held again; then the screen turns to the next like a tri-vision board, its slats
 * flipping in a wave. Positions are in the atlas row's own width (see [BillboardAtlas]); a pure
 * function of time.
 */
internal class Billboard(
  /** Each message's width, as a fraction of the atlas row. */
  private val widths: FloatArray,
  /** How much of a row the screen shows at once. */
  private val view: Float,
) {
  private val holds = FloatArray(widths.size) { i -> if (scrolls(i)) END_HOLD * 2 + (widths[i] - view) / speed() else HOLD }
  private val cycle = holds.sum() + FLIP * widths.size

  /** A message drawn to fit can come out a hair wider than the screen; only a real overrun scrolls. */
  private fun scrolls(i: Int) = widths[i] > view * 1.03f

  /** Screen widths per second. */
  private fun speed() = view / SCREEN_SECONDS

  /**
   * The screen at [time] into [out]: row, offset and width of the message showing and of the one
   * it is turning to, then the flip's progress (0 when still), the view, and whether to show at
   * all; three vec4s of the Frame uniform. With [calm] nothing scrolls or flips.
   */
  fun state(time: Float, calm: Boolean, out: FloatArray) {
    if (widths.isEmpty()) {
      out.fill(0f)
      return
    }
    var t = ((time % cycle) + cycle) % cycle
    for (i in widths.indices) {
      val next = (i + 1) % widths.size
      if (t < holds[i]) {
        put(out, i, if (calm) start(i) else offset(i, t), next, start(next), 0f)
        return
      }
      t -= holds[i]
      if (t < FLIP) {
        put(out, i, if (calm) start(i) else end(i), next, start(next), if (calm) 0f else t / FLIP)
        return
      }
      t -= FLIP
    }
    put(out, 0, start(0), 1 % widths.size, start(1 % widths.size), 0f)
  }

  /** Where message [i] starts: centred if it fits, else at its beginning. */
  private fun start(i: Int) = if (scrolls(i)) 0f else (widths[i] - view) / 2f

  private fun end(i: Int) = if (scrolls(i)) widths[i] - view else start(i)

  private fun offset(i: Int, t: Float): Float {
    if (!scrolls(i)) return start(i)
    return ((t - END_HOLD) * speed()).coerceIn(0f, widths[i] - view)
  }

  private fun put(out: FloatArray, a: Int, offsetA: Float, b: Int, offsetB: Float, flip: Float) {
    out[0] = a.toFloat()
    out[1] = offsetA
    out[2] = widths[a]
    out[3] = 0f
    out[4] = b.toFloat()
    out[5] = offsetB
    out[6] = widths[b]
    out[7] = 0f
    out[8] = flip
    out[9] = view
    out[10] = 1f
    out[11] = 0f
  }

  companion object {
    /** Seconds a message that fits stays up, a long one waits at each end, and a flip takes. */
    const val HOLD = 3.2f
    const val END_HOLD = 0.9f
    const val FLIP = 0.7f

    /** A long message moves one screen width in this many seconds. */
    const val SCREEN_SECONDS = 1.8f

    /** Floats of the Frame uniform the billboard fills. */
    const val FLOATS = 12

    val NONE = Billboard(FloatArray(0), 1f)
  }
}
