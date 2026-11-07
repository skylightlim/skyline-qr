package com.skylightlim.skylineqr.nfc.ndef

import java.io.ByteArrayOutputStream

/*
 * NDEF records and messages as bytes, written from the NFC Forum NDEF 1.0 specification.
 *
 * This is plain Kotlin rather than android.nfc, so the same bytes go to a written tag and to the
 * phone's emulated tag, and every encoder runs in JVM unit tests.
 */

/** How a record's type field is to be read (NDEF 1.0, section 3.2.6). */
enum class Tnf(val code: Int) {
  EMPTY(0x00),
  WELL_KNOWN(0x01),
  MIME_MEDIA(0x02),
  ABSOLUTE_URI(0x03),
  EXTERNAL(0x04),
  UNKNOWN(0x05),
  UNCHANGED(0x06),
  RESERVED(0x07);

  companion object {
    fun of(header: Int): Tnf = entries[header and 0x07]
  }
}

class NdefFormatException(message: String) : Exception(message)

/** One NDEF record. The arrays are shared, not copied: treat them as read-only. */
class NdefRecord(
  val tnf: Tnf,
  val type: ByteArray,
  val payload: ByteArray,
  val id: ByteArray = ByteArray(0),
) {
  init {
    require(type.size <= 0xFF) { "A record type is at most 255 bytes" }
    require(id.size <= 0xFF) { "A record id is at most 255 bytes" }
    if (tnf == Tnf.EMPTY) {
      require(type.isEmpty() && payload.isEmpty() && id.isEmpty()) { "An empty record carries no fields" }
    }
  }

  /** The type as text: an RTD name such as "U", a MIME type, or an absolute URI. */
  val typeText: String get() = String(type, Charsets.US_ASCII)

  fun isWellKnown(rtd: ByteArray) = tnf == Tnf.WELL_KNOWN && type.contentEquals(rtd)

  fun isMime(vararg mimeTypes: String) = tnf == Tnf.MIME_MEDIA && typeText.substringBefore(';').trim().lowercase() in mimeTypes

  override fun equals(other: Any?) =
    other is NdefRecord && tnf == other.tnf && type.contentEquals(other.type) &&
      payload.contentEquals(other.payload) && id.contentEquals(other.id)

  override fun hashCode() =
    ((tnf.hashCode() * 31 + type.contentHashCode()) * 31 + payload.contentHashCode()) * 31 + id.contentHashCode()

  override fun toString() = "NdefRecord($tnf, type=$typeText, ${payload.size} bytes)"

  companion object {
    /** Record type definitions (NFC Forum RTD) used here. */
    val RTD_URI = byteArrayOf(0x55) // "U"
    val RTD_TEXT = byteArrayOf(0x54) // "T"
    val RTD_SMART_POSTER = byteArrayOf(0x53, 0x70) // "Sp"

    fun mime(mimeType: String, payload: ByteArray) =
      NdefRecord(Tnf.MIME_MEDIA, mimeType.lowercase().toByteArray(Charsets.US_ASCII), payload)
  }
}

/** An NDEF message: one or more records, the first flagged MB (begin) and the last ME (end). */
class NdefMessage(val records: List<NdefRecord>) {
  init {
    require(records.isNotEmpty()) { "An NDEF message has at least one record" }
  }

  constructor(vararg records: NdefRecord) : this(records.toList())

  fun toByteArray(): ByteArray {
    val out = ByteArrayOutputStream()
    records.forEachIndexed { index, record ->
      val short = record.payload.size <= 0xFF
      var header = record.tnf.code
      if (index == 0) header = header or MB
      if (index == records.lastIndex) header = header or ME
      if (short) header = header or SR
      if (record.id.isNotEmpty()) header = header or IL
      out.write(header)
      out.write(record.type.size)
      val length = record.payload.size
      if (short) {
        out.write(length)
      } else {
        out.write(length ushr 24)
        out.write(length ushr 16)
        out.write(length ushr 8)
        out.write(length)
      }
      if (record.id.isNotEmpty()) out.write(record.id.size)
      out.write(record.type)
      out.write(record.id)
      out.write(record.payload)
    }
    return out.toByteArray()
  }

  override fun equals(other: Any?) = other is NdefMessage && records == other.records

  override fun hashCode() = records.hashCode()

  override fun toString() = "NdefMessage($records)"

  companion object {
    private const val MB = 0x80
    private const val ME = 0x40
    private const val CF = 0x20
    private const val SR = 0x10
    private const val IL = 0x08

    /**
     * Reads one message. Bytes after the record flagged ME are ignored. Chunked records (CF) are
     * refused: tags and phones don't use them for content this small.
     */
    fun parse(bytes: ByteArray): NdefMessage {
      val records = mutableListOf<NdefRecord>()
      var at = 0
      fun need(count: Int) {
        if (count < 0 || count > bytes.size - at) throw NdefFormatException("The message ends inside a record")
      }
      fun u8() = bytes[at++].toInt() and 0xFF

      while (true) {
        need(2)
        val header = u8()
        val begins = header and MB != 0
        if (records.isEmpty() && !begins) throw NdefFormatException("The first record isn't flagged as the message start")
        if (records.isNotEmpty() && begins) throw NdefFormatException("A second message starts inside the first")
        if (header and CF != 0) throw NdefFormatException("Chunked records aren't supported")
        val typeLength = u8()
        val payloadLength = if (header and SR != 0) {
          need(1)
          u8()
        } else {
          need(4)
          val length = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
          if (length < 0) throw NdefFormatException("A record claims more than 2 GB")
          length
        }
        val idLength = if (header and IL != 0) {
          need(1)
          u8()
        } else {
          0
        }
        need(typeLength)
        val type = bytes.copyOfRange(at, at + typeLength).also { at += typeLength }
        need(idLength)
        val id = bytes.copyOfRange(at, at + idLength).also { at += idLength }
        need(payloadLength)
        val payload = bytes.copyOfRange(at, at + payloadLength).also { at += payloadLength }
        val tnf = Tnf.of(header)
        if (tnf == Tnf.EMPTY && (typeLength or idLength or payloadLength) != 0) {
          throw NdefFormatException("An empty record carries fields")
        }
        records += NdefRecord(tnf, type, payload, id)
        if (header and ME != 0) return NdefMessage(records)
      }
    }
  }
}
