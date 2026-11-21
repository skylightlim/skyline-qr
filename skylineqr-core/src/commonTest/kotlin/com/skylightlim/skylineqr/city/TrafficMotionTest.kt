package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the traffic moves, pinned bit for bit (2026-11-21): where each car halts for the lights,
 * each train's stations, and where every mover is at moments over half an hour. The fingerprint
 * tests hash the routes but not these, which come from following the routes; this keeps the
 * planner's and the renderer's speed-ups from moving a single car. Runs on the JVM and on Wasm.
 */
class TrafficMotionTest {

  private val golden =
    linkedMapOf(
      "kl:https://github.com/skylightlim/skyline-qr" to "de9b9e0dad90aa99",
      "kl:https://www.instagram.com/chenyuulim/" to "2b149ad7c869ff2e",
      "https://example.com/" to "64d78975624a2b85",
    )

  @Test
  fun trafficMovesTheSame() {
    val actual = golden.keys.associateWith { fingerprint(it) }
    assertEquals(golden, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
  }

  private fun fingerprint(key: String): String {
    val preset = if (key.startsWith("kl:")) CityPreset.KUALA_LUMPUR else CityPreset.GENERIC
    val identity = CityIdentity.of(key.removePrefix("kl:"), IdentityScope.SITE, 1)
    val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload, 1), identity, preset)
    // FNV-1a, 64 bits, over the raw bits of every number, as CrossPlatformCityTest.
    var h = -0x340d631b7bdddcdbL
    fun put(bits: Int) {
      for (shift in 0 until 32 step 8) {
        h = h xor ((bits ushr shift) and 0xff).toLong()
        h *= 0x100000001b3L
      }
    }
    fun put(v: Float) = put(v.toRawBits())
    val at = FloatArray(4)
    for (m in city.movers) {
      m.stops.forEach(::put)
      m.stations.forEach(::put)
      var t = 0f
      while (t < 1800f) {
        m.at(t, at)
        at.forEach(::put)
        t += 0.731f
      }
    }
    return h.toULong().toString(16)
  }
}
