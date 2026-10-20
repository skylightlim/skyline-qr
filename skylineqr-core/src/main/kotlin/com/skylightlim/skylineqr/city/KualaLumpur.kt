package com.skylightlim.skylineqr.city

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

/**
 * Kuala Lumpur's three landmarks, built from boxes that can turn and taper: a square and the same
 * square turned 45 degrees make the eight-pointed star the twin towers are planned on, stacked
 * tapering boxes make setbacks, crowns and spires, a square and a turned square that trade places
 * make the supertall's folding facets, and four squares turned a sixteenth of a turn apart make a
 * round section, stacked into the telecom tower's head. Names stay generic in code: twin towers,
 * supertall, telecom.
 *
 * Every part stands on top of the landmark's own footprint of dark modules and only exists in the
 * 3D city: as the city flattens they shrink away, leaving the modules underneath to the code.
 */
internal object KualaLumpur {

  /** Landmark colours, in the order of the shader's landmark palette. */
  const val STEEL = 0
  const val GLASS = 1
  const val CONCRETE = 2
  const val HILL = 3
  const val COPPER = 4
  const val RED = 5
  const val SMOKE = 6
  const val BLUE_GLASS = 7

  /** The fixed materials of the KL preset's ordinary towers: glass, steel and cream render. */
  val materials =
    MaterialRecipe(
      "Kuala Lumpur",
      floatArrayOf(*Materials.rgb(0x8FA3B8), *Materials.rgb(0x3F6FA8), *Materials.rgb(0xE6DCC8), *Materials.rgb(0x3E8E8C)),
      Materials.rgb(0xD9473A),
      // Stainless steel, blue-silver glass, white concrete, the green of the telecom tower's hill,
      // the copper of its bowl, the red of its mast, the smoked glass of its decks, and the
      // supertall's deeper blue glass.
      floatArrayOf(
        *Materials.rgb(0xC9CED4), *Materials.rgb(0x8FAACB), *Materials.rgb(0xECEBE6), *Materials.rgb(0x4E8B46),
        *Materials.rgb(0xD08A5C), *Materials.rgb(0xC9402F), *Materials.rgb(0x3E4550), *Materials.rgb(0x52719C),
      ),
    )

  /** Twin tower height for a QR [modules] wide: the other landmarks follow in true proportion. */
  fun twinHeight(modules: Int) = max(10f, modules * 0.42f)

  /** 679 m against the twin towers' 452 m. */
  fun supertallHeight(modules: Int) = twinHeight(modules) * 679f / 452f

  /** 421 m against 452 m. */
  fun telecomHeight(modules: Int) = twinHeight(modules) * 421f / 452f

  /** Ordinary towers stay under about 60 % of the twin towers, so the landmarks own the skyline. */
  fun towerCap(modules: Int) = twinHeight(modules) * 0.6f

  /** Landmarks rise from two floors of podium on their own modules. */
  const val PODIUM = 2 * CityPlanner.FLOOR

  /** The monorail runs at this height above the street. */
  const val RAIL_Y = 2.4f

  private const val EIGHTH = (PI / 4).toFloat()
  private const val SIXTEENTH = (PI / 8).toFloat()

  /** One round section of the telecom tower's head; see [HEAD]. */
  private class Section(val y0: Float, val y1: Float, val r0: Float, val r1: Float, val colour: Int)

  /**
   * The telecom tower's head in profile, after photographs, from the collar on the shaft up: each
   * round section's bottom and top and its radius at each, in units of the head's widest radius.
   * A copper bowl flares like a trumpet; four tiers of dark windows under white ledges widen
   * upward to the widest ledge, like a spinning top; then a dark band and the white drum of the
   * observation deck under its roof; then a cylinder with two discs, and a red ring for the mast.
   */
  private val HEAD =
    listOf(
      Section(0.00f, 0.12f, 0.31f, 0.31f, CONCRETE),
      Section(0.12f, 0.30f, 0.30f, 0.36f, COPPER),
      Section(0.30f, 0.45f, 0.36f, 0.46f, COPPER),
      Section(0.45f, 0.57f, 0.46f, 0.57f, COPPER),
      Section(0.57f, 0.65f, 0.57f, 0.67f, COPPER),
      Section(0.65f, 0.74f, 0.69f, 0.70f, CONCRETE),
      Section(0.74f, 0.90f, 0.65f, 0.74f, SMOKE),
      Section(0.90f, 0.99f, 0.80f, 0.81f, CONCRETE),
      Section(0.99f, 1.15f, 0.76f, 0.83f, SMOKE),
      Section(1.15f, 1.24f, 0.87f, 0.88f, CONCRETE),
      Section(1.24f, 1.40f, 0.83f, 0.90f, SMOKE),
      Section(1.40f, 1.49f, 0.94f, 0.95f, CONCRETE),
      Section(1.49f, 1.65f, 0.90f, 0.95f, SMOKE),
      Section(1.65f, 1.75f, 1.00f, 1.00f, CONCRETE),
      Section(1.75f, 1.86f, 0.93f, 0.90f, SMOKE),
      Section(1.86f, 2.10f, 0.80f, 0.80f, CONCRETE),
      Section(2.10f, 2.14f, 0.83f, 0.83f, STEEL),
      Section(2.14f, 2.47f, 0.30f, 0.30f, CONCRETE),
      Section(2.47f, 2.58f, 0.30f, 0.38f, CONCRETE),
      Section(2.58f, 2.70f, 0.27f, 0.27f, CONCRETE),
      Section(2.70f, 2.80f, 0.27f, 0.36f, CONCRETE),
      Section(2.80f, 3.05f, 0.22f, 0.20f, CONCRETE),
      Section(3.05f, 3.10f, 0.16f, 0.16f, RED),
    )

  /** The top of the observation deck's roof in [HEAD]: 335 m of the tower's 421. */
  private const val DECK_TOP = 2.14f

  /** A square's side for a round section of mean radius 1: four turned squares reach 0.60 to 0.71 of it. */
  private const val ROUND_SIDE = 1.53f

  fun build(out: Instances, plan: LandmarkPlan, modules: Int) {
    val half = modules / 2f
    plan.supertall?.let { supertall(out, it.centerX - half, it.centerZ - half, supertallHeight(modules), it.size) }
    plan.twins?.let { (a, b) ->
      val h = twinHeight(modules)
      val k = a.size / 2f
      twin(out, a.centerX - half, a.centerZ - half, h, k)
      twin(out, b.centerX - half, b.centerZ - half, h, k)
      skybridge(out, a, b, half, h, k)
    }
    plan.telecom?.let { telecom(out, it.centerX - half, it.centerZ - half, telecomHeight(modules), it.size) }
  }

  /** The twin tower: tiers of the eight-pointed star, a crown, and a pinnacle. */
  private fun twin(out: Instances, x: Float, z: Float, h: Float, k: Float) {
    star(out, x, z, PODIUM, h * 0.60f, 1.80f * k, STEEL, glow = 0.55f)
    star(out, x, z, h * 0.60f, h * 0.71f, 1.58f * k, STEEL, glow = 0.6f)
    star(out, x, z, h * 0.71f, h * 0.785f, 1.36f * k, STEEL, glow = 0.65f)
    star(out, x, z, h * 0.785f, h * 0.835f, 1.10f * k, STEEL, taper = 0.85f, glow = 0.7f)
    star(out, x, z, h * 0.835f, h * 0.885f, 0.88f * k, STEEL, taper = 0.35f, glow = 0.8f)
    part(out, x, z, h * 0.885f, h, 0.14f * k, 0f, 0.15f, STEEL, glow = 1f)
  }

  /** The bridge between the twins, low on the towers, across the street between their footprints. */
  private fun skybridge(out: Instances, a: Footprint, b: Footprint, half: Float, h: Float, k: Float) {
    val ax = a.centerX - half
    val az = a.centerZ - half
    val bx = b.centerX - half
    val bz = b.centerZ - half
    val alongX = a.z == b.z
    val span = (if (alongX) bx - ax else bz - az) - 1.4f * k
    val y = h * 0.38f
    val sx = if (alongX) span else 0.36f * k
    val sz = if (alongX) 0.36f * k else span
    out.put((ax + bx) / 2f, y, (az + bz) / 2f, sx, 0.34f * k, sz, CityPlanner.ROLE_LANDMARK, 0.9f, 0f, colour(STEEL))
  }

  /**
   * The supertall, after photographs and a 3D model: a slender prism of blue glass, about seven
   * times as tall as it is wide and narrowing only a little, in three segments that fold at two
   * bands. In each, a turned square inside the square pushes its corners out through the faces
   * toward one end and lies flush at the other, alternately, so every face carries long triangles
   * that meet in diamonds across the bands. The crown slopes up toward one side and narrows to
   * about half its width there, and the spire, a fifth of the whole, rises from that high edge,
   * thicker over its first third.
   */
  private fun supertall(out: Instances, x: Float, z: Float, h: Float, size: Int) {
    val top = h * 0.79f
    val w = min(size * 0.9f, (top - PODIUM) / 7.2f)
    val drop = 0.7f * w
    val roof = top - drop
    val body = roof - PODIUM
    fun side(t: Float) = w * (1f - 0.12f * t)
    for (i in 0 until 3) {
      val t0 = i / 3f
      val t1 = (i + 1) / 3f
      // The turned square lies flush with the faces at 0.71 of the side and pushes out at 0.9.
      val b0 = side(t0) * if (i % 2 == 0) 0.71f else 0.9f
      val b1 = side(t1) * if (i % 2 == 0) 0.9f else 0.71f
      val y0 = PODIUM + body * t0
      val y1 = PODIUM + body * t1
      val glow = 0.4f + 0.4f * t1
      part(out, x, z, y0, y1, side(t0), 0f, side(t1) / side(t0), BLUE_GLASS, glow)
      part(out, x, z, y0, y1, b0, EIGHTH, b1 / b0, BLUE_GLASS, glow)
    }
    // The crown in steps that climb toward the spire's side, each shorter and shallower.
    val a = side(1f)
    val steps = 6
    for (i in 0 until steps) {
      val along = a * (1f - (i + 0.5f) / steps)
      val across = a * (1f - 0.45f * (i + 1) / steps)
      out.put(
        x + (a - along) / 2f, roof + drop * (i + 0.5f) / steps, z, along, drop / steps, across,
        CityPlanner.ROLE_LANDMARK, 0.85f, 0f, colour(BLUE_GLASS),
      )
    }
    val sx = x + a / 2f - 0.12f * a
    val spire = h - top
    star(out, sx, z, top - drop / steps, top + spire * 0.35f, 0.24f * w, STEEL, taper = 0.5f, glow = 0.9f, plain = true)
    part(out, sx, z, top + spire * 0.35f, h, 0.12f * w, EIGHTH, 0.05f, STEEL, glow = 1f, plain = true)
  }

  /**
   * The telecom tower on its hill: a slender shaft flaring at its foot, the head in [HEAD], and the
   * lattice mast in bands of red and white, thinning to a needle.
   */
  private fun telecom(out: Instances, x: Float, z: Float, h: Float, size: Int) {
    val k = if (size >= 2) 1f else 0.78f
    val hill = size + 1.6f
    part(out, x, z, 0f, 0.9f, hill, 0f, 0.55f, HILL, glow = 0f, plain = true)
    part(out, x, z, 0f, 0.9f, hill * 0.9f, EIGHTH, 0.55f, HILL, glow = 0f, plain = true)
    // The head is drawn a little larger than life, or it would be lost in the city.
    val r = min(0.08f * h, 1.25f * k)
    val collar = h * 0.796f - DECK_TOP * r
    round(out, x, z, PODIUM, PODIUM + 0.04f * h, 0.50f * r, 0.36f * r, CONCRETE, glow = 0.3f)
    round(out, x, z, PODIUM + 0.04f * h, collar, 0.36f * r, 0.29f * r, CONCRETE, glow = 0.4f)
    for (s in HEAD) {
      round(out, x, z, collar + s.y0 * r, collar + s.y1 * r, s.r0 * r, s.r1 * r, s.colour, glow = 0.6f + 0.35f * s.y1 / HEAD.last().y1)
    }
    val base = collar + HEAD.last().y1 * r
    val mast = h - base
    for (i in 0 until 6) {
      val y0 = base + mast * 0.72f * i / 6f
      val y1 = base + mast * 0.72f * (i + 1) / 6f
      val s0 = r * (0.20f - 0.12f * i / 6f)
      val s1 = r * (0.20f - 0.12f * (i + 1) / 6f)
      part(out, x, z, y0, y1, s0, 0f, s1 / s0, if (i % 2 == 0) RED else STEEL, glow = 1f, plain = true)
    }
    part(out, x, z, base + mast * 0.72f, h, r * 0.06f, 0f, 0.2f, STEEL, glow = 1f, plain = true)
  }

  /**
   * The monorail's track along [route]: a beam that bends with it, a pillar on every other module
   * of the street below ([cells], world centres), and a station at each end. Everything stays
   * within the street's own width, so the line never touches a building.
   */
  fun track(out: Instances, route: Route, cells: List<Pair<Float, Float>>) {
    for (i in 0 until route.count - 1) {
      val dx = route.x(i + 1) - route.x(i)
      val dz = route.z(i + 1) - route.z(i)
      val length = kotlin.math.sqrt(dx * dx + dz * dz)
      if (length < 1e-4f) continue
      val yaw = StrictMath.atan2(dz.toDouble(), dx.toDouble()).toFloat()
      out.put(
        (route.x(i) + route.x(i + 1)) / 2f, RAIL_Y, (route.z(i) + route.z(i + 1)) / 2f,
        length + 0.03f, 0.22f, 0.30f,
        CityPlanner.ROLE_RAIL, 0f, 0f, colour(CONCRETE), yaw,
      )
    }
    for (k in cells.indices step 2) {
      val (x, z) = cells[k]
      out.put(x, RAIL_Y / 2f, z, 0.16f, RAIL_Y, 0.16f, CityPlanner.ROLE_RAIL, 0f, 0f, colour(CONCRETE))
    }
    for (end in listOf(0 to 1, cells.size - 1 to cells.size - 2)) {
      val (x, z) = cells[end.first]
      val (nx, nz) = cells[end.second]
      val yaw = StrictMath.atan2((nz - z).toDouble(), (nx - x).toDouble()).toFloat()
      // A deck the train pulls in beside, and a canopy over it, on slim posts at its edges.
      out.put(x, RAIL_Y - 0.17f, z, 1.0f, 0.10f, 0.72f, CityPlanner.ROLE_RAIL, 0f, 0f, colour(CONCRETE), yaw)
      out.put(x, RAIL_Y + 0.78f, z, 1.0f, 0.06f, 0.72f, CityPlanner.ROLE_RAIL, 0f, 0f, colour(GLASS), yaw)
      for (side in floatArrayOf(-0.33f, 0.33f)) {
        // Across the track is (-sin, cos) of its heading.
        val px = x - side * StrictMath.sin(yaw.toDouble()).toFloat()
        val pz = z + side * StrictMath.cos(yaw.toDouble()).toFloat()
        out.put(px, RAIL_Y + 0.31f, pz, 0.05f, 0.85f, 0.05f, CityPlanner.ROLE_RAIL, 0f, 0f, colour(STEEL))
      }
    }
  }

  /** A square and the same square turned 45 degrees: the star plan. */
  private fun star(
    out: Instances, x: Float, z: Float, y0: Float, y1: Float, size: Float, colour: Int, taper: Float = 1f, glow: Float,
    plain: Boolean = false,
  ) {
    part(out, x, z, y0, y1, size, 0f, taper, colour, glow, plain)
    part(out, x, z, y0, y1, size, EIGHTH, taper, colour, glow, plain)
  }

  /** A round section from radius [r0] at the bottom to [r1] at the top. */
  private fun round(out: Instances, x: Float, z: Float, y0: Float, y1: Float, r0: Float, r1: Float, colour: Int, glow: Float, plain: Boolean = true) {
    for (i in 0 until 4) part(out, x, z, y0, y1, r0 * ROUND_SIDE, i * SIXTEENTH, r1 / r0, colour, glow, plain)
  }

  private fun part(
    out: Instances, x: Float, z: Float, y0: Float, y1: Float, size: Float, yaw: Float, taper: Float, colour: Int, glow: Float,
    plain: Boolean = false,
  ) {
    out.put(x, (y0 + y1) / 2f, z, size, y1 - y0, size, CityPlanner.ROLE_LANDMARK, glow, 0f, colour(colour, plain), yaw, taper)
  }

  /**
   * The shader reads a landmark's colour from its random slot, an eighth of it per colour, and
   * whether its walls have windows from where in that eighth it sits: early they do, late (plain) not.
   */
  private fun colour(index: Int, plain: Boolean = false) = (index + if (plain) 0.62f else 0.2f) / 8f
}
