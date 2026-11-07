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
  ) {
    System.arraycopy(pose.viewProjection, 0, out, 0, 16)
    System.arraycopy(light, 0, out, 16, 16)
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
    put(air.ink, hazeFront)
    put(materials.vehicle, wind)
    for (i in 0 until 4) put4(materials.families[i * 3], materials.families[i * 3 + 1], materials.families[i * 3 + 2], 0f)
    for (i in 0 until 32) put4(materials.landmarks[i * 3], materials.landmarks[i * 3 + 1], materials.landmarks[i * 3 + 2], 0f)
    // Each tone's tower colour and each ink, from FlatColours itself, so the screen shows exactly
    // the code its tests scan.
    for (t in 0 until FlatColours.MAX_TONES) put(FlatColours.block(materials, t))
    for (i in 0 until FlatColours.INKS) put(FlatColours.ink(i, air))
    put4(city?.plateHalf ?: 20f, city?.maxHeight ?: 4f, if (calm) 0.45f else 1f, pose.shiftY)
    put(meteor, meteor[3])
    put(damage, damage[3])
    put(bolt, bolt[3])
    System.arraycopy(screen, 0, out, o, Billboard.FLOATS)
    o += Billboard.FLOATS
    check(o == out.size) { "frame uniform packed $o of ${out.size} floats" }
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
