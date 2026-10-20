package com.skylightlim.skylineqr.core

/**
 * Which city a link builds: the link, how much of it counts, and the recipe. The same identity
 * always produces the same city, on every device, with no server involved.
 *
 * Presentation (time of day, weather, the city preset, the camera) never enters the identity, so
 * changing it never rebuilds a different city.
 */
class CityIdentity(val link: Link, val scope: IdentityScope, val generatorVersion: Int) {

  /** What the seed is made from: the site, or the exact page. */
  val key: String =
    when (scope) {
      IdentityScope.SITE -> "site:${link.siteIdentity}"
      IdentityScope.URL -> "url:${link.pageIdentity}"
    }

  val seed: Int = Identity.seed("skylineqr:$key")

  /** Short tag for the city, e.g. "K7QF3M". */
  val code: String = Identity.code(seed)

  companion object {
    fun of(
      input: String,
      scope: IdentityScope = IdentityScope.SITE,
      generatorVersion: Int = GeneratorVersion.CURRENT,
    ) = CityIdentity(Link.parse(input), scope, GeneratorVersion.resolve(generatorVersion))
  }
}

object Identity {

  /** FNV-1a over UTF-8 bytes: same text, same seed, everywhere. */
  fun seed(text: String): Int {
    var h = -2128831035 // 0x811c9dc5
    for (b in text.toByteArray(Charsets.UTF_8)) {
      h = h xor (b.toInt() and 0xff)
      h *= 16777619
    }
    return h
  }

  /** Crockford-style base 32: no I, L, O or U, so codes stay unambiguous when read aloud. */
  private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  fun code(seed: Int): String {
    var v = seed.toLong() and 0xffffffffL
    val sb = StringBuilder(6)
    repeat(6) {
      sb.append(ALPHABET[(v and 31L).toInt()])
      v = v shr 5
    }
    return sb.toString()
  }
}
