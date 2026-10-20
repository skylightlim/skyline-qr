package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The flat code's colours, module by module. Every dark module keeps the colour of the tower that
 * stood on it, in the hour's light, so the code carries the city and the time instead of one ink;
 * every light module is the hour's paper, a shade lighter or darker.
 *
 * What makes it scan: ZXing's HybridBinarizer thresholds each patch of the image at about the
 * average of the patches round it. Inside a solid dark area at a phone screen's module size that
 * threshold sits just above the darkest ink nearby, so any lighter ink beside it reads as paper,
 * however small the difference. So every ink has exactly the same brightness, [INK_LUMA], under
 * ZXing's own (R + 2G + B) / 4, and differs only in hue: to ZXing the code is one ink on paper.
 * The camera's BT.601 Y and the eye's Rec. 709 luma weigh the channels differently, so under them
 * the inks differ a little; each ink gives up as much chroma as it must to stay within [BAND_601]
 * and [BAND_709] of [INK_LUMA]. To the eye that difference is welcome: greens read lighter than
 * blues, as in a real city from above.
 *
 * The renderer's WGSL ink() and paperShade() are the same arithmetic; keep them in step.
 */
object FlatColours {

  /** The ink's brightness under ZXing's luma, well under half the paper's 0.8. */
  const val INK_LUMA = 0.29f

  /** How far an ink's BT.601 luma, which a phone camera's Y plane carries, may stray from [INK_LUMA]. */
  const val BAND_601 = 0.02f

  /** How far an ink's Rec. 709 luma may stray from [INK_LUMA]. */
  const val BAND_709 = 0.07f

  /** Chroma is boosted this much before fitting, so the city's colours read as colour. */
  const val INK_CHROMA = 2.0f

  /** How far a light module's brightness strays from the paper's. */
  const val PAPER_JITTER = 0.012f

  /** The ink of a dark module built of [roof], in [inkLight]. */
  fun ink(roof: FloatArray, inkLight: FloatArray): FloatArray {
    val lit = FloatArray(3) { roof[it] * inkLight[it] }
    val grey = lumaZxing(lit)
    val c = FloatArray(3) { (grey + (lit[it] - grey) * INK_CHROMA).coerceIn(0f, 1f) }
    val s = INK_LUMA / max(lumaZxing(c), 0.001f)
    for (i in 0..2) c[i] *= s
    // Moving toward grey keeps every luma formula's value, so it is how each limit is met: first
    // a hue too saturated to reach the brightness without clipping, then the two bands.
    val peak = max(c[0], max(c[1], c[2]))
    if (peak > 1f) toward(c, (1f - INK_LUMA) / (peak - INK_LUMA))
    var k = 1f
    val d601 = abs(luma601(c) - INK_LUMA)
    val d709 = abs(luma(c) - INK_LUMA)
    if (d601 > 1e-5f) k = min(k, BAND_601 / d601)
    if (d709 > 1e-5f) k = min(k, BAND_709 / d709)
    toward(c, k)
    return c
  }

  /** A light module: the hour's [paper], a little lighter or darker with the module's random [rnd]. */
  fun paper(paper: FloatArray, rnd: Float): FloatArray {
    val shade = 1f + PAPER_JITTER * (frac(rnd * 7f) * 2f - 1f)
    return FloatArray(3) { (paper[it] * shade).coerceIn(0f, 1f) }
  }

  /**
   * Module ([col], [row]) of [city]'s flat code under [air]; outside the code, the quiet zone's
   * paper. The function patterns (finders, timing, alignment, format) all take the palette's most
   * colourful family, so each finder reads as one colour.
   */
  fun module(city: City, col: Int, row: Int, air: Atmosphere): FloatArray {
    val n = city.modules
    if (col !in 0 until n || row !in 0 until n) return air.paper
    val rnd = CityPlanner.moduleRandom(col, row, city.identity.seed)
    if (!city.qr.isDark(col, row)) return paper(air.paper, rnd)
    val role = city.qr.role(col, row)
    val pattern = role != QrRole.DATA && role != QrRole.REMAINDER
    return ink(family(city.materials, if (pattern) anchorFamily(city.materials) else CityPlanner.family(rnd)), air.inkLight)
  }

  /** The family the function patterns are inked in: the palette's most colourful. */
  fun anchorFamily(materials: MaterialRecipe): Int =
    (0 until 4).maxBy { f -> (0..2).maxOf { materials.families[f * 3 + it] } - (0..2).minOf { materials.families[f * 3 + it] } }

  fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

  fun luma601(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

  fun lumaZxing(c: FloatArray) = (c[0] + 2f * c[1] + c[2]) / 4f

  private fun family(materials: MaterialRecipe, f: Int) = materials.families.copyOfRange(f * 3, f * 3 + 3)

  private fun toward(c: FloatArray, k: Float) {
    for (i in 0..2) c[i] = INK_LUMA + (c[i] - INK_LUMA) * k
  }

  private fun frac(v: Float) = v - kotlin.math.floor(v)
}
