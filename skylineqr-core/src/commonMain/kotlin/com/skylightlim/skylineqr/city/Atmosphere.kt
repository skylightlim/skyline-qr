package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.Platform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class TimeOfDay { AUTO, DAWN, DAY, DUSK, NIGHT }

enum class Weather { CLEAR, RAIN, HAZE, STORM }

/**
 * The light and the air of one moment: what the sky, the sun and the weather add to a frame.
 * Presentation only. It never enters a city's identity, so changing it never rebuilds the city.
 * Colours are display sRGB, 0..1.
 */
class Atmosphere(
  val skyTop: FloatArray,
  val skyHorizon: FloatArray,
  /** Toward the sun, or the moon at night. Unit length. */
  val sunDir: FloatArray,
  /** The sun's colour, already multiplied by its strength. */
  val sunColor: FloatArray,
  /** 0 by day, 1 at night: how many windows are lit, and how brightly. */
  val night: Float,
  val window: FloatArray,
  /**
   * The flat code's light modules: a pale tint of the hour's sky, never white, and always light
   * enough (luma 0.8 or more) for the ink to read against it.
   */
  val paper: FloatArray,
  /**
   * The flat code's colour for this hour and weather: teal by day, ember at dawn and dusk, deep blue
   * at night, greyed or warmed by the weather. Only its hue counts; see [FlatColours].
   */
  val ink: FloatArray,
  /** What the code's odd modules lean toward: a neighbour of [ink], like an olive leaf in a green tree. */
  val inkAccent: FloatArray,
  val rain: Float,
  val haze: Float,
  /** How wet the streets look: darker paving that reflects the sky. */
  val wet: Float,
  /** Lightning strikes. */
  val storm: Float,
  /** Where the sun's disc stands in the sky; below the horizon, it is down. */
  val sun: SunAndMoon.Place,
  /** Where the moon's disc stands, and how much of it is lit, 0 new to 1 full. */
  val moon: SunAndMoon.Place,
  val moonLit: Float,
)

/**
 * Time of day blends between keyframes on the clock, with equator hours: Kuala Lumpur's sunrise
 * and sunset move by only about half an hour across the year, so fixed times are right all year
 * and need no location. The fixed choices are those same keyframes, frozen.
 */
object Atmospheres {

  const val SUNRISE = 7 * 60 + 10
  const val SUNSET = 19 * 60 + 20
  private const val DAY_MINUTES = 24 * 60

  /**
   * The minute of the day each time setting shows. [TimeOfDay.AUTO] shows the clock's, with its
   * fraction, so the sun moves continuously rather than stepping once a minute.
   */
  fun minuteOf(time: TimeOfDay, clockMinute: Float): Float =
    when (time) {
      TimeOfDay.AUTO -> ((clockMinute % DAY_MINUTES) + DAY_MINUTES) % DAY_MINUTES
      TimeOfDay.DAWN -> SUNRISE.toFloat()
      TimeOfDay.DAY -> 12 * 60 + 30f
      TimeOfDay.DUSK -> SUNSET.toFloat()
      TimeOfDay.NIGHT -> 23 * 60f
    }

  /**
   * What the city sits against when seen from above: the air below the horizon. The renderer's
   * backdrop() in WGSL is the same blend, so the app can match the status bar to it.
   */
  fun backdrop(air: Atmosphere): FloatArray =
    FloatArray(3) { (air.skyHorizon[it] + (air.skyTop[it] - air.skyHorizon[it]) * 0.35f) * 0.92f }

  /** The phone's local clock as a minute of the day, with its fraction. */
  fun clockMinute(nowMillis: Long = Platform.nowMillis(), offsetMillis: Int = Platform.zoneOffsetMillis(nowMillis)): Float {
    val local = nowMillis + offsetMillis
    return (local.mod(86_400_000L) / 60_000.0).toFloat()
  }

  /**
   * Partway from [a] to [b] (2026-11-07: a change of hour or weather eases in instead of jumping):
   * the light, the sky and the code's colours by [light], each part of the weather by its own
   * share, so a storm can darken before its rain begins and its rain stop before it clears.
   */
  fun blend(a: Atmosphere, b: Atmosphere, light: Float, rain: Float, wet: Float, haze: Float, storm: Float): Atmosphere {
    val dir = mix(a.sunDir, b.sunDir, light)
    val length = kotlin.math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).coerceAtLeast(1e-4f)
    return Atmosphere(
      skyTop = mix(a.skyTop, b.skyTop, light),
      skyHorizon = mix(a.skyHorizon, b.skyHorizon, light),
      sunDir = FloatArray(3) { dir[it] / length },
      sunColor = mix(a.sunColor, b.sunColor, light),
      night = lerp(a.night, b.night, light),
      window = mix(a.window, b.window, light),
      paper = mix(a.paper, b.paper, light),
      ink = mix(a.ink, b.ink, light),
      inkAccent = mix(a.inkAccent, b.inkAccent, light),
      rain = lerp(a.rain, b.rain, rain),
      haze = lerp(a.haze, b.haze, haze),
      wet = lerp(a.wet, b.wet, wet),
      storm = lerp(a.storm, b.storm, storm),
      sun = between(a.sun, b.sun, light),
      moon = between(a.moon, b.moon, light),
      moonLit = lerp(a.moonLit, b.moonLit, light),
    )
  }

  /**
   * The light and the air of [time] and [weather] at [clockMinute] of the phone's clock. Given
   * [nowMillis], Now (2026-11-07, at the user's request) puts the sun and the moon where they
   * really stand over Kuala Lumpur at that instant; the fixed hours, and Now without it, keep a sun
   * on a fixed path and a moon high in the east after dark.
   */
  fun at(time: TimeOfDay, weather: Weather, clockMinute: Float, nowMillis: Long? = null): Atmosphere {
    val minute = minuteOf(time, clockMinute)
    val (a, b, t) = segment(minute)
    val night = lerp(a.night, b.night, t)
    var top = mix(a.top, b.top, t)
    var horizon = mix(a.horizon, b.horizon, t)
    var sun = scale(mix(a.sun, b.sun, t), lerp(a.strength, b.strength, t))
    val window = mix(a.window, b.window, t)
    var paper = mix(a.paper, b.paper, t)
    var ink = mix(a.ink, b.ink, t)
    var inkAccent = mix(a.inkAccent, b.inkAccent, t)

    // Weather is laid over the hour. Clouds are grey by day and dim at night.
    val daylight = 1f - 0.85f * night
    var windowsOn = night
    var rain = 0f
    var haze = 0f
    var wet = 0f
    var storm = 0f
    when (weather) {
      Weather.CLEAR -> {}
      Weather.RAIN -> {
        top = mix(top, scale(rgb(0x7F8C9E), 0.3f + 0.7f * daylight), 0.65f)
        horizon = mix(horizon, scale(rgb(0xA9B0B8), 0.3f + 0.7f * daylight), 0.65f)
        sun = scale(sun, 0.45f)
        windowsOn = max(windowsOn, 0.25f)
        rain = 0.75f
        wet = 1f
        haze = 0.22f
        paper = tint(paper, 0.95f, 0.97f, 1.0f) // cooler, keeping the hour's hue
        ink = mix(ink, rgb(0x3A6F96), 0.55f)
        inkAccent = mix(inkAccent, rgb(0x5B8FA0), 0.55f)
      }
      Weather.STORM -> {
        top = mix(top, scale(rgb(0x2A2F3D), 0.45f + 0.55f * daylight), 0.85f)
        horizon = mix(horizon, scale(rgb(0x4D5260), 0.45f + 0.55f * daylight), 0.8f)
        sun = scale(sun, 0.22f)
        windowsOn = max(windowsOn, 0.6f)
        rain = 1f
        wet = 1f
        storm = 1f
        haze = 0.3f
        paper = tint(paper, 0.91f, 0.94f, 1.0f)
        ink = mix(ink, rgb(0x3D5A80), 0.7f)
        inkAccent = mix(inkAccent, rgb(0x5A8090), 0.7f)
      }
      Weather.HAZE -> {
        // Thick smoke haze: the sun turns a dim orange and banks of it drift over the city,
        // swallowing the landmarks' upper floors.
        top = mix(top, scale(rgb(0xC2B697), 0.25f + 0.75f * daylight), 0.78f)
        horizon = mix(horizon, scale(rgb(0xD6C6A0), 0.25f + 0.75f * daylight), 0.82f)
        sun = floatArrayOf(sun[0] * 0.62f, sun[1] * 0.50f, sun[2] * 0.34f)
        haze = 1f
        paper = tint(paper, 1.0f, 0.98f, 0.94f) // warmer
        ink = mix(ink, rgb(0xA37A22), 0.6f)
        inkAccent = mix(inkAccent, rgb(0x8C8A3A), 0.6f)
      }
    }

    val now = nowMillis?.takeIf { time == TimeOfDay.AUTO }
    val clockSun = SunAndMoon.sunOnTheClock(minute)
    return Atmosphere(
      skyTop = top,
      skyHorizon = horizon,
      sunDir = lightDirection(minute),
      sunColor = sun,
      night = windowsOn,
      window = window,
      paper = paper,
      ink = ink,
      inkAccent = inkAccent,
      rain = rain,
      haze = haze,
      wet = wet,
      storm = storm,
      sun = if (now != null) SunAndMoon.sun(now) else clockSun,
      moon = if (now != null) SunAndMoon.moon(now) else clockMoon(clockSun.altitude),
      moonLit = if (now != null) SunAndMoon.lit(now) else NIGHT_MOON_LIT,
    )
  }

  /** Partway from [a] to [b] across the sky, the short way round. */
  private fun between(a: SunAndMoon.Place, b: SunAndMoon.Place, t: Float): SunAndMoon.Place {
    val turn = ((b.azimuth - a.azimuth + 3f * PI.toFloat()) % (2f * PI.toFloat())) - PI.toFloat()
    return SunAndMoon.Place(lerp(a.altitude, b.altitude, t), a.azimuth + turn * t)
  }

  /** How far below the horizon the sun is when a fixed hour's moon begins to rise: six degrees, civil dusk. */
  private const val DARK = -0.105f
  /** How much further the sun sinks while that moon climbs to its place: about eleven degrees, an hour of the clock. */
  private const val MOONRISE = 0.2f
  /** The fixed hours' moon: high in the east-south-east after dark, nearly full; below the horizon by day. */
  private val NIGHT_MOON = SunAndMoon.Place(0.87f, 2.0f)
  private const val SET_MOON = -0.12f
  private const val NIGHT_MOON_LIT = 0.92f

  /**
   * The fixed hours' moon with the sun at [sun] altitude: under the horizon until civil dusk, then
   * rising straight up to [NIGHT_MOON] as the sky darkens (2026-11-20: it used to jump there at
   * dusk, which the timelapse showed as a pop), and setting the same way before dawn.
   */
  private fun clockMoon(sun: Float) = SunAndMoon.Place(lerp(SET_MOON, NIGHT_MOON.altitude, smooth((DARK - sun) / MOONRISE)), NIGHT_MOON.azimuth)

  /** One keyframe. Its colour arrays are never written, so keyframes may share them. */
  private class Sky(
    val minute: Int,
    val top: FloatArray,
    val horizon: FloatArray,
    val sun: FloatArray,
    val strength: Float,
    val night: Float,
    val window: FloatArray,
    val paper: FloatArray,
    val ink: FloatArray,
    val inkAccent: FloatArray,
  ) {
    /** The same sky, held at another minute. */
    fun at(minute: Int) = Sky(minute, top, horizon, sun, strength, night, window, paper, ink, inkAccent)
  }

  private fun sky(minute: Int, top: Int, horizon: Int, sun: Int, strength: Float, night: Float, window: Int, paper: Int, ink: Int, inkAccent: Int) =
    Sky(minute, rgb(top), rgb(horizon), rgb(sun), strength, night, rgb(window), rgb(paper), rgb(ink), rgb(inkAccent))

  // The code's colours are nearly opposite each other across each change of hour, so the clock
  // carries them through grey on the way, never through purple.
  private val NIGHT = sky(0, 0x060B1F, 0x1A2650, 0x9EB8F2, 0.36f, 1f, 0xFFD98C, 0xD3DCF0, 0x1F78C8, 0x1796B0)
  private val DAWN = sky(SUNRISE, 0x7D93C9, 0xF7C4A0, 0xFFC08A, 0.90f, 0.35f, 0xFFD08A, 0xF4DEE2, 0xE0783A, 0xE0A030)
  private val DAY = sky(9 * 60, 0x6FA8E6, 0xE3EEF5, 0xFFF3DC, 1.08f, 0f, 0xFFE0A0, 0xDCEBF8, 0x07917F, 0x1F8FC4)
  private val DUSK = sky(SUNSET, 0x29386F, 0xF49558, 0xFF9A55, 1.05f, 0.40f, 0xFFC46B, 0xF8DCC6, 0xD96A1E, 0xC8402E)

  private val KEYS =
    listOf(
      NIGHT,
      NIGHT.at(5 * 60 + 40),
      DAWN,
      DAY,
      DAY.at(17 * 60 + 40),
      DUSK,
      NIGHT.at(20 * 60 + 40),
      NIGHT.at(DAY_MINUTES),
    )

  private fun segment(minute: Float): Triple<Sky, Sky, Float> {
    for (i in 0 until KEYS.size - 1) {
      val a = KEYS[i]
      val b = KEYS[i + 1]
      if (minute >= a.minute && minute <= b.minute) {
        val t = (minute - a.minute) / (b.minute - a.minute)
        return Triple(a, b, t * t * (3f - 2f * t))
      }
    }
    return Triple(NIGHT, NIGHT, 0f)
  }

  /**
   * The sun climbs from behind the city at sunrise, stands high over it at noon and sets in front
   * of the camera, so dusk lights the facades you see. At night the moon takes over, handing back
   * over two hours of twilight either side, slowly enough that shadows never visibly swing.
   */
  private fun lightDirection(minute: Float): FloatArray {
    val f = ((minute - SUNRISE) / (SUNSET - SUNRISE)).coerceIn(0f, 1f)
    val elevation = 0.10f + 1.05f * sin(PI.toFloat() * f)
    val azimuth = lerp(3.49f, 1.05f, f) // about 200 degrees round to 60
    val sun = floatArrayOf(cos(azimuth) * cos(elevation), sin(elevation), sin(azimuth) * cos(elevation))
    val moon = normalize(floatArrayOf(0.30f, 0.55f, -0.45f))
    val moonlight =
      if (minute < 12 * 60) 1f - smooth((minute - 5 * 60) / (SUNRISE - 5 * 60))
      else smooth((minute - SUNSET) / (SUNRISE - 5 * 60))
    return normalize(mix(sun, moon, moonlight))
  }

  private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

  private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

  private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(a.size) { lerp(a[it], b[it], t) }

  private fun scale(v: FloatArray, s: Float) = FloatArray(v.size) { v[it] * s }

  private fun tint(v: FloatArray, r: Float, g: Float, b: Float) = floatArrayOf(v[0] * r, v[1] * g, v[2] * b)

  private fun normalize(v: FloatArray): FloatArray {
    val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
  }

  private fun rgb(hex: Int) = Materials.rgb(hex)
}
