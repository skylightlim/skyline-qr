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
 * Generator version 1 is a draft until the first public release, so until then a deliberate change
 * to the planner may update these. After the release they are frozen: a visual change adds a new
 * generator version and leaves version 1, and these hashes, alone.
 */
class GeneratorFingerprintTest {

  // 2026-11-07: the flat code's inks became each district's own lively hue.
  private val golden =
    linkedMapOf(
      "kl:https://github.com/skylightlim/skyline-qr" to "4566c4804cf0a10c",
      "kl:https://um.edu.my/" to "128208c69eb6fb7e",
      "https://github.com/skylightlim/skyline-qr" to "f47d9cb06c8e00da",
      "https://example.com/" to "38bd5d47692c3323",
      "https://um.edu.my/" to "8d5f34b5dd83639b",
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
    city.materials.districts.forEach(::put)
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
