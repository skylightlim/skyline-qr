package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The flat code's colours (2026-11-12, second revision, at the user's request: the sixteen vivid
 * Pantone colours of that morning were "too vibrant and saturated"; he asked for subtler ones):
 * Pantone colours of middling strength (a third pick that evening: the muted ones wanted to be "a
 * bit more lively and colorful", with no brown) from the chart at pantone-colours.com, without the
 * magenta pair since 2026-11-20 (the user's call, after trying two other palettes that day), and
 * the city's towers are built of the very ink their module takes, so a district looks the same
 * standing as it does folded. Every dark module is a square of one of [INKS] inks: [HUES] hues
 * round the wheel, each in two Pantone shades. A module takes its district's hue in either shade,
 * at random, so each district is a mosaic of its colour with ragged, interlocking borders and
 * nothing drifting across it; about one in ten takes a neighbouring hue instead, like the odd leaf
 * of another colour. A finder is one ink whole, since its solid middle is the largest area with no
 * paper in it. The inks are the same at every hour and in all weather; the hour only tints the
 * paper. Every light module is a square of the hour's paper in one of four shades, like paving.
 *
 * What makes it scan: inside a solid dark area, ZXing's HybridBinarizer takes an 8 px block for
 * one tone only while its pixels span at most 24 levels; past that, it thresholds between them
 * and the lighter ink reads as paper. In practice a block mostly of a darker ink pulls the
 * threshold down well before that: at 42 px a module, where a module is wider than its window, 12
 * levels failed. So every ink has exactly [INK_LUMA] under ZXing's own (R + 2G + B) / 4 and they lie
 * within [INK_601_BAND] of [INK_601] under the camera's BT.601; any two can touch. Those two
 * brightnesses together leave only a narrow plane of colours, through the greys: muted colours of
 * every hue lie on it or next to it, vivid ones only from rose round through blue to green. The
 * Pantone colours were picked for lying next to that plane, and each is then moved the least
 * distance onto it ([solve]), most by a few levels. The paper's shades span under [PAPER_SPREAD].
 *
 * The renderer gets every ink from [ink] through its frame uniform, for the towers and the code
 * alike, so the screen shows exactly what the tests scan.
 *
 * Grey blue (2026-11-22, at the user's request: "make the city in grey, blueish, not this colourful
 * option") is a second set of the same [INKS] inks, picked in [CityColours]: slate, smoke, dusk,
 * denim, steel, storm and mist, on the same two brightnesses and solved the same way, so the
 * same modules take the same index and the code scans the same. Presentation only, like the hour:
 * the city, its districts and its identity do not change.
 */
object FlatColours {

  /**
   * Every ink's brightness under ZXing's luma, well under half the paper's 0.8 (2026-11-12: up
   * from 0.32, where every hue but blue and violet was a deep, dull shade of itself; 0.40 was
   * livelier still but a small code no longer scanned on the darker paper of a stormy night).
   */
  const val INK_LUMA = 0.36f

  /**
   * Every ink's brightness under the camera's BT.601, give or take [INK_601_BAND]: the same as
   * under ZXing's, which is where the greys lie, so muted colours of any hue fit.
   */
  const val INK_601 = 0.36f
  const val INK_601_BAND = 0.025f

  /**
   * The inks' Pantone colours as the chart shows them, two shades a hue, in order round the wheel:
   * red (703, 2033), rose (2048, 689), purple (520, 668), violet (5275, 534), blue (647, 653),
   * teal (5473, 562) and green (363, 364). No browns (the clay and olive of 2026-11-12) and, since
   * 2026-11-20, no magenta (513, 254): the user's calls. Neighbours in this list are a module's
   * accents.
   */
  private val PANTONE =
    intArrayOf(
      0xB73844, 0xCB4644, 0x993B60, 0x934266, 0x703572, 0x664975,
      0x605677, 0x3A4972, 0x26547C, 0x335687, 0x26686D, 0x1E7A6D, 0x3D8E33, 0x3A7728,
    )
  const val HUES = 7

  /**
   * The grey blue inks (2026-11-22), two shades a hue in the same places as [PANTONE]'s, so each
   * of Kuala Lumpur's districts keeps a shade of its own: the square slate (where red is), the
   * accents a smoky silver and a dusky lilac grey, the supertall denim, the mall and the twins steel
   * blue, the tower a storm blue leaning to teal, the gardens a misty blue grey. The first shade
   * of each is the bluer one.
   */
  private val GREY_BLUE =
    intArrayOf(
      0x586478, 0x646972, 0x66626F, 0x5A6266, 0x5D5E7E, 0x62637A, 0x4A5F8C,
      0x545C90, 0x3F6585, 0x4A6680, 0x3E6A78, 0x4C6A73, 0x52747A, 0x5F6E73,
    )

  /** How many inks there are: each hue in two shades. */
  const val INKS = HUES * 2

  /** How many of a district's modules take the second shade: half, so each reads as a mosaic. */
  private const val SOFT = 0.5f

  /** How many modules take a neighbouring hue: a few, like the odd leaf of another colour. */
  private const val ACCENTS = 0.1f

  /**
   * Kuala Lumpur's districts' hues: the gardens green, the mall blue like the twins (it was magenta
   * until 2026-11-20), the square red, the twins blue, the supertall violet, the tower teal. The
   * rose and the purple, the hues nearest magenta, are only accents. A district of its own in
   * either read as magenta again, and the mall in rose also scanned worse: a small code drawn at
   * every tenth of a pixel from 7.5 to 9.5 a module misses only at 7.7 and 8.9 with this, as it
   * did with magenta, and at 8.6 too with a rose mall.
   */
  private val KL_HUES = intArrayOf(6, 4, 0, 4, 3, 5)

  /** How many in a district's towers are paler. */
  private const val PALER = 0.4f

  /** How many times the districts' reaches are evened out toward their shares; see [tones]. */
  private const val BALANCE_ROUNDS = 60

  /** How ragged the districts' borders are: each module's distances shift by up to half this. */
  private const val RAGGED = 0.6f

  /**
   * How far a finder corner's district reaches against a landmark's: the landmarks stand toward
   * the middle, and without it the corners' districts would be little more than their finders.
   */
  private const val CORNER_REACH = 1.4f

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

  /** How many tones [recipe]'s districts give: two each, a tower's own and its paler one. */
  fun tones(recipe: MaterialRecipe) = recipe.districts * 2

  private fun solved(colours: IntArray) = Array(INKS) { i -> solve(FloatArray(3) { k -> ((colours[i] shr (16 - 8 * k)) and 0xFF) / 255f }) }

  private val SOLVED = solved(PANTONE)
  private val SOLVED_GREY_BLUE = solved(GREY_BLUE)

  /**
   * Ink [index] in [colours]: its hue (index / 2) in its first or second shade, on the inks' two
   * brightnesses. The same array every time; do not write to it.
   */
  fun ink(index: Int, colours: CityColours = CityColours.COLOURFUL): FloatArray =
    when (colours) {
      CityColours.COLOURFUL -> SOLVED[index]
      CityColours.GREY_BLUE -> SOLVED_GREY_BLUE[index]
    }

  /**
   * [c] as close to itself as it can be with ZXing's luma exactly [INK_LUMA] and BT.601's within
   * [INK_601_BAND] of [INK_601]: scaled to the first, then moved the least distance onto both, a
   * few times over, since a channel that would pass 0 or 1 stops there.
   */
  private fun solve(c: FloatArray): FloatArray {
    val s = INK_LUMA / max(lumaZxing(c), 0.001f)
    for (k in 0..2) c[k] = (c[k] * s).coerceIn(0f, 1f)
    // The two brightnesses' weights, and the inverse of their Gram matrix.
    val a = arrayOf(floatArrayOf(0.25f, 0.5f, 0.25f), floatArrayOf(0.299f, 0.587f, 0.114f))
    val g00 = a[0].indices.sumOf { (a[0][it] * a[0][it]).toDouble() }.toFloat()
    val g01 = a[0].indices.sumOf { (a[0][it] * a[1][it]).toDouble() }.toFloat()
    val g11 = a[1].indices.sumOf { (a[1][it] * a[1][it]).toDouble() }.toFloat()
    val det = g00 * g11 - g01 * g01
    repeat(24) {
      val r0 = INK_LUMA - lumaZxing(c)
      val r1 = luma601(c).coerceIn(INK_601 - INK_601_BAND * 0.9f, INK_601 + INK_601_BAND * 0.9f) - luma601(c)
      val l0 = (g11 * r0 - g01 * r1) / det
      val l1 = (g00 * r1 - g01 * r0) / det
      for (k in 0..2) c[k] = (c[k] + a[0][k] * l0 + a[1][k] * l1).coerceIn(0f, 1f)
    }
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
   * [hue]) in either of its shades at random, or now and then a neighbouring hue; a finder is
   * its district's first shade whole. Part of the city, so seeded and deterministic like the planner.
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
    // Where each dark module stands for the districts: a finder at its corner, so it is one
    // district whole; and each anchor's distance to it shifted a little for every module, so
    // neighbouring districts interlock along their border. Kuala Lumpur, whose districts are
    // balanced (below), shifts every distance by the same amount, near or far: shifted in
    // proportion, a border pushed far from its corner frayed into separate pieces.
    val balanced = preset == CityPreset.KUALA_LUMPUR
    val rough = RAGGED * (n / 4f) * (n / 4f)
    val darks = (0 until n * n).filter { matrix.isDark(it % n, it / n) }
    val spots = darks.map { i ->
      val col = i % n
      val row = i / n
      if (matrix.role(col, row) == QrRole.FINDER) floatArrayOf(if (col < 7) 3.5f else far, if (row < 7) 3.5f else far, 1f)
      else floatArrayOf(col + 0.5f, row + 0.5f, 0f)
    }
    val pull = FloatArray(anchors.size)
    fun home(m: Int): Int {
      val (x, z, finder) = spots[m]
      var home = 0
      var nearest = Float.MAX_VALUE
      for ((k, a) in anchors.withIndex()) {
        val shift = if (finder > 0f) 0f else unit(seed + 41 + k, darks[m]) - 0.5f
        val apart = ((x - a[0]) * (x - a[0]) + (z - a[1]) * (z - a[1])) * a[3]
        val d = if (balanced) apart + rough * shift - pull[k] else apart * (1f + RAGGED * shift)
        if (d < nearest) {
          nearest = d
          home = k
        }
      }
      return home
    }
    // In Kuala Lumpur every hue the districts take gets the same share of the dark modules outside
    // the finders (2026-11-20, at the user's request: "the amount of each colour are not
    // balance"), split evenly between the districts that share it: each district's pull, taken off
    // its distances, grows or shrinks a little at a time toward its share. Taken off, never
    // multiplied in: a district grown by a factor reached round its neighbours into far corners of
    // the code, while one grown by an offset stays one piece. The finders are left out because
    // each corner is the same size whatever its colour, and in the city it is lawn or the mall,
    // not towers of its colour. The mall and the twins share their blue, so each is half the size of the rest.
    // Other cities keep their seeded districts as they were: evened out, one recipe's code no
    // longer scanned on a stormy night at 42 px a module.
    val hueOf = IntArray(anchors.size) { hue(anchors[it][2].toInt(), preset, tones(recipe) / 2) }
    val hues = hueOf.distinct().size
    val share = FloatArray(anchors.size) { k -> 1f / hues / hueOf.count { it == hueOf[k] } }
    var step = 0.5f * n * n
    if (balanced) repeat(BALANCE_ROUNDS) {
      val counts = IntArray(anchors.size)
      var towers = 0
      for (m in darks.indices) {
        if (spots[m][2] > 0f) continue
        counts[home(m)]++
        towers++
      }
      for (k in anchors.indices) pull[k] += (share[k] - counts[k].toFloat() / towers) * step
      step *= 0.93f
    }
    val out = IntArray(n * n)
    for (i in 0 until n * n) if (!matrix.isDark(i % n, i / n)) out[i] = (unit(seed + 17, i) * PAPER_SHADES.size).toInt()
    for ((m, i) in darks.withIndex()) {
      val paler = spots[m][2] == 0f && unit(seed + 29, i) < PALER
      out[i] = anchors[home(m)][2].toInt() * 2 + if (paler) 1 else 0
    }
    return out
  }

  /** Module ([col], [row]) of [city]'s flat code under [air], in [colours]; outside the code, the quiet zone's paper. */
  fun module(city: City, col: Int, row: Int, air: Atmosphere, colours: CityColours = CityColours.COLOURFUL): FloatArray {
    val n = city.modules
    if (col !in 0 until n || row !in 0 until n) return paper(air)
    val i = row * n + col
    return if (city.qr.isDark(col, row)) ink(city.inks[i], colours) else paper(city.tones[i], air)
  }

  /** The flat code at the point ([x], [y]) in modules from its top left: square modules, as drawn. */
  fun at(city: City, x: Float, y: Float, air: Atmosphere, colours: CityColours = CityColours.COLOURFUL): FloatArray =
    module(city, floor(x).toInt(), floor(y).toInt(), air, colours)

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
