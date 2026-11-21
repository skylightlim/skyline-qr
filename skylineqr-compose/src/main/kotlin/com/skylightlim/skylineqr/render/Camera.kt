package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where the person has turned and zoomed the camera, on top of the city's own pose. */
internal data class View(val yaw: Float = 0f, val elevation: Float = 0f, val zoom: Float = 1f) {
  companion object {
    const val MIN_ELEVATION = -0.30f
    const val MAX_ELEVATION = 0.55f
    const val MIN_ZOOM = 0.6f
    const val MAX_ZOOM = 1.8f
    /** Half the arc the city can be turned through. */
    const val MAX_YAW = 1.25f
  }
}

internal class Pose(
  val eye: FloatArray,
  val fwd: FloatArray,
  val right: FloatArray,
  val up: FloatArray,
  val tanHalfFov: Float,
  val aspect: Float,
  /** The lens shift, in normalized device units. */
  val shiftY: Float,
  val viewProjection: FloatArray,
) {
  /** The way from the eye through a point on screen, [u] and [v] from the top left, 0..1; not unit length. */
  fun ray(u: Float, v: Float): FloatArray {
    val x = (u * 2f - 1f) * tanHalfFov * aspect
    val y = (1f - v * 2f - shiftY) * tanHalfFov
    return FloatArray(3) { fwd[it] + right[it] * x + up[it] * y }
  }

  /**
   * Where the ray through a point on screen ([u], [v] from the top left, 0..1) meets the ground,
   * or null if it points at the sky.
   */
  fun ground(u: Float, v: Float): FloatArray? {
    val dir = ray(u, v)
    if (dir[1] >= -1e-4f) return null
    val t = -eye[1] / dir[1]
    return floatArrayOf(eye[0] + dir[0] * t, 0f, eye[2] + dir[2] * t)
  }
}

/**
 * The camera orbits the plate from an isometric angle and then drops to straight down for the
 * code. The flat pose never quite reaches 90 degrees, so the view basis stays well defined.
 */
internal object Camera {

  private const val BASE_YAW = CityPlanner.VIEW_YAW
  private const val BASE_ELEVATION = CityPlanner.VIEW_ELEVATION
  private const val FLAT_ELEVATION = 1.558f
  private const val BASE_FOV = 45f
  private const val FLAT_FOV = 12f
  /** The isometric diamond is wider than the plate side, so the city pose needs extra room. */
  private const val CITY_HALF_FIT = 1.34f
  private const val FLAT_HALF_FIT = 1.10f
  /** Flat pose distance multiplier: fills the portrait width with the plate, quiet zone included. */
  private const val FLAT_DISTANCE = 1.02f

  private val UP = floatArrayOf(0f, 1f, 0f)

  /**
   * The city's pose, turned by [view]. The person's orbit and zoom fade out as the city flattens,
   * so the code always lands square to the screen and filling it.
   */
  fun pose(city: City?, aspect: Float, eased: Float, time: Float, view: View = View()): Pose {
    val half = (city?.plateHalf ?: 20f) * (FLAT_HALF_FIT + (CITY_HALF_FIT - FLAT_HALF_FIT) * (1f - eased))
    val yaw = (BASE_YAW + view.yaw + 0.06f * sin(time * 0.25f)) * (1f - eased)
    val elevation = BASE_ELEVATION + view.elevation + (FLAT_ELEVATION - BASE_ELEVATION - view.elevation) * eased
    val fov = BASE_FOV + (FLAT_FOV - BASE_FOV) * eased
    val tanHalf = tan(fov / 2.0 * (kotlin.math.PI / 180)).toFloat()
    val fit = max(half / tanHalf, half / (tanHalf * aspect))
    val zoom = view.zoom + (1f - view.zoom) * eased
    val distance = fit * (1.18f + (FLAT_DISTANCE - 1.18f) * eased) / zoom
    val targetY = (city?.maxHeight ?: 4f) * 0.40f * (1f - eased)
    // Lift the composition clear of the controls underneath it.
    val shiftY = CITY_SHIFT - 0.12f * eased
    val eye =
      floatArrayOf(
        distance * cos(elevation) * sin(yaw),
        targetY + distance * sin(elevation),
        distance * cos(elevation) * cos(yaw),
      )
    val fwd = normalize(floatArrayOf(-eye[0], targetY - eye[1], -eye[2]))
    val right = normalize(cross(fwd, UP))
    val up = cross(right, fwd)
    val look = Mat4.lookAt(eye[0], eye[1], eye[2], 0f, targetY, 0f, 0f, 1f, 0f)
    val proj = Mat4.perspective(fov, aspect, distance * 0.25f, distance * 3.5f, shiftY)
    return Pose(eye, fwd, right, up, tanHalf, aspect, shiftY, Mat4.multiply(proj, look))
  }

  /** Where the city's own camera looks: over the middle of the plate, two fifths of the skyline up. */
  fun target(city: City?) = floatArrayOf(0f, (city?.maxHeight ?: 4f) * 0.40f, 0f)

  /** The camera's lens shift in the city, which lifts it clear of the controls underneath. */
  const val CITY_SHIFT = 0.22f

  /**
   * A camera at [eye] looking at [target], for the tour (2026-11-09): the city's own field of view,
   * lens shift [shiftY] and a depth range wide enough for anything round the plate.
   */
  fun look(eye: FloatArray, target: FloatArray, aspect: Float, shiftY: Float): Pose {
    val fwd = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
    val right = normalize(cross(fwd, UP))
    val up = cross(right, fwd)
    val tanHalf = tan(BASE_FOV / 2.0 * (kotlin.math.PI / 180)).toFloat()
    val look = Mat4.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 1f, 0f)
    val proj = Mat4.perspective(BASE_FOV, aspect, 0.4f, 260f, shiftY)
    return Pose(eye.copyOf(), fwd, right, up, tanHalf, aspect, shiftY, Mat4.multiply(proj, look))
  }

  /**
   * The sun's view for the shadow map: an orthographic box looking along the light, wide enough
   * for the plate and the shadows its tallest towers throw across it.
   */
  fun light(city: City?, sunDir: FloatArray): FloatArray {
    val plate = city?.plateHalf ?: 20f
    val tallest = city?.maxHeight ?: 4f
    val reach = plate * 1.45f + tallest * 0.5f
    val distance = plate * 3f + tallest * 2f
    val target = floatArrayOf(0f, tallest * 0.25f, 0f)
    val eye = floatArrayOf(target[0] + sunDir[0] * distance, target[1] + sunDir[1] * distance, target[2] + sunDir[2] * distance)
    // Looking straight down needs a different "up", or the view basis collapses.
    val up = if (kotlin.math.abs(sunDir[1]) > 0.98f) floatArrayOf(0f, 0f, 1f) else UP
    val view = Mat4.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])
    val proj = Mat4.orthographic(reach, reach, distance - reach - tallest, distance + reach + tallest)
    return Mat4.multiply(proj, view)
  }
}

internal fun cross(a: FloatArray, b: FloatArray) =
  floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

internal fun normalize(v: FloatArray): FloatArray {
  val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
  if (len < 1e-6f) return floatArrayOf(0f, 1f, 0f)
  return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
}
