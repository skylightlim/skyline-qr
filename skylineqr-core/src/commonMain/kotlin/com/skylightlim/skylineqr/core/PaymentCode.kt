package com.skylightlim.skylineqr.core

/**
 * A payment QR code in EMVCo's merchant-presented format, as Malaysia National QR (DuitNow) and
 * most countries' payment codes are (2026-11-07, at the user's request: his Touch 'n Go code as a
 * city). It is a run of fields, each a two-digit tag, a two-digit length and that many characters,
 * starting with the format indicator (tag 00) and ending with a CRC-16 (tag 63) over everything
 * before the CRC's own four characters.
 *
 * A city of one keeps it character for character, never rewritten as a link is: change one and
 * the checksum, and so the payment, fails. skylineqr-nfc has its own copy of this check, so that
 * library needs nothing from this one.
 */
object PaymentCode {

  /** Whether [text] is a payment code: well-formed fields from tag 00 to a last tag 63 whose CRC matches. */
  fun isPaymentCode(text: String): Boolean = fields(text) != null

  /** Who the code pays, as it names them (tag 59), or null if [text] is not a payment code. */
  fun payee(text: String): String? = fields(text)?.get("59")?.trim()?.ifEmpty { null }

  /** The top-level fields of [text] by tag, or null unless it is a whole, unbroken payment code. */
  private fun fields(text: String): Map<String, String>? {
    if (text.length < MIN_LENGTH || !text.startsWith("00") || text.any { it.code !in 0x20..0x7e }) return null
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
    // The CRC is the last field, four hex digits over everything before them.
    val crc = fields["63"] ?: return null
    if (fields.keys.last() != "63" || crc.length != 4) return null
    return fields.takeIf { crc.equals(crc(text.substring(0, text.length - 4)), ignoreCase = true) }
  }

  /** CRC-16/CCITT-FALSE (polynomial 0x1021, start 0xFFFF), as four upper-case hex digits. */
  internal fun crc(text: String): String {
    var crc = 0xFFFF
    for (byte in text.encodeToByteArray()) {
      crc = crc xor ((byte.toInt() and 0xff) shl 8)
      repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
      crc = crc and 0xFFFF
    }
    return crc.toString(16).uppercase().padStart(4, '0')
  }

  /** The shortest code worth looking at: a format indicator and a CRC. */
  private const val MIN_LENGTH = 14
}
