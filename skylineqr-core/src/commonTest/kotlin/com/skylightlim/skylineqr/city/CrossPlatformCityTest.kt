package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The same link builds the same city on the JVM, on Android and in the browser (2026-11-21): a hash
 * of everything the planner hands the renderer, the same on every platform this test runs on (the
 * JVM and Kotlin/Wasm). The hashes are the JVM's; GeneratorFingerprintTest guards the JVM itself.
 */
class CrossPlatformCityTest {

  private val golden =
    linkedMapOf(
      "kl:https://github.com/skylightlim/skyline-qr" to "28170b696d1bc9b5",
      "kl:https://um.edu.my/" to "29ebc21bd36fd929",
      "https://github.com/skylightlim/skyline-qr" to "63aa37e0c98f39d7",
      "https://example.com/" to "4daf32b862d3733e",
      // A made-up DuitNow-style code, as in PaymentCodeTest: paying no one.
      "kl:00020101021126440014A0000006150001010689005302120012345678905204000053034585802MY5909TESTPAYEE6002MY62130809TESTREF006304109A" to "c1ca1cc581a73385",
    )

  @Test
  fun citiesAreTheSameEverywhere() {
    val actual = golden.keys.associateWith { fingerprint(it) }
    assertEquals(golden, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
  }

  private fun fingerprint(key: String): String {
    val preset = if (key.startsWith("kl:")) CityPreset.KUALA_LUMPUR else CityPreset.GENERIC
    val identity = CityIdentity.of(key.removePrefix("kl:"), IdentityScope.SITE, 1)
    val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload, 1), identity, preset)
    // FNV-1a, 64 bits, over the raw bits of every number.
    var h = -0x340d631b7bdddcdbL
    fun put(bits: Int) {
      for (shift in 0 until 32 step 8) {
        h = h xor ((bits ushr shift) and 0xff).toLong()
        h *= 0x100000001b3L
      }
    }
    fun put(v: Float) = put(v.toRawBits())
    for (i in city.qr.dark.indices) put(if (city.qr.dark[i]) 1 else 0)
    city.instances.forEach(::put)
    city.materials.families.forEach(::put)
    put(city.materials.districts)
    city.tones.forEach(::put)
    city.inks.forEach(::put)
    for (m in city.movers) {
      put(m.kind * 4 + (if (m.shuttle) 2 else 0) + (if (m.hovers) 1 else 0))
      listOf(m.speed, m.phase, m.rnd, m.dwell, m.inset).forEach(::put)
      m.route.points.forEach(::put)
    }
    return h.toULong().toString(16)
  }
}
