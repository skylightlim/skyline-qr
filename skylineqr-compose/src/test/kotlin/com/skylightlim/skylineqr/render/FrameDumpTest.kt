package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Vehicles
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.QrEncoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * Writes the buffers the renderer would upload for a few scenes, so a desktop script can draw
 * them with the app's own WGSL when no phone is at hand (tools/preview.py). Runs only when
 * SKYLINEQR_FRAMES names a directory.
 */
class FrameDumpTest {

  @Test
  fun dumpScenes() {
    val dir = System.getenv("SKYLINEQR_FRAMES")?.let(::File) ?: return
    dir.mkdirs()
    val identity = CityIdentity.of(System.getenv("SKYLINEQR_URL") ?: "https://github.com/skylightlim/skyline-qr")
    val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)
    // SKYLINEQR_SIZE="1080x2400" draws at the 6a's portrait screen, where the sky shows.
    val (width, height) = (System.getenv("SKYLINEQR_SIZE") ?: "1080x1350").split('x').map { it.trim().toInt() }
    val seconds = 12f
    // SKYLINEQR_LOOK="x,z" slides the city so that point is in the middle, for the close view.
    val look = System.getenv("SKYLINEQR_LOOK")?.split(',')?.map { it.trim().toFloat() } ?: listOf(0f, 0f)
    fun slide(data: FloatArray): FloatArray {
      for (o in data.indices step CityPlanner.STRIDE) {
        data[o] -= look[0]
        data[o + 2] -= look[1]
      }
      return data
    }
    write(File(dir, "instances.bin"), slide(city.instances.copyOf(city.count * CityPlanner.STRIDE)))
    write(File(dir, "cube.bin"), FrameData.CUBE_MESH)
    val traffic = ArrayList<Float>()
    val scratch = FloatArray(4)
    val sink = Vehicles.Sink { x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper ->
      traffic += listOf(x, y, z, sx, sy, sz, role, glow, 0f, rnd, yaw, taper)
    }
    for (m in city.movers) Vehicles.emit(m, seconds, sink, scratch)
    Vehicles.signals(city.signals, seconds, sink)
    // The billboard, its messages drawn with the desktop's fonts in place of Android's.
    val messages = (System.getenv("SKYLINEQR_BILLBOARD") ?: "马大牛逼|tarumt #1|i need argon").split('|')
    val (atlas, widths) = atlas(messages)
    File(dir, "atlas.bin").writeBytes(atlas)
    val board = Billboard(widths, BillboardAtlas.VIEW)
    val screenAt = (System.getenv("SKYLINEQR_SCREEN_TIME") ?: "1.0").toFloat()
    val screen = FloatArray(Billboard.FLOATS).also { board.state(screenAt, false, it) }
    city.screen?.let { traffic += listOf(it[0], it[1], it[2], it[3], it[4], 0.08f, CityPlanner.ROLE_SCREEN, 0f, 0f, 0f, 0f, 1f) }
    write(File(dir, "traffic.bin"), slide(traffic.toFloatArray()))
    val base = traffic.size
    // Each scene's sky as the renderer draws it once the weather has settled: its clouds, the sun
    // or the moon, a storm's bolt, haze's wind streaks; then its rain drops and streaks to draw.
    // [ways] overrides where the clear sky's clouds and the storm deck are, for a change caught
    // half way.
    fun weather(name: String, air: Atmosphere, weather: Weather, flash: Float = 0f, front: Float = 1f, ways: FloatArray? = null): IntArray {
      while (traffic.size > base) traffic.removeAt(traffic.size - 1)
      val amounts = SkyParts.Amounts(
        fair = ways?.get(0) ?: if (weather == Weather.CLEAR) 0f else 1f,
        deck = ways?.get(1) ?: if (weather == Weather.STORM || weather == Weather.RAIN) 0f else 1f,
        overcast = if (weather == Weather.RAIN) 0.7f else 1f,
        shine = if (weather == Weather.CLEAR || weather == Weather.HAZE) 1f else 0f,
      )
      var lit = -1
      if (flash > 0f) {
        // The first tall tower with a cloud close over it, as the renderer's aim prefers.
        val rods = CityRenderer.lightningRods(city)
        val rod = (0 until rods.size / 3).maxByOrNull { rods[it * 3 + 2] } ?: 0
        lit = SkyParts.nearestStormCloud(rods[rod * 3], rods[rod * 3 + 1], city, seconds, false, amounts)
        SkyParts.bolt(sink, city, seconds, false, amounts, 7, lit, rods[rod * 3], rods[rod * 3 + 1], rods[rod * 3 + 2], flash)
      }
      SkyParts.emit(sink, city, seconds, false, amounts, air, lit, flash)
      write(File(dir, "traffic-$name.bin"), slide(traffic.toFloatArray()))
      val drops = (2600 * air.rain).toInt()
      val gusts = if (weather == Weather.HAZE) 64 else 0
      return intArrayOf(traffic.size / CityPlanner.STRIDE, drops, gusts, (front * 1000).toInt())
    }
    val scenes =
      listOf(
        Triple("night", TimeOfDay.NIGHT, Weather.CLEAR),
        Triple("dusk", TimeOfDay.DUSK, Weather.CLEAR),
        Triple("day", TimeOfDay.DAY, Weather.CLEAR),
        Triple("storm", TimeOfDay.NIGHT, Weather.STORM),
      )
    val manifest = StringBuilder()
    fun scene(name: String, air: Atmosphere, weatherOf: Weather, fold: Float, view: View, flash: Float = 0f, front: Float = 1f, ways: FloatArray? = null) {
      val (moving, drops, gusts) = weather(name, air, weatherOf, flash, front, ways)
      val pose = Camera.pose(city, width.toFloat() / height, fold, seconds, view)
      val frame = FloatArray(Wgsl.FRAME_FLOATS)
      FrameData.pack(
        frame, pose, Camera.light(city, air.sunDir), fold, city, air, seconds, flash, false, false, front, if (gusts > 0) 1f else 0f,
        meteor = floatArrayOf(0f, 0f, -1f, 0f), damage = floatArrayOf(0f, 0f, -1f, 0f), bolt = floatArrayOf(0f, 0f, 0f, 0f),
        screen = screen,
      )
      write(File(dir, "frame-$name.bin"), frame)
      val sky = air.skyHorizon
      manifest.append("$name $fold ${sky[0] * 0.85f} ${sky[1] * 0.85f} ${sky[2] * 0.85f} $moving $drops $gusts\n")
    }
    // A closer look as well, pinched in and turned a little, for details the whole city hides.
    val close = View(yaw = (System.getenv("SKYLINEQR_YAW") ?: "0.3").toFloat(), elevation = -0.1f, zoom = 2.6f)
    for ((name, time, weather) in scenes) {
      for ((suffix, fold, view) in listOf(Triple("", 0f, View()), Triple("-flat", 1f, View()), Triple("-close", 0f, close))) {
        scene("$name$suffix", Atmospheres.at(time, weather, 0f), weather, fold, view)
      }
    }
    // The weather and the sky, from where the camera usually stands.
    scene("dawn", Atmospheres.at(TimeOfDay.DAWN, Weather.CLEAR, 0f), Weather.CLEAR, 0f, View())
    scene("bolt", Atmospheres.at(TimeOfDay.NIGHT, Weather.STORM, 0f), Weather.STORM, 0f, View(), flash = 0.8f)
    scene("bolt-day", Atmospheres.at(TimeOfDay.DAY, Weather.STORM, 0f), Weather.STORM, 0f, View(), flash = 0.8f)
    scene("haze", Atmospheres.at(TimeOfDay.DAY, Weather.HAZE, 0f), Weather.HAZE, 0f, View())
    scene("haze-coming", Atmospheres.at(TimeOfDay.DAY, Weather.HAZE, 0f), Weather.HAZE, 0f, View(), front = 0.45f)
    // Clear into a storm, caught on the way: the fair clouds blowing away downwind as the deck
    // blows in from upwind, and back.
    for ((i, ways) in listOf(floatArrayOf(0.2f, -0.6f), floatArrayOf(0.4f, -0.35f), floatArrayOf(0.65f, -0.15f)).withIndex()) {
      scene("to-storm-$i", Atmospheres.at(TimeOfDay.DAY, Weather.STORM, 0f), Weather.STORM, 0f, View(), ways = ways)
    }
    scene("to-clear", Atmospheres.at(TimeOfDay.DAY, Weather.CLEAR, 0f), Weather.CLEAR, 0f, View(), ways = floatArrayOf(-0.3f, 0.45f))
    // SKYLINEQR_NOW=<epoch millis>[,<millis>...] adds Now at those instants, with the real sun and moon.
    System.getenv("SKYLINEQR_NOW")?.split(',')?.forEachIndexed { i, at ->
      val millis = at.trim().toLong()
      scene("now-$i", Atmospheres.at(TimeOfDay.AUTO, Weather.CLEAR, Atmospheres.clockMinute(millis, java.util.TimeZone.getTimeZone("Asia/Kuala_Lumpur")), millis), Weather.CLEAR, 0f, View())
    }
    // SKYLINEQR_MORPH=raise (code to city) or fold (city to code) adds that way of the morph by
    // day, a frame every 85 ms from the tap, for a filmstrip of the timing.
    System.getenv("SKYLINEQR_MORPH")?.let { way ->
      val raise = way == "raise"
      val air = Atmospheres.at(TimeOfDay.DAY, Weather.CLEAR, 0f)
      val morph = Morph()
      morph.finish(flat = raise)
      morph.aim(flat = !raise, nowNanos = 0)
      for (step in 0..10) {
        val nanos = step * Morph.DURATION_NANOS / 10
        val fold = morph.fold(nanos)
        val pose = Camera.pose(city, width.toFloat() / height, morph.swing(nanos), seconds, View())
        val frame = FloatArray(Wgsl.FRAME_FLOATS)
        FrameData.pack(
          frame, pose, Camera.light(city, air.sunDir), fold, city, air, seconds, 0f, false, morph.raising, 1f, 0f,
          meteor = floatArrayOf(0f, 0f, -1f, 0f), damage = floatArrayOf(0f, 0f, -1f, 0f), bolt = floatArrayOf(0f, 0f, 0f, 0f),
          screen = screen,
        )
        val name = "$way-%02d".format(step)
        write(File(dir, "frame-$name.bin"), frame)
        val sky = air.skyHorizon
        manifest.append("$name $fold ${sky[0] * 0.85f} ${sky[1] * 0.85f} ${sky[2] * 0.85f}\n")
      }
    }
    File(dir, "scenes.txt").writeText(manifest.toString())
    File(dir, "meta.txt").writeText("$width $height ${city.count} ${base / CityPlanner.STRIDE}\n")
    // Where things are, for SKYLINEQR_LOOK: each junction with lights, nearest the mall first.
    val n = city.modules
    val junctions = (0 until city.signals.offsets.size).sortedBy { j ->
      val dx = city.signals.cells[j * 2] + 0.5f - (n - 3.5f)
      val dz = city.signals.cells[j * 2 + 1] + 0.5f - 3.5f
      dx * dx + dz * dz
    }
    File(dir, "places.txt").writeText(
      junctions.joinToString("\n") { j -> "junction ${city.signals.cells[j * 2] + 0.5f - n / 2f},${city.signals.cells[j * 2 + 1] + 0.5f - n / 2f}" } + "\n" +
        city.instances.toList().chunked(CityPlanner.STRIDE).filter { it[6] == CityPlanner.ROLE_LANDMARK && it[4] < 0.006f && it[3] > 0.99f }
          .take(1).joinToString("") { "crossing ${it[0]},${it[2]}\n" } +
        "garden ${3.5f - n / 2f},${3.5f - n / 2f}\nmall ${n - 3.5f - n / 2f},${3.5f - n / 2f}\nsquare ${3.5f - n / 2f},${n - 3.5f - n / 2f}\n"
    )
    File(dir, "skyline.wgsl").writeText(Wgsl.SOURCE)
    File(dir, "glow.wgsl").writeText(Wgsl.POST)
  }

  /** BillboardAtlas.draw, with java.awt in place of Android's canvas. */
  private fun atlas(messages: List<String>): Pair<ByteArray, FloatArray> {
    val width = BillboardAtlas.WIDTH
    val row = BillboardAtlas.ROW
    val out = ByteArray(width * row * BillboardAtlas.ROWS)
    val widths = FloatArray(messages.size)
    val margin = 0.06f * row
    for ((i, text) in messages.withIndex()) {
      val image = java.awt.image.BufferedImage(width, row, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
      val g = image.createGraphics()
      g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      val full = row * 0.74f
      var font = java.awt.Font("Noto Sans CJK SC", java.awt.Font.BOLD, 1).deriveFont(full)
      val fit = full * (BillboardAtlas.VIEW * width - 2 * margin) / g.getFontMetrics(font).stringWidth(text)
      if (fit < full && fit >= full * 0.5f) font = font.deriveFont(fit)
      g.font = font
      val metrics = g.fontMetrics
      g.drawString(text, margin, row / 2f + (metrics.ascent - metrics.descent) / 2f)
      val pixels = (image.raster.dataBuffer as java.awt.image.DataBufferByte).data
      System.arraycopy(pixels, 0, out, i * width * row, width * row)
      widths[i] = (metrics.stringWidth(text) + 2 * margin) / width
    }
    return out to widths
  }

  private fun write(file: File, data: FloatArray) {
    val bytes = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asFloatBuffer().put(data)
    file.writeBytes(bytes.array())
  }
}
