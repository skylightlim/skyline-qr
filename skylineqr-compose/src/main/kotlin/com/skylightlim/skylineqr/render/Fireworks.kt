package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.Vehicles
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fireworks over the city (2026-11-09, at the user's request), rebuilt as boxes every frame with
 * the traffic. A shell climbs from where it was let off, slowing as it goes, and bursts into a
 * ball of sparks that spread, slow in the air, sag and fade, crackling at the end. Each spark draws
 * itself and a dimmer one a moment behind, so it streaks. Colours are festive, gold the most
 * common (see the shader's sparkColour). Where everything is is a function of the shells' launch
 * times alone, like the meteor, so a dropped frame changes nothing.
 *
 * Pure Kotlin, so a JVM test can write exactly the boxes the renderer draws.
 */
internal class Fireworks {

  private class Shell(
    val launch: Float,
    val from: FloatArray,
    val burst: FloatArray,
    val colour: Float,
    /** Every other spark's colour, or the same. */
    val second: Float,
    val seed: Int,
    /** 1 for an ordinary shell; more for a finale's. */
    val size: Float,
  )

  private val shells = ArrayList<Shell>()

  /** Whether any shell is still climbing or burning. */
  val busy: Boolean
    get() = shells.isNotEmpty()

  fun clear() = shells.clear()

  /**
   * One shell let off at [at] seconds from [from] (x, y, z) to burst at [burst], its colours from
   * [seed]. The oldest goes if too many are up.
   */
  fun launch(at: Float, from: FloatArray, burst: FloatArray, seed: Int, size: Float = 1f) {
    if (shells.size >= MAX_SHELLS) shells.removeAt(0)
    val colour = colourOf(seed)
    val second = if (unit(seed * 5 + 3) < 0.35f) colourOf(seed * 7 + 1) else colour
    shells += Shell(at, from.copyOf(), burst.copyOf(), colour, second, seed, size)
  }

  /**
   * A long press's volley at [now]: three shells, a third of a second apart, from the ground at
   * ([x], [z]) to burst round ([bx], [by], [bz]), each a little to one side of the last.
   */
  fun volley(now: Float, x: Float, z: Float, bx: Float, by: Float, bz: Float, seed: Int) {
    for (k in 0 until 3) {
      val s = seed * 3 + k
      val dx = (unit(s * 11 + 1) - 0.5f) * 2.4f
      val dz = (unit(s * 11 + 2) - 0.5f) * 2.4f
      val dy = (unit(s * 11 + 3) - 0.5f) * 3f
      launch(now + k * 0.35f, floatArrayOf(x + dx * 0.3f, 0.1f, z + dz * 0.3f), floatArrayOf(bx + dx, by + dy, bz + dz), s)
    }
  }

  /** Every shell as it is at [t] seconds; those that have burnt out are forgotten. */
  fun emit(sink: Vehicles.Sink, t: Float) {
    shells.removeAll { t - it.launch > RISE + LIFE * it.size + 0.2f }
    for (shell in shells) {
      val age = t - shell.launch
      if (age < 0f) continue
      if (age < RISE) climb(sink, shell, age) else burst(sink, shell, age - RISE)
    }
  }

  /** The shell on its way up: a bright head and a short tail of embers below it. */
  private fun climb(sink: Vehicles.Sink, s: Shell, age: Float) {
    for (k in 0 until 5) {
      val u = max(0f, age - k * 0.035f) / RISE
      val e = 1f - (1f - u) * (1f - u)
      val x = s.from[0] + (s.burst[0] - s.from[0]) * e
      val y = s.from[1] + (s.burst[1] - s.from[1]) * e
      val z = s.from[2] + (s.burst[2] - s.from[2]) * e
      val size = 0.16f * (1f - k * 0.15f)
      sink.box(x, y, z, size, size, size, CityPlanner.ROLE_SPARK, 0.9f - k * 0.16f, GOLD, k * 0.7f, 1f)
    }
  }

  /** The ball of sparks, [age] seconds after the burst, and its flash. */
  private fun burst(sink: Vehicles.Sink, s: Shell, age: Float) {
    val life = LIFE * s.size
    if (age < 0.15f) {
      val flash = (1f - age / 0.15f) * 1.1f * s.size
      sink.box(s.burst[0], s.burst[1], s.burst[2], flash, flash, flash, CityPlanner.ROLE_SPARK, 1f, s.colour, 0f, 1f)
    }
    for (i in 0 until SPARKS) {
      // Spread evenly over a sphere (a Fibonacci lattice), turned its own way per shell.
      val y = 1f - 2f * (i + 0.5f) / SPARKS
      val r = sqrt(max(0f, 1f - y * y))
      val a = i * 2.3999632f + unit(s.seed * 13 + 7) * 6.2832f
      val speed = SPEED * s.size * (0.85f + 0.3f * unit(s.seed * 97 + i))
      val colour = if (i % 2 == 0) s.colour else s.second
      for (behind in 0..1) {
        val t = age - behind * 0.07f
        if (t < 0f) continue
        val out = speed * (1f - exp(-DRAG * t)) / DRAG
        val px = s.burst[0] + r * cos(a) * out
        val py = s.burst[1] + y * out - 0.5f * SAG * t * t
        val pz = s.burst[2] + r * sin(a) * out
        val left = 1f - t / life
        if (left <= 0f) continue
        var glow = left * sqrt(left) * (if (behind == 0) 1f else 0.45f)
        // It crackles as it burns out.
        if (t > life * 0.6f && unit(s.seed * 31 + i * 7 + (t * 20f).toInt()) < 0.4f) glow *= 0.3f
        val size = 0.15f * (0.5f + 0.5f * left) * (if (behind == 0) 1f else 0.7f)
        sink.box(px, py, pz, size, size, size, CityPlanner.ROLE_SPARK, glow, colour, a, 1f)
      }
    }
  }

  companion object {
    /** How long a shell climbs, and how long its sparks burn. */
    const val RISE = 0.9f
    const val LIFE = 1.7f

    private const val SPARKS = 40
    private const val SPEED = 5.5f
    private const val DRAG = 1.6f
    private const val SAG = 2.5f
    private const val MAX_SHELLS = 12

    /** The climbing shell's gold, in the shader's sparkColour. */
    private const val GOLD = 0.3f

    /** A colour for [seed], in the shader's sparkColour: gold half the time, else red, white, teal or magenta. */
    fun colourOf(seed: Int): Float {
      val u = unit(seed * 17 + 5)
      return when {
        u < 0.5f -> 0.3f
        u < 0.62f -> 0.5f
        u < 0.74f -> 0.64f
        u < 0.87f -> 0.77f
        else -> 0.9f
      }
    }

    /** 0..1 from an integer: lowbias32 (Chris Wellons, public domain), as in the shaders. */
    fun unit(n: Int): Float {
      var h = n * -0x61c88647
      h = h xor (h ushr 16)
      h *= 0x7feb352d
      h = h xor (h ushr 15)
      h *= -0x7b935975
      h = h xor (h ushr 16)
      return (h ushr 8) / 16777216f
    }
  }
}

/**
 * The mall fountain's water (2026-11-09, at the user's request: larger and more visible): a jet
 * out of the top of its stem that rises and falls back into the top bowl, and a curtain of drops
 * falling from the rim of each bowl to the next, the lowest into the pool. All a function of time.
 */
internal object Fountain {

  /** The bowls' rims, from the top of the stem down: how far out and how far below it, and where the water lands. */
  private val RIMS = floatArrayOf(0.42f, 0.24f, 0.6f, 0.8f, 0.8f, 1.48f)
  private val LANDS = floatArrayOf(0.8f, 1.48f, 2.3f)

  fun emit(sink: Vehicles.Sink, at: FloatArray, t: Float) {
    val x = at[0]
    val top = at[1]
    val z = at[2]
    val jet = 0.9f + 0.08f * sin(t * 7f) + 0.05f * sin(t * 11.3f)
    sink.box(x, top + jet / 2f, z, 0.07f, jet, 0.07f, CityPlanner.ROLE_SPARK, 0.6f, WATER, 0f, 0.7f)
    // The jet's drops arc out and back down into the top bowl.
    for (i in 0 until 16) {
      val life = 0.9f
      val u = ((t / life + i / 16f) % 1f) * life
      val a = i * 2.3999632f
      val up = 2.86f * u - 4.1f * u * u
      val out = 0.46f * u / life
      sink.box(x + cos(a) * out, top + jet * 0.7f + up, z + sin(a) * out, 0.06f, 0.09f, 0.06f, CityPlanner.ROLE_SPARK, 0.5f, WATER, a, 1f)
    }
    // Curtains over each rim.
    for (b in 0 until 3) {
      val r = RIMS[b * 2]
      val from = top - RIMS[b * 2 + 1]
      val drop = LANDS[b] - RIMS[b * 2 + 1]
      val fall = sqrt(2f * drop / G)
      for (i in 0 until 12) {
        val u = ((t / fall + i * 0.37f) % 1f) * fall
        val a = i * 0.5235988f + b * 0.26f
        val out = r + 0.12f * u / fall
        val y = from - 0.5f * G * u * u
        sink.box(x + cos(a) * out, y, z + sin(a) * out, 0.05f, 0.12f, 0.05f, CityPlanner.ROLE_SPARK, 0.4f, WATER, a, 1f)
      }
    }
  }

  /** The water's colour in the shader's sparkColour. */
  private const val WATER = 0.05f
  private const val G = 6f
}
