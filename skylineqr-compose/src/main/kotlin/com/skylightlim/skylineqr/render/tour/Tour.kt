package com.skylightlim.skylineqr.render.tour

import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.Mover
import com.skylightlim.skylineqr.render.Camera
import com.skylightlim.skylineqr.render.Pose
import com.skylightlim.skylineqr.render.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Flies the tour written in [TourScript] over one [city] (2026-11-09, at the user's request). To
 * change the tour, edit TourScript.kt; see README.md in this folder. This file is the machinery.
 *
 * The tour lifts off the city's own view, visits each stop of the script, and lands exactly where
 * the city's own camera is at that moment, so nothing jumps when it hands back. Everything that
 * moves is a function of time, and the renderer restarts its clock for a tour, so every run over
 * the same city is the same.
 *
 * The camera's place goes from stop to stop in cylindrical terms (bearing round the city's
 * middle, distance from it, height), so it goes round the city and never across it; what it looks
 * at goes in a straight line. Both follow a smooth spline through the stops, still at either end,
 * that never overshoots a stop. Pure Kotlin, so a JVM test can fly it.
 */
internal class Tour(private val city: City, private val aspect: Float) {

  /** A stop: when, where the camera stands (bearing, distance, height), and what it looks at, which may move. */
  private class Stop(val time: Float, var bearing: Float, val reach: Float, val height: Float, val look: (Float) -> FloatArray)

  /** Where a sight is: how tall it stands, and its foot at a moment of the tour (x, y, z). */
  private class Place(val height: Float, val foot: (Float) -> FloatArray)

  private val stops = ArrayList<Stop>()

  /** Seconds from lift-off until the camera is back at the city's own view. */
  val length: Float

  /** The sights this city's tour visits, in the order flown. */
  val sights: List<Sight>

  init {
    val start = Camera.pose(city, aspect, 0f, 0f, View())
    val startBearing = atan2(start.eye[0], start.eye[2])
    val target = Camera.target(city)
    stops += Stop(0f, startBearing, hypot(start.eye[0], start.eye[2]), start.eye[1]) { target }

    val placed = TourScript.SHOTS.mapNotNull { shot -> place(shot.sight)?.let { shot to it } }
    val ordered = if (TourScript.ROUND_THE_CITY) roundTheCity(placed, startBearing) else placed
    sights = ordered.map { it.first.sight }

    var t = 0f
    for ((i, entry) in ordered.withIndex()) {
      val (shot, place) = entry
      t += if (i == 0) TourScript.LIFT_OFF else shot.seconds
      val (bearing, reach, height) = camera(shot, place, t)
      val look: (Float) -> FloatArray =
        if (shot.sight == Sight.PLANE) {
          // Between the plane and the middle of the flag it tows, a little under them.
          { time -> FloatArray(4).also { place.foot(time).copyInto(it); it[0] -= cos(it[3]) * BEHIND; it[2] -= sin(it[3]) * BEHIND; it[1] -= 0.4f } }
        } else {
          val lift = shot.lookAt * place.height
          ({ time -> place.foot(time).also { it[1] += lift } })
        }
      stops += Stop(t, bearing, reach, height, look)
    }
    length = t + TourScript.LANDING
    val end = Camera.pose(city, aspect, 0f, length, View())
    stops += Stop(length, atan2(end.eye[0], end.eye[2]), hypot(end.eye[0], end.eye[2]), end.eye[1]) { target }
    // Each bearing as near the one before as it can be, so the camera never spins the long way.
    for (k in 1 until stops.size) {
      val before = stops[k - 1].bearing
      stops[k].bearing = before + wrap(stops[k].bearing - before + PI.toFloat()) - PI.toFloat()
    }
  }

  /**
   * Where the camera stands for [shot] of [place] at [t]: its bearing round the city's middle, its
   * distance from it and its height. The plane is seen from straight outside its circle; a sight
   * on the ground from straight out from the middle, or from the shot's side ([Shot.from]), and
   * never nearer the middle than the plate's edge plus [TourScript.OUTSIDE].
   */
  private fun camera(shot: Shot, place: Place, t: Float): Triple<Float, Float, Float> {
    val foot = place.foot(t)
    if (shot.sight == Sight.PLANE) {
      val distance = shot.distance ?: 14f
      return Triple(atan2(foot[0], foot[2]), hypot(foot[0], foot[2]) + distance, foot[1] + shot.above * distance)
    }
    val distance = shot.distance ?: (TourScript.NEAR + TourScript.PER_HEIGHT * place.height).coerceIn(TourScript.CLOSEST, TourScript.FARTHEST)
    val height = foot[1] + shot.lookAt * place.height + max(TourScript.LOWEST_ABOVE, distance * shot.above)
    val from = shot.from
    if (from == null) {
      return Triple(atan2(foot[0], foot[2]), max(hypot(foot[0], foot[2]) + distance * 0.8f, city.plateHalf + TourScript.OUTSIDE), height)
    }
    // A compass bearing on the code as a map: north is -z, east +x.
    val a = from.toDouble() * (PI / 180)
    val x = foot[0] + sin(a).toFloat() * distance
    val z = foot[2] - cos(a).toFloat() * distance
    return Triple(atan2(x, z), max(hypot(x, z), city.plateHalf + TourScript.OUTSIDE), height)
  }

  /** Where [sight] is in this city, or null if it has none. */
  private fun place(sight: Sight): Place? {
    val n = city.modules
    val half = n / 2f
    fun still(x: Float, z: Float, height: Float) = Place(height) { floatArrayOf(x, 0f, z) }
    fun moving(mover: Mover?, height: Float) = mover?.let { m -> Place(height) { time -> FloatArray(4).also { m.at(time, it) } } }
    val plan = city.landmarks
    return when (sight) {
      Sight.SUPERTALL -> plan?.supertall?.let { still(it.centerX - half, it.centerZ - half, plan.height(it, n)) }
      Sight.TELECOM_TOWER -> plan?.telecom?.let { still(it.centerX - half, it.centerZ - half, plan.height(it, n)) }
      Sight.TWIN_TOWERS -> plan?.twins?.let { (a, b) -> still((a.centerX + b.centerX) / 2f - half, (a.centerZ + b.centerZ) / 2f - half, plan.height(a, n)) }
      Sight.MONORAIL -> moving(city.movers.firstOrNull { it.kind == Mover.MONORAIL }, 1f)
      Sight.GARDEN -> still(3.5f - half, 3.5f - half, 3f)
      Sight.SQUARE -> still(3.5f - half, n - 3.5f - half, 3.5f)
      Sight.FOUNTAIN -> city.fountain?.let { still(it[0], it[2], it[1]) } ?: still(n - 3.5f - half, 3.5f - half, 3f)
      Sight.CROSSING -> city.crossing?.let { still(it[0], it[1], 2f) }
      Sight.TALLEST_TOWER -> if (plan != null) null else tallestTower()
      Sight.PLANE -> moving(city.movers.firstOrNull { it.kind == Mover.PLANE }, 1f)
    }
  }

  /** The tallest ordinary tower, for a city without the landmarks. */
  private fun tallestTower(): Place? {
    var best = -1
    for (i in 0 until city.count) {
      val o = i * CityPlanner.STRIDE
      if (city.instances[o + 6] == CityPlanner.ROLE_TOWER && (best < 0 || city.instances[o + 4] > city.instances[best * CityPlanner.STRIDE + 4])) best = i
    }
    if (best < 0) return null
    val o = best * CityPlanner.STRIDE
    val x = city.instances[o]
    val z = city.instances[o + 2]
    return Place(city.instances[o + 4]) { floatArrayOf(x, 0f, z) }
  }

  /**
   * The sights on the ground in the order the camera's places for them stand round the city, one
   * way round from [startBearing], whichever way reaches the first sooner; the plane keeps its
   * place in the list. A moving sight counts where it is half way through the tour.
   */
  private fun roundTheCity(placed: List<Pair<Shot, Place>>, startBearing: Float): List<Pair<Shot, Place>> {
    val ground = placed.filter { it.first.sight != Sight.PLANE }
    // Where the camera will stand for it, which is what has to go round.
    fun bearing(entry: Pair<Shot, Place>) = camera(entry.first, entry.second, ORDER_AT).first
    fun around(b: Float, way: Float) = wrap((b - startBearing) * way)
    val ccw = ground.minOfOrNull { around(bearing(it), 1f) } ?: 0f
    val cw = ground.minOfOrNull { around(bearing(it), -1f) } ?: 0f
    val way = if (ccw <= cw) 1f else -1f
    val sorted = ground.sortedBy { around(bearing(it), way) }.iterator()
    return placed.map { if (it.first.sight == Sight.PLANE) it else sorted.next() }
  }

  /** The camera [t] seconds into the tour. */
  fun pose(t: Float): Pose {
    val time = t.coerceIn(0f, length)
    var k = 0
    while (k < stops.size - 2 && stops[k + 1].time <= time) k++
    val a = stops[k]
    val b = stops[k + 1]
    val u = ((time - a.time) / (b.time - a.time)).coerceIn(0f, 1f)
    fun value(of: (Stop) -> Float) =
      hermite(of(a), of(b), slope(k, of) * (b.time - a.time), slope(k + 1, of) * (b.time - a.time), u)
    val bearing = value { it.bearing }
    val reach = value { it.reach }
    val height = value { it.height }
    val eye = floatArrayOf(reach * sin(bearing), height, reach * cos(bearing))
    val look = FloatArray(3) { axis -> value { it.look(time)[axis] } }
    // The lens shift eases off as the panel goes, and back as the tour lands.
    val shift = Camera.CITY_SHIFT * (1f - smooth(time / TourScript.LIFT_OFF) * smooth((length - time) / TourScript.LANDING))
    return Camera.look(eye, look, aspect, shift)
  }

  /**
   * The spline's slope at stop [k]: still at the first and last, else from its neighbours, but
   * never so steep that it overshoots a stop (Fritsch and Carlson's limit), and flat where the
   * way turns back, so the camera never swings past where it is going.
   */
  private fun slope(k: Int, of: (Stop) -> Float): Float {
    if (k <= 0 || k >= stops.size - 1) return 0f
    val before = stops[k - 1]
    val here = stops[k]
    val after = stops[k + 1]
    val inSlope = (of(here) - of(before)) / (here.time - before.time)
    val outSlope = (of(after) - of(here)) / (after.time - here.time)
    if (inSlope * outSlope <= 0f) return 0f
    val m = (of(after) - of(before)) / (after.time - before.time)
    val limit = 3f * min(abs(inSlope), abs(outSlope))
    return m.coerceIn(-limit, limit)
  }

  private fun hermite(p0: Float, p1: Float, m0: Float, m1: Float, u: Float): Float {
    val u2 = u * u
    val u3 = u2 * u
    return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1
  }

  private companion object {
    /** When a moving sight is counted for the order round the city: about half way through a tour. */
    const val ORDER_AT = 8f

    /** How far behind the plane the camera aims: half way along the flag it tows. */
    const val BEHIND = 4.8f

    val TWO_PI = (2 * PI).toFloat()

    /** An angle in 0..2 pi. */
    fun wrap(a: Float): Float = ((a % TWO_PI) + TWO_PI) % TWO_PI

    fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
  }
}
