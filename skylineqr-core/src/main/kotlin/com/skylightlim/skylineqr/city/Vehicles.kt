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
          Mover.PLANE -> plane(part, mover.rnd)
        }
      }
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

  private fun car(p: Part, rnd: Float) {
    p.box(0f, 0.035f, 0f, 0.70f, 0.07f, 0.36f, DETAIL, RUBBER) // wheels and the shadow under them
    p.box(0f, 0.13f, 0f, 0.78f, 0.13f, 0.40f, PAINT, rnd)
    p.box(-0.05f, 0.25f, 0f, 0.42f, 0.12f, 0.36f, DETAIL, GLASS, taper = 0.78f)
  }

  private fun bus(p: Part, rnd: Float) {
    p.box(0f, 0.04f, 0f, 1.22f, 0.08f, 0.44f, DETAIL, RUBBER)
    p.box(0f, 0.25f, 0f, 1.35f, 0.34f, 0.48f, PAINT, rnd)
    p.box(0.02f, 0.33f, 0f, 1.31f, 0.11f, 0.50f, DETAIL, GLASS) // the window band, a hair proud of the body
  }

  /** Three cars on the beam, each at its own point of the curve, so the train bends with it. */
  private fun train(m: Mover, time: Float, sink: Sink, scratch: FloatArray) {
    val middle = m.distance(time)
    for (k in -1..1) {
      m.route.at(middle + k * CAR_GAP, scratch)
      val p = Part(scratch[0], scratch[1], scratch[2], scratch[3], sink)
      val nose = if (k == 0) 1f else 0.82f // the end cars round off toward their cabs
      p.box(0f, 0.30f, 0f, 0.92f, 0.36f, 0.34f, PAINT, m.rnd)
      p.box(0f, 0.36f, 0f, 0.88f, 0.10f, 0.36f, DETAIL, GLASS)
      p.box(0f, 0.53f, 0f, 0.86f, 0.10f, 0.30f, PAINT, m.rnd, taper = nose)
    }
  }

  private const val CAR_GAP = 0.98f

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
  }

  /** A twin-engined airliner, small at that height: fuselage, wings, tail and engines. */
  private fun plane(p: Part, rnd: Float) {
    p.box(0f, 0f, 0f, 2.5f, 0.30f, 0.30f, PAINT, rnd) // fuselage
    p.box(1.42f, -0.02f, 0f, 0.36f, 0.26f, 0.26f, PAINT, rnd, taper = 0.45f) // nose
    p.box(-1.45f, 0.03f, 0f, 0.42f, 0.24f, 0.24f, PAINT, rnd, taper = 0.55f) // tail cone
    p.box(0.12f, 0.06f, 0f, 2.1f, 0.06f, 0.31f, DETAIL, GLASS) // windows
    p.box(0.10f, -0.06f, 0f, 0.62f, 0.06f, 3.6f, PAINT, rnd) // wings
    for (side in floatArrayOf(-0.85f, 0.85f)) p.box(0.32f, -0.18f, side, 0.48f, 0.16f, 0.16f, DETAIL, METAL) // engines
    p.box(-1.30f, 0.10f, 0f, 0.32f, 0.05f, 1.25f, PAINT, rnd) // tailplane
    p.box(-1.32f, 0.40f, 0f, 0.46f, 0.55f, 0.06f, PAINT, rnd, taper = 0.5f) // fin
    for (side in floatArrayOf(-1.78f, 1.78f)) p.box(0.10f, -0.06f, side, 0.08f, 0.08f, 0.08f, CityPlanner.ROLE_BEACON, rnd)
  }
}
