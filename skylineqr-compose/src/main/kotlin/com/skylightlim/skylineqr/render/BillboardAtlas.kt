package com.skylightlim.skylineqr.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A billboard's messages drawn as text, white on nothing, one per row of a single-channel texture
 * the shader colours and lights. Android's own fonts draw them, so any script works (马大牛逼 comes
 * out in Noto Sans CJK). A message is drawn as large as fits the screen, down to half size; longer
 * than that, it is drawn full size and the [Billboard] scrolls it.
 */
internal object BillboardAtlas {

  const val WIDTH = 2048
  const val ROW = 128
  const val ROWS = 8

  /** The screen's width over its height. Keep in step with the core's mall screen. */
  const val ASPECT = 2.8f / 1.2f

  /** How much of a row the screen shows at once. */
  const val VIEW = ASPECT * ROW / WIDTH

  class Drawn(val pixels: ByteBuffer, val widths: FloatArray)

  fun draw(messages: List<String>): Drawn {
    val shown = messages.filter { it.isNotBlank() }.take(ROWS)
    val pixels = ByteBuffer.allocateDirect(WIDTH * ROW * ROWS).order(ByteOrder.nativeOrder())
    val widths = FloatArray(shown.size)
    val paint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      }
    val row = Bitmap.createBitmap(WIDTH, ROW, Bitmap.Config.ALPHA_8)
    val canvas = Canvas(row)
    val margin = 0.06f * ROW
    for ((i, text) in shown.withIndex()) {
      val full = ROW * 0.74f
      paint.textSize = full
      val fit = full * (VIEW * WIDTH - 2 * margin) / paint.measureText(text)
      if (fit < full && fit >= full * 0.5f) paint.textSize = fit
      val width = (paint.measureText(text) + 2 * margin).coerceAtMost(WIDTH.toFloat())
      row.eraseColor(Color.TRANSPARENT)
      val metrics = paint.fontMetrics
      canvas.drawText(text, margin, ROW / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
      pixels.position(i * WIDTH * ROW)
      row.copyPixelsToBuffer(pixels)
      widths[i] = width / WIDTH
    }
    row.recycle()
    pixels.position(0)
    return Drawn(pixels, widths)
  }
}
