package com.skylightlim.skylineqr.city

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Something that moves through the city along a [route]: forward round a loop, or end to end and
 * back. Where it is is a pure function of time, so the renderer only ever asks "where now?".
 */
class Mover(
  val kind: Int,
  val route: Route,
  /** World units per second. */
  val speed: Float,
  /** Where along the route it starts, as a fraction of the route. */
  val phase: Float,
  val rnd: Float,
  /** Runs end to end and back, waiting [dwell] seconds at each end, instead of round a loop. */
  val shuttle: Boolean = false,
  val dwell: Float = 0f,
  /** For a shuttle: how far from each end the middle of the vehicle stops. */
  val inset: Float = 0f,
  /** Slows almost to a hover once a lap, the way a patrolling helicopter does. */
  val hovers: Boolean = false,
) {
  /** How far along the route it is at [time] seconds. */
  fun distance(time: Float): Float {
    if (shuttle) {
      val span = max(0.01f, route.length - 2f * inset)
      val travel = span / speed
      val cycle = 2f * (travel + dwell)
      val t = (((time + phase * cycle) % cycle) + cycle) % cycle
      val f =
        when {
          t < dwell -> 0f
          t < dwell + travel -> ease((t - dwell) / travel)
          t < 2f * dwell + travel -> 1f
          else -> 1f - ease((t - 2f * dwell - travel) / travel)
        }
      return inset + f * span
    }
    var t = time
    if (hovers) {
      val lap = route.length / speed
      t += lap / TWO_PI * 0.75f * StrictMath.sin((TWO_PI * time / lap).toDouble()).toFloat()
    }
    return t * speed + phase * route.length
  }

  /** Position into out[0..2] and heading into out[3] at [time] seconds. */
  fun at(time: Float, out: FloatArray) = route.at(distance(time), out)

  companion object {
    const val CAR = 0
    const val BUS = 1
    const val HELICOPTER = 2
    const val PLANE = 3
    const val MONORAIL = 4

    private const val TWO_PI = (2 * Math.PI).toFloat()

    /** Gentle start and stop: a train accelerating out of a station and braking into the next. */
    private fun ease(t: Float) = t * t * (3f - 2f * t)
  }
}

/**
 * Plans everything that moves. Cars loop through the streets keeping left, as in Malaysia, and turn
 * at junctions on rounded corners; a bus runs the ring road around the code; in Kuala Lumpur a
 * monorail follows the road round its bends. A helicopter patrols among the towers, steering clear
 * of the landmarks, and a plane circles wide above it all. All of it is seeded by the identity.
 */
internal object Traffic {

  private val DX = intArrayOf(1, 0, -1, 0)
  private val DZ = intArrayOf(0, 1, 0, -1)

  /** Cars keep this far left of the middle of the street. */
  private const val KEEP_LEFT = 0.15f

  fun plan(
    heights: FloatArray,
    n: Int,
    seed: Int,
    skyline: Float,
    towerTop: Float,
    landmarks: LandmarkPlan?,
    data: Instances,
  ): List<Mover> {
    val out = ArrayList<Mover>()
    val streets = Streets(n, heights)
    val half = n / 2f

    // The bus takes the ring road; the cars take the longest distinct loops through the streets.
    ringRoad(streets, n)?.let {
      out += Mover(Mover.BUS, route(it, n), 1.2f, hash(1, 2, seed + 41), 0.2f)
    }
    val loops = carLoops(streets, n, seed)
    val cars = ArrayList<Pair<List<Pair<Int, Int>>, Int>>()
    for (loop in loops) {
      // About one car per eighteen modules of road, at least one on every loop we keep.
      val count = max(1, loop.size / 18)
      for (k in 0 until count) cars += loop to k
      if (cars.size >= 12) break
    }
    for ((i, entry) in cars.take(12).withIndex()) {
      val (loop, k) = entry
      val count = max(1, loop.size / 18)
      val phase = (k + hash(i, loop.size, seed + 77) * 0.6f) / count
      out += Mover(Mover.CAR, route(loop, n), 1.5f + 1.0f * hash(i, 3, seed + 733), phase % 1f, hash(i, 5, seed + 811))
    }

    if (landmarks != null) monorail(heights, n, seed, data)?.let { out += it }
    out += helicopter(n, seed, skyline, towerTop, landmarks)
    out += plane(half, skyline)
    return out
  }

  // ---------------------------------------------------------------------------------------------
  // Streets
  // ---------------------------------------------------------------------------------------------

  /**
   * The road network: every light module, and a ring road one module outside the code, with dead
   * ends pruned away so a car never has to turn round.
   */
  private class Streets(val n: Int, heights: FloatArray) {
    private val m = n + 2
    private val free = BooleanArray(m * m)

    init {
      for (z in -1..n) {
        for (x in -1..n) {
          val ring = x < 0 || z < 0 || x >= n || z >= n
          free[index(x, z)] = ring || heights[z * n + x] <= 0f
        }
      }
      var pruned = true
      while (pruned) {
        pruned = false
        for (z in -1..n) {
          for (x in -1..n) {
            if (!open(x, z)) continue
            var ways = 0
            for (d in 0 until 4) if (open(x + DX[d], z + DZ[d])) ways++
            if (ways < 2) {
              free[index(x, z)] = false
              pruned = true
            }
          }
        }
      }
    }

    private fun index(x: Int, z: Int) = (z + 1) * m + (x + 1)

    fun open(x: Int, z: Int) = x >= -1 && z >= -1 && x <= n && z <= n && free[index(x, z)]

    /**
     * The loop a driver makes keeping a hand on the wall to the left: from a cell and a direction,
     * turn left if possible, else go straight, else turn right. It always comes back round, so the
     * result is a closed loop of cells.
     */
    fun followLeftWall(x0: Int, z0: Int, d0: Int): List<Pair<Int, Int>> {
      val seen = HashMap<Int, Int>()
      val cells = ArrayList<Pair<Int, Int>>()
      var x = x0
      var z = z0
      var d = d0
      while (true) {
        val state = index(x, z) * 4 + d
        val first = seen[state]
        if (first != null) return cells.subList(first, cells.size).toList()
        seen[state] = cells.size
        cells += x to z
        for (turn in intArrayOf(3, 0, 1, 2)) {
          val next = (d + turn) % 4
          if (open(x + DX[next], z + DZ[next])) {
            d = next
            break
          }
        }
        x += DX[d]
        z += DZ[d]
      }
    }
  }

  private fun ringRoad(streets: Streets, n: Int): List<Pair<Int, Int>>? =
    if (streets.open(0, -1)) streets.followLeftWall(0, -1, 0).takeIf { it.size >= 4 * n } else null

  /** Distinct loops through the city's streets, longest first, at least sixteen modules long. */
  private fun carLoops(streets: Streets, n: Int, seed: Int): List<List<Pair<Int, Int>>> {
    val starts = ArrayList<Triple<Int, Int, Int>>()
    for (z in 0 until n) {
      for (x in 0 until n) {
        if (!streets.open(x, z)) continue
        for (d in 0 until 4) {
          val left = (d + 3) % 4
          if (streets.open(x + DX[d], z + DZ[d]) && !streets.open(x + DX[left], z + DZ[left])) starts += Triple(x, z, d)
        }
      }
    }
    starts.sortBy { hash(it.first * 4 + it.third, it.second, seed + 97) }
    val loops = ArrayList<List<Pair<Int, Int>>>()
    val claimed = HashSet<Pair<Int, Int>>()
    for ((x, z, d) in starts) {
      if ((x to z) in claimed) continue
      val loop = streets.followLeftWall(x, z, d)
      claimed += loop
      // The ring road belongs to the bus; tiny loops round one block look like a car lost.
      val ring = loop.all { it.first < 0 || it.second < 0 || it.first >= n || it.second >= n }
      if (ring || loop.size < 16) continue
      loops += loop
      if (loops.size >= 10) break
    }
    return loops.sortedByDescending { it.size }
  }

  /** A loop of cells as a drivable route: kept left of the middle, corners rounded. */
  private fun route(cells: List<Pair<Int, Int>>, n: Int): Route {
    val half = n / 2f
    val count = cells.size
    val shifted = ArrayList<Pair<Float, Float>>(count)
    for (i in 0 until count) {
      val (x, z) = cells[i]
      val (px, pz) = cells[(i - 1 + count) % count]
      val (nx, nz) = cells[(i + 1) % count]
      // Left of a heading (dx, dz) is (dz, -dx). At a corner both sides' offsets add up, which is
      // exactly where the two kept-left lanes meet.
      val inX = (x - px).toFloat()
      val inZ = (z - pz).toFloat()
      val outX = (nx - x).toFloat()
      val outZ = (nz - z).toFloat()
      val straight = inX == outX && inZ == outZ
      val lx = if (straight) inZ else inZ + outZ
      val lz = if (straight) -inX else -inX - outX
      shifted += (x + 0.5f - half + lx * KEEP_LEFT) to (z + 0.5f - half + lz * KEEP_LEFT)
    }
    return Route.rounded(shifted, closed = true, radius = 0.4f, y = CityPlanner.TILE_TOP)
  }

  // ---------------------------------------------------------------------------------------------
  // Monorail
  // ---------------------------------------------------------------------------------------------

  /** A straight run of light modules. */
  private class Run(val alongX: Boolean, val fixed: Int, val from: Int, val to: Int) {
    val size get() = to - from + 1

    fun covers(x: Int, z: Int) = if (alongX) z == fixed && x in from..to else x == fixed && z in from..to
  }

  private fun runs(heights: FloatArray, n: Int): List<Run> {
    val out = ArrayList<Run>()
    for (alongX in booleanArrayOf(true, false)) {
      for (fixed in 0 until n) {
        var start = -1
        for (k in 0..n) {
          val free = k < n && (if (alongX) heights[fixed * n + k] else heights[k * n + fixed]) <= 0f
          if (free && start < 0) start = k
          if (!free && start >= 0) {
            if (k - start >= 3) out += Run(alongX, fixed, start, k - 1)
            start = -1
          }
        }
      }
    }
    return out
  }

  /**
   * The monorail follows the road: it starts on the longest street and, at each end, turns onto the
   * longest street that meets it there, up to twice, so the line bends round the city the way a
   * real elevated line follows the roads below it. Beam, pillars and two stations go into [data].
   */
  private fun monorail(heights: FloatArray, n: Int, seed: Int, data: Instances): Mover? {
    val all = runs(heights, n)
    val first = all.filter { it.size >= 5 }.maxWithOrNull(compareBy<Run> { it.size }.thenBy { hash(it.fixed, it.from, seed + 5) }) ?: return null
    val path = ArrayList<Pair<Int, Int>>()
    for (k in first.from..first.to) path += if (first.alongX) k to first.fixed else first.fixed to k

    fun extend(atEnd: Boolean) {
      repeat(2) {
        val end = if (atEnd) path.last() else path.first()
        val before = if (atEnd) path[path.size - 2] else path[1]
        val goingAlongX = end.second == before.second
        var best: List<Pair<Int, Int>> = emptyList()
        for (run in all) {
          if (run.alongX == goingAlongX || !run.covers(end.first, end.second)) continue
          val at = if (run.alongX) end.first else end.second
          for (leg in listOf((at + 1..run.to).toList(), (run.from until at).toList().reversed())) {
            val cells = leg.map { if (run.alongX) it to run.fixed else run.fixed to it }
            if (cells.size >= 3 && cells.size > best.size && cells.none { it in path }) best = cells
          }
        }
        if (best.isEmpty()) return
        if (atEnd) path.addAll(best) else path.addAll(0, best.reversed())
      }
    }
    extend(atEnd = true)
    extend(atEnd = false)

    val half = n / 2f
    val world = path.map { (x, z) -> (x + 0.5f - half) to (z + 0.5f - half) }
    val route = Route.rounded(world, closed = false, radius = 0.5f, y = KualaLumpur.RAIL_Y)
    KualaLumpur.track(data, route, world)
    return Mover(Mover.MONORAIL, route, 3.0f, 0f, 0.42f, shuttle = true, dwell = 1.8f, inset = 1.6f)
  }

  // ---------------------------------------------------------------------------------------------
  // Flights
  // ---------------------------------------------------------------------------------------------

  /**
   * The helicopter patrols an ellipse among the towers, below the landmarks' tops, on a path that
   * keeps clear of every landmark; seeded, so each site gets its own patrol. Where no such path
   * exists it flies above everything instead.
   */
  private fun helicopter(n: Int, seed: Int, skyline: Float, towerTop: Float, landmarks: LandmarkPlan?): Mover {
    val half = n / 2f
    val obstacles = ArrayList<FloatArray>() // x, z, radius, height
    if (landmarks != null) {
      landmarks.supertall?.let { obstacles += obstacle(it, half, KualaLumpur.supertallHeight(n)) }
      landmarks.twins?.let { (a, b) ->
        obstacles += obstacle(a, half, KualaLumpur.twinHeight(n))
        obstacles += obstacle(b, half, KualaLumpur.twinHeight(n))
      }
      landmarks.telecom?.let { obstacles += obstacle(it, half, KualaLumpur.telecomHeight(n)) }
    }
    val cruise = towerTop + 2.2f
    for (k in 0 until 32) {
      val cx = (hash(k, 1, seed + 301) - 0.5f) * half * 0.5f
      val cz = (hash(k, 2, seed + 301) - 0.5f) * half * 0.5f
      val a = half * (0.35f + 0.35f * hash(k, 3, seed + 301))
      val b = a * (0.55f + 0.45f * hash(k, 4, seed + 301))
      val turn = hash(k, 5, seed + 301) * 3.14159f
      val route = flight(cx, cz, a, b, turn, cruise, 0.6f, 96)
      if (clear(route, obstacles)) return Mover(Mover.HELICOPTER, route, 4.2f, hash(k, 6, seed), 0.3f, hovers = true)
    }
    return Mover(Mover.HELICOPTER, flight(0f, 0f, half * 0.6f, half * 0.45f, 0.4f, skyline + 2.5f, 0.6f, 96), 4.2f, 0.13f, 0.3f, hovers = true)
  }

  private fun obstacle(f: Footprint, half: Float, height: Float) =
    floatArrayOf(f.centerX - half, f.centerZ - half, f.size * 0.75f + 1.6f, height)

  private fun clear(route: Route, obstacles: List<FloatArray>): Boolean {
    for (i in 0 until route.count) {
      for (o in obstacles) {
        val dx = route.x(i) - o[0]
        val dz = route.z(i) - o[1]
        if (route.y(i) < o[3] + 1.2f && sqrt(dx * dx + dz * dz) < o[2]) return false
      }
    }
    return true
  }

  /** The plane circles wide and high above the whole city. */
  private fun plane(half: Float, skyline: Float): Mover =
    Mover(Mover.PLANE, flight(0f, 0f, half * 1.25f + 3f, half * 1.25f + 3f, 0f, skyline + 6f, 0.8f, 128), 7f, 0.56f, 0.66f)

  /** A closed ellipse, turned by [turn], rising and falling by [bob] twice a lap. */
  private fun flight(cx: Float, cz: Float, a: Float, b: Float, turn: Float, y: Float, bob: Float, steps: Int): Route {
    val points = FloatArray(steps * 3)
    val c = StrictMath.cos(turn.toDouble()).toFloat()
    val s = StrictMath.sin(turn.toDouble()).toFloat()
    for (i in 0 until steps) {
      val angle = 2.0 * Math.PI * i / steps
      val ex = a * StrictMath.cos(angle).toFloat()
      val ez = b * StrictMath.sin(angle).toFloat()
      points[i * 3] = cx + ex * c - ez * s
      points[i * 3 + 1] = y + bob * StrictMath.sin(2.0 * angle).toFloat()
      points[i * 3 + 2] = cz + ex * s + ez * c
    }
    return Route(points, closed = true)
  }

  /** Integer hash with wrapping overflow: identical on every device, no RNG state. */
  private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = seed * 374761393 + x * 668265263 + y * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
  }
}
