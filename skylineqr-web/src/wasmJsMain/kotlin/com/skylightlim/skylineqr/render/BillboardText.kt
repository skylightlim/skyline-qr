package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.web.gpuDrawAtlas

/**
 * The billboard's messages drawn as text, white on nothing, one per row of a single-channel
 * texture, as skylineqr-compose's BillboardAtlas does with Android's fonts; here the browser's
 * canvas draws them (gpu.js), in the page's own fonts. Same sizes and layout, so [Billboard] times
 * them the same.
 */
internal object BillboardText {

  const val WIDTH = 2048
  const val ROW = 128
  const val ROWS = 8

  /** The screen's width over its height. Keep in step with the core's mall screen. */
  const val ASPECT = 2.8f / 1.2f

  /** How much of a row the screen shows at once. */
  const val VIEW = ASPECT * ROW / WIDTH

  /** Draws [messages] into the GPU's atlas; returns each one's width as a share of a row. */
  fun draw(messages: List<String>): FloatArray {
    val shown = messages.filter { it.isNotBlank() }.take(ROWS).map { it.replace('\n', ' ') }
    if (shown.isEmpty()) return FloatArray(0)
    val widths = gpuDrawAtlas(shown.joinToString("\n"), WIDTH, ROW, ROWS, VIEW)
    return widths.split(',').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
  }
}
