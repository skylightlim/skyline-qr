package com.skylightlim.skylineqr.city

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * What each mover looks like, as boxes placed around its position and heading: cars with a glass
 * cabin, a bus with a window band, a three-car monorail train that bends through the curves, a
 * helicopter with cockpit, tail boom, skids and spinning rotors, and a plane. Body panels take the
 * city's traffic paint; glass, metal and rubber are neutral details.
 */
object Vehicles {

  /** Receives one box: centre, size, role, glow, random (or a detail's grey), yaw and taper. */
  fun interface Sink {
    fun box(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, role: Float, glow: Float, rnd: Float, yaw: Float, taper: Float)
  }

  // Greys for detail parts (the shader reads them from the random slot).
  private const val POLE = 0.16f
  private const val GLASS = 0.10f
  private const val RUBBER = 0.05f
  private const val METAL = 0.55f

  /** Emits [mover] as it is at [time] seconds. [scratch] needs four floats. */
  fun emit(mover: Mover, time: Float, sink: Sink, scratch: FloatArray) {
    when (mover.kind) {
      Mover.MONORAIL -> train(mover, time, sink, scratch)
      else -> {
        mover.at(time, scratch)
        val x = scratch[0]
        val y = scratch[1]
        val z = scratch[2]
        val heading = scratch[3]
        val part = Part(x, y, z, heading, sink)
        when (mover.kind) {
          Mover.CAR -> car(part, mover.rnd)
          Mover.BUS -> bus(part, mover.rnd)
          Mover.HELICOPTER -> helicopter(part, mover.rnd, time)
          Mover.PLANE -> {
            plane(part, mover.rnd)
            if (mover.banner) banner(part, time)
          }
        }
      }
    }
  }

  /**
   * Every traffic light at [time]: a dark metal pole and head, drawn a little larger than life like
   * the telecom tower's head or the lamps would be lost, and the lit lamp, red at the top of the
   * head, amber, green at the bottom, just proud of the face its traffic sees. The lamp's random
   * slot carries its colour.
   */
  fun signals(signals: Signals, time: Float, sink: Sink) {
    for (h in 0 until signals.count) {
      val o = h * Signals.HEAD
      val d = signals.heads
      sink.box(d[o], 0.39f, d[o + 1], 0.045f, 0.78f, 0.045f, DETAIL, 0f, POLE, 0f, 1f)
      sink.box(d[o], 0.71f, d[o + 1], 0.10f, 0.27f, 0.10f, DETAIL, 0f, POLE, 0f, 1f)
      val state = signals.state(d[o + 4].toInt(), d[o + 5].toInt(), time)
      val y = when (state) {
        Signals.RED -> 0.80f
        Signals.AMBER -> 0.71f
        else -> 0.62f
      }
      sink.box(d[o] - d[o + 2] * 0.06f, y, d[o + 1] - d[o + 3] * 0.06f, 0.075f, 0.075f, 0.075f, CityPlanner.ROLE_SIGNAL, 0f, 0.1f + 0.4f * state, 0f, 1f)
    }
  }

  /** Places boxes in a vehicle's own frame: x forward, y up, z to its right. */
  private class Part(val x: Float, val y: Float, val z: Float, val heading: Float, val sink: Sink) {
    private val c = cos(heading)
    private val s = sin(heading)

    fun box(lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float, role: Float, rnd: Float, yaw: Float = 0f, taper: Float = 1f, glow: Float = 0f) {
      sink.box(x + lx * c - lz * s, y + ly, z + lx * s + lz * c, sx, sy, sz, role, glow, rnd, heading + yaw, taper)
    }
  }

  private const val PAINT = CityPlanner.ROLE_VEHICLE
  private const val DETAIL = CityPlanner.ROLE_DETAIL

  /**
   * A vehicle's lamps (2026-11-12, at the user's request: lights on at night): beacons whose glow
   * is a half, which the shader shows after dark only, their colour by the random slot: red tail
   * lights, white headlights, and the warm light of a cabin behind its windows.
   */
  private const val TAIL = 0.1f
  private const val HEAD = 0.45f
  private const val CABIN = 0.8f

  private fun Part.lamp(lx: Float, ly: Float, lz: Float, sx: Float, sy: Float, sz: Float, kind: Float) =
    box(lx, ly, lz, sx, sy, sz, CityPlanner.ROLE_BEACON, kind, glow = 0.5f)

  /** Headlights at [front] and tail lights at [back], a pair each, [wide] apart at height [y]. */
  private fun Part.lights(front: Float, back: Float, y: Float, wide: Float) {
    for (side in floatArrayOf(-wide, wide)) {
      lamp(front, y, side, 0.03f, 0.05f, 0.08f, HEAD)
      lamp(back, y, side, 0.03f, 0.05f, 0.08f, TAIL)
    }
  }

  private fun car(p: Part, rnd: Float) {
    p.box(0f, 0.035f, 0f, 0.70f, 0.07f, 0.36f, DETAIL, RUBBER) // wheels and the shadow under them
    p.box(0f, 0.13f, 0f, 0.78f, 0.13f, 0.40f, PAINT, rnd)
    p.box(-0.05f, 0.25f, 0f, 0.42f, 0.12f, 0.36f, DETAIL, GLASS, taper = 0.78f)
    p.lights(0.39f, -0.39f, 0.14f, 0.13f)
  }

  private fun bus(p: Part, rnd: Float) {
    p.box(0f, 0.04f, 0f, 1.22f, 0.08f, 0.44f, DETAIL, RUBBER)
    p.box(0f, 0.25f, 0f, 1.35f, 0.34f, 0.48f, PAINT, rnd)
    p.box(0.02f, 0.33f, 0f, 1.31f, 0.11f, 0.50f, DETAIL, GLASS) // the window band, a hair proud of the body
    p.lamp(0.02f, 0.33f, 0f, 1.25f, 0.07f, 0.51f, CABIN)
    p.lights(0.675f, -0.675f, 0.16f, 0.16f)
  }

  /** Three cars on the beam, each at its own point of the curve, so the train bends with it. */
  private fun train(m: Mover, time: Float, sink: Sink, scratch: FloatArray) {
    val middle = m.distance(time)
    for (k in -1..1) {
      m.route.at(middle + k * CAR_GAP, scratch)
      val p = Part(scratch[0], scratch[1], scratch[2], scratch[3], sink)
      val nose = if (k == 0) 1f else 0.82f // the end cars round off toward their cabs
      p.box(0f, 0.30f, 0f, 0.92f, 0.36f, 0.34f, PAINT, m.rnd)
      p.box(0f, 0.20f, 0f, 0.925f, 0.07f, 0.345f, PAINT, STRIPE) // a band of colour along the skirt
      p.box(0f, 0.36f, 0f, 0.88f, 0.10f, 0.36f, DETAIL, GLASS)
      p.lamp(0f, 0.36f, 0f, 0.84f, 0.06f, 0.37f, CABIN)
      p.box(0f, 0.53f, 0f, 0.86f, 0.10f, 0.30f, PAINT, m.rnd, taper = nose)
      // A train runs both ways, so each end car carries a headlight on its outer end.
      if (k != 0) p.lamp(k * 0.46f, 0.27f, 0f, 0.03f, 0.06f, 0.2f, HEAD)
    }
  }

  private const val CAR_GAP = 0.98f

  /** The train's band, in the shader's blue. */
  private const val STRIPE = 0.55f

  /**
   * A helicopter: cabin with a glass nose, a tail boom narrowing to the fin and stabiliser, skids
   * on struts, and a two-blade main rotor and tail rotor that spin.
   */
  private fun helicopter(p: Part, rnd: Float, time: Float) {
    val spin = time * 24f
    p.box(0.05f, 0f, 0f, 1.05f, 0.58f, 0.62f, PAINT, rnd, taper = 0.82f) // cabin
    p.box(0.60f, -0.04f, 0f, 0.42f, 0.44f, 0.54f, DETAIL, GLASS, taper = 0.62f) // cockpit glass
    p.box(-0.42f, 0.30f, 0f, 0.42f, 0.10f, 0.40f, PAINT, rnd, taper = 0.7f) // engine housing
    p.box(-1.10f, 0.10f, 0f, 1.25f, 0.16f, 0.16f, PAINT, rnd, taper = 0.6f) // tail boom
    p.box(-1.70f, 0.32f, 0f, 0.30f, 0.44f, 0.06f, PAINT, rnd, taper = 0.55f) // fin
    p.box(-1.48f, 0.12f, 0f, 0.18f, 0.04f, 0.56f, PAINT, rnd) // stabiliser
    for (side in floatArrayOf(-0.32f, 0.32f)) {
      p.box(0.05f, -0.47f, side, 1.30f, 0.05f, 0.05f, DETAIL, METAL) // skid
      for (along in floatArrayOf(-0.25f, 0.35f)) p.box(along, -0.37f, side, 0.05f, 0.18f, 0.05f, DETAIL, METAL)
    }
    p.box(0f, 0.37f, 0f, 0.10f, 0.16f, 0.10f, DETAIL, METAL) // mast
    for (blade in floatArrayOf(0f, 1.5708f)) p.box(0f, 0.46f, 0f, 3.0f, 0.03f, 0.13f, DETAIL, RUBBER, yaw = spin + blade)
    // The tail rotor turns across the boom: drawn as a blade whose height pulses as it spins.
    p.box(-1.76f, 0.30f, 0.07f, 0.06f, 0.10f + 0.36f * abs(cos(spin * 1.7f)), 0.03f, DETAIL, RUBBER)
    p.box(-1.86f, 0.55f, 0f, 0.07f, 0.07f, 0.07f, CityPlanner.ROLE_BEACON, rnd) // anti-collision light
    p.lamp(0.80f, -0.20f, 0f, 0.06f, 0.08f, 0.14f, HEAD) // the landing light under its nose
  }

  /**
   * Kuala Lumpur's flag (2026-11-09, at the user's request), towed on a line behind the plane like
   * an advertising banner, after the public-domain drawing on Wikimedia Commons ("Flag of Kuala
   * Lumpur, Malaysia.svg"): white, three thin red stripes above and three below a blue band across
   * the middle, and on the band near the hoist a yellow crescent round a star. It hangs from a
   * spar and waves, still at the hoist and most at the fly; each of its upright slices turns with
   * the wave, so neighbouring slices meet.
   */
  private fun banner(p: Part, time: Float) {
    val hoist = -BANNER_FROM
    val mid = -0.25f
    p.box((-1.62f + hoist) / 2f, 0.02f, 0f, -1.62f - hoist, 0.025f, 0.025f, DETAIL, METAL) // the tow line
    p.box(hoist, mid, 0f, 0.05f, BANNER_HIGH + 0.06f, 0.05f, DETAIL, METAL) // the spar
    val slice = BANNER_LONG / BANNER_SLICES
    val white = KualaLumpur.colour(KualaLumpur.FLAG_WHITE, plain = true)
    val red = KualaLumpur.colour(KualaLumpur.FLAG_RED, plain = true)
    val blue = KualaLumpur.colour(KualaLumpur.FLAG_BLUE, plain = true)
    val yellow = KualaLumpur.colour(KualaLumpur.FLAG_YELLOW, plain = true)
    for (i in 0 until BANNER_SLICES) {
      val along = (i + 0.5f) * slice
      val x = hoist - along
      val z = wave(along, time)
      // Facing back along the flight, a slice's slope across is the wave's, mirrored.
      val yaw = -kotlin.math.atan((wave(along + 0.01f, time) - wave(along - 0.01f, time)) / 0.02f)
      val w = slice * 1.04f
      p.box(x, mid, z, w, BANNER_HIGH, 0.02f, CityPlanner.ROLE_LANDMARK, white, yaw, glow = 0.5f)
      p.box(x, mid, z, w, BANNER_HIGH / 3f, 0.03f, CityPlanner.ROLE_LANDMARK, blue, yaw, glow = 0.5f)
      for (stripe in STRIPES) {
        p.box(x, mid + BANNER_HIGH * (0.5f - stripe), z, w, BANNER_HIGH * 0.0476f, 0.03f, CityPlanner.ROLE_LANDMARK, red, yaw, glow = 0.5f)
      }
    }
    // The star, two squares an eighth of a turn apart, and the crescent round its hoist side.
    val star = BANNER_HIGH * 0.5f
    val starZ = wave(star, time)
    for (turn in floatArrayOf(0f, 0.785f)) {
      p.box(hoist - star, mid, starZ, BANNER_HIGH * 0.11f, BANNER_HIGH * 0.11f, 0.04f, CityPlanner.ROLE_LANDMARK, yellow, turn, glow = 0.8f)
    }
    for (k in 0 until 5) {
      val a = 1.75f + k * 0.69f
      val along = star + BANNER_HIGH * (-0.052f + 0.111f * cos(a))
      p.box(hoist - along, mid + BANNER_HIGH * 0.11f * sin(a), wave(along, time), BANNER_HIGH * 0.05f, BANNER_HIGH * 0.05f, 0.04f, CityPlanner.ROLE_LANDMARK, yellow, 0f, glow = 0.8f)
    }
  }

  /** How far the banner swings out sideways [along] from its hoist at [time]: nothing at the hoist, most at the fly. */
  private fun wave(along: Float, time: Float): Float {
    val reach = (along / BANNER_LONG).coerceIn(0f, 1f)
    return 0.16f * reach * sin(along * 2.1f - time * 6f)
  }

  /** The banner: how far behind the plane's middle its hoist hangs, how long and how high it is, and its slices. */
  private const val BANNER_FROM = 3.0f
  private const val BANNER_LONG = 3.6f
  private const val BANNER_HIGH = 1.8f
  private const val BANNER_SLICES = 9

  /** The red stripes' middles, as a share of the flag's height from its top. */
  private val STRIPES = floatArrayOf(0.0714f, 0.1667f, 0.2619f, 0.7381f, 0.8333f, 0.9286f)

  /** The plane's fin, in the shader's red. */
  private const val TAIL_FIN = 0.05f

  /** A twin-engined airliner, small at that height: fuselage, wings, tail and engines. */
  private fun plane(p: Part, rnd: Float) {
    p.box(0f, 0f, 0f, 2.5f, 0.30f, 0.30f, PAINT, rnd) // fuselage
    p.box(1.42f, -0.02f, 0f, 0.36f, 0.26f, 0.26f, PAINT, rnd, taper = 0.45f) // nose
    p.box(-1.45f, 0.03f, 0f, 0.42f, 0.24f, 0.24f, PAINT, rnd, taper = 0.55f) // tail cone
    p.box(0.12f, 0.06f, 0f, 2.1f, 0.06f, 0.31f, DETAIL, GLASS) // windows
    p.lamp(0.12f, 0.06f, 0f, 2.0f, 0.035f, 0.315f, CABIN)
    p.box(0.10f, -0.06f, 0f, 0.62f, 0.06f, 3.6f, PAINT, rnd) // wings
    for (side in floatArrayOf(-0.85f, 0.85f)) p.box(0.32f, -0.18f, side, 0.48f, 0.16f, 0.16f, DETAIL, METAL) // engines
    p.box(-1.30f, 0.10f, 0f, 0.32f, 0.05f, 1.25f, PAINT, rnd) // tailplane
    p.box(-1.32f, 0.40f, 0f, 0.46f, 0.55f, 0.06f, PAINT, TAIL_FIN, taper = 0.5f) // fin
    for (side in floatArrayOf(-1.78f, 1.78f)) p.box(0.10f, -0.06f, side, 0.08f, 0.08f, 0.08f, CityPlanner.ROLE_BEACON, rnd)
  }
}
