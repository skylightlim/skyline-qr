package com.skylightlim.skylineqr.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.skylightlim.skylineqr.city.CityColours
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.core.Link
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real phone, never an emulator: real WebGPU frames, read back and scanned. This is the
 * proof behind "the flat view always scans".
 */
@RunWith(AndroidJUnit4::class)
class SkylineQrDeviceTest {

  @get:Rule val compose = createComposeRule()

  private val url = "https://github.com/skylightlim/skyline-qr"

  @Test
  fun flatCodeScansInEveryCityLightAndWeather() {
    val state = SkylineQrState()
    var city by mutableStateOf(CityPreset.GENERIC)
    var time by mutableStateOf(TimeOfDay.DAY)
    var weather by mutableStateOf(Weather.CLEAR)
    var colours by mutableStateOf(CityColours.COLOURFUL)
    compose.setContent { SkylineQrCode(url, Modifier.fillMaxSize(), city = city, time = time, weather = weather, colours = colours, state = state) }
    compose.waitUntil(15_000) { state.identity != null }

    for (c in CityPreset.entries) for (inks in CityColours.entries) {
      compose.runOnIdle {
        city = c
        colours = inks
      }
      compose.waitUntil(15_000) { state.city?.preset == c }
      for (t in TimeOfDay.entries - TimeOfDay.AUTO) {
        for (w in Weather.entries) {
          compose.runOnIdle {
            time = t
            weather = w
          }
          compose.waitForIdle()
          val shot = snapshot(state)
          keep(shot, "$c-$inks-$t-$w")
          assertFalse("WebGPU fell back to the 2D code", state.fallback)
          assertEquals("$c $inks $t $w", Link.parse(url).payload, decode(shot))
        }
      }
    }
  }

  @Test
  fun stormHoldsSixtyFramesPerSecond() {
    val state = SkylineQrState()
    // Kuala Lumpur in a storm is the heaviest frame: landmarks, shadows, every rain streak, lightning.
    compose.setContent {
      SkylineQrCode(url, Modifier.fillMaxSize(), city = CityPreset.KUALA_LUMPUR, weather = Weather.STORM, state = state)
    }
    compose.waitUntil(15_000) { (state.renderer?.framesDrawn ?: 0) > 30 }

    val renderer = state.renderer ?: error("no renderer")
    val before = renderer.framesDrawn
    Thread.sleep(3_000)
    val perSecond = (renderer.framesDrawn - before) / 3f
    assertFalse(state.fallback)
    // A 60 Hz panel with FIFO presentation tops out at 60; anything well under means dropped frames.
    assertTrue("only $perSecond frames per second", perSecond >= 55f)
  }

  @Test
  fun aMeteorStrikeHoldsTheFrameRateAndTheCodeStillScans() {
    val state = SkylineQrState()
    compose.setContent { SkylineQrCode(url, Modifier.fillMaxSize(), city = CityPreset.KUALA_LUMPUR, state = state) }
    compose.waitUntil(15_000) { (state.renderer?.framesDrawn ?: 0) > 30 }
    val renderer = state.renderer ?: error("no renderer")

    compose.runOnIdle { assertTrue(state.strike(0.5f, 0.42f)) }
    val before = renderer.framesDrawn
    Thread.sleep(3_000) // the fall, the blast and most of the rebuild
    val perSecond = (renderer.framesDrawn - before) / 3f
    assertTrue("only $perSecond frames per second during a strike", perSecond >= 55f)
    // Folding to the code mid-strike cancels it; the code scans.
    assertEquals(Link.parse(url).payload, decode(snapshot(state)))
  }

  private fun snapshot(state: SkylineQrState): Bitmap =
    runBlocking(Dispatchers.Main) { withTimeout(10_000) { state.snapshot() } } ?: error("no snapshot")

  /** Saves what was scanned, so a failure can be looked at: adb pull the test app's files dir. */
  private fun keep(bitmap: Bitmap, label: String) {
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
    File(dir, "snapshot-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun decode(bitmap: Bitmap): String {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
    return QRCodeReader()
      .decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
      .text
  }
}
