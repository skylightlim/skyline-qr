package com.skylightlim.skylineqr.nfc.card

/**
 * Payment QR codes in EMVCo's merchant-presented format, as Malaysia National QR (DuitNow) is
 * (2026-11-07): fields of a two-digit tag, a two-digit length and the value, from the format
 * indicator (tag 00) to a CRC-16 (tag 63) over everything before the CRC's own four characters.
 * Kept apart from skylineqr-core's PaymentCode, which checks the same, so this library needs nothing
 * from that one.
 */
internal object PaymentQr {

  /** Whether [text] is a whole payment code whose checksum matches. */
  fun isPaymentCode(text: String): Boolean = fields(text) != null

  /** Who the code pays (tag 59), or null. */
  fun payee(text: String): String? = fields(text)?.get("59")?.trim()?.ifEmpty { null }

  /** The country it pays in (tag 58, two letters: "MY" for DuitNow), or null. */
  fun country(text: String): String? = fields(text)?.get("58")

  private fun fields(text: String): Map<String, String>? {
    if (text.length < 14 || !text.startsWith("00") || text.any { it.code !in 0x20..0x7e }) return null
    val fields = LinkedHashMap<String, String>()
    var at = 0
    while (at < text.length) {
      if (at + 4 > text.length) return null
      val tag = text.substring(at, at + 2)
      val length = text.substring(at + 2, at + 4).toIntOrNull() ?: return null
      if (!tag.all(Char::isDigit) || at + 4 + length > text.length) return null
      fields[tag] = text.substring(at + 4, at + 4 + length)
      at += 4 + length
    }
    val crc = fields["63"] ?: return null
    if (fields.keys.last() != "63" || crc.length != 4) return null
    return fields.takeIf { crc.equals(crc(text.substring(0, text.length - 4)), ignoreCase = true) }
  }

  /** CRC-16/CCITT-FALSE as four upper-case hex digits. */
  fun crc(text: String): String {
    var crc = 0xFFFF
    for (byte in text.toByteArray(Charsets.US_ASCII)) {
      crc = crc xor ((byte.toInt() and 0xff) shl 8)
      repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
      crc = crc and 0xFFFF
    }
    return "%04X".format(crc)
  }
}
