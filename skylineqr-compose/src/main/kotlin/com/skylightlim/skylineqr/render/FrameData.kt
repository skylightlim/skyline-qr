package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.FlatColours
import com.skylightlim.skylineqr.city.Materials
import kotlin.math.min

/**
 * What the renderer hands the GPU besides the city itself: the Frame uniform packed float by float
 * in the order of the WGSL struct, and the unit cube every block is drawn from. Pure Kotlin, so a JVM test can pack exactly the frame the renderer uploads.
 */
internal object FrameData {

  /** vec4s of the Frame uniform that hold the flat code: a version 6 code's 41 x 41 modules, four a float. */
  const val CODE_VEC4 = 106

  private var codeOf: City? = null
  private var code = FloatArray(4 * CODE_VEC4)

  /** [city]'s flat code packed for the uniform, worked out once a city. */
  private fun code(city: City?): FloatArray {
    if (city === codeOf) return code
    val packed = IntArray(4 * CODE_VEC4)
    if (city != null) {
      for (i in 0 until city.modules * city.modules) {
        if (city.qr.isDark(i % city.modules, i / city.modules)) packed[i / 4] = packed[i / 4] or ((city.inks[i] + 1) shl (i % 4 * 5))
      }
    }
    code = FloatArray(packed.size) { packed[it].toFloat() }
    codeOf = city
    return code
  }

  /** Paving: one warm stone for every city, lit by the hour. */
  private val PLAZA = floatArrayOf(0xEE / 255f, 0xE8 / 255f, 0xDC / 255f)

  /**
   * Packs the Frame uniform into [out]. [meteor], [damage] and [bolt] are the strike fields as the
   * struct describes them; [screen] is the billboard's, from [Billboard.state]. [raising] is
   * [Morph.raising]: the fold is on its way from the code back to the city. [hazeFront] is how far
   * haze has rolled across the plate as it comes, 0 to 1; [wind] is how strongly haze's wind
   * streaks show.
   */
  fun pack(
    out: FloatArray,
    pose: Pose,
    light: FloatArray,
    fold: Float,
    city: City?,
    air: Atmosphere,
    seconds: Float,
    flash: Float,
    calm: Boolean,
    raising: Boolean,
    hazeFront: Float,
    wind: Float,
    meteor: FloatArray,
    damage: FloatArray,
    bolt: FloatArray,
    screen: FloatArray,
    /** The night sky's: see [stars]. */
    stars: FloatArray = floatArrayOf(0f, 0f, 1f, 0f),
  ) {
    pose.viewProjection.copyInto(out, 0, 0, 16)
    light.copyInto(out, 16, 0, 16)
    var o = 32
    fun put4(x: Float, y: Float, z: Float, w: Float) {
      out[o] = x
      out[o + 1] = y
      out[o + 2] = z
      out[o + 3] = w
      o += 4
    }
    fun put(v: FloatArray, w: Float = 0f) = put4(v[0], v[1], v[2], w)

    val materials = city?.materials ?: Materials.recipes.first()
    val sunLuma = 0.2126f * air.sunColor[0] + 0.7152f * air.sunColor[1] + 0.0722f * air.sunColor[2]
    // Strong sun throws dark shadows; overcast, haze and moonlight only soft ones.
    val shadowStrength = (0.35f + 0.6f * min(1f, sunLuma / 0.9f)) * (1f - 0.5f * air.night)

    put(pose.eye, fold)
    put(pose.right, pose.tanHalfFov)
    put(pose.up, pose.aspect)
    put(pose.fwd, seconds)
    put(air.sunDir, air.night)
    put(air.sunColor, shadowStrength)
    put(air.skyTop, air.rain)
    put(air.skyHorizon, air.haze)
    put(PLAZA, air.wet)
    put(air.window, flash)
    put(air.paper, if (raising) 1f else 0f)
    put4((city?.modules ?: 0).toFloat(), 0f, 0f, hazeFront)
    put(materials.vehicle, wind)
    for (i in 0 until 32) put4(materials.landmarks[i * 3], materials.landmarks[i * 3 + 1], materials.landmarks[i * 3 + 2], 0f)
    // Each ink from FlatColours itself, so the screen shows exactly the code its tests scan.
    for (i in 0 until FlatColours.INKS) put(FlatColours.ink(i))
    put4(city?.plateHalf ?: 20f, city?.maxHeight ?: 4f, if (calm) 0.45f else 1f, pose.shiftY)
    put(meteor, meteor[3])
    put(damage, damage[3])
    put(bolt, bolt[3])
    put(stars, stars[3])
    screen.copyInto(out, o, 0, Billboard.FLOATS)
    o += Billboard.FLOATS
    // The flat code for the landmarks to turn into: four modules a float, five bits each, which a
    // float holds exactly.
    code(city).copyInto(out, o, 0, 4 * CODE_VEC4)
    o += 4 * CODE_VEC4
    check(o == out.size) { "frame uniform packed $o of ${out.size} floats" }
  }

  /**
   * The night sky's field of the Frame uniform (2026-11-09): [city]'s own star seed, how many of
   * the stars show through [air] (none in rain or a storm, few in haze; the shader adds the dark),
   * and the screen's [height] in pixels, so stars and shooting stars stay a pixel or two wide.
   */
  fun stars(city: City?, air: Atmosphere, height: Int): FloatArray {
    val seed = ((city?.identity?.seed ?: 0) and 0xffff) / 65536f
    return floatArrayOf(seed, (1f - air.rain) * (1f - 0.85f * air.haze), height.toFloat(), 0f)
  }

  /** The unit cube: position and normal per vertex, four vertices a face so normals stay flat. */
  val CUBE_MESH =
    floatArrayOf(
      0.5f, -0.5f, 0.5f, 1f, 0f, 0f,
      0.5f, -0.5f, -0.5f, 1f, 0f, 0f,
      0.5f, 0.5f, -0.5f, 1f, 0f, 0f,
      0.5f, 0.5f, 0.5f, 1f, 0f, 0f,
      -0.5f, -0.5f, -0.5f, -1f, 0f, 0f,
      -0.5f, -0.5f, 0.5f, -1f, 0f, 0f,
      -0.5f, 0.5f, 0.5f, -1f, 0f, 0f,
      -0.5f, 0.5f, -0.5f, -1f, 0f, 0f,
      -0.5f, 0.5f, 0.5f, 0f, 1f, 0f,
      0.5f, 0.5f, 0.5f, 0f, 1f, 0f,
      0.5f, 0.5f, -0.5f, 0f, 1f, 0f,
      -0.5f, 0.5f, -0.5f, 0f, 1f, 0f,
      -0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
      0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
      0.5f, -0.5f, 0.5f, 0f, -1f, 0f,
      -0.5f, -0.5f, 0.5f, 0f, -1f, 0f,
      -0.5f, -0.5f, 0.5f, 0f, 0f, 1f,
      0.5f, -0.5f, 0.5f, 0f, 0f, 1f,
      0.5f, 0.5f, 0.5f, 0f, 0f, 1f,
      -0.5f, 0.5f, 0.5f, 0f, 0f, 1f,
      0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
      -0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
      -0.5f, 0.5f, -0.5f, 0f, 0f, -1f,
      0.5f, 0.5f, -0.5f, 0f, 0f, -1f,
    )

  val CUBE_INDICES =
    shortArrayOf(
      0, 1, 2, 0, 2, 3,
      4, 5, 6, 4, 6, 7,
      8, 9, 10, 8, 10, 11,
      12, 13, 14, 12, 14, 15,
      16, 17, 18, 16, 18, 19,
      20, 21, 22, 20, 22, 23,
    )
}
