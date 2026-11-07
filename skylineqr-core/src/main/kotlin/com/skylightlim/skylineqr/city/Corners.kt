package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.city.KualaLumpur.BLUE_GLASS
import com.skylightlim.skylineqr.city.KualaLumpur.BRICK
import com.skylightlim.skylineqr.city.KualaLumpur.BRONZE
import com.skylightlim.skylineqr.city.KualaLumpur.CHAMPAGNE
import com.skylightlim.skylineqr.city.KualaLumpur.CONCRETE
import com.skylightlim.skylineqr.city.KualaLumpur.COPPER
import com.skylightlim.skylineqr.city.KualaLumpur.CRYSTAL
import com.skylightlim.skylineqr.city.KualaLumpur.FLAG_BLUE
import com.skylightlim.skylineqr.city.KualaLumpur.FLAG_RED
import com.skylightlim.skylineqr.city.KualaLumpur.FLAG_WHITE
import com.skylightlim.skylineqr.city.KualaLumpur.FLAG_YELLOW
import com.skylightlim.skylineqr.city.KualaLumpur.GLASS
import com.skylightlim.skylineqr.city.KualaLumpur.GRANITE
import com.skylightlim.skylineqr.city.KualaLumpur.LATTICE
import com.skylightlim.skylineqr.city.KualaLumpur.LAWN
import com.skylightlim.skylineqr.city.KualaLumpur.LEAF
import com.skylightlim.skylineqr.city.KualaLumpur.NEON_COOL
import com.skylightlim.skylineqr.city.KualaLumpur.RED
import com.skylightlim.skylineqr.city.KualaLumpur.SMOKE
import com.skylightlim.skylineqr.city.KualaLumpur.STEEL
import com.skylightlim.skylineqr.city.KualaLumpur.WATER
import com.skylightlim.skylineqr.core.QrMatrix
import com.skylightlim.skylineqr.core.QrRole
import kotlin.math.floor
import kotlin.math.max

/**
 * Kuala Lumpur's three finder corners (2026-10-21), laid out like a map with north at the top of
 * the code: the botanical garden and the national monument at the top left (north-west), the mall
 * on Bukit Bintang at the top right (east), and the independence square at the bottom left
 * (west). Named generically, like the other landmarks.
 *
 * The garden is a ring of lawn and big trees round a pool with the monument on its island; the mall
 * (redone 2026-10-22, after photographs of its 2024 facade by day and at night) fills its finder,
 * two cubes of champagne fins over white lattice either side of a recessed glass entrance under
 * its name, the crystal fountain on the plaza in front and the two residence towers behind, and
 * after dark its fins and lattice glow like neon; the square is a ring of lawn, with the old buildings
 * along two sides, round a promenade and the padang with its flag. Near the mall, the junction
 * with lights closest to it becomes Bukit Bintang's crossing, zebra stripes on every arm and
 * across both diagonals, with a fast-food restaurant on its corner. Like every landmark part they
 * exist only in the 3D city and shrink away as it flattens, and the modules underneath fold into
 * the code.
 */
internal object Corners {

  /** The garden's and the square's finder modules: a low lawn. */
  const val LAWN_HEIGHT = 0.12f

  /** The mall: seven floors of shops, drawn three high like every finder podium of the city. */
  const val MALL = 3 * CityPlanner.FLOOR

  /** The two cubes of the mall's facade stand well over its body, nearly as tall as they are wide. */
  const val CUBE = MALL + 0.84f

  /** Where the cubes start, from the finder's north edge: behind them, the body. */
  private const val CUBES_FROM = 4.3f

  /** The cubes' inner walls, either side of the entrance, and its face, set back from the street. */
  private const val WEST_CUBE = 2.55f
  private const val EAST_CUBE = 4.45f
  private const val ENTRANCE = 5.85f
  private const val ENTRANCE_TOP = CUBE - 0.22f

  /** The restaurant on the crossing: two floors. */
  const val RESTAURANT = 2 * CityPlanner.FLOOR

  /** Above the lawn, so a slab over the finder's modules hides their walls. */
  private const val GROUND = LAWN_HEIGHT + 0.015f

  /** The residence towers behind the mall: about 40 % and 34 % of the twin towers. */
  fun residences(n: Int) = floatArrayOf(KualaLumpur.twinHeight(n) * 0.40f, KualaLumpur.twinHeight(n) * 0.34f)

  /** The flagpole in the square: 95 m against the twin towers' 452, but never lost in the lawn. */
  fun flagpole(n: Int) = max(2.2f, KualaLumpur.twinHeight(n) * 95f / 452f)

  /**
   * How tall a finder module ([x], [z]) stands in Kuala Lumpur: the mall's are its full height but
   * for the plaza before its entrance, the middle three of its south side; the rest are lawn.
   */
  fun height(x: Int, z: Int, n: Int): Float {
    if (x < 7 || z >= 7) return LAWN_HEIGHT
    val plaza = z == 6 && x - (n - 7) in 2..4
    return if (plaza) LAWN_HEIGHT else MALL
  }

  fun build(out: Instances, n: Int) {
    val half = n / 2f
    garden(Place(out, -half, -half))
    mall(Place(out, n - 7 - half, -half), n)
    square(Place(out, -half, n - 7 - half), n)
  }

  /**
   * The billboard's screen, standing on the roof of the mall's east cube, set back behind its
   * facade so the fins show beneath it, facing south over the plaza: as [City.screen].
   */
  const val SCREEN_WIDTH = 2.33f
  const val SCREEN_HEIGHT = 1.0f

  fun screen(n: Int): FloatArray {
    val half = n / 2f
    return floatArrayOf(n - 7 + 5.72f - half, CUBE + 0.33f + SCREEN_HEIGHT / 2f, 5.4f - half, SCREEN_WIDTH, SCREEN_HEIGHT)
  }

  /** Where the helicopter must not fly: x, z, radius and height, as Traffic's obstacles. */
  fun obstacles(n: Int): List<FloatArray> {
    val half = n / 2f
    return listOf(
      floatArrayOf(n - 3.5f - half, 3.5f - half, 3.4f, MALL + residences(n)[0] + 1f),
      floatArrayOf(3.5f - half, n - 3.5f - half, 2.2f, flagpole(n) + 0.8f),
    )
  }

  /**
   * One finder corner: positions in modules from its top-left corner, so (3.5, 3.5) is the middle
   * of its core. Boxes stand on y0 and reach y1.
   */
  private class Place(val out: Instances, val x0: Float, val z0: Float) {
    fun box(
      u: Float, v: Float, y0: Float, y1: Float, su: Float, sv: Float, colour: Int,
      glow: Float = 0f, plain: Boolean = true, yaw: Float = 0f, taper: Float = 1f,
    ) {
      out.put(x0 + u, (y0 + y1) / 2f, z0 + v, su, y1 - y0, sv, CityPlanner.ROLE_LANDMARK, glow, 0f, KualaLumpur.colour(colour, plain), yaw, taper)
    }

    fun round(u: Float, v: Float, y0: Float, y1: Float, r0: Float, r1: Float, colour: Int, glow: Float = 0f) {
      KualaLumpur.round(out, x0 + u, z0 + v, y0, y1, r0, r1, colour, glow)
    }

    /** A piece of flag cloth, [wave] from the pole (0) to the fly (1); see [KualaLumpur.flagColour]. */
    fun cloth(u: Float, v: Float, y0: Float, y1: Float, su: Float, sv: Float, colour: Int, wave: Float) {
      out.put(x0 + u, (y0 + y1) / 2f, z0 + v, su, y1 - y0, sv, CityPlanner.ROLE_LANDMARK, 0.9f, 0f, KualaLumpur.flagColour(colour, wave), 0f, 1f)
    }

    /** A slab over the finder's ring of modules, [y1] high, in four pieces that meet without overlapping. */
    fun ring(y0: Float, y1: Float, colour: Int, overhang: Float = 0.02f, glow: Float = 0f, plain: Boolean = true) {
      val w = 1f + overhang
      box(3.5f, 0.5f, y0, y1, 7f + overhang, w, colour, glow, plain)
      box(3.5f, 6.5f, y0, y1, 7f + overhang, w, colour, glow, plain)
      box(0.5f, 3.5f, y0, y1, w, 5f - overhang, colour, glow, plain)
      box(6.5f, 3.5f, y0, y1, w, 5f - overhang, colour, glow, plain)
    }

    /**
     * The national flag, its hoist at ([u], [v]) and its top edge at [top], flying [length] along +x
     * and half as tall: red and white stripes, the blue canton over the top of the hoist half, the
     * yellow crescent and star in it. Cut in eight pieces along its length, so it can wave.
     */
    fun flag(u: Float, v: Float, top: Float, length: Float) {
      val tall = length / 2f
      val piece = length / 8f
      for (i in 0 until 8) {
        val wave = (i + 0.5f) / 8f
        val at = u + (i + 0.5f) * piece
        for (k in 0 until 7) {
          val y1 = top - k * tall / 7f
          cloth(at, v, y1 - tall / 7f, y1, piece * 1.01f, 0.02f, if (k % 2 == 0) FLAG_RED else FLAG_WHITE, wave)
        }
        // A hair over the stripes' top edge, not on it.
        if (i < 4) cloth(at, v, top - tall * 0.57f, top + 0.003f, piece * 1.01f, 0.03f, FLAG_BLUE, wave)
        if (i == 1 || i == 2) cloth(at, v, top - tall * 0.45f, top - tall * 0.12f, piece * 1.01f, 0.04f, FLAG_YELLOW, wave)
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // The botanical garden and the national monument
  // -----------------------------------------------------------------------------------------------

  /**
   * Lawn and big trees round the ring, drawn larger than life so they read from across the city:
   * wide rain trees, rounder trees and tall palms, with hibiscus shrubs between. The gap is the
   * reflecting pool, a fountain at each corner, and on the island of the core the monument: bronze
   * figures on a stepped granite plinth, the tallest holding up the flag, two fallen at their feet.
   */
  private fun garden(p: Place) {
    p.ring(0f, GROUND, LAWN)
    val trees = listOf(0.8f to 0.8f, 3.5f to 0.6f, 6.2f to 0.8f, 6.4f to 3.5f, 6.2f to 6.2f, 3.5f to 6.4f, 0.8f to 6.2f, 0.6f to 3.5f)
    for ((k, spot) in trees.withIndex()) {
      val (u, v) = spot
      when (k % 4) {
        0 -> rainTree(p, u, v)
        1 -> palm(p, u, v, unit(k, 1, 6))
        2 -> roundTree(p, u, v)
        else -> palm(p, u, v, unit(k, 2, 6))
      }
    }
    for ((u, v) in listOf(2.1f to 0.5f, 4.9f to 0.5f, 6.5f to 2.1f, 6.5f to 4.9f, 4.9f to 6.5f, 2.1f to 6.5f, 0.5f to 4.9f, 0.5f to 2.1f)) {
      p.round(u, v, GROUND, GROUND + 0.26f, 0.26f, 0.2f, LEAF)
      for (k in 0 until 3) p.box(u + (k - 1) * 0.12f, v + (unit(k, 3, 7) - 0.5f) * 0.2f, GROUND + 0.22f, GROUND + 0.32f, 0.1f, 0.1f, RED, glow = 0.3f)
    }
    // The pool, just above the paving of the finder's gap, and its fountains.
    p.box(3.5f, 1.5f, 0.06f, 0.10f, 4.98f, 0.98f, WATER)
    p.box(3.5f, 5.5f, 0.06f, 0.10f, 4.98f, 0.98f, WATER)
    p.box(1.5f, 3.5f, 0.06f, 0.10f, 0.98f, 2.98f, WATER)
    p.box(5.5f, 3.5f, 0.06f, 0.10f, 0.98f, 2.98f, WATER)
    for ((u, v) in listOf(1.5f to 1.5f, 5.5f to 1.5f, 1.5f to 5.5f, 5.5f to 5.5f)) {
      p.round(u, v, 0.08f, 0.18f, 0.3f, 0.3f, CONCRETE, glow = 0.5f)
      p.box(u, v, 0.18f, 0.8f, 0.08f, 0.08f, CONCRETE, glow = 0.9f)
      p.round(u, v, 0.66f, 0.84f, 0.06f, 0.2f, CONCRETE, glow = 0.9f)
    }
    // The plinth.
    p.box(3.5f, 3.5f, 0f, 0.30f, 3.02f, 3.02f, GRANITE)
    p.box(3.5f, 3.5f, 0.30f, 0.50f, 2.2f, 2.2f, GRANITE)
    p.box(3.5f, 3.5f, 0.50f, 0.62f, 1.4f, 1.4f, GRANITE, glow = 0.4f)
    // The figures, bronze, the standard bearer in the middle and highest.
    val base = 0.62f
    figure(p, 3.5f, 3.5f, base, 1.2f, 0f)
    for (f in arrayOf(
      floatArrayOf(-0.46f, 0.08f, 0.95f, 0.3f), floatArrayOf(0.46f, -0.08f, 1.0f, -0.4f), floatArrayOf(-0.2f, -0.42f, 0.85f, 0.8f),
      floatArrayOf(0.28f, 0.42f, 0.78f, -0.2f), floatArrayOf(-0.55f, -0.34f, 0.72f, 0.5f),
    )) {
      figure(p, 3.5f + f[0], 3.5f + f[1], base, f[2], f[3])
    }
    p.box(3.5f, 4.0f, base, base + 0.17f, 0.8f, 0.22f, BRONZE, glow = 0.5f, yaw = 0.3f)
    p.box(3.62f, 3.0f, base, base + 0.17f, 0.8f, 0.22f, BRONZE, glow = 0.5f, yaw = -0.5f)
    // The raised arm, the staff and the flag on it.
    p.box(3.68f, 3.5f, base + 0.95f, base + 1.75f, 0.1f, 0.1f, BRONZE, glow = 0.7f)
    p.box(3.7f, 3.5f, base + 1.2f, base + 2.45f, 0.05f, 0.05f, STEEL, glow = 0.8f)
    p.flag(3.72f, 3.5f, base + 2.42f, 0.9f)
  }

  /** A bronze figure [h] tall on [y0]: a body narrowing to the shoulders, and a head. */
  private fun figure(p: Place, u: Float, v: Float, y0: Float, h: Float, yaw: Float) {
    p.box(u, v, y0, y0 + h * 0.84f, 0.3f, 0.22f, BRONZE, glow = 0.6f, yaw = yaw, taper = 0.8f)
    p.box(u, v, y0 + h * 0.84f, y0 + h, 0.17f, 0.17f, BRONZE, glow = 0.7f, yaw = yaw)
  }

  /** A rain tree: a short trunk and a wide, flat crown. */
  private fun rainTree(p: Place, u: Float, v: Float) {
    p.box(u, v, GROUND, GROUND + 0.55f, 0.12f, 0.12f, BRONZE)
    p.round(u, v, GROUND + 0.5f, GROUND + 0.85f, 0.56f, 0.62f, LEAF)
    p.round(u, v, GROUND + 0.85f, GROUND + 1.02f, 0.6f, 0.28f, LEAF)
  }

  /** A rounder tree. */
  private fun roundTree(p: Place, u: Float, v: Float) {
    p.box(u, v, GROUND, GROUND + 0.42f, 0.1f, 0.1f, BRONZE)
    p.round(u, v, GROUND + 0.35f, GROUND + 0.78f, 0.38f, 0.55f, LEAF)
    p.round(u, v, GROUND + 0.78f, GROUND + 1.2f, 0.55f, 0.14f, LEAF)
  }

  /** A palm: a tall slim trunk and a star of long fronds, turned by [turn]. */
  private fun palm(p: Place, u: Float, v: Float, turn: Float) {
    val top = GROUND + 1.5f
    p.box(u, v, GROUND, top, 0.09f, 0.09f, BRONZE, taper = 0.7f)
    for (k in 0 until 4) {
      p.box(u, v, top - 0.05f, top + 0.03f, 1.05f, 0.13f, LEAF, yaw = turn * 3f + k * 0.785f)
    }
    p.box(u, v, top, top + 0.14f, 0.2f, 0.2f, LEAF)
  }

  // -----------------------------------------------------------------------------------------------
  // The mall on Bukit Bintang
  // -----------------------------------------------------------------------------------------------

  /**
   * The mall fills its finder, its modules standing [MALL] high, all but the plaza in the middle
   * of its south side, which faces the city. On the street stand the two cubes of its facade,
   * either side of the plaza: white lattice below, champagne fins above with a band of glass
   * across them. Between them, set back, the glass entrance under its name, the canopy, and on
   * the plaza the crystal fountain, three bowls of rose crystal over a pool ringed by posts. Behind,
   * the body roofs the finder's gap, and the two blue residence towers stand on it.
   *
   * After dark the fins' edges glow warm gold and the lattice's lines and the name cool white, like
   * neon: softly, and in those two colours only (the shader's NEON_WARM and NEON_COOL).
   */
  private fun mall(p: Place, n: Int) {
    val roof = MALL + 0.02f
    // The body behind the cubes, and the atrium between them, its glass the entrance.
    // The body ends where the cubes begin, so their side walls never overlap on one plane.
    p.box(3.5f, (CUBES_FROM - 0.02f) / 2f, 0f, roof, 7.02f, CUBES_FROM, CONCRETE, glow = 0.15f)
    p.box(3.5f, (CUBES_FROM + ENTRANCE) / 2f, 0f, ENTRANCE_TOP, EAST_CUBE - WEST_CUBE, ENTRANCE - CUBES_FROM, GLASS, glow = 0.3f, plain = false)
    p.box(3.5f, (CUBES_FROM + ENTRANCE) / 2f, ENTRANCE_TOP, ENTRANCE_TOP + 0.04f, EAST_CUBE - WEST_CUBE + 0.04f, ENTRANCE - CUBES_FROM + 0.04f, CONCRETE, glow = 0.2f)
    for ((u0, u1) in listOf(0f to WEST_CUBE, EAST_CUBE to 7f)) cube(p, u0, u1)
    // The name on a dark band over the entrance, its letters cool white; the glass canopy below.
    val band = ENTRANCE_TOP - 0.36f
    p.box(3.5f, ENTRANCE + 0.03f, band, ENTRANCE_TOP - 0.03f, EAST_CUBE - WEST_CUBE - 0.02f, 0.06f, SMOKE)
    name(p, ENTRANCE + 0.07f, (band + ENTRANCE_TOP - 0.03f) / 2f)
    p.box(3.5f, ENTRANCE + 0.17f, 0.62f, 0.66f, EAST_CUBE - WEST_CUBE - 0.1f, 0.34f, STEEL, glow = 0.3f)
    // The plaza's paving over its low modules, and the crystal fountain on it.
    val y = GROUND
    // A hair past the street edge, in front of the modules' walls rather than on their plane.
    p.box(3.5f, (ENTRANCE + 7.01f) / 2f, 0f, y, EAST_CUBE - WEST_CUBE, 7.01f - ENTRANCE, GRANITE)
    p.round(3.5f, 6.4f, y, y + 0.08f, 0.5f, 0.5f, WATER)
    for (k in 0 until 8) {
      val a = k * 0.785398f
      p.box(3.5f + 0.6f * StrictMath.cos(a.toDouble()).toFloat(), 6.4f + 0.42f * StrictMath.sin(a.toDouble()).toFloat(), y, y + 0.16f, 0.04f, 0.04f, RED, glow = 0.5f)
    }
    p.round(3.5f, 6.4f, y + 0.08f, y + 1.2f, 0.07f, 0.05f, STEEL, glow = 0.6f)
    p.round(3.5f, 6.4f, y + 0.22f, y + 0.46f, 0.16f, 0.5f, CRYSTAL, glow = 0.9f)
    p.round(3.5f, 6.4f, y + 0.6f, y + 0.8f, 0.12f, 0.38f, CRYSTAL, glow = 0.95f)
    p.round(3.5f, 6.4f, y + 0.92f, y + 1.08f, 0.09f, 0.26f, CRYSTAL, glow = 1f)
    // Two legs for the billboard's screen on the east cube's roof; the screen is the renderer's.
    for (u in floatArrayOf(5.0f, 6.45f)) p.box(u, 5.4f, CUBE + 0.03f, CUBE + 0.35f, 0.07f, 0.07f, STEEL, glow = 0.4f)
    // The residence towers on the body, the western one taller, each with a stepped crown.
    val (west, east) = residences(n).let { it[0] to it[1] }
    tower(p, RESIDENCES[0], RESIDENCES[1], roof, roof + west)
    tower(p, RESIDENCES[2], RESIDENCES[3], roof, roof + east)
  }

  /** Where the residence towers stand on the mall, west then east: u and v of each. */
  private val RESIDENCES = floatArrayOf(2.0f, 1.7f, 5.0f, 2.0f)

  /**
   * Raises [skyline], a height for each module row by row, over the modules the mall's residence
   * towers stand on, for what they hide from the camera.
   */
  fun residencesOver(skyline: FloatArray, n: Int) {
    val heights = residences(n)
    for (i in 0 until 2) {
      val u = n - 7 + RESIDENCES[i * 2]
      val v = RESIDENCES[i * 2 + 1]
      for (row in (v - 0.65f).toInt()..(v + 0.65f).toInt()) {
        for (col in (u - 0.75f).toInt()..(u + 0.75f).toInt()) {
          skyline[row * n + col] = max(skyline[row * n + col], MALL + heights[i])
        }
      }
    }
  }

  /**
   * One cube of the facade from [u0] to [u1], from the body to the street: a lattice base, fins
   * above with a band of glass across them, set in a little, and a thin parapet.
   */
  private fun cube(p: Place, u0: Float, u1: Float) {
    val u = (u0 + u1) / 2f
    val v = (CUBES_FROM + 7f) / 2f
    val su = u1 - u0 + 0.02f
    val sv = 7f - CUBES_FROM + 0.02f
    val base = CUBE * 0.4f
    val glass = CUBE * 0.67f
    p.box(u, v, 0f, base, su, sv, LATTICE, glow = 0.15f)
    p.box(u, v, base, glass, su, sv, CHAMPAGNE, glow = 0.12f)
    p.box(u, v, glass, glass + 0.15f, su - 0.03f, sv - 0.03f, GLASS, glow = 0.3f, plain = false)
    p.box(u, v, glass + 0.15f, CUBE, su, sv, CHAMPAGNE, glow = 0.12f)
    p.box(u, v, CUBE, CUBE + 0.03f, su + 0.02f, sv + 0.02f, CONCRETE, glow = 0.2f)
  }

  /** The mall's name, three by five pixels a letter, a pixel between letters. */
  private val NAME =
    listOf(
      arrayOf("XXX", "X.X", "XXX", "X..", "X.."),
      arrayOf(".X.", "X.X", "XXX", "X.X", "X.X"),
      arrayOf("X.X", "X.X", "X.X", ".X.", ".X."),
      arrayOf("XXX", ".X.", ".X.", ".X.", "XXX"),
      arrayOf("X..", "X..", "X..", "X..", "XXX"),
      arrayOf("XXX", ".X.", ".X.", ".X.", "XXX"),
      arrayOf("XXX", "X.X", "X.X", "X.X", "XXX"),
      arrayOf("XX.", "X.X", "X.X", "X.X", "X.X"),
    )

  /** The name in lit pixels across the entrance, its face at [v], centred on height [y]. */
  private fun name(p: Place, v: Float, y: Float) {
    val columns = NAME.size * 4 - 1
    val pitch = (EAST_CUBE - WEST_CUBE - 0.2f) / columns
    for ((k, letter) in NAME.withIndex()) for (row in 0 until 5) for (col in 0 until 3) {
      if (letter[row][col] != 'X') continue
      val u = 3.5f + (k * 4 + col - (columns - 1) / 2f) * pitch
      val h = y + (2 - row) * pitch
      p.box(u, v, h - pitch * 0.42f, h + pitch * 0.42f, pitch * 0.84f, 0.02f, NEON_COOL, glow = 1f)
    }
  }

  /** A residence tower of blue glass, its floors in bands, stepping in at the top. */
  private fun tower(p: Place, u: Float, v: Float, y0: Float, y1: Float) {
    p.box(u, v, y0, y1, 1.5f, 1.3f, BLUE_GLASS, glow = 0.5f, plain = false)
    p.box(u, v, y1, y1 + 0.18f, 1.3f, 1.1f, BLUE_GLASS, glow = 0.7f, plain = false)
    p.box(u, v, y1 + 0.18f, y1 + 0.26f, 1.36f, 1.16f, CONCRETE, glow = 0.8f)
  }

  // -----------------------------------------------------------------------------------------------
  // The independence square
  // -----------------------------------------------------------------------------------------------

  /**
   * The padang: lawn on the ring and the core, the promenade the finder's gap. On the core, the
   * flagpole with the national flag waving; along the far side, the old secretariat in red brick
   * with white bands, its clock tower and copper onion domes (on the real square it stands on the
   * east side; here it lines the north, so it hides none of the lawn); along the west, the club
   * house in white and dark timber under its hipped roof.
   */
  private fun square(p: Place, n: Int) {
    p.ring(0f, GROUND, LAWN)
    p.box(3.5f, 3.5f, 0f, GROUND, 3.02f, 3.02f, LAWN)
    // Mown stripes across the padang.
    for (k in 0 until 3) p.box(2.25f + k, 3.5f, GROUND, GROUND + 0.012f, 0.5f, 2.9f, LEAF)
    val h = flagpole(n)
    p.box(3.5f, 3.5f, GROUND, GROUND + 0.18f, 0.5f, 0.5f, GRANITE, glow = 0.4f)
    p.box(3.5f, 3.5f, GROUND + 0.18f, h, 0.07f, 0.07f, STEEL, glow = 1f, taper = 0.6f)
    p.box(3.5f, 3.5f, h, h + 0.07f, 0.11f, 0.11f, STEEL, glow = 1f)
    p.flag(3.53f, 3.5f, h - 0.02f, 1.3f)
    secretariat(p)
    // The club house.
    p.box(0.5f, 3.8f, GROUND, 0.55f, 0.8f, 3.0f, CONCRETE, glow = 0.4f)
    for (y in floatArrayOf(0.30f, 0.50f)) p.box(0.5f, 3.8f, y, y + 0.03f, 0.82f, 3.02f, SMOKE)
    p.box(0.5f, 3.8f, 0.55f, 0.85f, 0.9f, 3.1f, SMOKE, taper = 0.45f)
  }

  /** The secretariat along the ring's north side: a long brick block, a clock tower, and domed towers at its ends. */
  private fun secretariat(p: Place) {
    val v = 0.5f
    // The brick stops inside the cornice and the caps over it: a roof on the same plane as another
    // flickers between the two.
    p.box(3.5f, v, GROUND, 0.82f, 6.4f, 0.8f, BRICK, glow = 0.3f, plain = false)
    for (y in floatArrayOf(0.40f, 0.80f)) p.box(3.5f, v, y, y + 0.05f, 6.44f, 0.84f, CONCRETE, glow = 0.4f)
    for (u in floatArrayOf(2.2f, 4.8f)) dome(p, u, v, 0.85f, 0.18f)
    for (u in floatArrayOf(0.9f, 6.1f)) {
      p.box(u, v, GROUND, 1.32f, 0.45f, 0.45f, BRICK, glow = 0.4f, plain = false)
      p.box(u, v, 1.30f, 1.35f, 0.5f, 0.5f, CONCRETE, glow = 0.5f)
      dome(p, u, v, 1.35f, 0.26f)
    }
    // The clock tower, its faces on all four sides.
    p.box(3.5f, v, GROUND, 1.90f, 0.6f, 0.6f, BRICK, glow = 0.5f, plain = false)
    p.box(3.5f, v, 1.55f, 1.87f, 0.32f, 0.64f, CONCRETE, glow = 0.9f)
    p.box(3.5f, v, 1.55f, 1.87f, 0.64f, 0.32f, CONCRETE, glow = 0.9f)
    p.box(3.5f, v, 1.88f, 1.92f, 0.66f, 0.66f, CONCRETE, glow = 0.6f)
    dome(p, 3.5f, v, 1.92f, 0.36f)
  }

  /** An onion dome of radius [r] on [y]: it swells, then draws in to a point, with a finial. */
  private fun dome(p: Place, u: Float, v: Float, y: Float, r: Float) {
    p.round(u, v, y, y + r * 0.3f, r * 0.85f, r, COPPER, glow = 0.8f)
    p.round(u, v, y + r * 0.3f, y + r * 0.8f, r, r * 0.8f, COPPER, glow = 0.85f)
    p.round(u, v, y + r * 0.8f, y + r * 1.3f, r * 0.8f, r * 0.15f, COPPER, glow = 0.9f)
    p.box(u, v, y + r * 1.3f, y + r * 2f, 0.03f, 0.03f, STEEL, glow = 1f)
  }

  // -----------------------------------------------------------------------------------------------
  // Bukit Bintang's crossing
  // -----------------------------------------------------------------------------------------------

  /**
   * The junction with lights nearest the plaza in front of the mall that the monorail ([rail], its
   * track) does not pass over and no landmark of [landmarks] crowds, which becomes the crossing;
   * -1 if there is none.
   */
  fun crossing(signals: Signals, n: Int, rail: Route?, landmarks: LandmarkPlan?): Int {
    var best = -1
    var nearest = Float.MAX_VALUE
    for (j in signals.offsets.indices) {
      val col = signals.cells[j * 2] + 0.5f
      val row = signals.cells[j * 2 + 1] + 0.5f
      val dx = col - (n - 3.5f)
      val dz = row - 9f
      if (rail != null && overhead(rail, col - n / 2f, row - n / 2f)) continue
      val crowded = landmarks?.all?.any { f -> kotlin.math.abs(f.centerX - col) < f.size / 2f + 2.5f && kotlin.math.abs(f.centerZ - row) < f.size / 2f + 2.5f } == true
      if (crowded) continue
      if (dx * dx + dz * dz < nearest) {
        nearest = dx * dx + dz * dz
        best = j
      }
    }
    return best
  }

  /** Whether the track passes within about two modules of the point ([x], [z]): its curves and stations reach that far. */
  private fun overhead(rail: Route, x: Float, z: Float): Boolean {
    for (i in 0 until rail.count) {
      val dx = rail.x(i) - x
      val dz = rail.z(i) - z
      if (dx * dx + dz * dz < 2.2f * 2.2f) return true
    }
    return false
  }

  /**
   * The module, row by row, the restaurant takes on the crossing's corner: an ordinary dark module
   * diagonal to the junction, the one away from the camera if it can, so it hides none of the
   * crossing and its sign stands up behind it; -1 if none will do.
   */
  fun restaurant(signals: Signals, matrix: QrMatrix, landmarks: LandmarkPlan?, rail: Route?): Int {
    val n = matrix.size
    val j = crossing(signals, n, rail, landmarks)
    if (j < 0) return -1
    for ((dc, dr) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
      val c = signals.cells[j * 2] + dc
      val r = signals.cells[j * 2 + 1] + dr
      if (c !in 0 until n || r !in 0 until n || !matrix.isDark(c, r)) continue
      val role = matrix.role(c, r)
      if (role != QrRole.DATA && role != QrRole.REMAINDER) continue
      if (landmarks?.covers(c, r) == true || LandmarkPlan.inCorner(c, r, n)) continue
      return r * n + c
    }
    return -1
  }

  /**
   * The crossing, once the city's lights are known: zebra stripes across every arm that is a
   * street, just short of the junction, and along both diagonals across it, the way people cross
   * every way at once; and the restaurant on its corner (module [restaurant], or none if -1).
   */
  fun crossing(out: Instances, signals: Signals, matrix: QrMatrix, landmarks: LandmarkPlan?, restaurant: Int, rail: Route?) {
    val n = matrix.size
    val half = n / 2f
    val j = crossing(signals, n, rail, landmarks)
    if (j < 0) return
    val col = signals.cells[j * 2]
    val row = signals.cells[j * 2 + 1]
    val cx = col + 0.5f - half
    val cz = row + 0.5f - half
    val white = KualaLumpur.colour(CONCRETE, plain = true)
    val asphalt = KualaLumpur.colour(SMOKE, plain = true)
    fun stripe(x: Float, z: Float, sx: Float, sz: Float, yaw: Float) {
      out.put(x, 0.08f, z, sx, 0.01f, sz, CityPlanner.ROLE_LANDMARK, 0f, 0f, white, yaw, 1f)
    }
    // Dark asphalt over the paving of the junction and its arms, so the white stripes show.
    out.put(cx, 0.0725f, cz, 1f, 0.005f, 1f, CityPlanner.ROLE_LANDMARK, 0f, 0f, asphalt, 0f, 1f)
    for ((dc, dr) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
      val c = col + dc
      val r = row + dr
      if (c !in 0 until n || r !in 0 until n || matrix.isDark(c, r)) continue
      out.put(cx + dc, 0.0725f, cz + dr, 1f, 0.005f, 1f, CityPlanner.ROLE_LANDMARK, 0f, 0f, asphalt, 0f, 1f)
      for (k in 0 until 5) {
        val t = 0.6f + k * 0.16f
        if (dc != 0) stripe(cx + dc * t, cz, 0.08f, 0.8f, 0f) else stripe(cx, cz + dr * t, 0.8f, 0.08f, 0f)
      }
    }
    for (k in -2..2) for (sign in floatArrayOf(1f, -1f)) {
      // Each stripe lies across its diagonal: turned an eighth of a turn one way or the other.
      stripe(cx + k * 0.15f, cz + k * 0.15f * sign, 0.07f, 0.34f, sign * EIGHTH)
    }
    if (restaurant >= 0) restaurant(out, restaurant % n + 0.5f - half, restaurant / n + 0.5f - half)
  }

  private const val EIGHTH = 0.7853982f

  /** The camera's usual bearing from the city's middle. */
  private const val FACING = CityPlanner.VIEW_YAW

  /** The restaurant's sign: a yellow M of two arches, row by row from the top, in five by five pixels. */
  private val SIGN = arrayOf(".X.X.", "X.X.X", "X.X.X", "X.X.X", "X.X.X")

  /**
   * The fast-food restaurant at ([x], [z]) on the crossing's corner, two floors: a glass shop
   * front under a red facade with a yellow trim, and on its roof a pole sign, a red board with the
   * [SIGN] in yellow pixels on both faces.
   */
  private fun restaurant(out: Instances, x: Float, z: Float) {
    fun box(y0: Float, y1: Float, sx: Float, sz: Float, colour: Int, glow: Float, plain: Boolean = true) {
      out.put(x, (y0 + y1) / 2f, z, sx, y1 - y0, sz, CityPlanner.ROLE_LANDMARK, glow, 0f, KualaLumpur.colour(colour, plain), 0f, 1f)
    }
    box(0f, 0.3f, 0.98f, 0.98f, GLASS, 0.5f, plain = false)
    box(0.3f, RESTAURANT + 0.02f, 0.98f, 0.98f, RED, 0.5f)
    box(RESTAURANT - 0.06f, RESTAURANT + 0.01f, 1.0f, 1.0f, FLAG_YELLOW, 0.6f)
    val top = RESTAURANT + 1.05f
    box(RESTAURANT, top, 0.07f, 0.07f, STEEL, 0.6f)
    // The board faces the camera's usual side of the city, so the sign reads from across it: a box
    // turned by yaw has its face toward (-sin yaw, cos yaw) and its width along (cos yaw, sin yaw).
    val yaw = -FACING
    val ux = -StrictMath.sin(yaw.toDouble()).toFloat()
    val uz = StrictMath.cos(yaw.toDouble()).toFloat()
    val rx = StrictMath.cos(yaw.toDouble()).toFloat()
    val rz = StrictMath.sin(yaw.toDouble()).toFloat()
    val board = top - 0.3f
    out.put(x, board, z, 0.62f, 0.56f, 0.06f, CityPlanner.ROLE_LANDMARK, 0.8f, 0f, KualaLumpur.colour(RED, plain = true), yaw, 1f)
    val yellow = KualaLumpur.colour(FLAG_YELLOW, plain = true)
    for (gy in 0 until 5) for (gx in 0 until 5) {
      if (SIGN[gy][gx] != 'X') continue
      for (side in floatArrayOf(1f, -1f)) {
        val across = (gx - 2) * 0.095f
        out.put(
          x + rx * across + ux * side * 0.035f, board + (2 - gy) * 0.095f, z + rz * across + uz * side * 0.035f,
          0.085f, 0.085f, 0.02f, CityPlanner.ROLE_LANDMARK, 1f, 0f, yellow, yaw, 1f,
        )
      }
    }
  }

  /** 0..1 from a module of a corner and a salt: the same garden for every city. */
  private fun unit(u: Int, v: Int, salt: Int): Float {
    var h = salt * 374761393 + u * 668265263 + v * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0x7fffffff) / 2147483647f
  }
}
