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

  private val golden =
    linkedMapOf(
      "kl:https://github.com/skylightlim/skyline-qr" to "a23fc2a82c07f53a",
      "kl:https://um.edu.my/" to "7f79d1220d56334f",
      "https://github.com/skylightlim/skyline-qr" to "bb9475b9eaae3256",
      "https://example.com/" to "a070c00c574adb46",
      "https://um.edu.my/" to "2c217a9b2b66f694",
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
    for (m in city.movers) {
      digest.update(bytes.clear().putInt(m.kind * 4 + (if (m.shuttle) 2 else 0) + (if (m.hovers) 1 else 0)).array())
      listOf(m.speed, m.phase, m.rnd, m.dwell, m.inset).forEach(::put)
      m.route.points.forEach(::put)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
  }
}
