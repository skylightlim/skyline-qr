package com.skylightlim.skylineqr.nfc.ndef

/**
 * URI records (NFC Forum URI RTD 1.0). The first payload byte stands for a common prefix, so
 * `https://www.` costs one byte.
 *
 * The record's length is written in whichever form the payload needs, so links longer than 254
 * bytes stay valid (a short record's one-byte length would overflow).
 */
object UriRecord {

  /** Prefix codes 0x00–0x23, in order. */
  private val PREFIXES = listOf(
    "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
    "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
    "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
    "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
    "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
  )

  fun create(uri: String): NdefRecord {
    var code = 0
    PREFIXES.forEachIndexed { index, prefix ->
      if (prefix.length > PREFIXES[code].length && uri.startsWith(prefix)) code = index
    }
    val rest = uri.substring(PREFIXES[code].length).toByteArray(Charsets.UTF_8)
    return NdefRecord(Tnf.WELL_KNOWN, NdefRecord.RTD_URI, byteArrayOf(code.toByte()) + rest)
  }

  /** The URI in a URI record or an absolute-URI record, or null for any other record. */
  fun parse(record: NdefRecord): String? = when {
    record.isWellKnown(NdefRecord.RTD_URI) && record.payload.isNotEmpty() -> {
      // Reserved codes (above 0x23) add no prefix, as the RTD says.
      val prefix = PREFIXES.getOrElse(record.payload[0].toInt() and 0xFF) { "" }
      prefix + String(record.payload, 1, record.payload.size - 1, Charsets.UTF_8)
    }
    record.tnf == Tnf.ABSOLUTE_URI -> String(record.type, Charsets.UTF_8)
    else -> null
  }
}

/** Text records (NFC Forum Text RTD 1.0): a status byte, an IANA language tag, then the text. */
object TextRecord {

  fun create(text: String, language: String = "en"): NdefRecord {
    val lang = language.toByteArray(Charsets.US_ASCII)
    require(lang.size <= 0x3F) { "A language tag is at most 63 bytes" }
    val body = text.toByteArray(Charsets.UTF_8)
    // Status byte: bit 7 clear for UTF-8, bits 5..0 the language tag's length.
    return NdefRecord(Tnf.WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(lang.size.toByte()) + lang + body)
  }

  /** The text of a text record, or null for any other record. */
  fun parse(record: NdefRecord): String? {
    if (!record.isWellKnown(NdefRecord.RTD_TEXT) || record.payload.isEmpty()) return null
    val status = record.payload[0].toInt() and 0xFF
    val start = 1 + (status and 0x3F)
    if (start > record.payload.size) return null
    val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
    return String(record.payload, start, record.payload.size - start, charset)
  }
}

/** Smart Poster records (NFC Forum Smart Poster RTD): a nested message with a URI and a title. */
object SmartPoster {

  data class Poster(val uri: String, val title: String?)

  fun parse(record: NdefRecord): Poster? {
    if (!record.isWellKnown(NdefRecord.RTD_SMART_POSTER)) return null
    val inner = try {
      NdefMessage.parse(record.payload)
    } catch (e: NdefFormatException) {
      return null
    }
    val uri = inner.records.firstNotNullOfOrNull(UriRecord::parse) ?: return null
    return Poster(uri, inner.records.firstNotNullOfOrNull(TextRecord::parse))
  }
}
