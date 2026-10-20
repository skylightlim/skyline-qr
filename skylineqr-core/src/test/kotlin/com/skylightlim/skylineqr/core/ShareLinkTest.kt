package com.skylightlim.skylineqr.core

import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {

  @Test
  fun everyChoiceSurvivesTheRoundTrip() {
    for (preset in CityPreset.entries) for (time in TimeOfDay.entries) for (weather in Weather.entries) for (scope in IdentityScope.entries) {
      val city = SharedCity("https://例子.测试/路径?q=1#top", 1, scope, preset, time, weather)
      val link = ShareLink.encode(city)
      assertTrue(link, link.startsWith("https://skylightlim.github.io/city/?c="))
      assertTrue("not URL-safe: $link", link.substringAfter("?c=").all { it.isLetterOrDigit() || it == '-' || it == '_' })
      assertEquals(city.copy(url = Link.parse(city.url).payload), ShareLink.decode(link))
    }
  }

  @Test
  fun linksStayShort() {
    val link = ShareLink.encode(SharedCity("https://github.com/skylightlim/skyline-qr"))
    assertTrue("${link.length} characters", link.length < 110)
  }

  @Test
  fun foreignOrBrokenLinksAreNotCities() {
    assertNull(ShareLink.decode("https://example.com/city/?c=AQEA"))
    assertNull(ShareLink.decode("https://skylightlim.github.io/city/"))
    assertNull(ShareLink.decode("https://skylightlim.github.io/city/?c=!!!"))
    assertNull(ShareLink.decode("https://skylightlim.github.io/city/?c=AQ"))
    // A newer link format than this build knows is an error to report, not a silent miss.
    assertThrows(UnsupportedVersionException::class.java) { ShareLink.decode("https://skylightlim.github.io/city/?c=AgEAaHR0cHM6Ly9hLmNvLw") }
  }
}
