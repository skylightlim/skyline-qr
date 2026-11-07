package com.skylightlim.skylineqr.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.FlatColours

/**
 * The code alone, without the city: what shows when WebGPU cannot start, so the link still scans,
 * and the shared image. Colours follow the flat 3D code (see [FlatColours]): a mosaic of square
 * modules, each dark one in its ink and each light one in its shade of the hour's paper.
 */
internal object FlatCode {

  const val QUIET = 4

  fun paper(air: Atmosphere): Int = argb(air.paper)

  fun bitmap(city: City, air: Atmosphere, modulePx: Int = 16): Bitmap {
    val side = (city.modules + 2 * QUIET) * modulePx
    val n = city.modules
    val colours = IntArray(n * n) { argb(FlatColours.module(city, it % n, it / n, air)) }
    val paper = paper(air)
    val pixels = IntArray(side * side) { i ->
      val col = (i % side) / modulePx - QUIET
      val row = (i / side) / modulePx - QUIET
      if (col in 0 until n && row in 0 until n) colours[row * n + col] else paper
    }
    return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
  }

  fun argb(c: FloatArray): Int = (0xff shl 24) or (channel(c[0]) shl 16) or (channel(c[1]) shl 8) or channel(c[2])

  private fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

@Composable
internal fun FlatCodeView(city: City, air: Atmosphere, modifier: Modifier = Modifier) {
  val n = city.modules
  val colours = remember(city, air) { List(n * n) { Color(FlatCode.argb(FlatColours.module(city, it % n, it / n, air))) } }
  Canvas(modifier) {
    val cell = size.minDimension / (n + 2 * FlatCode.QUIET)
    val origin = Offset((size.width - cell * (n + 2 * FlatCode.QUIET)) / 2f, (size.height - cell * (n + 2 * FlatCode.QUIET)) / 2f)
    drawRect(Color(FlatCode.paper(air)))
    for (row in 0 until n) {
      for (col in 0 until n) {
        drawRect(
          colours[row * n + col],
          topLeft = Offset(origin.x + (col + FlatCode.QUIET) * cell, origin.y + (row + FlatCode.QUIET) * cell),
          size = Size(cell + 0.5f, cell + 0.5f), // a half-pixel overlap hides seams between modules
        )
      }
    }
  }
}
