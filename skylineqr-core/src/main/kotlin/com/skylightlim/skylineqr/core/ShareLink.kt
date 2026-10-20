package com.skylightlim.skylineqr.core

import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import java.util.Base64

/** Everything needed to rebuild a city exactly as someone saw it. */
data class SharedCity(
  val url: String,
  val generatorVersion: Int = GeneratorVersion.CURRENT,
  val scope: IdentityScope = IdentityScope.SITE,
  val preset: CityPreset = CityPreset.KUALA_LUMPUR,
  val time: TimeOfDay = TimeOfDay.AUTO,
  val weather: Weather = Weather.CLEAR,
)

/**
 * Share links: `https://skylightlim.github.io/city/?c=` followed by base64url bytes.
 *
 * Byte 0 is this format's version, byte 1 the generator version, byte 2 packs the identity scope,
 * city preset, time and weather, and the rest is the link's payload in UTF-8. The generator version
 * travels with the link, so a shared city keeps the recipe it was made with.
 */
object ShareLink {

  const val BASE = "https://skylightlim.github.io/city/"
  private const val FORMAT = 1

  fun encode(city: SharedCity): String {
    val payload = Link.parse(city.url).payload.toByteArray(Charsets.UTF_8)
    val packed =
      (city.scope.ordinal shl 7) or (city.preset.ordinal shl 5) or (city.time.ordinal shl 2) or city.weather.ordinal
    val bytes = byteArrayOf(FORMAT.toByte(), city.generatorVersion.toByte(), packed.toByte()) + payload
    return BASE + "?c=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** The city in a share link, or null if the link is not one of ours. */
  fun decode(link: String): SharedCity? {
    if (!link.startsWith(BASE)) return null
    val value = link.substringAfter("?", "").split('&').firstOrNull { it.startsWith("c=") }?.removePrefix("c=") ?: return null
    val bytes = try {
      Base64.getUrlDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
      return null
    }
    if (bytes.size < 4) return null
    if (bytes[0].toInt() != FORMAT) throw UnsupportedVersionException("share link", bytes[0].toInt())
    val packed = bytes[2].toInt() and 0xff
    val scope = IdentityScope.entries.getOrNull(packed shr 7) ?: return null
    val preset = CityPreset.entries.getOrNull((packed shr 5) and 3) ?: return null
    val time = TimeOfDay.entries.getOrNull((packed shr 2) and 7) ?: return null
    val weather = Weather.entries.getOrNull(packed and 3) ?: return null
    val url = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    return SharedCity(url, bytes[1].toInt() and 0xff, scope, preset, time, weather)
  }
}
