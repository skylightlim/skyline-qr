package com.skylightlim.skylineqr.nfc.card

/** Checking, tidying and naming card content before it is saved. */
object CardRules {

  /** Schemes that are written without `//`. */
  private val OPAQUE_SCHEMES = setOf("tel", "mailto", "sms", "smsto", "geo", "sip", "sips", "urn", "news")

  /** What stops [content] from being saved, or null if it can be. */
  fun problem(content: CardContent): String? = when (content) {
    is CardContent.Link -> when {
      content.url.isBlank() -> "Enter a link."
      content.url.trim().any(Char::isWhitespace) -> "A link can't contain spaces."
      else -> null
    }
    is CardContent.Text -> if (content.text.isBlank()) "Enter some text." else null
    is CardContent.Contact -> if (content.isEmpty) "Fill in at least one field." else null
    is CardContent.Wifi -> wifiProblem(content)
    is CardContent.Payment -> if (PaymentQr.isPaymentCode(content.code)) null else "This payment code is damaged. Scan it again."
  }

  /** Something worth knowing that doesn't stop saving. */
  fun warning(content: CardContent): String? =
    if (content is CardContent.Wifi && content.security == WifiSecurity.WPA3) {
      "Tapped phones get it as WPA2. Routers that allow only WPA3 may refuse it; a QR code keeps WPA3."
    } else {
      null
    }

  /** [content] as it should be stored: trimmed, and links given a scheme. */
  fun normalized(content: CardContent): CardContent = when (content) {
    is CardContent.Link -> CardContent.Link(normalizeLink(content.url))
    is CardContent.Text -> CardContent.Text(content.text.trimEnd())
    is CardContent.Contact -> CardContent.Contact(
      name = content.name.trim(),
      phone = content.phone.trim(),
      email = content.email.trim(),
      organization = content.organization.trim(),
      website = content.website.takeIf(String::isNotBlank)?.let(::normalizeLink).orEmpty(),
    )
    // SSIDs and passwords are kept exactly as typed: spaces can be part of them.
    is CardContent.Wifi -> if (content.security == WifiSecurity.OPEN) content.copy(password = "") else content
    // Exactly as scanned: any change breaks its checksum.
    is CardContent.Payment -> content
  }

  /** `instagram.com/x` → `https://instagram.com/x`; the scheme is lower-cased; anything else is kept. */
  fun normalizeLink(input: String): String {
    val link = input.trim()
    if (link.isEmpty()) return link
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(link)?.groupValues?.get(1)
    val hasScheme = scheme != null && (link.startsWith("$scheme://") || scheme.lowercase() in OPAQUE_SCHEMES)
    return if (hasScheme) scheme!!.lowercase() + link.substring(scheme.length) else "https://$link"
  }

  /** A title for a card nobody has named yet. */
  fun suggestedTitle(content: CardContent): String = when (content) {
    is CardContent.Link -> linkTitle(content.url)
    is CardContent.Text -> content.text.trim().lineSequence().firstOrNull().orEmpty().let(::shorten).ifEmpty { "Text" }
    is CardContent.Contact -> listOf(content.name, content.organization, content.phone, content.email)
      .map(String::trim).firstOrNull(String::isNotEmpty) ?: "Contact"
    is CardContent.Wifi -> content.ssid.ifBlank { "Wi-Fi" }
    // Malaysia National QR is DuitNow; anywhere else, just what it is.
    is CardContent.Payment -> if (PaymentQr.country(content.code) == "MY") "DuitNow" else "Payment QR"
  }

  /** One line describing [content], for lists. */
  fun preview(content: CardContent): String = when (content) {
    is CardContent.Link -> content.url
    is CardContent.Text -> shorten(content.text.trim().replace(Regex("\\s+"), " "), 60)
    is CardContent.Contact -> listOf(content.name, content.phone, content.email, content.organization)
      .filter(String::isNotBlank).joinToString(" · ")
    is CardContent.Wifi -> "${content.ssid} · ${content.security.label}"
    is CardContent.Payment -> listOfNotNull("Payment QR", content.payee).joinToString(" · ")
  }

  private fun wifiProblem(wifi: CardContent.Wifi): String? {
    if (wifi.ssid.isEmpty()) return "Enter the network name."
    if (wifi.ssid.toByteArray(Charsets.UTF_8).size > 32) return "A network name is at most 32 bytes."
    if (wifi.security == WifiSecurity.OPEN) return null
    val password = wifi.password
    val isHexKey = password.length == 64 && password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return if (password.length in 8..63 || isHexKey) null else "A Wi-Fi password is 8 to 63 characters."
  }

  private fun linkTitle(url: String): String {
    val link = url.trim()
    val scheme = link.substringBefore(':', "").lowercase()
    return when (scheme) {
      "tel", "sms", "smsto" -> link.substringAfter(':')
      "mailto" -> link.substringAfter(':').substringBefore('?')
      else -> {
        val host = link.substringAfter("://", link).substringBefore('/').substringBefore('?').substringBefore('#')
        host.removePrefix("www.").ifEmpty { shorten(link) }
      }
    }.ifEmpty { "Link" }
  }

  private fun shorten(text: String, max: Int = 32) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
