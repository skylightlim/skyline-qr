package com.skylightlim.skylineqr.compose

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.GeneratorVersion
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import com.skylightlim.skylineqr.core.SkylineQrException
import com.skylightlim.skylineqr.render.CityRenderer
import com.skylightlim.skylineqr.render.View
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Controls one [SkylineQrCode]: city or flat code, the camera, and what the current link built. */
@Stable
class SkylineQrState {

  /** True shows the flat, scannable code; false shows the city. */
  var flat by mutableStateOf(false)

  /** The identity of the city on screen, once one has been built. */
  var identity by mutableStateOf<CityIdentity?>(null)
    internal set

  /** Why the current link could not become a city. The last good city stays on screen meanwhile. */
  var error by mutableStateOf<SkylineQrException?>(null)
    internal set

  /** True when WebGPU could not run here, and a flat 2D code is drawn instead of the city. */
  var fallback by mutableStateOf(false)
    internal set

  internal var view by mutableStateOf(View())

  /** True once the camera has been turned or zoomed away from where the city puts it. */
  val cameraMoved: Boolean
    get() = view != View()

  /** Glides the camera back to the city's own angle and distance. */
  fun resetCamera() {
    view = View()
  }

  internal fun orbit(yaw: Float, elevation: Float) {
    view =
      view.copy(
        yaw = (view.yaw + yaw).coerceIn(-View.MAX_YAW, View.MAX_YAW),
        elevation = (view.elevation + elevation).coerceIn(View.MIN_ELEVATION, View.MAX_ELEVATION),
      )
  }

  internal fun zoom(factor: Float) {
    view = view.copy(zoom = (view.zoom * factor).coerceIn(View.MIN_ZOOM, View.MAX_ZOOM))
  }

  private var lastStrike = 0L

  /** A meteor on the city at ([u], [v]) of the view, 0..1 from the top left. False if none fell. */
  internal fun strike(u: Float, v: Float): Boolean {
    val renderer = renderer ?: return false
    val now = SystemClock.uptimeMillis()
    if (flat || now - lastStrike < STRIKE_MILLIS) return false
    lastStrike = now
    renderer.strike(u, v)
    return true
  }

  internal var renderer: CityRenderer? = null
  internal var city: City? = null
  internal var time = TimeOfDay.AUTO
  internal var weather = Weather.CLEAR

  internal fun atmosphere(): Atmosphere = Atmospheres.at(time, weather, Atmospheres.clockMinute())

  private companion object {
    /** A strike, its rebuild and a beat to spare. */
    const val STRIKE_MILLIS = 4_700L
  }

  /**
   * The next frame as a bitmap. By default the city folds flat first, so the image scans; with
   * [flat] false it is the city exactly as it looks now, for a postcard. Null before the first city.
   */
  suspend fun snapshot(flat: Boolean = true): Bitmap? {
    if (flat) this.flat = true
    val renderer = renderer
    if (fallback || renderer == null) return codeBitmap()
    return suspendCancellableCoroutine { continuation -> renderer.requestSnapshot(flat) { continuation.resume(it) } }
  }

  /**
   * The code alone, drawn without the GPU: [modulePx] pixels a module, with a four-module quiet
   * zone, in the colours of the flat city at this hour. Null before the first city.
   */
  fun codeBitmap(modulePx: Int = 16): Bitmap? = city?.let { FlatCode.bitmap(it, atmosphere(), modulePx) }
}

@Composable fun rememberSkylineQrState(): SkylineQrState = remember { SkylineQrState() }

/**
 * A link as a city that folds flat into its scannable QR code.
 *
 * Tap to switch between the city and the code. In the city, drag to turn it, pinch to zoom,
 * double-tap to put the camera back, and long-press to bring a meteor down on that spot (it
 * rebuilds itself). The same [url], [identityScope], [generatorVersion] and [city]
 * always build the same city; [time] and [weather] only light it. [city] is Kuala Lumpur, with its
 * landmarks and monorail, unless an app asks for the generic city without them. A saved or shared city should store its
 * [generatorVersion] and pass it back here. [onEvent] hears lightning and meteors, for sound.
 */
@Composable
fun SkylineQrCode(
  url: String,
  modifier: Modifier = Modifier,
  city: CityPreset = CityPreset.KUALA_LUMPUR,
  time: TimeOfDay = TimeOfDay.AUTO,
  weather: Weather = Weather.CLEAR,
  identityScope: IdentityScope = IdentityScope.SITE,
  generatorVersion: Int = GeneratorVersion.CURRENT,
  state: SkylineQrState = rememberSkylineQrState(),
  haptics: Boolean = true,
  onEvent: (SkylineEvent) -> Unit = {},
) {
  var built by remember { mutableStateOf<City?>(null) }

  LaunchedEffect(url, identityScope, generatorVersion, city) {
    try {
      val planned =
        withContext(Dispatchers.Default) {
          val identity = CityIdentity.of(url, identityScope, generatorVersion)
          CityPlanner.plan(QrEncoder.encode(identity.link.payload, generatorVersion), identity, city)
        }
      built = planned
      state.city = planned
      state.identity = planned.identity
      state.error = null
    } catch (e: SkylineQrException) {
      state.error = e
    }
  }
  state.time = time
  state.weather = weather

  val context = LocalContext.current
  val calm = remember(context) { animationsOff(context) }
  val feedback = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val toggle by rememberUpdatedState {
    state.flat = !state.flat
    if (haptics) feedback.performHapticFeedback(if (state.flat) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
  }
  val flat = state.flat
  val described = Modifier.semantics {
    contentDescription = if (flat) "QR code for $url" else "City QR code for $url"
    onClick(label = if (flat) "show the city" else "show the code") {
      toggle()
      true
    }
  }
  val gestures =
    Modifier.pointerInput(state) {
        detectTransformGestures { _, pan, zoom, _ ->
          if (!state.flat) {
            // A full-width drag turns the city by about 170 degrees; a full-height one tilts it.
            state.orbit(yaw = -pan.x / size.width * 3f, elevation = pan.y / size.height * 1.4f)
            state.zoom(zoom)
          }
        }
      }
      // Only offer double-tap once there is a camera move to undo: until then, taps act at once.
      .pointerInput(state, state.cameraMoved) {
        detectTapGestures(
          onTap = { toggle() },
          onDoubleTap = if (state.cameraMoved && !state.flat) ({ state.resetCamera() }) else null,
          onLongPress = { at ->
            if (!calm && state.strike(at.x / size.width, at.y / size.height) && haptics) {
              feedback.performHapticFeedback(HapticFeedbackType.LongPress)
              scope.launch {
                delay(900) // the moment it lands
                rumble(context)
              }
            }
          },
        )
      }

  if (state.fallback) {
    val air = state.atmosphere()
    Box(modifier.background(Color(FlatCode.paper(air))).then(described).then(gestures)) {
      built?.let {
        FlatCodeView(
          it,
          air,
          Modifier.align(Alignment.Center).fillMaxWidth(0.9f).aspectRatio(1f),
        )
      }
    }
    return
  }

  val events by rememberUpdatedState(onEvent)
  val renderer = remember(state) { CityRenderer(onFailure = { state.fallback = true }, onEvent = { events(it) }) }
  DisposableEffect(renderer) {
    state.renderer = renderer
    onDispose {
      state.renderer = null
      renderer.close()
    }
  }
  // Read here, in composition, so a new city, a tap or a drag recomposes and reaches the renderer.
  val current = built
  val view = state.view
  SideEffect {
    renderer.calm = calm
    renderer.city = current
    renderer.time = time
    renderer.weather = weather
    renderer.view = view
    renderer.setFlat(flat)
  }

  Box(modifier.then(described)) {
    AndroidExternalSurface(Modifier.fillMaxSize()) {
      onSurface { surface, width, height ->
        renderer.attach(surface, width, height)
        surface.onChanged { newWidth, newHeight -> renderer.resize(newWidth, newHeight) }
        surface.onDestroyed { renderer.detach() }
      }
    }
    Box(Modifier.matchParentSize().then(gestures))
  }
}

/** The thud of the impact, then aftershocks dying away. */
private fun rumble(context: Context) {
  val vibrator =
    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)
  if (vibrator == null || !vibrator.hasVibrator()) return
  val thud = VibrationEffect.Composition.PRIMITIVE_THUD
  val tick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
  val effect =
    if (Build.VERSION.SDK_INT >= 31 && vibrator.areAllPrimitivesSupported(thud, tick)) {
      VibrationEffect.startComposition()
        .addPrimitive(thud, 1f)
        .addPrimitive(tick, 0.8f, 60)
        .addPrimitive(tick, 0.5f, 70)
        .addPrimitive(tick, 0.3f, 90)
        .compose()
    } else {
      VibrationEffect.createWaveform(longArrayOf(0, 70, 50, 60, 70, 40), intArrayOf(0, 255, 0, 160, 0, 80), -1)
    }
  vibrator.vibrate(effect)
}

/** Android's "Remove animations" accessibility setting. */
private fun animationsOff(context: Context): Boolean =
  Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
