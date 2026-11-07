package com.skylightlim.skylineqr.city

/**
 * The city's traffic lights (2026-10-21): up to [MAX_JUNCTIONS] junctions the cars really drive
 * through, spread out and clear of the corners, with a signal on the left kerb (Malaysia keeps
 * left) of every approach a car uses. A junction lets one axis go at a time: green, amber, a moment
 * of all red, then the other axis. Each junction starts its cycle at a seeded point, so the same
 * link always has the same lights, and the cars stop for them (see [Mover]).
 */
class Signals(
  /**
   * [HEAD] floats per signal: the pole's x and z, the heading (dx, dz) of the traffic it faces,
   * its junction, and the axis that traffic travels along (0 along x, 1 along z).
   */
  val heads: FloatArray,
  /** Per junction: how many seconds into its cycle it is at time 0. */
  val offsets: FloatArray,
  /** Per junction: its module's column and row. */
  val cells: IntArray,
) {
  val count: Int
    get() = heads.size / HEAD

  /** [RED], [AMBER] or [GREEN] for traffic along [axis] at [junction], [time] seconds in. */
  fun state(junction: Int, axis: Int, time: Float): Int {
    val local = cycle(junction, time) - axis * HALF
    val t = ((local % CYCLE) + CYCLE) % CYCLE
    return when {
      t < GREEN_SECONDS -> GREEN
      t < GREEN_SECONDS + AMBER_SECONDS -> AMBER
      else -> RED
    }
  }

  /** The first moment at or after [time] that traffic along [axis] at [junction] may go. */
  fun nextGreen(junction: Int, axis: Int, time: Float): Float {
    if (state(junction, axis, time) == GREEN) return time
    val t = ((cycle(junction, time) - axis * HALF) % CYCLE + CYCLE) % CYCLE
    return time + (CYCLE - t)
  }

  private fun cycle(junction: Int, time: Float) = time + offsets[junction]

  companion object {
    const val HEAD = 6
    const val RED = 0
    const val AMBER = 1
    const val GREEN = 2

    const val MAX_JUNCTIONS = 6
    const val GREEN_SECONDS = 3.0f
    const val AMBER_SECONDS = 0.8f
    const val ALL_RED_SECONDS = 0.6f

    /** One axis's turn: green, amber, and all red before the other axis goes. */
    const val HALF = GREEN_SECONDS + AMBER_SECONDS + ALL_RED_SECONDS
    const val CYCLE = 2 * HALF

    /** A city with no lights. */
    val NONE = Signals(FloatArray(0), FloatArray(0), IntArray(0))
  }
}

/**
 * Where a car is along its loop when it stops for the lights: built lazily, forward from time 0,
 * one decision per stop line. Cruising toward a line, the car looks at its light where it would
 * start braking: green, it drives on; amber or red, it eases to a halt at the line, waits for
 * green and eases away. So its position stays a pure function of time, however often it is asked.
 */
internal class Schedule(
  private val route: Route,
  private val speed: Float,
  start: Float,
  /** Per stop line: where along the loop the car halts, its junction, and its axis. */
  private val stops: FloatArray,
  private val signals: Signals,
) {
  // Segments: when each starts, where the car is then, and how it moves until the next.
  private var times = FloatArray(32)
  private var places = FloatArray(32)
  private var kinds = IntArray(32)
  private var size = 0

  /** When the next decision falls due, and the stop line it is about. */
  private var decideAt = 0f
  private var line = 0f
  private var lineIndex = 0

  /** Past here, the next line comes; lines closer than a braking distance to the start are skipped. */
  private var searchFrom: Float

  init {
    add(0f, start, CRUISE)
    searchFrom = start + BRAKE
    plan()
  }

  fun distance(time: Float): Float {
    while (decideAt <= time) decide()
    var lo = 0
    var hi = size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (times[mid] <= time) lo = mid else hi = mid - 1
    }
    val t = time - times[lo]
    val ease = 2f * BRAKE / speed
    val u = (t / ease).coerceIn(0f, 1f)
    return when (kinds[lo]) {
      CRUISE -> places[lo] + speed * t
      BRAKING -> places[lo] + BRAKE * (1f - (1f - u) * (1f - u))
      WAITING -> places[lo]
      else -> places[lo] + BRAKE * u * u
    }
  }

  /** The next stop line past [searchFrom], and when the car would start braking for it. */
  private fun plan() {
    val length = route.length
    var best = Float.MAX_VALUE
    var bestIndex = 0
    for (k in 0 until stops.size / 3) {
      // The first time this line comes round past searchFrom.
      var s = stops[k * 3] + kotlin.math.floor((searchFrom - stops[k * 3]) / length) * length
      if (s < searchFrom) s += length
      if (s < best) {
        best = s
        bestIndex = k
      }
    }
    line = best
    lineIndex = bestIndex
    decideAt = times[size - 1] + (line - BRAKE - places[size - 1]) / speed
  }

  private fun decide() {
    val junction = stops[lineIndex * 3 + 1].toInt()
    val axis = stops[lineIndex * 3 + 2].toInt()
    val stopping = signals.state(junction, axis, decideAt) != Signals.GREEN
    if (stopping) {
      val ease = 2f * BRAKE / speed
      add(decideAt, line - BRAKE, BRAKING)
      val halted = decideAt + ease
      val go = signals.nextGreen(junction, axis, halted)
      if (go > halted) add(halted, line, WAITING)
      add(go, line, GOING)
      add(go + ease, line + BRAKE, CRUISE)
    }
    // After a stop the next line must leave room to brake from full speed again.
    searchFrom = if (stopping) line + 2f * BRAKE else line + 1e-3f
    plan()
  }

  private fun add(time: Float, place: Float, kind: Int) {
    if (size == times.size) {
      times = times.copyOf(size * 2)
      places = places.copyOf(size * 2)
      kinds = kinds.copyOf(size * 2)
    }
    times[size] = time
    places[size] = place
    kinds[size] = kind
    size++
  }

  companion object {
    /** How far before the line a car starts braking, and after it reaches full speed again. */
    const val BRAKE = 0.5f

    private const val CRUISE = 0
    private const val BRAKING = 1
    private const val WAITING = 2
    private const val GOING = 3
  }
}
