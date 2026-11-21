package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.SunAndMoon
import com.skylightlim.skylineqr.city.Vehicles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The weather's own shapes, blocky like the city, rebuilt as boxes every frame with the traffic
 * (2026-11-07, at the user's request): the clear sky's five to eight clouds, spread evenly over it;
 * the sun and the moon, as high in the sky as they stand (see [disc]); a storm's deck of dark
 * clouds; and lightning, from the base of one of those clouds down onto a roof. Haze has no shapes
 * of its own: it is the fog the block shader rolls in, and the wind streaks the rain pipeline
 * draws. All of them drift with the wind, wrapping round, and none casts a shadow.
 *
 * Clouds never shrink or fade away. When the weather changes, or the clouds are turned off, they
 * blow on downwind (+x) out of sight, and ones that come blow in from upwind (see [Amounts]).
 * Every cloud is its own shape, from its city's seed and its place: a heap of soft balls along a
 * level base, billowing up in the middle (see [fairShape]), which the cloud shader draws soft-edged
 * and blended (2026-11-12); a storm's are larger, dark and heavy underneath, some with a tower on
 * top.
 *
 * Pure Kotlin, so a JVM test can write exactly the boxes the renderer draws.
 */
internal object SkyParts {

  /**
   * Where each layer of the weather's sky is, as the renderer moves it. A layer of clouds has a way
   * across: -1 waiting upwind out of sight, 0 in place over the city, 1 blown away downwind out of
   * sight (see [travel]).
   */
  class Amounts(
    /** The clear sky's clouds' way across. */
    var fair: Float = 0f,
    /** The storm deck's way across. */
    var deck: Float = 1f,
    /** How dark the deck is: 1 a storm's, less for rain's. */
    var overcast: Float = 1f,
    /** The sun and the moon, 0 to 1: out in a clear sky and, dimmed, in haze. */
    var shine: Float = 1f,
  )

  /**
   * A layer's way across after [dt] seconds more: toward its place over the city if it is
   * [wanted], blowing in from upwind (one that has blown away comes again from upwind); if not,
   * on downwind and out of sight, carried right through the city if it was still arriving.
   */
  fun travel(way: Float, wanted: Boolean, dt: Float): Float {
    val step = dt / CLOUD_MOVE
    if (wanted) {
      val from = if (way >= 1f) -1f else way
      return if (from < 0f) min(0f, from + step) else max(0f, from - step)
    }
    if (way <= -1f) return -1f
    return min(1f, way + step)
  }

  /** Whether a layer at [way] shows at all. */
  fun shows(way: Float) = abs(way) < 1f

  /** How many clouds [city]'s clear sky has: five to eight, by its seed. */
  fun fairCount(city: City) = 5 + city.identity.seed.mod(4)

  /**
   * Every weather shape for this frame. [lit] is the storm cloud the current bolt leaves, glowing
   * by [flash]; -1 for none.
   */
  fun emit(box: Vehicles.Sink, city: City, seconds: Float, calm: Boolean, amounts: Amounts, air: Atmosphere, lit: Int = -1, flash: Float = 0f) {
    val wind = drift(seconds, calm)
    val cloud = FloatArray(4)
    val seed = city.identity.seed
    if (shows(amounts.deck)) {
      val grey = 0.86f + (0.34f - 0.86f) * amounts.overcast
      for (k in 0 until STORM_CLOUDS) {
        stormCloud(k, city, wind, amounts, cloud)
        stormShape(box, cloud, grey + 0.06f * unit(k * 3 + 105), seed + k * 31, if (k == lit) 0.45f * flash else 0f)
      }
    }
    if (shows(amounts.fair)) {
      for (k in 0 until fairCount(city)) {
        fairCloud(k, city, wind, amounts, cloud)
        fairShape(box, cloud, 0.95f + 0.04f * unit(seed + k * 3 + 7), seed + k * 17 + 5)
      }
    }
    if (amounts.shine > 0.01f) {
      val at = FloatArray(3)
      // The sun, deeper orange the lower it stands; it sinks behind the skyline as it sets.
      val sunUp = horizon(air.sun.altitude)
      if (sunUp > 0f) {
        disc(air.sun, city, at)
        val high = sin(air.sun.altitude).coerceIn(0f, 1f)
        round(box, at, SUN * amounts.shine * sunUp, SUN_GLOW, high)
      }
      // The moon, its whole disc whenever it is up and any of it is lit (its glow says how much);
      // only at new moon, lost in the sun, is it gone. Never with the sun (2026-11-12, at the
      // user's request: the real sky often has both, and it read as a mistake): it comes out as
      // the sun goes down.
      val moonUp = horizon(air.moon.altitude) * smooth((air.moonLit - 0.02f) / 0.03f) * (1f - sunUp)
      if (moonUp > 0f) {
        disc(air.moon, city, at)
        round(box, at, MOON * amounts.shine * moonUp, MOON_GLOW, air.moonLit)
      }
    }
  }

  /**
   * Where the sun or the moon at [place] hangs over [city], into [out] (x, y, z): on an arc
   * standing upright behind the city, square to the camera as it usually stands
   * ([CityPlanner.VIEW_YAW]), so it reads as the sky. Its height on the arc is its real altitude,
   * just over the far skyline on the horizon and near the top of the screen overhead; across, it
   * is the share of its direction that lies to the camera's right, so it rises on the east side
   * of the screen and sets on the west. Turning the city carries the arc round with it.
   */
  fun disc(place: SunAndMoon.Place, city: City, out: FloatArray) {
    val half = city.plateHalf
    val yaw = CityPlanner.VIEW_YAW
    val across = cos(place.altitude) * sin(place.azimuth + yaw) * ARC_WIDE * half
    val back = ARC_BACK * half
    out[0] = cos(yaw) * across - sin(yaw) * back
    out[1] = (ARC_BASE + ARC_HIGH * sin(place.altitude)) * half
    out[2] = -sin(yaw) * across - cos(yaw) * back
  }

  /** Storm cloud [k]'s middle and size into [out]: x, y, z and size. */
  fun stormCloud(k: Int, city: City, wind: Float, amounts: Amounts, out: FloatArray) {
    val half = city.plateHalf
    val span = half * SPAN
    // A jittered grid, so the deck has no wide gaps for a bolt to come out of nothing.
    val column = k % STORM_COLUMNS
    val row = k / STORM_COLUMNS
    val x = wrap(-span / 2f + (column + 0.2f + 0.6f * unit(k * 3 + 101)) * span / STORM_COLUMNS + wind * 0.8f, span)
    out[0] = x + away(amounts.deck, k + 400, half)
    out[1] = city.maxHeight + 7f + 3.5f * unit(k * 3 + 104)
    out[2] = -half * 1.15f + (row + 0.15f + 0.7f * unit(k * 3 + 102)) * half * 2.3f / STORM_ROWS
    out[3] = 2.4f + 1.6f * unit(k * 3 + 103)
  }

  /**
   * The storm cloud nearest above ([x], [z]) at [seconds], for a bolt to leave: the one whose
   * middle is closest across the ground.
   */
  fun nearestStormCloud(x: Float, z: Float, city: City, seconds: Float, calm: Boolean, amounts: Amounts): Int {
    val cloud = FloatArray(4)
    val wind = drift(seconds, calm)
    var best = 0
    var bestDistance = Float.MAX_VALUE
    for (k in 0 until STORM_CLOUDS) {
      stormCloud(k, city, wind, amounts, cloud)
      val d = hypot(cloud[0] - x, cloud[2] - z)
      if (d < bestDistance) {
        bestDistance = d
        best = k
      }
    }
    return best
  }

  /**
   * The bolt of [strike] down onto the roof at ([x], [z]) [top] high, in steps that jog sideways
   * less and less, each a glowing column and a rung to the next. It leaves storm cloud [cloud]
   * from under its middle, leaning toward the roof, so it comes out of the cloud, never out of the
   * clear sky; with no deck to come from ([cloud] -1: the clouds are off), it comes from high
   * above. Boxes only turn about their axis, so a bolt is drawn as a staircase. Its jitter is
   * seeded by the strike, so it holds still while it flickers.
   */
  fun bolt(box: Vehicles.Sink, city: City, seconds: Float, calm: Boolean, amounts: Amounts, strike: Int, cloud: Int, x: Float, z: Float, top: Float, flash: Float) {
    val startX: Float
    val startZ: Float
    val sky: Float
    if (cloud >= 0) {
      val c = FloatArray(4)
      stormCloud(cloud, city, drift(seconds, calm), amounts, c)
      val reach = c[3] * 0.35f
      startX = c[0] + (x - c[0]).coerceIn(-reach, reach)
      startZ = c[2] + (z - c[2]).coerceIn(-reach, reach)
      // Inside the cloud's dark base a little, so the top of the bolt is hidden in it.
      sky = c[1] - c[3] * 0.1f
    } else {
      startX = x + (unit(strike * 13 + 1) - 0.5f) * 7f
      startZ = z + (unit(strike * 13 + 2) - 0.5f) * 7f
      sky = city.maxHeight + 14f
    }
    var px = startX
    var pz = startZ
    var py = sky
    val steps = 9
    for (i in 1..steps) {
      val t = i / steps.toFloat()
      val wobble = 1.6f * (1f - t)
      val nx = startX + (x - startX) * t + (unit(strike * 13 + i * 2 + 3) - 0.5f) * wobble
      val nz = startZ + (z - startZ) * t + (unit(strike * 13 + i * 2 + 4) - 0.5f) * wobble
      val ny = sky + (top - sky) * t
      val w = 0.22f - 0.08f * t
      box.box(px, (py + ny) / 2f, pz, w, py - ny, w, CityPlanner.ROLE_BOLT, flash, 0.5f, 0f, 1f)
      val dx = nx - px
      val dz = nz - pz
      val run = hypot(dx, dz)
      if (run > 0.01f) {
        box.box((px + nx) / 2f, ny, (pz + nz) / 2f, run + w, w, w, CityPlanner.ROLE_BOLT, flash, 0.5f, atan2(dz, dx), 1f)
      }
      px = nx
      py = ny
      pz = nz
    }
  }

  /**
   * Clear-sky cloud [k]'s middle and size into [out]: spread evenly round the sky, each in its own
   * slot along the wind and the next one a golden-ratio step across it, so no two bunch together
   * as the wind carries them round.
   */
  private fun fairCloud(k: Int, city: City, wind: Float, amounts: Amounts, out: FloatArray) {
    val half = city.plateHalf
    val span = half * SPAN
    val count = fairCount(city)
    val seed = city.identity.seed
    val slot = (k + 0.5f + 0.5f * (unit(seed + k * 3 + 201) - 0.5f)) / count
    out[0] = wrap(-span / 2f + slot * span + wind * 0.5f, span) + away(amounts.fair, k, half)
    val across = (k * GOLDEN + 0.37f) - floor(k * GOLDEN + 0.37f)
    out[2] = -half * 1.25f + across * half * 2.3f
    out[1] = city.maxHeight + 6f + 6f * unit(seed + k * 3 + 203)
    out[3] = 1.8f + 1.1f * unit(seed + k * 3 + 204)
  }

  /**
   * How far cloud [k] of a layer at [way] is from its place along the wind: none in place, and far
   * enough upwind or downwind to be out of sight at either end. Each cloud goes its own distance,
   * so the layer loosens as it travels; it gathers speed as it leaves and slows as it arrives.
   */
  private fun away(way: Float, k: Int, half: Float): Float {
    if (way == 0f) return 0f
    val eased = smooth(abs(way)) * (if (way < 0f) -1f else 1f)
    return eased * half * AWAY * (0.85f + 0.3f * unit(k * 5 + 77))
  }

  /**
   * One clear-sky cloud, [at] its middle and size (x, y, z, size), grey [grey], its shape from
   * [seed] (2026-11-12, second revision, at the user's request: like a real cloud; faceted boxes
   * never read as one). A cloud is a heap of soft balls the cloud shader draws (Wgsl.cloudFragment),
   * each a box here that holds one: a row of them along a level base, overlapping, the biggest
   * near the middle; a shorter row heaped on top, set back; and a crown or two over that, so it
   * billows up in the middle like a fair-weather cumulus. How many, how long and how tall all
   * come from the seed.
   */
  private fun fairShape(box: Vehicles.Sink, at: FloatArray, grey: Float, seed: Int) {
    val cloud = Cloud(box, at, grey, seed, glow = 0f)
    val puffs = 4 + (unit(seed * 7 + 3) * 4f).toInt()
    val big = 0.5f + 0.18f * unit(seed * 7 + 4)
    cloud.row(puffs, puffs * big * 0.95f, big, BOTTOM, 0.3f, squash = 0.62f, shade = 0.97f)
    cloud.row(puffs - 2, (puffs - 2) * big * 0.8f, big * 0.82f, BOTTOM + big * 0.45f, 0.22f, squash = 0.8f, shade = 1f)
    val crowns = 1 + (unit(seed * 7 + 1) * 2f).toInt()
    for (k in 0 until crowns) {
      cloud.puff((unit(seed * 7 + 20 + k) - 0.5f) * big * 1.6f, BOTTOM + big * (1.25f + 0.2f * unit(seed * 7 + 30 + k)), (unit(seed * 7 + 40 + k) - 0.5f) * 0.3f, big * 0.62f, 0.85f, 1f)
    }
  }

  /**
   * One cloud of a storm's deck: the same balls, larger, flatter and closer together, dark at the
   * base and lighter where they rise, and on some a tower of them climbing out of the top.
   */
  private fun stormShape(box: Vehicles.Sink, at: FloatArray, grey: Float, seed: Int, glow: Float) {
    val cloud = Cloud(box, at, grey, seed, glow)
    val puffs = 5 + (unit(seed * 7 + 3) * 3f).toInt()
    val big = 0.66f + 0.16f * unit(seed * 7 + 2)
    cloud.row(puffs, puffs * big * 0.8f, big, 0f, 0.4f, squash = 0.55f, shade = 0.8f)
    cloud.row(puffs - 1, (puffs - 1) * big * 0.7f, big * 0.85f, big * 0.4f, 0.3f, squash = 0.7f, shade = 1f)
    if (unit(seed * 7 + 4) < 0.45f) cloud.puff((unit(seed * 7 + 5) - 0.5f) * 0.6f, big * 1.3f, 0f, big * 0.7f, 0.9f, 1.06f)
  }

  /**
   * A cloud being put together: puffs placed about its middle in its size, turned with it by its
   * own small yaw, each in a shade of its grey.
   */
  private class Cloud(private val box: Vehicles.Sink, private val at: FloatArray, private val grey: Float, private val seed: Int, private val glow: Float) {
    private val turn = (unit(seed * 7 + 11) - 0.5f) * 0.9f
    private val c = cos(turn)
    private val s = sin(turn)
    private var placed = 0

    /**
     * [count] puffs side by side along [length], their undersides at [bottom]: the biggest, of
     * radius [radius], near the middle and the ends smaller, each up to [spread] in front or
     * behind, [squash] times as tall as wide.
     */
    fun row(count: Int, length: Float, radius: Float, bottom: Float, spread: Float, squash: Float, shade: Float) {
      for (i in 0 until count) {
        val k = placed
        val t = if (count == 1) 0f else i / (count - 1f) * 2f - 1f
        val r = radius * (1f - 0.4f * t * t) * (0.85f + 0.3f * unit(seed * 17 + k))
        val x = t * (length - 2f * radius).coerceAtLeast(0f) / 2f + (unit(seed * 13 + k) - 0.5f) * radius * 0.4f
        puff(x, bottom + r * squash, (unit(seed * 23 + k) - 0.5f) * 2f * spread, r, squash, shade)
      }
    }

    /** One puff about ([dx], [dy], [dz]): a ball [r] across its middle and [squash] times that up and down. */
    fun puff(dx: Float, dy: Float, dz: Float, r: Float, squash: Float, shade: Float) {
      placed++
      val size = at[3]
      // The glow says what it is to the shaders: under a half, a cloud; a bolt lights it up to that.
      box.box(
        at[0] + (dx * c - dz * s) * size, at[1] + dy * size, at[2] + (dx * s + dz * c) * size,
        2f * r * size, 2f * r * squash * size, 2f * r * size,
        CityPlanner.ROLE_WEATHER, glow.coerceAtMost(0.5f), (grey * shade).coerceIn(0f, 1f), 0f, 1f,
      )
    }
  }

  /** A disc of radius [r] at [at]: two boxes a quarter-turn apart, so it reads round. */
  private fun round(box: Vehicles.Sink, at: FloatArray, r: Float, glow: Float, rnd: Float) {
    if (r < 0.05f) return
    box.box(at[0], at[1], at[2], r, r, r, CityPlanner.ROLE_WEATHER, glow, rnd, 0f, 1f)
    box.box(at[0], at[1], at[2], r, r * 0.92f, r, CityPlanner.ROLE_WEATHER, glow, rnd, 0.785f, 1f)
  }

  /** How far up a disc is: whole above the horizon, gone four degrees below it. */
  private fun horizon(altitude: Float) = smooth((altitude + 0.07f) / 0.07f)

  /** How far the wind has carried everything by [seconds]; it is still with animations off. */
  fun drift(seconds: Float, calm: Boolean) = if (calm) 0f else seconds * WIND

  private fun wrap(x: Float, span: Float) = ((x + span / 2f) % span + span) % span - span / 2f

  private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

  /** 0..1 from an integer: lowbias32 (Chris Wellons, public domain), as in the shaders. */
  private fun unit(n: Int): Float {
    var h = n * -0x61c88647
    h = h xor (h ushr 16)
    h *= 0x7feb352d
    h = h xor (h ushr 15)
    h *= -0x7b935975
    h = h xor (h ushr 16)
    return (h ushr 8) / 16777216f
  }

  /** The wind the weather's shapes drift on, in units a second, toward +x. */
  private const val WIND = 0.6f

  /** How long a layer of clouds takes to blow in, or away, in seconds. */
  private const val CLOUD_MOVE = 3.2f

  /** How far out of sight a layer waits, or is blown, in plate halves: past the screen at either end. */
  private const val AWAY = 4.2f

  /** How wide the band the clouds wrap round in is, in plate halves. */
  private const val SPAN = 2.8f

  /** A storm's deck as a grid of columns along the wind and rows across it. */
  private const val STORM_COLUMNS = 6
  private const val STORM_ROWS = 3
  const val STORM_CLOUDS = STORM_COLUMNS * STORM_ROWS

  private const val GOLDEN = 0.618034f

  /** Where a clear-sky cloud's flat base is, in its sizes below its middle. */
  private const val BOTTOM = -0.2f

  /** The sun's and the moon's radius, and the glow that tells the shader which is which. */
  private const val SUN = 2.6f
  private const val MOON = 2.1f
  private const val SUN_GLOW = 1f
  private const val MOON_GLOW = 0.75f

  /**
   * The sky arc, in plate halves: half its width, how far behind the city's middle it stands, and
   * the height of its foot and of its top over that. Fitted on the phone's portrait screen, where
   * the horizon sits just over the far skyline and overhead just under the status bar.
   */
  private const val ARC_WIDE = 1.27f
  private const val ARC_BACK = 1.1f
  private const val ARC_BASE = 0.33f
  private const val ARC_HIGH = 1.88f
}
