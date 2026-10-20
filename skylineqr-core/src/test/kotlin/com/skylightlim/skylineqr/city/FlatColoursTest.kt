package com.skylightlim.skylineqr.city

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
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
  fun everyInkIsOneToneToZxingAndCloseUnderTheOtherFormulas() {
    for (recipe in Materials.recipes + KualaLumpur.materials) for ((label, air) in airs) for (f in 0 until 4) {
      val ink = FlatColours.ink(recipe.families.copyOfRange(f * 3, f * 3 + 3), air.inkLight)
      val where = "${recipe.name} $label family $f"
      assertEquals(where, FlatColours.INK_LUMA, FlatColours.lumaZxing(ink), 1e-4f)
      assertTrue(where, abs(FlatColours.luma601(ink) - FlatColours.INK_LUMA) <= FlatColours.BAND_601 + 1e-4f)
      assertTrue(where, abs(FlatColours.luma(ink) - FlatColours.INK_LUMA) <= FlatColours.BAND_709 + 1e-4f)
      assertTrue(where, ink.all { it in 0f..1f })
    }
  }

  @Test
  fun theInkIsColourNotBlack() {
    // By day, at least, the Kuala Lumpur code shows its glass blue and its teal as colours.
    val air = Atmospheres.at(TimeOfDay.DAY, Weather.CLEAR, 0f)
    val city = cities.first { it.preset == CityPreset.KUALA_LUMPUR }
    var colourful = 0
    var dark = 0
    for (row in 0 until city.modules) for (col in 0 until city.modules) {
      if (!city.qr.isDark(col, row)) continue
      dark++
      val c = FlatColours.module(city, col, row, air)
      if (c.max() - c.min() > 0.12f) colourful++
    }
    assertTrue("only $colourful of $dark inks have colour", colourful > dark / 3)
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
   * The flat view as the renderer frames it: the code, paper a module wider than the plate all
   * round, then the hour's surround. Supersampled, so module edges blend as they do under MSAA.
   */
  private fun render(city: City, air: Atmosphere, modulePx: Float): BufferedImage {
    val n = city.modules
    val side = ((n + 2 * MARGIN) * modulePx).toInt()
    val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val surround = Atmospheres.surround(air)
    val colours = Array(n * n) { FlatColours.module(city, it % n, it / n, air) }
    val sub = 3
    for (y in 0 until side) for (x in 0 until side) {
      var r = 0f
      var g = 0f
      var b = 0f
      for (sy in 0 until sub) for (sx in 0 until sub) {
        val mx = (x + (sx + 0.5f) / sub) / modulePx - MARGIN
        val my = (y + (sy + 0.5f) / sub) / modulePx - MARGIN
        val col = floor(mx).toInt()
        val row = floor(my).toInt()
        val c =
          when {
            col in 0 until n && row in 0 until n -> colours[row * n + col]
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
    /** Paper round the code (the plate's two modules plus the card's one), then the surround. */
    const val PAPER = 3f
    const val MARGIN = 5
  }
}
