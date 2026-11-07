package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The flat code's colours (2026-11-07 revision, at the user's request: "look lively", after two
 * many-coloured pictures of Kuala Lumpur): every part of the city has its own colour, as each
 * building does in those pictures, and still no gradient (2026-10-31). Every dark module is a
 * square of one of [INKS] inks: [HUES] lively hues, each in a full and a softer shade. A module
 * takes its district's hue in either shade, at random, so each district is a mosaic of its colour
 * with ragged, interlocking borders and nothing drifting across it; about one in [ACCENTS] takes
 * a neighbouring hue instead, like the odd leaf of another colour. A finder is one ink whole, since
 * its solid middle is the largest area with no paper in it. The hour and weather still tint every
 * ink a little ([HOUR_SHARE] of [Atmosphere.ink]). Every light module is a square of the hour's
 * paper in one of four shades, like paving.
 *
 * The city keeps its districts: each one's towers are built of its colour ([block]), and the morph
 * washes them to their inks before they land.
 *
 * What makes it scan: inside a solid dark area, ZXing's HybridBinarizer takes an 8 px block for
 * one tone only while its pixels span at most 24 levels; past that, it thresholds between them
 * and the lighter ink reads as paper. In practice a block mostly of a darker ink pulls the
 * threshold down well before that: at 42 px a module, where a module is wider than its window, 12
 * levels failed. So every ink has exactly [INK_LUMA] under ZXing's own (R + 2G + B) / 4 and they lie
 * within [INK_601_BAND] of [INK_601] under the camera's BT.601; any two can touch. Those two
 * brightnesses together leave only a narrow plane of colours: crimson, pinks, magenta, purples,
 * blue, teal and green fit; orange and yellow cannot, being bright colours, and come out brown, so
 * they are left out. Each hue is solved onto that plane by least squares, keeping it as close to
 * itself as it can be. The paper's shades span under [PAPER_SPREAD].
 *
 * The renderer gets every ink from [ink] and every tower's colour from [block] through its frame
 * uniform, so the screen shows exactly what the tests scan.
 */
object FlatColours {

  /** Every ink's brightness under ZXing's luma, well under half the paper's 0.8. */
  const val INK_LUMA = 0.32f

  /** Every ink's brightness under the camera's BT.601, give or take [INK_601_BAND]. */
  const val INK_601 = 0.31f
  const val INK_601_BAND = 0.025f

  /**
   * The lively hues, in order round the wheel from warm to cool: crimson, pink, magenta, purple,
   * violet, blue, teal, green. Neighbours in this list are a module's accents.
   */
  private val HUE_COLOURS = intArrayOf(0xE0303A, 0xF0609A, 0xD83FA8, 0xA040C0, 0x8A4BE0, 0x2F8FE0, 0x12A89A, 0x38B04A)
  const val HUES = 8

  /** How many inks there are: each hue in a full shade and a softer one. */
  const val INKS = HUES * 2

  /** How much colour the softer shade keeps. */
  private const val SOFT_CHROMA = 0.68f

  /** How many of a district's modules take the softer shade: half, so each reads as a mosaic. */
  private const val SOFT = 0.5f

  /** How many modules take a neighbouring hue: a few, like the odd leaf of another colour. */
  private const val ACCENTS = 0.1f

  /** How much of the hour's own ink tints every hue, so the code still carries the hour and weather. */
  private const val HOUR_SHARE = 0.2f

  /** Kuala Lumpur's districts' hues: the gardens green, the mall magenta, the square purple, the twins teal, the supertall blue, the tower crimson. */
  private val KL_HUES = intArrayOf(7, 2, 3, 6, 5, 0)

  /** A paler tower is its district's colour this far toward [STONE]. */
  const val PALE_BLOCK = 0.4f
  private val STONE = floatArrayOf(0.92f, 0.90f, 0.86f)

  /** How many in a district's towers are paler. */
  private const val PALER = 0.4f

  /** How ragged the districts' borders are: each module's distances shift by up to half this. */
  private const val RAGGED = 0.6f

  /**
   * How far a finder corner's district reaches against a landmark's: the landmarks stand toward
   * the middle, and without it the corners' districts would be little more than their finders.
   */
  private const val CORNER_REACH = 1.4f

  /** Tower colours a city can have: two for each of up to eight districts. */
  const val MAX_TONES = 16

  /** The paper's four shades, as factors of the hour's paper. */
  val PAPER_SHADES = floatArrayOf(0.968f, 0.982f, 0.994f, 1.006f)

  /** The paper's shades span at most this much brightness, darkest to lightest, under any formula. */
  const val PAPER_SPREAD = 0.04f

  /** Kuala Lumpur's districts, in the order of its recipe's district colours. */
  const val GARDEN = 0
  const val MALL = 1
  const val SQUARE = 2
  const val TWINS = 3
  const val SUPERTALL = 4
  const val TELECOM = 5

  /** How many tower colours [recipe]'s districts give: each one's own and its paler one. */
  fun tones(recipe: MaterialRecipe) = min(recipe.districts.size / 3, MAX_TONES / 2) * 2

  /** The colour of a tower of tone [tone]: its district's, or (an odd tone) a paler one. */
  fun block(recipe: MaterialRecipe, tone: Int): FloatArray {
    val d = (tone / 2).coerceIn(0, recipe.districts.size / 3 - 1)
    val c = FloatArray(3) { recipe.districts[d * 3 + it] }
    if (tone % 2 == 1) for (k in 0..2) c[k] += (STONE[k] - c[k]) * PALE_BLOCK
    return c
  }

  /**
   * Ink [index] under [air]: its hue (index / 2), a little of the hour in it, the softer shade for
   * an odd index, solved onto the inks' two brightnesses.
   */
  fun ink(index: Int, air: Atmosphere): FloatArray {
    val hue = HUE_COLOURS[index / 2]
    val c = FloatArray(3) { k ->
      val own = ((hue shr (16 - 8 * k)) and 0xFF) / 255f
      own + (air.ink[k] - own) * HOUR_SHARE
    }
    if (index % 2 == 1) {
      val grey = lumaZxing(c)
      for (k in 0..2) c[k] = grey + (c[k] - grey) * SOFT_CHROMA
    }
    return solve(c)
  }

  /**
   * [c] as close to itself as it can be with ZXing's luma exactly [INK_LUMA] and BT.601's within
   * [INK_601_BAND] of [INK_601]: scaled to the first, then moved the least distance onto both.
   */
  private fun solve(c: FloatArray): FloatArray {
    val s = INK_LUMA / max(lumaZxing(c), 0.001f)
    for (k in 0..2) c[k] *= s
    val target601 = luma601(c).coerceIn(INK_601 - INK_601_BAND, INK_601 + INK_601_BAND)
    // The two brightnesses' weights, and the inverse of their Gram matrix.
    val a = arrayOf(floatArrayOf(0.25f, 0.5f, 0.25f), floatArrayOf(0.299f, 0.587f, 0.114f))
    val g00 = a[0].indices.sumOf { (a[0][it] * a[0][it]).toDouble() }.toFloat()
    val g01 = a[0].indices.sumOf { (a[0][it] * a[1][it]).toDouble() }.toFloat()
    val g11 = a[1].indices.sumOf { (a[1][it] * a[1][it]).toDouble() }.toFloat()
    val det = g00 * g11 - g01 * g01
    val r0 = INK_LUMA - lumaZxing(c)
    val r1 = target601 - luma601(c)
    val l0 = (g11 * r0 - g01 * r1) / det
    val l1 = (g00 * r1 - g01 * r0) / det
    for (k in 0..2) c[k] = (c[k] + a[0][k] * l0 + a[1][k] * l1).coerceIn(0f, 1f)
    return c
  }

  /** Which of the [HUES] a district takes: Kuala Lumpur's by name, any other city's spread round the wheel. */
  fun hue(district: Int, preset: CityPreset, districts: Int): Int =
    if (preset == CityPreset.KUALA_LUMPUR) KL_HUES[district.coerceIn(0, KL_HUES.size - 1)]
    else (district * (HUES - 1) / max(districts - 1, 1)).coerceIn(0, HUES - 1)

  /** The paper in shade [shade] of [PAPER_SHADES]. */
  fun paper(shade: Int, air: Atmosphere): FloatArray = FloatArray(3) { (air.paper[it] * PAPER_SHADES[shade]).coerceIn(0f, 1f) }

  /** The quiet zone's paper: the hour's own. */
  fun paper(air: Atmosphere): FloatArray = air.paper

  /**
   * Each module's ink, row by row: a dark module takes its district's hue ([tones] / 2, through
   * [hue]) in its full or softer shade at random, or now and then a neighbouring hue; a finder is
   * its district's full shade whole. Part of the city, so seeded and deterministic like the planner.
   */
  fun inks(matrix: QrMatrix, tones: IntArray, preset: CityPreset, districts: Int, seed: Int): IntArray {
    val n = matrix.size
    return IntArray(n * n) { i ->
      val col = i % n
      val row = i / n
      // A finder, light ring and all, takes the district of its outer corner, which is dark.
      if (matrix.role(col, row) == QrRole.FINDER) {
        val corner = (if (col < 7) 0 else n - 1) + (if (row < 7) 0 else n * (n - 1))
        return@IntArray hue(tones[corner] / 2, preset, districts) * 2
      }
      if (!matrix.isDark(col, row)) return@IntArray 0
      val home = hue(tones[i] / 2, preset, districts)
      if (unit(seed + 53, i) < ACCENTS) {
        val side = if (unit(seed + 59, i) < 0.5f) -1 else 1
        val other = (home + side).let { if (it in 0 until HUES) it else home - side }
        return@IntArray other * 2
      }
      home * 2 + if (unit(seed + 67, i) < SOFT) 1 else 0
    }
  }

  /**
   * Each module's tone, row by row: a dark module's tower colour (twice its district, plus one if
   * paler), a light module's paper shade. Part of the city, so seeded and deterministic too.
   */
  fun tones(matrix: QrMatrix, preset: CityPreset, landmarks: LandmarkPlan?, seed: Int, recipe: MaterialRecipe): IntArray {
    val n = matrix.size
    val far = n - 3.5f
    val anchors = ArrayList<FloatArray>()
    fun anchor(x: Float, z: Float, district: Int, reach: Float = 1f) {
      anchors += floatArrayOf(x, z, district.toFloat(), 1f / (reach * reach))
    }
    if (preset == CityPreset.KUALA_LUMPUR) {
      anchor(3.5f, 3.5f, GARDEN, CORNER_REACH)
      anchor(far, 3.5f, MALL, CORNER_REACH)
      anchor(3.5f, far, SQUARE, CORNER_REACH)
      landmarks?.twins?.let { (a, b) -> anchor((a.centerX + b.centerX) / 2f, (a.centerZ + b.centerZ) / 2f, TWINS) }
      landmarks?.supertall?.let { anchor(it.centerX, it.centerZ, SUPERTALL) }
      landmarks?.telecom?.let { anchor(it.centerX, it.centerZ, TELECOM) }
    } else {
      val districts = tones(recipe) / 2
      for (k in 0 until 5) {
        anchor(3.5f + (n - 7) * unit(seed, k * 3 + 1), 3.5f + (n - 7) * unit(seed, k * 3 + 2), (unit(seed, k * 3 + 3) * districts).toInt().coerceAtMost(districts - 1))
      }
    }
    val out = IntArray(n * n)
    for (row in 0 until n) for (col in 0 until n) {
      val i = row * n + col
      if (!matrix.isDark(col, row)) {
        out[i] = (unit(seed + 17, i) * PAPER_SHADES.size).toInt()
        continue
      }
      var x = col + 0.5f
      var z = row + 0.5f
      val finder = matrix.role(col, row) == QrRole.FINDER
      if (finder) {
        x = if (col < 7) 3.5f else far
        z = if (row < 7) 3.5f else far
      }
      // The nearest anchor's district, each distance shifted a little for every module, so
      // neighbouring districts interlock along their border.
      var home = 0
      var nearest = Float.MAX_VALUE
      for ((k, a) in anchors.withIndex()) {
        val ragged = if (finder) 1f else 1f + RAGGED * (unit(seed + 41 + k, i) - 0.5f)
        val d = ((x - a[0]) * (x - a[0]) + (z - a[1]) * (z - a[1])) * a[3] * ragged
        if (d < nearest) {
          nearest = d
          home = a[2].toInt()
        }
      }
      val paler = !finder && unit(seed + 29, i) < PALER
      out[i] = home * 2 + if (paler) 1 else 0
    }
    return out
  }

  /** Module ([col], [row]) of [city]'s flat code under [air]; outside the code, the quiet zone's paper. */
  fun module(city: City, col: Int, row: Int, air: Atmosphere): FloatArray {
    val n = city.modules
    if (col !in 0 until n || row !in 0 until n) return paper(air)
    val i = row * n + col
    return if (city.qr.isDark(col, row)) ink(city.inks[i], air) else paper(city.tones[i], air)
  }

  /** The flat code at the point ([x], [y]) in modules from its top left: square modules, as drawn. */
  fun at(city: City, x: Float, y: Float, air: Atmosphere): FloatArray = module(city, floor(x).toInt(), floor(y).toInt(), air)

  fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

  fun luma601(c: FloatArray) = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

  fun lumaZxing(c: FloatArray) = (c[0] + 2f * c[1] + c[2]) / 4f

  private fun unit(seed: Int, salt: Int): Float {
    var h = seed * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
  }
}
