package com.skylightlim.skylineqr.nfc

import com.skylightlim.skylineqr.nfc.ndef.NdefFormatException
import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import com.skylightlim.skylineqr.nfc.ndef.NdefRecord
import com.skylightlim.skylineqr.nfc.ndef.SmartPoster
import com.skylightlim.skylineqr.nfc.ndef.TextRecord
import com.skylightlim.skylineqr.nfc.ndef.Tnf
import com.skylightlim.skylineqr.nfc.ndef.UriRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NdefTest {

  @Test
  fun profileLinkIsTheTextbookUriRecord() {
    val bytes = NdefMessage(UriRecord.create("https://www.instagram.com/chenyuulim/")).toByteArray()
    val rest = "instagram.com/chenyuulim/".toByteArray()
    // MB|ME|SR|TNF=1, type length 1, payload length, "U", prefix 0x02 = "https://www."
    val expected = byteArrayOf(0xD1.toByte(), 0x01, (rest.size + 1).toByte(), 0x55, 0x02) + rest
    assertArrayEquals(expected, bytes)
  }

  @Test
  fun linksLongerThan254BytesUseTheLongLengthForm() {
    // Tap Tap Tap wrote one length byte whatever the size, so long links came out corrupt.
    val url = "https://maps.example.com/place?" + "q=x".repeat(120)
    val bytes = NdefMessage(UriRecord.create(url)).toByteArray()
    assertEquals(0xC1, bytes[0].toInt() and 0xFF) // MB|ME, no SR
    val length = ((bytes[2].toInt() and 0xFF) shl 24) or ((bytes[3].toInt() and 0xFF) shl 16) or
      ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
    assertEquals(1 + url.length - "https://".length, length)
    assertEquals(url, UriRecord.parse(NdefMessage.parse(bytes).records.single()))
  }

  @Test
  fun theLongestPrefixWins() {
    assertEquals(0x02, UriRecord.create("https://www.x.com").payload[0].toInt())
    assertEquals(0x04, UriRecord.create("https://x.com").payload[0].toInt())
    assertEquals(0x05, UriRecord.create("tel:+60123456789").payload[0].toInt())
    assertEquals(0x1E, UriRecord.create("urn:epc:id:sgtin:1").payload[0].toInt())
    assertEquals(0x00, UriRecord.create("geo:3.12,101.65").payload[0].toInt())
  }

  @Test
  fun reservedPrefixCodesAddNothing() {
    val record = NdefRecord(Tnf.WELL_KNOWN, NdefRecord.RTD_URI, byteArrayOf(0x7F) + "x.com".toByteArray())
    assertEquals("x.com", UriRecord.parse(record))
  }

  @Test
  fun textRecordsCarryTheirLanguage() {
    val record = TextRecord.create("Hai! 你好")
    assertEquals(2, record.payload[0].toInt()) // UTF-8, two-byte language tag
    assertEquals("en", String(record.payload, 1, 2))
    assertEquals("Hai! 你好", TextRecord.parse(record))
  }

  @Test
  fun utf16TextRecordsAreRead() {
    val text = "Selamat datang".toByteArray(Charsets.UTF_16BE)
    val record = NdefRecord(Tnf.WELL_KNOWN, NdefRecord.RTD_TEXT, byteArrayOf(0x82.toByte()) + "ms".toByteArray() + text)
    assertEquals("Selamat datang", TextRecord.parse(record))
  }

  @Test
  fun multiRecordMessagesRoundTrip() {
    val message = NdefMessage(
      UriRecord.create("https://example.com"),
      TextRecord.create("hello"),
      NdefRecord.mime("text/plain", ByteArray(300) { 'a'.code.toByte() }),
      NdefRecord(Tnf.EXTERNAL, "android.com:pkg".toByteArray(), "com.example".toByteArray(), id = byteArrayOf(1, 2)),
    )
    val bytes = message.toByteArray()
    assertEquals(message, NdefMessage.parse(bytes))
    assertEquals(0x91, bytes[0].toInt() and 0xFF) // MB|SR|TNF=1: first, not last
  }

  @Test
  fun smartPostersGiveTheirLinkAndTitle() {
    val inner = NdefMessage(UriRecord.create("https://example.com/menu"), TextRecord.create("Menu"))
    val poster = NdefRecord(Tnf.WELL_KNOWN, NdefRecord.RTD_SMART_POSTER, inner.toByteArray())
    assertEquals(SmartPoster.Poster("https://example.com/menu", "Menu"), SmartPoster.parse(poster))
  }

  @Test
  fun brokenMessagesAreRefused() {
    val good = NdefMessage(TextRecord.create("hello")).toByteArray()
    assertThrows(NdefFormatException::class.java) { NdefMessage.parse(good.copyOf(good.size - 1)) }
    assertThrows(NdefFormatException::class.java) { NdefMessage.parse(byteArrayOf()) }
    val noBegin = good.copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
    assertThrows(NdefFormatException::class.java) { NdefMessage.parse(noBegin) }
    val chunked = good.copyOf().also { it[0] = (it[0].toInt() or 0x20).toByte() }
    assertThrows(NdefFormatException::class.java) { NdefMessage.parse(chunked) }
  }

  @Test
  fun otherRecordsAreNotLinksOrText() {
    val mime = NdefRecord.mime("text/vcard", "x".toByteArray())
    assertNull(UriRecord.parse(mime))
    assertNull(TextRecord.parse(mime))
  }
}
