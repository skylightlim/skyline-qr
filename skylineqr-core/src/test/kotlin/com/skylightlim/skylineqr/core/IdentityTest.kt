package com.skylightlim.skylineqr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

  @Test
  fun siteScopeGivesEveryPageOnAHostOneCity() {
    val home = CityIdentity.of("https://example.com/")
    val page = CityIdentity.of("https://www.example.com/blog/post?id=7")
    assertEquals(home.seed, page.seed)
    assertNotEquals(home.link.payload, page.link.payload) // the QR still points at each page
    assertNotEquals(home.seed, CityIdentity.of("https://example.org/").seed)
  }

  @Test
  fun urlScopeGivesEachPageItsOwnCity() {
    val a = CityIdentity.of("https://example.com/a", IdentityScope.URL)
    val b = CityIdentity.of("https://example.com/b", IdentityScope.URL)
    assertNotEquals(a.seed, b.seed)
    // Tracking parameters and fragments do not make a different page.
    assertEquals(a.seed, CityIdentity.of("https://example.com/a?utm_source=mail#top", IdentityScope.URL).seed)
  }

  @Test
  fun codesAreSixUnambiguousCharacters() {
    val code = CityIdentity.of("example.com").code
    assertEquals(6, code.length)
    assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
  }

  @Test
  fun unknownGeneratorVersionsFailInsteadOfFallingBack() {
    assertThrows(UnsupportedVersionException::class.java) { CityIdentity.of("example.com", generatorVersion = 2) }
    assertThrows(UnsupportedVersionException::class.java) { QrEncoder.encode("https://example.com/", 0) }
  }
}
