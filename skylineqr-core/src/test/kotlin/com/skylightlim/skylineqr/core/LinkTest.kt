package com.skylightlim.skylineqr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Cases follow every-qrcode's url.test.ts (MIT, see NOTICE), plus the www rule that is ours.
class LinkTest {

  @Test
  fun addsHttpsToABareHostname() {
    val link = Link.parse("example.com")
    assertEquals("https://example.com/", link.payload)
    assertEquals("example.com", link.displayHost)
    assertEquals("example.com", link.siteIdentity)
    assertEquals("https://example.com/", link.pageIdentity)
  }

  @Test
  fun keepsPathQueryOrderAndFragmentInThePayload() {
    assertEquals("https://example.com/a?b=2&a=1#part", Link.parse("https://Example.com/a?b=2&a=1#part").payload)
  }

  @Test
  fun neverRewritesWhereTheScanLands() {
    // www, a trailing slash and the fragment all stay: dropping them could send a scan elsewhere.
    val link = Link.parse("https://WWW.Example.com/Path/#top")
    assertEquals("https://www.example.com/Path/#top", link.payload)
    assertEquals("example.com", link.siteIdentity)
  }

  @Test
  fun acceptsSchemeRelativeLocalhostAndIpInputs() {
    assertEquals("https://example.com/a", Link.parse("//example.com/a").payload)
    assertEquals("https://localhost:8080/", Link.parse("localhost:8080").payload)
    assertEquals("https://127.0.0.1:3000/", Link.parse("127.0.0.1:3000").payload)
    assertEquals("https://[::1]:3000/", Link.parse("[::1]:3000").payload)
  }

  @Test
  fun encodesUnicodeAndResolvesDotSegmentsLikeABrowser() {
    assertEquals(
      "https://xn--fsqu00a.xn--0zwm56d/%E8%B7%AF%E5%BE%84",
      Link.parse("例子.测试/a/../路径").payload,
    )
    assertEquals("https://example.com/a%20b", Link.parse("https://example.com/a b").payload)
    assertEquals("https://example.com/b/", Link.parse("https://example.com/a/./../b/.").payload)
    assertEquals("https://example.com/%F0%9F%8F%99", Link.parse("https://example.com/🏙").payload)
  }

  @Test
  fun removesOnlyDefaultPorts() {
    assertEquals("http://example.com/", Link.parse("http://example.com:80").payload)
    assertEquals("https://example.com/", Link.parse("https://example.com:443").payload)
    val custom = Link.parse("https://example.com:444")
    assertEquals("https://example.com:444/", custom.payload)
    assertEquals("example.com", custom.siteIdentity)
  }

  @Test
  fun pageIdentityDropsTrackingAndSortsTheRest() {
    val link = Link.parse("https://example.com/p?utm_source=x&b=2&fbclid=y&a=1#frag")
    assertEquals("https://example.com/p?a=1&b=2", link.pageIdentity)
    assertFalse(link.hasSensitiveQuery)
    assertTrue(Link.parse("https://example.com/?token=abc").hasSensitiveQuery)
  }

  @Test
  fun rejectsWhatCannotBeAWebLink() {
    assertThrows(InvalidLinkException::class.java) { Link.parse("javascript:alert(1)") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("not a valid url") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("https://user:secret@example.com/") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("   ") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("https://300.1.1.1/") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("https://example.com:99999/") }
    assertThrows(InvalidLinkException::class.java) { Link.parse("https://example.com/" + "界".repeat(1400)) }
  }

  @Test
  fun payloadIsAlwaysAscii() {
    for (input in listOf("例子.测试/路径?q=你好#片段", "example.com/a b\"c", "https://example.com/?x=<y>")) {
      assertTrue(input, Link.parse(input).payload.all { it.code in 0x21..0x7e })
    }
  }
}
