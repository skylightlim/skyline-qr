package com.skylightlim.skylineqr.core

import java.net.IDN

/** How much of a link decides its city: the whole site, or this exact page. */
enum class IdentityScope { SITE, URL }

/**
 * A link as the person typed it, made safe to encode.
 *
 * [payload] is what the QR carries. It is only ever rewritten the way a browser would rewrite it
 * (scheme, lowercase host, default port, dot segments, percent-encoding), so a scan always lands
 * where the link pointed. The identities are separate and only decide what the city looks like.
 *
 * Adapted from every-qrcode's url.ts (MIT, see NOTICE). One deliberate difference: the site
 * identity drops a leading "www.", so example.com and www.example.com share a city.
 */
class Link private constructor(
  val input: String,
  val payload: String,
  /** The host as people read it, with its port when the port is not the default. */
  val displayHost: String,
  val siteIdentity: String,
  /** The page without its fragment or tracking parameters, the rest sorted by key. */
  val pageIdentity: String,
  /** The query carries something like a token or a password: worth a warning before sharing. */
  val hasSensitiveQuery: Boolean,
  /**
   * It is a payment code, not a web link (see [PaymentCode]): its payload is the code exactly as
   * given, its identity the whole code, and it is never put in a share link.
   */
  val isPaymentCode: Boolean = false,
) {

  companion object {

    const val MAX_INPUT_BYTES = 4096

    private val EXPLICIT_SCHEME = Regex("^[a-z][a-z\\d+.-]*:", RegexOption.IGNORE_CASE)
    private val HTTP_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    private val IPV6 = Regex("^\\[[0-9a-f:.]+]$", RegexOption.IGNORE_CASE)
    private val IPV6_AUTHORITY = Regex("^\\[[0-9a-f:.]+](?::\\d+)?$", RegexOption.IGNORE_CASE)
    private val DNS_HOST = Regex("^[a-z\\d](?:[a-z\\d-]*[a-z\\d])?(?:\\.[a-z\\d](?:[a-z\\d-]*[a-z\\d])?)*\\.?$")
    private val PARTS = Regex("^([a-zA-Z][a-zA-Z\\d+.-]*)://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$", RegexOption.DOT_MATCHES_ALL)

    private val TRACKING_KEYS = setOf("_ga", "_gl", "fbclid", "gclid", "msclkid")
    private val SENSITIVE_KEYS =
      setOf(
        "access_token", "api_key", "apikey", "client_secret", "password", "passwd",
        "session", "sessionid", "signature", "token", "x-amz-signature",
      )

    // WHATWG percent-encode sets, minus the C0 controls and non-ASCII that every set shares.
    private const val PATH_SET = " \"#<>?`{}"
    private const val QUERY_SET = " \"#<>'"
    private const val FRAGMENT_SET = " \"<>`"

    fun parse(input: String): Link {
      if (input.toByteArray(Charsets.UTF_8).size > MAX_INPUT_BYTES) {
        throw InvalidLinkException("That link is longer than SkylineQR can safely process.")
      }
      // A payment code builds a city too (2026-11-07), kept exactly: only blank space around it,
      // which is no part of any field, is dropped.
      val code = input.trim()
      if (PaymentCode.isPaymentCode(code)) {
        return Link(code, code, PaymentCode.payee(code) ?: "Payment code", "pay:$code", "pay:$code", hasSensitiveQuery = false, isPaymentCode = true)
      }
      // Browsers drop tabs and newlines anywhere in a URL, so a pasted link survives line wraps.
      val trimmed = input.trim().filterNot { it == '\t' || it == '\n' || it == '\r' }
      if (trimmed.isEmpty()) throw InvalidLinkException("Enter a link to build its city.")

      val parts = PARTS.matchEntire(slashesBeforeQuery(withDefaultScheme(trimmed)))
        ?: throw InvalidLinkException("That link does not look valid yet.")
      val scheme = parts.groupValues[1].lowercase()
      if (scheme != "http" && scheme != "https") {
        throw InvalidLinkException("SkylineQR supports only HTTP and HTTPS links.")
      }
      val authority = parts.groupValues[2]
      if ('@' in authority) {
        throw InvalidLinkException("Links containing a username or password are not supported.")
      }

      val (rawHost, rawPort) = splitAuthority(authority)
      if (rawHost.isEmpty()) throw InvalidLinkException("That link needs a hostname.")
      val host = asciiHost(rawHost)
      val port = port(rawPort, scheme)
      val hostPort = if (port == null) host else "$host:$port"

      val path = encode(removeDotSegments(parts.groupValues[3].ifEmpty { "/" }), PATH_SET)
      val query = parts.groups[4]?.value?.let { "?" + encode(it.substring(1), QUERY_SET) } ?: ""
      val fragment = parts.groups[5]?.value?.let { "#" + encode(it.substring(1), FRAGMENT_SET) } ?: ""

      val parameters = query.removePrefix("?").split('&').filter { it.isNotEmpty() }
      val keys = parameters.map { it.substringBefore('=').lowercase() }
      val kept = parameters.filterIndexed { i, _ -> !isTracking(keys[i]) }.sortedBy { it.substringBefore('=') }
      val page = "$scheme://$hostPort$path" + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")

      return Link(
        input = input,
        payload = "$scheme://$hostPort$path$query$fragment",
        displayHost = hostPort,
        siteIdentity = host.removePrefix("www."),
        pageIdentity = page,
        hasSensitiveQuery = keys.any { it in SENSITIVE_KEYS },
      )
    }

    private fun isTracking(key: String) = key.startsWith("utm_") || key in TRACKING_KEYS

    private fun withDefaultScheme(value: String): String =
      when {
        value.startsWith("//") -> "https:$value"
        HTTP_SCHEME.containsMatchIn(value) -> value
        isHostLike(value) -> "https://$value"
        EXPLICIT_SCHEME.containsMatchIn(value) ->
          throw InvalidLinkException("SkylineQR supports only HTTP and HTTPS links.")
        else -> throw InvalidLinkException("That link does not look valid yet.")
      }

    private fun isHostLike(value: String): Boolean {
      val authority = value.split('/', '?', '#', limit = 2)[0]
      if (authority.isEmpty() || '@' in authority) return false
      if (IPV6_AUTHORITY.matches(authority)) return true
      val colon = authority.lastIndexOf(':')
      val host = if (colon >= 0) authority.substring(0, colon) else authority
      val port = if (colon >= 0) authority.substring(colon + 1) else ""
      if (colon >= 0 && (port.isEmpty() || !port.all(Char::isDigit))) return false
      if (host.equals("localhost", ignoreCase = true)) return true
      if (IPV4.matches(host)) return true
      return '.' in host && !host.startsWith('.') && !host.endsWith('.')
    }

    /** Web URLs treat a backslash before the query as a slash; so does every browser. */
    private fun slashesBeforeQuery(url: String): String {
      val end = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
      return url.substring(0, end).replace('\\', '/') + url.substring(end)
    }

    private fun splitAuthority(authority: String): Pair<String, String> {
      if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) throw InvalidLinkException("That link does not look valid yet.")
        val rest = authority.substring(close + 1)
        if (rest.isNotEmpty() && !rest.startsWith(":")) throw InvalidLinkException("That link does not look valid yet.")
        return authority.substring(0, close + 1) to rest.removePrefix(":")
      }
      val colon = authority.lastIndexOf(':')
      if (colon < 0) return authority to ""
      return authority.substring(0, colon) to authority.substring(colon + 1)
    }

    private fun asciiHost(raw: String): String {
      val lower = raw.lowercase()
      if (IPV6.matches(lower)) return lower
      if (IPV4.matches(lower)) {
        if (lower.split('.').any { it.toInt() > 255 }) throw InvalidLinkException("That link has an invalid address.")
        return lower
      }
      val ascii =
        try {
          IDN.toASCII(lower).lowercase()
        } catch (e: IllegalArgumentException) {
          throw InvalidLinkException("That link has an invalid hostname.")
        }
      if (!DNS_HOST.matches(ascii)) throw InvalidLinkException("That link has an invalid hostname.")
      return ascii
    }

    private fun port(raw: String, scheme: String): Int? {
      if (raw.isEmpty()) return null
      if (!raw.all(Char::isDigit) || raw.length > 5) throw InvalidLinkException("That link has an invalid port.")
      val port = raw.toInt()
      if (port > 65535) throw InvalidLinkException("That link has an invalid port.")
      val isDefault = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
      return if (isDefault) null else port
    }

    /** RFC 3986 section 5.2.4, so `/a/../b` and `/b` are one link, as they are in a browser. */
    private fun removeDotSegments(path: String): String {
      val out = ArrayList<String>()
      val segments = path.split('/')
      for ((i, segment) in segments.withIndex()) {
        if (i == 0) continue // the empty segment before the leading slash
        val last = i == segments.lastIndex
        when (segment) {
          "." -> if (last) out += ""
          ".." -> {
            if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            if (last) out += ""
          }
          else -> out += segment
        }
      }
      return "/" + out.joinToString("/")
    }

    /** Percent-encodes what a browser would, and leaves existing escapes alone. */
    private fun encode(text: String, set: String): String {
      val sb = StringBuilder(text.length)
      var i = 0
      while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        if (cp in 0x21..0x7e && cp.toChar() !in set) {
          sb.append(cp.toChar())
        } else {
          for (b in text.substring(i, i + n).toByteArray(Charsets.UTF_8)) {
            sb.append('%').append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
          }
        }
        i += n
      }
      return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
  }
}
