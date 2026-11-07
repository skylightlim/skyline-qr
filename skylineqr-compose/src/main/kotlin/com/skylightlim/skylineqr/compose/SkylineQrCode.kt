package com.skylightlim.skylineqr.compose

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
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
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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

  /** Where the fingers have taken the camera, limits and all; [view] is that with soft edges. */
  private var reach = View()

  /** True once the camera has been turned or zoomed away from where the city puts it. */
  val cameraMoved: Boolean
    get() = view != View()

  /** Glides the camera back to the city's own angle and distance. */
  fun resetCamera() {
    reach = View()
    view = View()
  }

  /**
   * A drag of ([dx], [dy]) pixels on a view [width] by [height]: across the whole width turns the
   * city by about 170 degrees, down the whole height tilts it. Past a limit it gives less and less.
   */
  internal fun turn(dx: Float, dy: Float, width: Int, height: Int) {
    reach = reach.copy(yaw = reach.yaw - dx / width * YAW_PER_WIDTH, elevation = reach.elevation + dy / height * TILT_PER_HEIGHT)
    view = soft(reach)
  }

  internal fun zoom(factor: Float) {
    reach = reach.copy(zoom = reach.zoom * factor)
    view = soft(reach)
  }

  /**
   * After a touch lets go, at [velocity] in pixels a second: a turn runs on at the finger's speed and
   * slows quickly, stopping early at a limit (Apple's momentum, Designing Fluid Interfaces, WWDC
   * 2018). Then the camera comes back inside its limits, and the renderer glides it there.
   */
  internal suspend fun coast(velocity: Velocity, width: Int, height: Int) {
    try {
      coroutineScope {
        // Each axis on its own, so a drag mostly across doesn't drag the tilt along with it.
        launch { slow(velocity.x) { turn(it, 0f, width, height) } }
        launch { slow(velocity.y) { turn(0f, it, width, height) } }
      }
    } finally {
      reach =
        View(
          reach.yaw.coerceIn(-View.MAX_YAW, View.MAX_YAW),
          reach.elevation.coerceIn(View.MIN_ELEVATION, View.MAX_ELEVATION),
          reach.zoom.coerceIn(View.MIN_ZOOM, View.MAX_ZOOM),
        )
      view = reach
    }
  }

  private suspend fun slow(velocity: Float, step: (Float) -> Unit) {
    if (velocity == 0f) return
    var last = 0f
    AnimationState(0f, velocity).animateDecay(Coasting) {
      step(value - last)
      last = value
      if (pastLimits()) cancelAnimation()
    }
  }

  private fun pastLimits() = reach.yaw !in -View.MAX_YAW..View.MAX_YAW || reach.elevation !in View.MIN_ELEVATION..View.MAX_ELEVATION

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

  /** What of the sky is at ([u], [v]) of the view; [onResult] hears it on the main thread, null for nothing. */
  internal fun pick(u: Float, v: Float, onResult: (SkyPart?) -> Unit) {
    val renderer = renderer
    if (renderer == null || flat) onResult(null) else renderer.pick(u, v, onResult)
  }

  internal var renderer: CityRenderer? = null
  internal var city: City? = null
  internal var time = TimeOfDay.AUTO
  internal var weather = Weather.CLEAR

  internal fun atmosphere(): Atmosphere = Atmospheres.at(time, weather, Atmospheres.clockMinute())

  private companion object {
    /** A strike, its rebuild and a beat to spare. */
    const val STRIKE_MILLIS = 4_700L

    /** The turn, in radians, for a drag across the whole view. */
    const val YAW_PER_WIDTH = 3f

    /** The tilt, in radians, for a drag down the whole view. */
    const val TILT_PER_HEIGHT = 1.4f

    /** How a released turn slows: it carries on about a tenth of the finger's speed in pixels. */
    val Coasting = exponentialDecay<Float>(frictionMultiplier = 2.5f)

    /** The camera with soft limits: as far past one as it likes, it shows at most a little past. */
    fun soft(view: View) =
      View(
        soft(view.yaw, -View.MAX_YAW, View.MAX_YAW, give = 0.3f),
        soft(view.elevation, View.MIN_ELEVATION, View.MAX_ELEVATION, give = 0.12f),
        if (view.zoom in View.MIN_ZOOM..View.MAX_ZOOM) view.zoom else exp(soft(ln(view.zoom), ln(View.MIN_ZOOM), ln(View.MAX_ZOOM), give = 0.12f)),
      )

    /**
     * [x] kept within [min]..[max] by a rubber band: past them it follows at half speed at first,
     * then less and less, never more than [give] past (Apple's rubber-banding, WWDC 2018).
     */
    fun soft(x: Float, min: Float, max: Float, give: Float): Float {
      fun band(over: Float) = over * give * 0.55f / (give + 0.55f * over)
      return when {
        x > max -> max + band(x - max)
        x < min -> min - band(min - x)
        else -> x
      }
    }
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
 * Tap to switch between the city and the code. In the city, drag to turn it, pinch to zoom, and
 * long-press to bring a meteor down on that spot (it rebuilds itself). Given [onSkyLongPress], a
 * long press on a cloud, the sun or the moon goes there instead, with where it was, so an app can
 * offer a setting for the sky; [clouds] off blows the clouds away (and back in when it is on);
 * [SkylineQrState.resetCamera] puts the camera back. The same [url], [identityScope], [generatorVersion] and [city]
 * always build the same city; [time] and [weather] only light it. [city] is Kuala Lumpur, with its
 * landmarks and monorail, unless an app asks for the generic city without them. A saved or shared city should store its
 * [generatorVersion] and pass it back here. [billboard] is what the city's screen shows, a message at a
 * time (Kuala Lumpur has one, on its mall); like [time], it never changes the city. [onEvent] hears
 * lightning and meteors, for sound.
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
  billboard: List<String> = emptyList(),
  clouds: Boolean = true,
  onSkyLongPress: ((SkyPart, Offset) -> Unit)? = null,
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
  val skyPress by rememberUpdatedState(onSkyLongPress)
  val meteor: (Float, Float) -> Unit = { u, v ->
    if (!calm && state.strike(u, v) && haptics) {
      feedback.performHapticFeedback(HapticFeedbackType.LongPress)
      scope.launch {
        delay(900) // the moment it lands
        rumble(context)
      }
    }
  }
  val described = Modifier.semantics {
    contentDescription = if (flat) "QR code for $url" else "City QR code for $url"
    onClick(label = if (flat) "show the city" else "show the code") {
      toggle()
      true
    }
  }
  val gestures =
    Modifier.pointerInput(state) {
        // Sees each touch after the detectors below and consumes nothing: once a turn lets go it
        // runs on at the finger's speed, and the camera settles back inside its limits. A new touch
        // catches it.
        coroutineScope {
          var coasting: Job? = null
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            coasting?.cancel()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var turned = false
            var pinched = false
            do {
              val event = awaitPointerEvent()
              if (event.changes.count { it.pressed } > 1) pinched = true
              event.changes.firstOrNull { it.id == down.id }?.let {
                tracker.addPointerInputChange(it)
                // A move the transform detector took: this touch is turning the city.
                if (it.pressed && it.isConsumed) turned = true
              }
            } while (event.changes.any { it.pressed })
            val fling = if (turned && !pinched && !calm && !state.flat) tracker.calculateVelocity() else Velocity.Zero
            coasting = launch { state.coast(fling, size.width, size.height) }
          }
        }
      }
      .pointerInput(state) {
        detectTransformGestures { _, pan, zoom, _ ->
          if (!state.flat) {
            state.turn(pan.x, pan.y, size.width, size.height)
            state.zoom(zoom)
          }
        }
      }
      // No double-tap: with one, every tap would wait out the double-tap timeout (about 300 ms)
      // before the morph could start. Apps put the camera back with resetCamera().
      .pointerInput(state) {
        detectTapGestures(
          onTap = { toggle() },
          // A long press asks the renderer what of the sky is under it first: a cloud, the sun or
          // the moon goes to the app, anything else brings the meteor down.
          onLongPress = { at ->
            val u = at.x / size.width
            val v = at.y / size.height
            val sky = skyPress
            if (sky == null) {
              meteor(u, v)
            } else {
              state.pick(u, v) { part ->
                if (part == null) {
                  meteor(u, v)
                } else {
                  if (haptics) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                  sky(part, at)
                }
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
    renderer.clouds = clouds
    renderer.city = current
    renderer.time = time
    renderer.weather = weather
    renderer.billboard = billboard
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
