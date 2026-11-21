package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.FdLibm
import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.floor
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
  /** For a car: three floats per stop line, where along the loop it halts, its junction and axis. */
  val stops: FloatArray = FloatArray(0),
  /** The lights the car stops for. */
  val signals: Signals = Signals.NONE,
  /** For the plane: it tows Kuala Lumpur's flag (2026-11-09, at the user's request). */
  val banner: Boolean = false,
  /**
   * For a shuttle with stations on the way (2026-11-12): where along the route its middle stops,
   * in order, the two ends included. It waits [dwell] at each, both ways.
   */
  val stations: FloatArray = FloatArray(0),
) {
  private val schedule = if (stops.isNotEmpty()) Schedule(route, speed, phase * route.length, stops, signals) else null

  /** How far along the route it is at [time] seconds. */
  fun distance(time: Float): Float {
    schedule?.let { return it.distance(time) }
    if (stations.size >= 2) {
      // There and back: at each station a wait, then an eased run to the next.
      val legs = stations.size - 1
      fun travel(leg: Int) = max(1.2f, (stations[leg + 1] - stations[leg]) / speed)
      var there = 0f
      for (leg in 0 until legs) there += dwell + travel(leg)
      val cycle = 2f * there
      var t = (((time + phase * cycle) % cycle) + cycle) % cycle
      val back = t >= there
      if (back) t -= there
      for (k in 0 until legs) {
        // Coming back, the legs run in the other order and the other way.
        val leg = if (back) legs - 1 - k else k
        val from = if (back) stations[leg + 1] else stations[leg]
        val to = if (back) stations[leg] else stations[leg + 1]
        if (t < dwell) return from
        t -= dwell
        val run = travel(leg)
        if (t < run) return from + (to - from) * ease(t / run)
        t -= run
      }
      return if (back) stations[0] else stations[legs]
    }
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
      t += lap / TWO_PI * 0.75f * FdLibm.sin((TWO_PI * time / lap).toDouble()).toFloat()
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

    private const val TWO_PI = (2 * kotlin.math.PI).toFloat()

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
    matrix: QrMatrix,
    heights: FloatArray,
    n: Int,
    seed: Int,
    skyline: Float,
    towerTop: Float,
    landmarks: LandmarkPlan?,
    data: Instances,
    /** Dark modules paved over as road all the same: the crossing on the alignment pattern. */
    paved: Set<Int> = emptySet(),
    /** The crossing's middle module, row by row, or -1 for none. */
    crossing: Int = -1,
  ): Plan {
    val out = ArrayList<Mover>()
    val streets = Streets(matrix, heights, paved)
    val half = n / 2f

    // The bus takes the ring road; the cars take the longest distinct loops through the streets.
    val ring = ringRoad(streets, n)
    val loops = carLoops(streets, n, seed)
    val cars = ArrayList<Pair<List<Pair<Int, Int>>, Int>>()
    for (loop in loops) {
      // About one car per eighteen modules of road, at least one on every loop we keep.
      val count = max(1, loop.size / 18)
      for (k in 0 until count) cars += loop to k
      if (cars.size >= 12) break
    }
    val signals = signals(streets, cars.take(12).map { it.first }.distinct(), ring, n, seed, crossing)
    // Four buses share the ring road (2026-11-12, at the user's request: more buses), a quarter
    // of a lap apart, each in a paint of its own.
    ring?.let {
      val route = route(it, n)
      val stops = stopLines(route, signals, n, BUS_SHORT)
      for ((k, paint) in BUS_PAINTS.withIndex()) {
        out += Mover(Mover.BUS, route, 1.2f, (hash(1, 2, seed + 41) + k / BUS_PAINTS.size.toFloat()) % 1f, paint, stops = stops, signals = signals)
      }
    }
    for ((i, entry) in cars.take(12).withIndex()) {
      val (loop, k) = entry
      val count = max(1, loop.size / 18)
      val phase = (k + hash(i, loop.size, seed + 77) * 0.6f) / count
      val route = route(loop, n)
      out += Mover(
        Mover.CAR, route, 1.5f + 1.0f * hash(i, 3, seed + 733), phase % 1f, hash(i, 5, seed + 811),
        stops = stopLines(route, signals, n), signals = signals,
      )
    }

    if (landmarks != null) monorail(heights, n, seed, landmarks, data, crossing)?.let { out += it }
    out += helicopter(n, seed, skyline, towerTop, landmarks)
    out += plane(half, skyline, banner = landmarks != null)
    return Plan(out, signals)
  }

  /** The buses' paints, as the shader's vehiclePaint reads a random slot: orange, blue, green and red. */
  private val BUS_PAINTS = floatArrayOf(0.2f, 0.55f, 0.68f, 0.05f)

  /** Everything that moves, and the lights the cars stop for. */
  class Plan(val movers: List<Mover>, val signals: Signals)

  // ---------------------------------------------------------------------------------------------
  // Traffic lights
  // ---------------------------------------------------------------------------------------------

  /** Junctions with lights stand at least this many modules apart. */
  private const val SIGNAL_SPACING = 4f

  /** How far short of the junction a car's middle halts: half a car and a little. */
  private const val STOP_SHORT = 0.32f

  /** The same for the bus, which is longer. */
  private const val BUS_SHORT = 0.75f

  /**
   * Traffic lights at junctions the cars drive through. First Kuala Lumpur's crossing on the
   * alignment pattern, if [crossing] is its middle module: its three by three square and the four
   * arms through the pattern's ring, with a light at the mouth of every arm, so they change even
   * where no car comes. Then crossings (cars arriving along both axes), then the rest in seeded
   * order, spread out and clear of the corners. Then, on the ring road's two edges nearest the
   * camera (the bottom and the right), up to [Signals.MAX_EDGE] each where a street meets it, for
   * the bus and the cars that come out there. A signal stands on the left kerb of each approach a
   * car uses, at the near corner where the arriving driver sees it; it is drawn with the traffic
   * ([Vehicles.signals]), as its lamp changes.
   */
  private fun signals(streets: Streets, loops: List<List<Pair<Int, Int>>>, ring: List<Pair<Int, Int>>?, n: Int, seed: Int, crossing: Int): Signals {
    val half = n / 2f
    val heads = ArrayList<Float>()
    val chosen = ArrayList<Int>()
    val areas = ArrayList<Int>()
    fun pole(col: Int, row: Int, d: Int, junction: Int) {
      val cx = col + 0.5f - half
      val cz = row + 0.5f - half
      val dx = DX[d].toFloat()
      val dz = DZ[d].toFloat()
      // Left of a heading (dx, dz) is (dz, -dx): the module's near corner on the driver's left.
      heads += listOf(cx - dx * 0.44f + dz * 0.44f, cz - dz * 0.44f - dx * 0.44f, dx, dz, junction.toFloat(), if (dx != 0f) 0f else 1f)
    }
    fun key(col: Int, row: Int) = (row + 1) * (n + 2) + (col + 1)

    if (crossing >= 0) {
      val cc = crossing % n
      val cr = crossing / n
      chosen += crossing
      for (dr in -2..2) for (dc in -2..2) {
        val square = kotlin.math.abs(dc) <= 1 && kotlin.math.abs(dr) <= 1
        val arm = (dc == 0) != (dr == 0) && (kotlin.math.abs(dc) == 2 || kotlin.math.abs(dr) == 2)
        if ((square || arm) && !(dc == 0 && dr == 0)) areas += listOf(cc + dc, cr + dr, 0)
      }
      // Into the square along each arm: its light at the arm's outer mouth.
      for (d in 0 until 4) pole(cc - 2 * DX[d], cr - 2 * DZ[d], d, 0)
    }

    val approaches = HashMap<Int, MutableSet<Int>>()
    for (loop in loops) for (i in loop.indices) {
      val (x, z) = loop[i]
      if (x !in 0 until n || z !in 0 until n || LandmarkPlan.inCorner(x, z, n)) continue
      var ways = 0
      for (d in 0 until 4) if (streets.open(x + DX[d], z + DZ[d])) ways++
      if (ways < 3) continue
      val (px, pz) = loop[(i - 1 + loop.size) % loop.size]
      val d = (0 until 4).firstOrNull { DX[it] == x - px && DZ[it] == z - pz } ?: continue
      approaches.getOrPut(z * n + x) { HashSet() } += d
    }
    fun axes(cell: Int) = approaches.getValue(cell).map { it % 2 }.distinct().size
    fun far(col: Int, row: Int) = chosen.all { other ->
      val dx = (col - other % n).toFloat()
      val dz = (row - other / n).toFloat()
      sqrt(dx * dx + dz * dz) >= SIGNAL_SPACING
    }
    for (cell in approaches.keys.sortedWith(compareBy({ -axes(it) }, { hash(it % n, it / n, seed + 907) }))) {
      if (chosen.size == Signals.MAX_JUNCTIONS) break
      if (far(cell % n, cell / n)) {
        for (d in approaches.getValue(cell).sorted()) pole(cell % n, cell / n, d, chosen.size)
        chosen += cell
      }
    }
    val cols = ArrayList<Int>()
    for (cell in chosen) {
      cols += cell % n
      cols += cell / n
    }

    // The ring road's edges nearest the camera: a light where a street comes out onto it.
    if (ring != null) {
      val edge = HashMap<Int, MutableSet<Int>>()
      for (loop in loops + listOf(ring)) for (i in loop.indices) {
        val (x, z) = loop[i]
        val bottom = z == n && x in 0 until n && streets.open(x, n - 1)
        val right = x == n && z in 0 until n && streets.open(n - 1, z)
        if (!bottom && !right) continue
        val (px, pz) = loop[(i - 1 + loop.size) % loop.size]
        val d = (0 until 4).firstOrNull { DX[it] == x - px && DZ[it] == z - pz } ?: continue
        edge.getOrPut(key(x, z)) { HashSet() } += d
      }
      for (side in 0..1) {
        var placed = 0
        val candidates = edge.keys.filter { (it / (n + 2) - 1 == n) == (side == 0) }
        for (k in candidates.sortedWith(compareBy({ -edge.getValue(it).size }, { hash(it, side, seed + 929) }))) {
          if (placed == Signals.MAX_EDGE) break
          val col = k % (n + 2) - 1
          val row = k / (n + 2) - 1
          // Spread out, and out of the way of the corners' buildings.
          val near = (0 until cols.size / 2).any { hypot((col - cols[it * 2]).toFloat(), (row - cols[it * 2 + 1]).toFloat()) < SIGNAL_SPACING }
          if (near || col >= n - 2 && row >= n - 2) continue
          val j = cols.size / 2
          for (d in edge.getValue(k).sorted()) pole(col, row, d, j)
          cols += col
          cols += row
          placed++
        }
      }
    }

    val count = cols.size / 2
    val offsets = FloatArray(count) { hash(cols[it * 2], cols[it * 2 + 1], seed + 919) * Signals.CYCLE }
    return Signals(heads.toFloatArray(), offsets, cols.toIntArray(), areas.toIntArray(), if (crossing >= 0) 0 else -1)
  }

  private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y)

  /**
   * Where along a car's [route] it halts for each light it meets, three floats a line: found by
   * walking the loop and noting where it crosses into a junction's module from the one before.
   */
  private fun stopLines(route: Route, signals: Signals, n: Int, short: Float = STOP_SHORT): FloatArray {
    if (signals.count == 0) return FloatArray(0)
    val junction = HashMap<Int, Int>()
    for (j in 0 until signals.cells.size / 2) junction[signals.cells[j * 2 + 1] * (n + 2) + signals.cells[j * 2]] = j
    for (k in 0 until signals.areas.size / 3) junction[signals.areas[k * 3 + 1] * (n + 2) + signals.areas[k * 3]] = signals.areas[k * 3 + 2]
    val half = n / 2f
    val point = FloatArray(4)
    fun cellAt(s: Float): Int {
      route.at(s, point)
      return kotlin.math.floor(point[2] + half).toInt() * (n + 2) + kotlin.math.floor(point[0] + half).toInt()
    }
    val out = ArrayList<Float>()
    val step = 0.02f
    var before = cellAt(0f)
    var s = step
    while (s <= route.length + step) {
      val cell = cellAt(s)
      if (cell != before) {
        val j = junction[cell]
        val across = cell % (n + 2) - before % (n + 2)
        val along = cell / (n + 2) - before / (n + 2)
        // Where it comes into a junction's modules, never from one of them into the next.
        if (j != null && junction[before] != j && kotlin.math.abs(across) + kotlin.math.abs(along) == 1) {
          out += ((s - short) % route.length + route.length) % route.length
          out += j.toFloat()
          out += if (across != 0) 0f else 1f
        }
        before = cell
      }
      s += step
    }
    return out.toFloatArray()
  }

  // ---------------------------------------------------------------------------------------------
  // Streets
  // ---------------------------------------------------------------------------------------------

  /**
   * The road network: every light module but the gaps inside the finder patterns (a closed ring no
   * street leads into; in Kuala Lumpur, a pool and a promenade), and a ring road one module outside
   * the code, with dead ends pruned away so a car never has to turn round.
   */
  private class Streets(matrix: QrMatrix, heights: FloatArray, paved: Set<Int>) {
    val n = matrix.size
    private val m = n + 2
    private val free = BooleanArray(m * m)

    init {
      for (z in -1..n) {
        for (x in -1..n) {
          val ring = x < 0 || z < 0 || x >= n || z >= n
          free[index(x, z)] = ring || ((heights[z * n + x] <= 0f || z * n + x in paved) && matrix.role(x, z) != QrRole.FINDER)
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

  /** Straight runs of at least three modules on which [free] holds, along x and along z. */
  private fun runs(n: Int, free: (Int, Int) -> Boolean): List<Run> {
    val out = ArrayList<Run>()
    for (alongX in booleanArrayOf(true, false)) {
      for (fixed in 0 until n) {
        var start = -1
        for (k in 0..n) {
          val free = k < n && (if (alongX) free(k, fixed) else free(fixed, k))
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
   * The monorail follows the road and joins the city's sights (2026-11-12, at the user's request:
   * a longer line that connects several places; before, it was one street and two turns at most).
   * From a street the camera sees it runs on at each end through the streets ([grow]), so the
   * line winds round the city the way a real elevated line follows the roads below it, never
   * into a finder corner. Of the lines every street in view starts, the best wins: a module
   * in view counts for one, a module behind a tower for [HIDDEN], each sight the line passes
   * within [NEAR] modules of (the landmarks, the corners and the crossing) for [SIGHT] modules,
   * and a module nearer the camera a little more. A city with no street of three modules in view
   * starts from its longest street instead. A station stands at each end and beside every sight
   * on the way, and the train calls at them all. Beam, pillars and stations go into [data].
   */
  private fun monorail(heights: FloatArray, n: Int, seed: Int, landmarks: LandmarkPlan, data: Instances, crossing: Int): Mover? {
    val seen = inView(heights, n, landmarks)
    val half = n / 2f
    // Each sight: its middle in modules, and how far from it the line may pass and still call.
    val sights = ArrayList<FloatArray>()
    landmarks.supertall?.let { sights += floatArrayOf(it.centerX, it.centerZ, NEAR) }
    landmarks.twins?.let { (a, b) -> sights += floatArrayOf((a.centerX + b.centerX) / 2f, (a.centerZ + b.centerZ) / 2f, NEAR) }
    landmarks.telecom?.let { sights += floatArrayOf(it.centerX, it.centerZ, NEAR) }
    // A corner's sight is its finder's middle, three and a half modules from its edge.
    for ((x, z) in listOf(3.5f to 3.5f, n - 3.5f to 3.5f, 3.5f to n - 3.5f)) sights += floatArrayOf(x, z, NEAR + 3.5f)
    if (crossing >= 0) sights += floatArrayOf(crossing % n + 0.5f, crossing / n + 0.5f, NEAR)
    // The module of [path] nearest each sight within reach, its index along the path; never a
    // bend, where a station's deck would stand askew.
    fun calls(path: List<Pair<Int, Int>>): List<Int> {
      fun straight(k: Int) = k in 1 until path.size - 1 &&
        (path[k - 1].first == path[k + 1].first || path[k - 1].second == path[k + 1].second)
      return sights.mapNotNull { sight ->
        fun far(k: Int) = hypot(path[k].first + 0.5f - sight[0], path[k].second + 0.5f - sight[1])
        path.indices.filter(::straight).minByOrNull(::far)?.takeIf { far(it) <= sight[2] }
      }
    }
    fun score(path: List<Pair<Int, Int>>): Float {
      var toward = 0f
      var length = 0f
      for ((x, z) in path) {
        toward += Sight.toward(x + 0.5f - half, z + 0.5f - half)
        length += if (seen[z * n + x]) 1f else HIDDEN
      }
      return length + 0.5f * toward / path.size + SIGHT * calls(path).size
    }
    val all = runs(n) { x, z -> heights[z * n + x] <= 0f }
    val visible = runs(n) { x, z -> heights[z * n + x] <= 0f && seen[z * n + x] }
    var best: List<Pair<Int, Int>>? = null
    var bestScore = -Float.MAX_VALUE
    fun closed(x: Int, z: Int) = heights[z * n + x] > 0f || (x < 7 || x >= n - 7) && (z < 7 || z >= n - 7)
    for (run in visible.ifEmpty { all.filter { it.size >= 5 } }) {
      val first = (run.from..run.to).map { if (run.alongX) it to run.fixed else run.fixed to it }
      if (first.any { (x, z) -> closed(x, z) }) continue
      val path = grow(first, n, ::closed, seed, ::score)
      if (path.size < 6) continue
      // The seed breaks ties between equally good lines.
      val score = score(path) + 0.01f * hash(run.fixed, run.from * 2 + if (run.alongX) 1 else 0, seed + 5)
      if (score > bestScore) {
        bestScore = score
        best = path
      }
    }
    if (best == null) return null
    val world = best.map { (x, z) -> (x + 0.5f - half) to (z + 0.5f - half) }
    val route = Route.rounded(world, closed = false, radius = 0.5f, y = KualaLumpur.RAIL_Y)
    // Stations: the ends, and each sight's module if it is a train's length from the last one.
    val stops = ArrayList<Int>()
    stops += 0
    for (k in calls(best).sorted()) if (k - stops.last() >= STATION_GAP && best.size - 1 - k >= STATION_GAP) stops += k
    stops += best.size - 1
    KualaLumpur.track(data, route, world, stops)
    val inset = 1.6f
    val stations = FloatArray(stops.size) { i ->
      when (i) {
        0 -> inset
        stops.size - 1 -> route.length - inset
        else -> along(route, world[stops[i]])
      }
    }
    return Mover(Mover.MONORAIL, route, 3.0f, 0f, 0.42f, shuttle = true, dwell = 1.8f, inset = inset, stations = stations)
  }

  /** What a module of the line behind a tower counts for, against one in view. */
  private const val HIDDEN = 0.35f

  /** How near the line must pass a sight to call there, in modules, and what a call is worth. */
  private const val NEAR = 3.5f
  private const val SIGHT = 4f

  /** Stations stand at least this many modules apart. */
  private const val STATION_GAP = 4

  /** How far along [route] its point nearest [at] is. */
  private fun along(route: Route, at: Pair<Float, Float>): Float {
    val p = FloatArray(4)
    var best = 0f
    var nearest = Float.MAX_VALUE
    var s = 0f
    while (s <= route.length) {
      route.at(s, p)
      val d = hypot(p[0] - at.first, p[2] - at.second)
      if (d < nearest) {
        nearest = d
        best = s
      }
      s += 0.05f
    }
    return best
  }

  /**
   * Which modules' streets the camera sees the monorail's beam over from its usual side: nothing
   * between there and the camera stands up into the line of sight, not a tower, not the mall's
   * towers, and not a landmark, each a column a little wider than its footprint (turned squares
   * and the telecom tower's head reach past it) up to where it thins to a spire or a mast.
   */
  private fun inView(heights: FloatArray, n: Int, landmarks: LandmarkPlan): BooleanArray {
    val skyline = heights.copyOf()
    Corners.residencesOver(skyline, n)
    val half = n / 2f
    val columns = ArrayList<FloatArray>() // x, z, radius, height
    fun column(f: Footprint, reach: Float, h: Float) {
      columns += floatArrayOf(f.centerX - half, f.centerZ - half, max(f.size * 0.75f, reach), h)
    }
    landmarks.supertall?.let { column(it, 0f, KualaLumpur.supertallHeight(n) * 0.79f) }
    landmarks.twins?.let { (a, b) ->
      column(a, 0f, KualaLumpur.twinHeight(n) * 0.885f)
      column(b, 0f, KualaLumpur.twinHeight(n) * 0.885f)
    }
    landmarks.telecom?.let { column(it, 1.35f, KualaLumpur.telecomHeight(n) * 0.86f + 1.2f) }
    var top = 0f
    for (h in skyline) top = max(top, h)
    for (c in columns) top = max(top, c[3])
    fun height(x: Float, z: Float): Float {
      val col = floor(x + half).toInt()
      val row = floor(z + half).toInt()
      var h = if (col < 0 || row < 0 || col >= n || row >= n) 0f else skyline[row * n + col]
      for (c in columns) {
        val dx = x - c[0]
        val dz = z - c[1]
        if (dx * dx + dz * dz < c[2] * c[2]) h = max(h, c[3])
      }
      return h
    }
    return BooleanArray(n * n) { i ->
      Sight.clear(i % n + 0.5f - half, KualaLumpur.RAIL_Y + 0.11f, i / n + 0.5f - half, top, ::height)
    }
  }

  /**
   * The best line through the streets from [start], grown at one end and then the other: a search
   * that goes straight on where it can and turns where it must, backs out of dead ends, and keeps
   * the best line [score] has seen within its budget of steps. The line never touches or runs
   * beside itself, goes two modules at least between bends, and stays out of [closed] modules.
   */
  private fun grow(start: List<Pair<Int, Int>>, n: Int, closed: (Int, Int) -> Boolean, seed: Int, score: (List<Pair<Int, Int>>) -> Float): List<Pair<Int, Int>> {
    var line = start
    repeat(2) {
      val path = ArrayList(line)
      val used = HashSet(line)
      var best = line
      var bestScore = score(line)
      var budget = GROW_BUDGET
      fun step(sinceBend: Int) {
        if (budget-- <= 0 || path.size >= 3 * n) return
        val (x, z) = path.last()
        val (px, pz) = path[path.size - 2]
        val d = (0 until 4).first { DX[it] == x - px && DZ[it] == z - pz }
        // Straight on first; then the turns, in the seed's order.
        val left = hash(x, z, seed + 61) < 0.5f
        for (turn in if (sinceBend >= 2) intArrayOf(0, if (left) 3 else 1, if (left) 1 else 3) else intArrayOf(0)) {
          val nd = (d + turn) % 4
          val nx = x + DX[nd]
          val nz = z + DZ[nd]
          if (nx !in 0 until n || nz !in 0 until n || closed(nx, nz) || (nx to nz) in used) continue
          // Beside nothing of the line but the module it comes from.
          if ((0 until 4).any { val c = (nx + DX[it]) to (nz + DZ[it]); c != (x to z) && c in used }) continue
          path += nx to nz
          used += nx to nz
          val now = score(path)
          if (now > bestScore) {
            bestScore = now
            best = path.toList()
          }
          step(if (turn == 0) sinceBend + 1 else 1)
          used -= path.removeAt(path.size - 1)
        }
      }
      step(2)
      line = best.reversed()
    }
    return line
  }

  /** How many steps the search for the line takes from each street, at each end. */
  private const val GROW_BUDGET = 3000

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
      obstacles += Corners.obstacles(n)
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

  /** The plane circles wide and high above the whole city; over Kuala Lumpur it tows the city's flag. */
  private fun plane(half: Float, skyline: Float, banner: Boolean): Mover =
    Mover(Mover.PLANE, flight(0f, 0f, half * 1.25f + 3f, half * 1.25f + 3f, 0f, skyline + 6f, 0.8f, 128), 7f, 0.56f, 0.40f, banner = banner)

  /** A closed ellipse, turned by [turn], rising and falling by [bob] twice a lap. */
  private fun flight(cx: Float, cz: Float, a: Float, b: Float, turn: Float, y: Float, bob: Float, steps: Int): Route {
    val points = FloatArray(steps * 3)
    val c = FdLibm.cos(turn.toDouble()).toFloat()
    val s = FdLibm.sin(turn.toDouble()).toFloat()
    for (i in 0 until steps) {
      val angle = 2.0 * kotlin.math.PI * i / steps
      val ex = a * FdLibm.cos(angle).toFloat()
      val ez = b * FdLibm.sin(angle).toFloat()
      points[i * 3] = cx + ex * c - ez * s
      points[i * 3 + 1] = y + bob * FdLibm.sin(2.0 * angle).toFloat()
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
