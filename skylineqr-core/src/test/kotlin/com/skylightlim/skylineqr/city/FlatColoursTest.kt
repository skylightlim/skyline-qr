package com.skylightlim.skylineqr.city

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import com.skylightlim.skylineqr.core.QrRole
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coloured flat code scans: every material recipe, at every fixed hour and through the clock,
 * in every weather, drawn small and large, read by ZXing with its own luma formula and with the
 * camera's BT.601. The device test proves the same of real rendered frames; this one covers far
 * more combinations, fast. Set SKYLINEQR_PREVIEW to a directory to also get contact sheets.
 */
class FlatColoursTest {

  /** Every generic recipe, Kuala Lumpur, and a link long enough for a version 6 code. */
  private val cities: List<City> by lazy {
    val byRecipe = linkedMapOf<String, City>()
    var i = 0
    while (byRecipe.size < Materials.recipes.size) {
      val city = plan("https://site$i.example/", CityPreset.GENERIC)
      byRecipe.putIfAbsent(city.materials.name, city)
      i++
    }
    byRecipe.values.toList() +
      plan("https://github.com/skylightlim/skyline-qr", CityPreset.KUALA_LUMPUR) +
      plan("https://en.wikipedia.org/wiki/Kuala_Lumpur_Tower?section=history-and-design", CityPreset.KUALA_LUMPUR)
  }

  private val airs: List<Pair<String, Atmosphere>> by lazy {
    val hours = (TimeOfDay.entries - TimeOfDay.AUTO).map { Triple(it.name, it, 0f) } +
      (0 until 24 step 2).map { Triple("AUTO-$it:30", TimeOfDay.AUTO, it * 60f + 30f) }
    hours.flatMap { (label, time, minute) -> Weather.entries.map { w -> "$label-$w" to Atmospheres.at(time, w, minute) } }
  }

  @Test
  fun anyTwoInksCanTouchAndAnyTwoPapers() {
    for ((label, air) in airs) {
      val inks = (0 until FlatColours.INKS).map { FlatColours.ink(it, air) }
      for ((i, ink) in inks.withIndex()) {
        val where = "$label ink $i"
        assertEquals(where, FlatColours.INK_LUMA, FlatColours.lumaZxing(ink), 1e-4f)
        // Well under the paper, to the camera as well.
        assertTrue(where, FlatColours.luma601(ink) < 0.42f)
        assertTrue(where, ink.all { it in 0f..1f })
      }
      // Every pair, so modules can take their inks in any order: close to the camera too, well
      // inside the 24 levels that HybridBinarizer allows a block of one tone.
      for (a in inks) for (b in inks) {
        assertTrue(label, abs(FlatColours.luma601(a) - FlatColours.luma601(b)) * 255f <= MAX_601_LEVELS)
      }
      val papers = FlatColours.PAPER_SHADES.indices.map { FlatColours.paper(it, air) }
      for (a in papers) for (b in papers) {
        assertTrue(label, abs(FlatColours.luma601(a) - FlatColours.luma601(b)) <= FlatColours.PAPER_SPREAD)
        assertTrue(label, abs(FlatColours.lumaZxing(a) - FlatColours.lumaZxing(b)) <= FlatColours.PAPER_SPREAD)
      }
    }
  }

  @Test
  fun theInksAreLively() {
    // Every fixed hour in clear weather: each hue's full shade is a real colour, the softer shade
    // less so, and the hues spread round the wheel (2026-11-07: "look lively").
    for (time in TimeOfDay.entries - TimeOfDay.AUTO) {
      val air = Atmospheres.at(time, Weather.CLEAR, 0f)
      val full = (0 until FlatColours.HUES).map { FlatColours.ink(it * 2, air) }
      val soft = (0 until FlatColours.HUES).map { FlatColours.ink(it * 2 + 1, air) }
      for (h in 0 until FlatColours.HUES) {
        val chroma = full[h].max() - full[h].min()
        assertTrue("$time hue $h is grey: $chroma", chroma > 0.15f)
        assertTrue("$time hue $h's softer shade is not softer", soft[h].max() - soft[h].min() < chroma)
      }
      // Distinct colours: at least six of them 20 degrees or more from each other.
      val hues = full.map { hue(it) }.sorted()
      val distinct = hues.fold(mutableListOf<Float>()) { kept, x -> if (kept.none { apart(it, x) < 20f }) kept += x; kept }
      assertTrue("$time has only ${distinct.size} distinct hues: $hues", distinct.size >= 6)
    }
  }

  @Test
  fun everyDistrictHasItsOwnColour() {
    for (city in cities) {
      val n = city.modules
      val districts = FlatColours.tones(city.materials) / 2
      var own = 0
      var mosaic = 0
      for (row in 0 until n) for (col in 0 until n) {
        if (!city.qr.isDark(col, row)) continue
        val i = row * n + col
        val home = FlatColours.hue(city.tones[i] / 2, city.preset, districts)
        val hue = city.inks[i] / 2
        // Its district's hue, or the one beside it on the wheel: a mosaic, never a blend.
        assertTrue("module $col,$row takes hue $hue in a district of hue $home", abs(hue - home) <= 1)
        if (city.qr.role(col, row) != QrRole.FINDER) {
          mosaic++
          if (hue == home) own++
        }
      }
      assertTrue("only $own of $mosaic modules take their district's hue", own > mosaic * 0.8)
      // Each district shows both shades, so it reads as a mosaic of its colour, not a flat fill.
      var changes = 0
      var pairs = 0
      for (row in 0 until n) for (col in 0 until n - 1) {
        if (!city.qr.isDark(col, row) || !city.qr.isDark(col + 1, row)) continue
        pairs++
        if (city.inks[row * n + col] != city.inks[row * n + col + 1]) changes++
      }
      assertTrue("only $changes of $pairs neighbours differ", changes > pairs / 3)
      // A finder is one ink whole, or it may not scan.
      for (row in 0 until n) for (col in 0 until n) {
        if (city.qr.role(col, row) == QrRole.FINDER) {
          val corner = (if (col < 7) 0 else n - 1) + (if (row < 7) 0 else n * (n - 1))
          assertEquals("finder at $col,$row", city.inks[corner], city.inks[row * n + col])
        }
      }
    }
    // Kuala Lumpur shows every hue: its six districts', and the two between them as accents.
    for (city in cities.filter { it.preset == CityPreset.KUALA_LUMPUR }) {
      val n = city.modules
      val hues = (0 until n * n).filter { city.qr.isDark(it % n, it / n) }.map { city.inks[it] / 2 }.toSet()
      assertEquals("hues used", FlatColours.HUES, hues.size)
    }
  }

  private fun apart(a: Float, b: Float) = abs(a - b).let { minOf(it, 360f - it) }

  @Test
  fun theCityKeepsItsDistricts() {
    for (city in cities.filter { it.preset == CityPreset.KUALA_LUMPUR }) {
      val n = city.modules
      val used = HashSet<Int>()
      for (row in 0 until n) for (col in 0 until n) if (city.qr.isDark(col, row)) used += city.tones[row * n + col] / 2
      assertEquals("districts used", 6, used.size)
      // Each landmark stands in its own district.
      val plan = city.landmarks!!
      fun district(f: Footprint) = city.tones[f.z * n + f.x] / 2
      assertEquals(FlatColours.SUPERTALL, district(plan.supertall!!))
      assertEquals(FlatColours.TWINS, district(plan.twins!!.first))
      assertEquals(FlatColours.TELECOM, district(plan.telecom!!))
      assertEquals(FlatColours.MALL, city.tones[3 * n + n - 4] / 2)
    }
  }

  private fun hue(c: FloatArray): Float {
    val max = c.max()
    val d = max - c.min()
    if (d < 1e-5f) return 0f
    val h = when (max) {
      c[0] -> ((c[1] - c[2]) / d).mod(6f)
      c[1] -> (c[2] - c[0]) / d + 2f
      else -> (c[0] - c[1]) / d + 4f
    }
    return h * 60f
  }

  @Test
  fun theCodeScansInEveryRecipeHourAndWeather() {
    val reader = QRCodeReader()
    val failures = ArrayList<String>()
    for (city in cities) for ((label, air) in airs) for (modulePx in SCALES) {
      val image = render(city, air, modulePx)
      for ((formula, source) in listOf("zxing" to zxing(image), "bt601" to bt601(image))) {
        val text =
          runCatching { reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text }
            .getOrNull()
        if (text != city.identity.link.payload) failures += "${city.materials.name} v${city.qr.symbolVersion} $label ${modulePx}px $formula"
      }
    }
    assertEquals("did not scan:\n" + failures.joinToString("\n"), 0, failures.size)
  }

  @Test
  fun preview() {
    val dir = System.getenv("SKYLINEQR_PREVIEW")?.let(::File) ?: return
    dir.mkdirs()
    val px = 6
    for (city in cities.distinctBy { it.materials.name }) {
      val shown = airs.filter { (label, _) -> label.startsWith("AUTO").not() }
      val side = (city.modules + 2 * MARGIN) * px
      val sheet = BufferedImage(side * Weather.entries.size, side * (shown.size / Weather.entries.size), BufferedImage.TYPE_INT_RGB)
      shown.forEachIndexed { i, (_, air) ->
        val tile = render(city, air, px.toFloat())
        sheet.graphics.drawImage(tile, (i % Weather.entries.size) * side, (i / Weather.entries.size) * side, null)
      }
      ImageIO.write(sheet, "png", File(dir, "${city.materials.name.replace(' ', '-')}.png"))
    }
  }

  private fun plan(url: String, preset: CityPreset): City {
    val identity = CityIdentity.of(url)
    return CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, preset)
  }

  /**
   * The flat view as the renderer frames it: the code's square modules, the plate's paper round
   * it, then the hour's surround. Supersampled, so edges blend as they do under MSAA.
   */
  private fun render(city: City, air: Atmosphere, modulePx: Float): BufferedImage {
    val n = city.modules
    val side = ((n + 2 * MARGIN) * modulePx).toInt()
    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val surround = Atmospheres.surround(air)
    val sub = 3
    for (y in 0 until side) for (x in 0 until side) {
      var r = 0f
      var g = 0f
      var b = 0f
      for (sy in 0 until sub) for (sx in 0 until sub) {
        val mx = (x + (sx + 0.5f) / sub) / modulePx - MARGIN
        val my = (y + (sy + 0.5f) / sub) / modulePx - MARGIN
        val c =
          when {
            mx >= 0f && my >= 0f && mx < n && my < n -> FlatColours.at(city, mx, my, air)
            mx >= -PAPER && my >= -PAPER && mx < n + PAPER && my < n + PAPER -> air.paper
            else -> surround
          }
        r += c[0]
        g += c[1]
        b += c[2]
      }
      val k = 255f / (sub * sub)
      image.setRGB(x, y, ((r * k + 0.5f).toInt() shl 16) or ((g * k + 0.5f).toInt() shl 8) or (b * k + 0.5f).toInt())
    }
    return image
  }

  private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

  private fun zxing(image: BufferedImage): LuminanceSource = RGBLuminanceSource(image.width, image.height, pixels(image))

  /** What a phone camera's Y plane holds: BT.601 luma. */
  private fun bt601(image: BufferedImage): LuminanceSource {
    val argb = pixels(image)
    val y = ByteArray(argb.size) {
      val p = argb[it]
      (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255) + 0.5f).toInt().toByte()
    }
    return com.google.zxing.PlanarYUVLuminanceSource(y, image.width, image.height, 0, 0, image.width, image.height, false)
  }

  private companion object {
    /** Small as a camera sees it across a room, up to a phone screen's own pixels for a small code. */
    val SCALES = listOf(3.4f, 5f, 8.6f, 14f, 23.3f, 29.1f, 41.7f)
    /** Paper round the code (the plate's two modules), then the surround. */
    const val PAPER = 2f
    const val MARGIN = 5
    /** How far apart any two inks may lie under BT.601, in levels. */
    const val MAX_601_LEVELS = 14f
  }
}
