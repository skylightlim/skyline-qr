package com.skylightlim.skylineqr.city

/**
 * The city as the camera sees it from where it usually stands, [CityPlanner.VIEW_YAW] round and
 * [CityPlanner.VIEW_ELEVATION] up: where a point falls across the screen, how near the camera it
 * is, and whether anything taller stands between it and the camera. In world units with the
 * code's middle at 0, x to the right of the code and z down it. The camera stands far enough off
 * to treat its rays as parallel here.
 */
internal object Sight {

  private val SIN = StrictMath.sin(CityPlanner.VIEW_YAW.toDouble()).toFloat()
  private val COS = StrictMath.cos(CityPlanner.VIEW_YAW.toDouble()).toFloat()

  /**
   * How much a ray toward the camera climbs for each unit it crosses the ground: a little less
   * than the camera's own angle, as the rays to the far side of the city do, the camera being
   * further from there.
   */
  private val RISE = 0.92f * StrictMath.tan(CityPlanner.VIEW_ELEVATION.toDouble()).toFloat()

  private const val STEP = 0.2f

  /** How far right on the screen: the camera's right is (cos, 0, -sin) of its bearing. */
  fun across(x: Float, z: Float) = x * COS - z * SIN

  /** How near the camera: it stands out along (sin, cos) of its bearing. */
  fun toward(x: Float, z: Float) = x * SIN + z * COS

  /**
   * Whether the camera sees the point ([x], [y], [z]): nothing [height] stands up over the ground
   * reaches the ray from it, which is clear once it climbs past [top], the tallest thing there.
   */
  fun clear(x: Float, y: Float, z: Float, top: Float, height: (Float, Float) -> Float): Boolean {
    var t = STEP
    while (y + t * RISE < top) {
      if (height(x + SIN * t, z + COS * t) > y + t * RISE) return false
      t += STEP
    }
    return true
  }
}
