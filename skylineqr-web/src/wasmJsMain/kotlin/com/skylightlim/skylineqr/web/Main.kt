@file:OptIn(ExperimentalWasmJsInterop::class)

package com.skylightlim.skylineqr.web

import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.FlatColours
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.LongPress
import com.skylightlim.skylineqr.compose.SkylineEvent
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import com.skylightlim.skylineqr.core.SkylineQrException
import com.skylightlim.skylineqr.render.View
import com.skylightlim.skylineqr.render.WebRenderer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * The website (2026-11-21): the demo app's screen in a browser. The city, a link box below it, and the
 * app's controls: Now, Day or Night; Clear, Storm or Haze; the tour, the timelapse, what a long
 * press does, the clouds and the sign board. Tap to fold the city into its code and back; drag to
 * turn it, pinch or scroll to zoom, long-press for a meteor or fireworks. Where WebGPU cannot run,
 * the flat code is drawn on a 2D canvas instead, so the link still scans.
 */
fun main() {
  Page().start()
}

private class Page {
  private val renderer = WebRenderer(onFailure = { fallBack() }, onEvent = ::event)
  private var city: City? = null
  private var link = DEFAULT_LINK
  private var time = TimeOfDay.AUTO
  private var weather = Weather.CLEAR
  private var longPress = LongPress.METEOR
  private var flat = false
  private var fallback = false
  private var touring = false
  private var timelapsing = false
  private var signs = DEFAULT_SIGNS

  /** Where the fingers have taken the camera, limits and all; [view] is that with soft edges. */
  private var reach = View()
  private var view = View()

  fun start() {
    val calm = Dom.reducedMotion()
    renderer.calm = calm
    // No share links, as in the app (DESIGN.md, 2026-11-09): the page always opens on the default.
    Dom.setValue("link-input", link)
    Dom.setValue("signs-input", signs.joinToString("\n"))
    renderer.billboard = signs

    Dom.onInput("link-input") { build(Dom.value("link-input")) }
    Dom.onClick("time-now") { setTime(TimeOfDay.AUTO) }
    Dom.onClick("time-day") { setTime(TimeOfDay.DAY) }
    Dom.onClick("time-night") { setTime(TimeOfDay.NIGHT) }
    Dom.onClick("weather-clear") { setWeather(Weather.CLEAR) }
    Dom.onClick("weather-storm") { setWeather(Weather.STORM) }
    Dom.onClick("weather-haze") { setWeather(Weather.HAZE) }
    Dom.onClick("press-meteor") { setLongPress(LongPress.METEOR) }
    Dom.onClick("press-fireworks") { setLongPress(LongPress.FIREWORKS) }
    Dom.onClick("clouds") {
      renderer.clouds = !renderer.clouds
      Dom.setPressed("clouds", renderer.clouds)
    }
    Dom.onClick("tour") { startTour() }
    Dom.onClick("timelapse") { startTimelapse() }
    Dom.onClick("reset") { resetCamera() }
    Dom.onClick("stop") {
      stopTour()
      stopTimelapse()
    }
    Dom.onInput("signs-input") {
      signs = Dom.value("signs-input").split('\n')
      renderer.billboard = signs
    }
    Dom.onPointer("stage", ::pointer)
    Dom.onWheel("stage") { deltaY -> if (!flat && !touring) zoom(exp(-deltaY.toFloat() * 0.0015f)) }
    Dom.onResize { resize() }
    Dom.everySecond { refreshTheme() }

    build(link)
    setTime(time)
    setWeather(weather)
    setLongPress(longPress)
    Dom.setPressed("clouds", true)
    showControls()
    val (w, h) = Dom.canvasSize("city")
    renderer.start(w, h)
  }

  // The link and its city.

  private fun build(input: String) {
    val text = input.ifBlank { DEFAULT_LINK }
    try {
      val identity = CityIdentity.of(text, IdentityScope.SITE)
      val planned = CityPlanner.plan(QrEncoder.encode(identity.link.payload), identity, CityPreset.KUALA_LUMPUR)
      city = planned
      link = text
      renderer.city = planned
      Dom.setText(
        "error",
        if (identity.link.hasSensitiveQuery) "This link looks like it carries a token or password. Think twice before sharing its code." else "",
      )
      drawFallback()
    } catch (e: SkylineQrException) {
      // The last good city stays on screen meanwhile.
      Dom.setText("error", e.message ?: "That link does not work.")
    }
  }

  private fun setTime(value: TimeOfDay) {
    stopTimelapse()
    time = value
    renderer.time = value
    Dom.setPressed("time-now", value == TimeOfDay.AUTO)
    Dom.setPressed("time-day", value == TimeOfDay.DAY)
    Dom.setPressed("time-night", value == TimeOfDay.NIGHT)
    refreshTheme()
  }

  private fun setWeather(value: Weather) {
    weather = value
    renderer.weather = value
    Dom.setPressed("weather-clear", value == Weather.CLEAR)
    Dom.setPressed("weather-storm", value == Weather.STORM)
    Dom.setPressed("weather-haze", value == Weather.HAZE)
    refreshTheme()
  }

  private fun setLongPress(value: LongPress) {
    longPress = value
    Dom.setPressed("press-meteor", value == LongPress.METEOR)
    Dom.setPressed("press-fireworks", value == LongPress.FIREWORKS)
  }

  private fun air() = Atmospheres.at(time, weather, Atmospheres.clockMinute())

  /** The page's chrome follows the sky behind the city, as the phone's status bar does. */
  private fun refreshTheme() {
    val c = Atmospheres.backdrop(air())
    Dom.setTheme(css(c), (0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]) > 0.55f)
    if (fallback) drawFallback()
  }

  // Folding, the tour and the timelapse.

  private fun toggle() {
    if (touring) return
    if (timelapsing) {
      stopTimelapse()
      return
    }
    setFlat(!flat)
  }

  private fun setFlat(value: Boolean) {
    flat = value
    renderer.setFlat(value)
    Dom.setLabel("stage", if (value) "QR code for $link. Tap to show the city." else "City QR code for $link. Tap to show the code.")
  }

  private fun startTour() {
    if (fallback || city == null) return
    stopTimelapse()
    touring = true
    setFlat(false)
    resetCamera()
    renderer.startTour { completed ->
      touring = false
      if (completed) setFlat(true)
      showControls()
    }
    showControls()
  }

  private fun stopTour() {
    if (!touring) return
    touring = false
    renderer.stopTour()
    showControls()
  }

  private fun startTimelapse() {
    if (fallback || city == null) return
    stopTour()
    timelapsing = true
    setFlat(false)
    renderer.startTimelapse { completed ->
      if (!timelapsing) return@startTimelapse
      timelapsing = false
      // It ends on Night's sky and stays there, so show Night as the hour.
      if (completed) setTime(TimeOfDay.NIGHT)
      showControls()
    }
    showControls()
  }

  private fun stopTimelapse() {
    if (!timelapsing) return
    timelapsing = false
    renderer.stopTimelapse()
    showControls()
  }

  /** The app hides its controls while the tour flies or the timelapse runs. */
  private fun showControls() {
    val busy = touring || timelapsing
    Dom.setHidden("controls", busy)
    Dom.setHidden("stop", !busy)
    Dom.setText("stop", if (touring) "Stop the tour" else "Stop the timelapse")
    Dom.setHidden("reset", busy || view == View() || fallback)
    Dom.setHidden("city-only", fallback)
    Dom.setHidden("city-settings", fallback)
  }

  // The camera: the app's drag, pinch, momentum and soft limits.

  private fun resetCamera() {
    reach = View()
    view = View()
    renderer.view = view
    showControls()
  }

  private fun turn(dx: Float, dy: Float) {
    val (w, h) = Dom.canvasCssSize("stage")
    reach = reach.copy(yaw = reach.yaw - dx / w * YAW_PER_WIDTH, elevation = reach.elevation + dy / h * TILT_PER_HEIGHT)
    view = soft(reach)
    renderer.view = view
  }

  private fun zoom(factor: Float) {
    reach = reach.copy(zoom = reach.zoom * factor)
    view = soft(reach)
    renderer.view = view
    showControls()
  }

  private fun settle() {
    reach = View(
      reach.yaw.coerceIn(-View.MAX_YAW, View.MAX_YAW),
      reach.elevation.coerceIn(View.MIN_ELEVATION, View.MAX_ELEVATION),
      reach.zoom.coerceIn(View.MIN_ZOOM, View.MAX_ZOOM),
    )
    view = reach
    renderer.view = view
    showControls()
  }

  private class Touch(val id: Int, var x: Double, var y: Double)

  private val touches = ArrayList<Touch>()
  private var downAt = 0L
  private var downX = 0.0
  private var downY = 0.0
  private var moved = false
  private var pinched = false
  private var swallow = false
  private var pressTimer = -1
  private var pressed = false
  /** Recent moves of the first finger, for the speed it lets go at: time in ms, x, y. */
  private val trail = ArrayList<DoubleArray>()
  private var coasting = 0

  private fun pointer(kind: Int, id: Int, x: Double, y: Double) {
    when (kind) {
      Dom.DOWN -> {
        if (touches.isEmpty()) {
          // A touch while the tour flies stops it, and does nothing else.
          swallow = touring
          stopTour()
          coasting++
          downAt = Browser.millis()
          downX = x
          downY = y
          moved = false
          pinched = false
          pressed = false
          trail.clear()
          pressTimer = Dom.after(LONG_PRESS_MILLIS) { longPress(x, y) }
        } else {
          pinched = true
          Dom.cancel(pressTimer)
        }
        touches += Touch(id, x, y)
        trail += doubleArrayOf(Browser.millis().toDouble(), x, y)
      }
      Dom.MOVE -> {
        val t = touches.firstOrNull { it.id == id } ?: return
        if (!moved && hypot(x - downX, y - downY) > TOUCH_SLOP) {
          moved = true
          Dom.cancel(pressTimer)
        }
        if (touches.size >= 2) {
          val a = touches[0]
          val b = touches[1]
          val before = hypot(a.x - b.x, a.y - b.y)
          val beforeMidX = (a.x + b.x) / 2
          val beforeMidY = (a.y + b.y) / 2
          t.x = x
          t.y = y
          val after = hypot(a.x - b.x, a.y - b.y)
          if (!flat && !swallow) {
            if (before > 1.0) zoom((after / before).toFloat())
            turn(((a.x + b.x) / 2 - beforeMidX).toFloat(), ((a.y + b.y) / 2 - beforeMidY).toFloat())
          }
        } else {
          val dx = x - t.x
          val dy = y - t.y
          t.x = x
          t.y = y
          if (moved && !flat && !swallow) turn(dx.toFloat(), dy.toFloat())
          val now = Browser.millis().toDouble()
          trail += doubleArrayOf(now, x, y)
          while (trail.size > 2 && now - trail[0][0] > 100) trail.removeAt(0)
        }
      }
      Dom.UP, Dom.CANCEL -> {
        val t = touches.firstOrNull { it.id == id } ?: return
        touches.remove(t)
        if (touches.isNotEmpty()) return
        Dom.cancel(pressTimer)
        val tap = kind == Dom.UP && !moved && !pinched && !pressed && Browser.millis() - downAt < LONG_PRESS_MILLIS
        when {
          swallow -> {}
          tap -> toggle()
          moved && !pinched && !flat && !renderer.calm -> coast()
          else -> settle()
        }
      }
    }
  }

  /** A released turn runs on at the finger's speed and slows quickly, as the app's does. */
  private fun coast() {
    val first = trail.firstOrNull()
    val last = trail.lastOrNull()
    val span = if (first == null || last == null) 0.0 else (last[0] - first[0]) / 1000.0
    if (first == null || last == null || span <= 0.0) {
      settle()
      return
    }
    var vx = ((last[1] - first[1]) / span).toFloat()
    var vy = ((last[2] - first[2]) / span).toFloat()
    val run = ++coasting
    var then = Browser.nanos()
    fun step(nanos: Long) {
      if (run != coasting) return
      val dt = ((nanos - then) / 1e9f).coerceIn(0f, 0.05f)
      then = nanos
      turn(vx * dt, vy * dt)
      val decay = exp(-COAST_FRICTION * dt)
      vx *= decay
      vy *= decay
      val pastLimits = reach.yaw !in -View.MAX_YAW..View.MAX_YAW || reach.elevation !in View.MIN_ELEVATION..View.MAX_ELEVATION
      if (pastLimits || (kotlin.math.abs(vx) < 5f && kotlin.math.abs(vy) < 5f)) settle() else Browser.nextFrame(::step)
    }
    Browser.nextFrame(::step)
  }

  private fun longPress(x: Double, y: Double) {
    pressed = true
    if (swallow || flat || fallback || renderer.calm) return
    val (w, h) = Dom.canvasCssSize("stage")
    val u = (x / w).toFloat()
    val v = (y / h).toFloat()
    when (longPress) {
      LongPress.METEOR -> {
        val now = Browser.millis()
        if (now - lastStrike < STRIKE_MILLIS) return
        lastStrike = now
        renderer.strike(u, v)
        Dom.vibrate(intArrayOf(30))
      }
      LongPress.FIREWORKS -> {
        renderer.fireworks(u, v)
        Dom.vibrate(intArrayOf(30))
      }
    }
  }

  private var lastStrike = 0L

  private fun event(event: SkylineEvent) {
    // The thud of the impact, then aftershocks dying away, where the phone can.
    if (event == SkylineEvent.METEOR_IMPACT) Dom.vibrate(intArrayOf(70, 50, 60, 70, 40))
  }

  // The canvas and the fallback.

  private fun resize() {
    val (w, h) = Dom.canvasSize("city")
    renderer.resize(w, h)
    if (fallback) drawFallback()
  }

  private fun fallBack() {
    fallback = true
    Dom.setHidden("city", true)
    Dom.setHidden("flat", false)
    Dom.setText(
      "notice",
      "This browser can't run WebGPU, so here is the flat code. Try a recent Chrome, Edge or Safari for the city.",
    )
    Dom.setHidden("notice", false)
    showControls()
    drawFallback()
  }

  /** The code alone, drawn in the colours of the flat city at this hour, as the app's fallback. */
  private fun drawFallback() {
    if (!fallback) return
    val city = city ?: return
    val air = air()
    val n = city.modules
    Dom.codeBegin(n, css(FlatColours.paper(air)))
    for (row in 0 until n) for (col in 0 until n) Dom.codeModule(col, row, css(FlatColours.module(city, col, row, air)))
    Dom.codeDraw("flat", QUIET)
  }

  private companion object {
    const val DEFAULT_LINK = "https://github.com/skylightlim/skyline-qr"

    /** The app's sign board, as it ships. */
    val DEFAULT_SIGNS = listOf("马大牛逼", "tarumt #1", "i need argon")

    const val QUIET = 4
    const val LONG_PRESS_MILLIS = 500
    const val TOUCH_SLOP = 8.0
    const val STRIKE_MILLIS = 4_700L
    const val YAW_PER_WIDTH = 3f
    const val TILT_PER_HEIGHT = 1.4f

    /** Compose's exponentialDecay(frictionMultiplier = 2.5f), which the app coasts with. */
    const val COAST_FRICTION = 4.2f * 2.5f

    fun css(c: FloatArray): String {
      fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      return "rgb(${channel(c[0])},${channel(c[1])},${channel(c[2])})"
    }

    /** The camera with soft limits: as far past one as it likes, it shows at most a little past. */
    fun soft(view: View) =
      View(
        soft(view.yaw, -View.MAX_YAW, View.MAX_YAW, give = 0.3f),
        soft(view.elevation, View.MIN_ELEVATION, View.MAX_ELEVATION, give = 0.12f),
        if (view.zoom in View.MIN_ZOOM..View.MAX_ZOOM) view.zoom else exp(soft(ln(view.zoom), ln(View.MIN_ZOOM), ln(View.MAX_ZOOM), give = 0.12f)),
      )

    fun soft(x: Float, min: Float, max: Float, give: Float): Float {
      fun band(over: Float) = over * give * 0.55f / (give + 0.55f * over)
      return when {
        x > max -> max + band(x - max)
        x < min -> min - band(min - x)
        else -> x
      }
    }
  }
}
