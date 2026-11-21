package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.FdLibm
import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.GeneratorVersion
import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One city as raw instance data, drawn in a single instanced draw call. */
class City(
  val instances: FloatArray,
  val count: Int,
  val modules: Int,
  val maxHeight: Float,
  val identity: CityIdentity,
  /** Cars, the tram and the helicopter: the part of the city that moves. */
  val movers: List<Mover>,
  /** The code itself, for anything that draws it without the city (a 2D fallback, a share page). */
  val qr: QrMatrix,
  /** What the city is built of. Picked by the site seed, so it is part of the identity. */
  val materials: MaterialRecipe,
  val preset: CityPreset,
  /** Where the Kuala Lumpur landmarks stand; null in the generic city. */
  val landmarks: LandmarkPlan?,
  /** Each module's tone, row by row: its tower colour or paper shade; see [FlatColours.tones]. */
  val tones: IntArray,
  /** Each module's ink in the flat code, row by row; see [FlatColours.inks]. */
  val inks: IntArray,
  /** The traffic lights, drawn with the traffic as their lamps change. */
  val signals: Signals = Signals.NONE,
  /**
   * Where a screen for a billboard can stand: the middle of its face (x, y, z), its width and
   * height, facing +z. On the mall tower in Kuala Lumpur; null in a city without one.
   */
  val screen: FloatArray? = null,
  /** Where the fountain's water leaves the top of its stem (x, y, z), in Kuala Lumpur; null without one. */
  val fountain: FloatArray? = null,
  /** The middle of Kuala Lumpur's crossing (x, z); null in a city without one. */
  val crossing: FloatArray? = null,
) {
  /** Half-width of the floating plate, quiet zone included. */
  val plateHalf: Float
    get() = modules / 2f + CityPlanner.QUIET
}

/**
 * Turns a QR matrix into a city. Deterministic: the same matrix and identity always produce the
 * same instance buffer, on every device. QR dark modules become towers, light modules become plaza
 * tiles, and the plate under everything carries the quiet zone. Light and weather are not inputs:
 * they are presentation, and shadows are cast live by the renderer.
 */
object CityPlanner {

  /**
   * Floats per instance: offset xyz, size xyz, then role, height 0..1, the flat code's module
   * (see [module]), a per-instance random, and the yaw and taper that let a box turn about its axis
   * and narrow toward its top.
   */
  const val STRIDE = 12

  /**
   * Where the camera usually stands, in radians: its bearing round the city, 0 looking from +z
   * (below the code) and growing toward +x, and its height above the ground. The renderer's
   * default camera is this one, and the planner lays the landmarks and the monorail out for it.
   */
  const val VIEW_YAW = 0.62f
  const val VIEW_ELEVATION = 0.60f
  const val QUIET = 2
  const val PLATE_THICK = 1.3f
  const val FLOOR = 0.42f
  const val GAP = 0.94f
  const val TILE_TOP = 0.07f

  const val ROLE_PLATE = 0f
  const val ROLE_TILE = 1f
  const val ROLE_TOWER = 2f
  const val ROLE_BEACON = 3f
  const val ROLE_VEHICLE = 4f
  /** Parts of a landmark: they exist only in the 3D city and shrink away as it flattens. */
  const val ROLE_LANDMARK = 5f
  /** Monorail beam and pillars: 3D only, like the landmarks. */
  const val ROLE_RAIL = 6f
  /** The meteor and its fireball, drawn as traffic: they glow on their own. */
  const val ROLE_METEOR = 7f
  /** Glass, metal and rubber on the vehicles: neutral, their grey carried in the random slot. */
  const val ROLE_DETAIL = 8f
  /** A lightning bolt, drawn as traffic: it glows on its own, white-blue. */
  const val ROLE_BOLT = 9f
  /** A traffic light's lit lamp, drawn as traffic: red, amber or green by its random slot. */
  const val ROLE_SIGNAL = 10f
  /** A billboard's screen, drawn as traffic: its face shows the renderer's messages. */
  const val ROLE_SCREEN = 11f

  /**
   * The weather's own shapes, added every frame (2026-11-07): clouds and smoke, matte, their grey
   * in the random slot; the sun or moon, which glows, with 1 in the glow slot. They cast no shadow.
   */
  const val ROLE_WEATHER = 12f

  /**
   * A firework's spark, or the mall fountain's water, added every frame (2026-11-09): it glows on
   * its own, its colour by its random slot and its brightness by its glow. It casts no shadow.
   */
  const val ROLE_SPARK = 13f

  fun plan(matrix: QrMatrix, identity: CityIdentity, preset: CityPreset = CityPreset.GENERIC): City {
    // Version 1 is the only recipe so far; a new one branches here instead of editing this one.
    GeneratorVersion.resolve(identity.generatorVersion)
    val seed = identity.seed
    val n = matrix.size
    val heights = FloatArray(n * n)
    val kl = preset == CityPreset.KUALA_LUMPUR
    val landmarks = if (kl) LandmarkPlan.plan(matrix, seed) else null
    val materials = if (kl) KualaLumpur.materials else Materials.pick(seed)
    val tones = FlatColours.tones(matrix, preset, landmarks, seed, materials)
    val inks = FlatColours.inks(matrix, tones, preset, FlatColours.tones(materials) / 2, seed)

    for (z in 0 until n) {
      for (x in 0 until n) {
        if (!matrix.isDark(x, z)) continue
        heights[z * n + x] =
          when {
            matrix.role(x, z) == QrRole.FINDER -> if (kl) Corners.height(x, z, n) else podiumHeight(x, z, n)
            landmarks?.covers(x, z) == true -> KualaLumpur.PODIUM
            kl -> min(columnHeight(x, z, n, seed), KualaLumpur.towerCap(n))
            else -> columnHeight(x, z, n, seed)
          }
      }
    }

    // Kuala Lumpur's crossing on the alignment pattern (2026-11-09): its middle and arms paved as
    // road, low shops round it, and the restaurant on its far corner.
    val aligned = if (kl) Corners.alignment(matrix) else -1
    val paved = if (aligned >= 0) Corners.paved(aligned, n) else emptySet()
    if (aligned >= 0) {
      val ac = aligned % n
      val ar = aligned / n
      for (z in ar - 2..ar + 2) for (x in ac - 2..ac + 2) {
        if (matrix.isDark(x, z)) heights[z * n + x] = if (z * n + x in paved) Corners.PAVED else Corners.ringHeight(x, z, seed)
      }
      heights[Corners.restaurant(aligned, n)] = Corners.RESTAURANT
    }

    // The generic city makes its own centrepiece; Kuala Lumpur already has three.
    if (!kl) boostLandmark(heights, matrix)
    var maxH = FLOOR
    for (h in heights) if (h > maxH) maxH = h

    val data = Instances()
    val half = n * 0.5f
    val skyline = if (landmarks != null) max(maxH, KualaLumpur.supertallHeight(n)) else maxH
    // Traffic before the towers: Kuala Lumpur's crossing is a junction with lights, and the
    // restaurant on its corner stands two floors high, whatever tower its module would have had.
    val traffic = Traffic.plan(matrix, heights, n, seed, skyline, maxH, landmarks, data, paved, aligned)
    val rail = traffic.movers.firstOrNull { it.kind == Mover.MONORAIL }?.route
    // A version 1 code has no alignment pattern: there the crossing keeps to the lit junction
    // nearest the mall, the restaurant on its corner.
    val restaurant = if (kl && aligned < 0) Corners.restaurant(traffic.signals, matrix, landmarks, rail) else -1
    if (restaurant >= 0) heights[restaurant] = Corners.RESTAURANT

    // The plate: ground for the whole city plus the QR quiet zone.
    data.put(0f, -PLATE_THICK * 0.5f, 0f, n + 2f * QUIET, PLATE_THICK, n + 2f * QUIET, ROLE_PLATE, 0f, 0f, 0f)

    for (z in 0 until n) {
      for (x in 0 until n) {
        val wx = x + 0.5f - half
        val wz = z + 0.5f - half
        val rnd = moduleRandom(x, z, seed)
        val h = heights[z * n + x]
        if (h > 0f) {
          // Taller towers keep a slimmer footprint, so the skyline has a silhouette. Finder podiums
          // fill their modules edge to edge, so each corner reads as one solid building.
          val role = matrix.role(x, z)
          val podium = role == QrRole.FINDER
          val slim = if (podium) 1f else GAP * (1f - 0.22f * (h / maxH))
          val pattern = role != QrRole.DATA && role != QrRole.REMAINDER
          data.put(wx, h * 0.5f, wz, slim, h, slim, ROLE_TOWER, h / maxH, module(tones[z * n + x], inks[z * n + x], pattern), rnd)
        } else {
          data.put(wx, 0.035f, wz, 1f, 0.07f, 1f, ROLE_TILE, 0f, module(tones[z * n + x], 0, false), rnd)
        }
      }
    }

    for (z in 0 until n) {
      for (x in 0 until n) {
        val h = heights[z * n + x]
        if (isBeacon(h, maxH)) {
          data.put(x + 0.5f - half, h + 0.22f, z + 0.5f - half, 0.3f, 0.3f, 0.3f, ROLE_BEACON, 1f, 0f, hash(x, z, seed + 97))
        }
      }
    }

    var crossing: FloatArray? = null
    if (landmarks != null) {
      KualaLumpur.build(data, landmarks, n)
      if (aligned >= 0) {
        Corners.alignmentCrossing(data, aligned, n)
        crossing = floatArrayOf(aligned % n + 0.5f - half, aligned / n + 0.5f - half)
      } else {
        Corners.crossing(data, traffic.signals, matrix, landmarks, restaurant, rail)
        val j = Corners.crossing(traffic.signals, n, rail, landmarks)
        if (j >= 0) crossing = floatArrayOf(traffic.signals.cells[j * 2] + 0.5f - half, traffic.signals.cells[j * 2 + 1] + 0.5f - half)
      }
    }

    val screen = if (kl) Corners.screen(n) else null
    val fountain = if (kl) Corners.fountain(n) else null
    return City(
      data.toArray(), data.count, n, skyline, identity, traffic.movers, matrix, materials, preset, landmarks, tones, inks, traffic.signals,
      screen, fountain, crossing,
    )
  }

  /**
   * What a tower or tile carries for the flat code, as one exact whole number: 1 on the code's
   * function patterns (finders, timing, alignment, format), plus twice its tone, its tower colour
   * or paper shade ([FlatColours.tones]), plus 32 times its ink ([FlatColours.inks]).
   */
  private fun module(tone: Int, ink: Int, pattern: Boolean): Float = ((if (pattern) 1 else 0) + 2 * tone + 32 * ink).toFloat()

  /** Whether an instance's flat-code number marks a function pattern; see [module]. */
  fun isPattern(module: Float) = (module.toInt() and 1) == 1

  /** The random a module's tower or tile carries in its instance: the grain of its roof and panes. */
  internal fun moduleRandom(x: Int, z: Int, seed: Int) = hash(x, z, seed + 31)

  private fun isBeacon(h: Float, maxH: Float) = h > 0f && h >= maxH * 0.92f

  /** Towers rise in shallow downtown-shaped hills, quantised to whole floors. */
  private fun columnHeight(x: Int, z: Int, n: Int, seed: Int): Float {
    val broad = noise(x * 0.13f, z * 0.13f, seed)
    val fine = noise(x * 0.42f, z * 0.42f, seed + 7919)
    val dx = x - (n - 1) * 0.5f
    val dz = z - (n - 1) * 0.5f
    val downtown = (1f - hypot(dx, dz) / (n * 0.55f)).coerceIn(0f, 1f)
    val shape = broad * 0.7f + fine * 0.3f
    var floors = 1f + 30f * pow(shape, 2.2f) * (0.22f + 0.78f * downtown * downtown)
    // Now and then a tower shoots up out of the pack, the way a skyline actually breaks.
    if (hash(x, z, seed + 5171) > 0.900f) floors *= 1.9f
    return max(FLOOR, floor(floors) * FLOOR)
  }

  /**
   * The three finder patterns are podiums: a low wall round the ring and a squat block on the
   * core. The corners stay readable from any angle and leave the height to the middle.
   */
  private fun podiumHeight(x: Int, z: Int, n: Int): Float {
    val lx = if (x < 7) x else x - (n - 7)
    val lz = if (z < 7) z else z - (n - 7)
    val core = lx in 2..4 && lz in 2..4
    return if (core) 3 * FLOOR else FLOOR
  }

  /** Gives the tallest cluster a landmark bump so the skyline has a centre. */
  private fun boostLandmark(heights: FloatArray, matrix: QrMatrix) {
    val n = matrix.size
    var peakX = -1
    var peakZ = -1
    var peak = 0f
    for (z in 0 until n) {
      for (x in 0 until n) {
        val h = heights[z * n + x]
        if (h > peak && matrix.role(x, z) != QrRole.FINDER) {
          peak = h
          peakX = x
          peakZ = z
        }
      }
    }
    if (peakX < 0) return
    for (z in max(0, peakZ - 1)..min(n - 1, peakZ + 1)) {
      for (x in max(0, peakX - 1)..min(n - 1, peakX + 1)) {
        val idx = z * n + x
        if (heights[idx] > 0f && matrix.role(x, z) != QrRole.FINDER) heights[idx] = min(heights[idx] * 1.35f, 40f)
      }
    }
  }

  private fun smooth(t: Float) = t * t * (3f - 2f * t)

  // FdLibm (StrictMath's bits in plain Kotlin), not kotlin.math: Math may differ in the last bit
  // between the JVM, Android's runtime and the browser, and a height that rounds to a different
  // floor would build a different city.
  private fun pow(x: Float, y: Float) = FdLibm.pow(x.toDouble(), y.toDouble()).toFloat()

  private fun hypot(x: Float, y: Float) = FdLibm.hypot(x.toDouble(), y.toDouble()).toFloat()

  private fun noise(x: Float, z: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val zi = floor(z).toInt()
    val tx = smooth(x - xi)
    val tz = smooth(z - zi)
    val a = hash(xi, zi, seed)
    val b = hash(xi + 1, zi, seed)
    val c = hash(xi, zi + 1, seed)
    val d = hash(xi + 1, zi + 1, seed)
    val top = a + (b - a) * tx
    val bottom = c + (d - c) * tx
    return top + (bottom - top) * tz
  }

  /** Integer hash with wrapping overflow: identical on every device, no RNG state. */
  private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = seed * 374761393 + x * 668265263 + y * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
  }
}

/** A growing instance buffer, [CityPlanner.STRIDE] floats per box. */
internal class Instances {
  private var data = FloatArray(1024 * CityPlanner.STRIDE)
  var count = 0
    private set

  fun put(
    ox: Float, oy: Float, oz: Float,
    sx: Float, sy: Float, sz: Float,
    role: Float, heightNorm: Float, pattern: Float, rnd: Float,
    yaw: Float = 0f, taper: Float = 1f,
  ) {
    if ((count + 1) * CityPlanner.STRIDE > data.size) data = data.copyOf(data.size * 2)
    val o = count * CityPlanner.STRIDE
    data[o] = ox
    data[o + 1] = oy
    data[o + 2] = oz
    data[o + 3] = sx
    data[o + 4] = sy
    data[o + 5] = sz
    data[o + 6] = role
    data[o + 7] = heightNorm
    data[o + 8] = pattern
    data[o + 9] = rnd
    data[o + 10] = yaw
    data[o + 11] = taper
    count++
  }

  fun toArray(): FloatArray = data.copyOf(count * CityPlanner.STRIDE)
}
