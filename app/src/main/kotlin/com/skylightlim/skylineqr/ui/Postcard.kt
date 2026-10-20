package com.skylightlim.skylineqr.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The city as it looks right now, with its code on a white card in the corner, so the picture
 * people actually want to share still takes them to the link.
 */
fun postcard(city: Bitmap, code: Bitmap, host: String): Bitmap {
  // The live view leaves room at the bottom for the control panel; a postcard is a 4:5 frame
  // around the city instead, with the card in the empty sky below it.
  val top = (city.height * 0.14f).toInt()
  val frame = minOf(city.height - top, (city.width * 1.25f).toInt())
  val out = Bitmap.createBitmap(city, 0, top, city.width, frame).copy(Bitmap.Config.ARGB_8888, true)
  val canvas = Canvas(out)
  val width = out.width.toFloat()
  val side = width * 0.34f
  val margin = width * 0.05f
  val pad = side * 0.05f
  val captionHeight = side * 0.16f
  val card = RectF(width - margin - side, out.height - margin - side - captionHeight, width - margin, out.height - margin)

  val paper =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      setShadowLayer(side * 0.04f, 0f, side * 0.012f, Color.argb(70, 0, 0, 0))
    }
  canvas.drawRoundRect(card, side * 0.06f, side * 0.06f, paper)

  // Nearest-neighbour scaling keeps the modules crisp; a blurred code scans worse.
  val crisp = Paint().apply { isFilterBitmap = false }
  canvas.drawBitmap(code, null, RectF(card.left + pad, card.top + pad, card.right - pad, card.top + side - pad), crisp)

  val caption =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0x2B, 0x2F, 0x38)
      textSize = captionHeight * 0.42f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textAlign = Paint.Align.CENTER
    }
  canvas.drawText(host, card.centerX(), card.top + side + captionHeight * 0.38f, caption)
  return out
}
