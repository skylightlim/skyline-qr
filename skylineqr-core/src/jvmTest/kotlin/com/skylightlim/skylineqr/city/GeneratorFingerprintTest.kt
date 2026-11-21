package com.skylightlim.skylineqr.city

import com.skylightlim.skylineqr.core.CityIdentity
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.QrEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden fingerprints: a hash of everything the planner hands the renderer, for a few fixed links.
 * If one changes, the same link no longer builds the same city.
 *
 * Generator version 1 shipped in the first public release, v1.0.0 (2026-11-20), so these are
 * frozen: a visual change adds a new generator version and leaves version 1, and these hashes,
 * alone.
 */
class GeneratorFingerprintTest {

  // 2026-11-20: the Pantone inks without magenta, seven hues round the wheel; Kuala Lumpur's
  // districts balanced, and its lawn finder modules under the lawn.
  private val golden =
    linkedMapOf(
      "kl:https://github.com/skylightlim/skyline-qr" to "06133630828fa1c9",
      "kl:https://um.edu.my/" to "e3cc4dcb86f40313",
      "https://github.com/skylightlim/skyline-qr" to "1c216bcc3f16e9d4",
      "https://example.com/" to "29a667bc04715d9b",
      "https://um.edu.my/" to "7538e091fa75d8cc",
    )

  @Test
  fun versionOneCitiesStayTheSame() {
    val actual = golden.keys.associateWith { fingerprint(it) }
    assertEquals(
      "update only while version 1 is unreleased:\n" + actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," },
      golden,
      actual,
    )
  }

  private fun fingerprint(key: String): String {
    val preset = if (key.startsWith("kl:")) CityPreset.KUALA_LUMPUR else CityPreset.GENERIC
    val identity = CityIdentity.of(key.removePrefix("kl:"), IdentityScope.SITE, 1)
    val city = CityPlanner.plan(QrEncoder.encode(identity.link.payload, 1), identity, preset)
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = ByteBuffer.allocate(4)
    fun put(v: Float) = digest.update(bytes.clear().putFloat(v).array())
    city.instances.forEach(::put)
    city.materials.families.forEach(::put)
    put(city.materials.districts.toFloat())
    city.tones.forEach { put(it.toFloat()) }
    city.inks.forEach { put(it.toFloat()) }
    for (m in city.movers) {
      digest.update(bytes.clear().putInt(m.kind * 4 + (if (m.shuttle) 2 else 0) + (if (m.hovers) 1 else 0)).array())
      listOf(m.speed, m.phase, m.rnd, m.dwell, m.inset).forEach(::put)
      m.route.points.forEach(::put)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }
}
