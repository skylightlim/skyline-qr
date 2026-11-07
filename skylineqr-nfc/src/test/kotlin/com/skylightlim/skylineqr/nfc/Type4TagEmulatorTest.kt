package com.skylightlim.skylineqr.nfc

import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import com.skylightlim.skylineqr.nfc.tag.Type4TagEmulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Plays a phone's tag reader against the emulated tag, the way Android and iOS read Type 4 tags. */
class Type4TagEmulatorTest {

  private var sharing: ByteArray? = null
  private var reads = 0
  private val tag = Type4TagEmulator { sharing }.apply { onMessageRead = { reads++ } }

  private fun apdu(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }
  private fun send(vararg bytes: Int) = tag.process(apdu(*bytes))
  private fun status(response: ByteArray) = response.takeLast(2).joinToString("") { "%02X".format(it) }
  private fun data(response: ByteArray) = response.copyOf(response.size - 2)

  private val selectApp = intArrayOf(0x00, 0xA4, 0x04, 0x00, 0x07, 0xD2, 0x76, 0x00, 0x00, 0x85, 0x01, 0x01, 0x00)
  private val selectCc = intArrayOf(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x03)
  private val selectNdef = intArrayOf(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x04)
  private fun readBinary(offset: Int, length: Int) = send(0x00, 0xB0, offset ushr 8, offset and 0xFF, length)

  /** Reads the NDEF message the way a phone does: CC, NLEN, then chunks of at most MLe bytes. */
  private fun readLikeAPhone(): ByteArray {
    assertEquals("9000", status(send(*selectApp)))
    assertEquals("9000", status(send(*selectCc)))
    val cc = data(readBinary(0, 15))
    val mle = ((cc[3].toInt() and 0xFF) shl 8) or (cc[4].toInt() and 0xFF)
    assertEquals("9000", status(send(*selectNdef)))
    val nlenBytes = data(readBinary(0, 2))
    val nlen = ((nlenBytes[0].toInt() and 0xFF) shl 8) or (nlenBytes[1].toInt() and 0xFF)
    val out = ByteArrayOutputStream()
    var offset = 2
    while (offset < 2 + nlen) {
      val chunk = readBinary(offset, minOf(mle, 2 + nlen - offset))
      assertEquals("9000", status(chunk))
      out.write(data(chunk))
      offset += chunk.size - 2
    }
    return out.toByteArray()
  }

  @Test
  fun aPhoneReadsTheSharedCardInChunks() {
    val message = CardCodec.toNdef(CardContent.Text("x".repeat(700))).toByteArray()
    sharing = message
    assertArrayEquals(message, readLikeAPhone())
    assertEquals(1, reads)
  }

  @Test
  fun theCapabilityContainerDescribesAReadOnlyTag() {
    sharing = CardCodec.toNdef(CardContent.Link("https://www.instagram.com/chenyuulim/")).toByteArray()
    send(*selectApp)
    send(*selectCc)
    val cc = data(readBinary(0, 15))
    assertEquals(15, cc.size)
    assertEquals(0x20, cc[2].toInt()) // mapping version 2.0
    assertArrayEquals(apdu(0x04, 0x06, 0xE1, 0x04), cc.copyOfRange(7, 11))
    val maxSize = ((cc[11].toInt() and 0xFF) shl 8) or (cc[12].toInt() and 0xFF)
    assertEquals(sharing!!.size + 2, maxSize)
    assertEquals(0x00, cc[13].toInt()) // read: free
    assertEquals(0xFF, cc[14].toInt() and 0xFF) // write: never
  }

  @Test
  fun nothingSharedMeansNoTag() {
    sharing = null
    assertEquals("6A82", status(send(*selectApp)))
    assertEquals("6A82", status(send(*selectNdef)))
    assertEquals("6986", status(readBinary(0, 2)))
  }

  @Test
  fun otherApplicationsAreNotAnswered() {
    sharing = byteArrayOf(1)
    assertEquals("6A82", status(send(0x00, 0xA4, 0x04, 0x00, 0x07, 0xA0, 0x00, 0x00, 0x00, 0x03, 0x10, 0x10, 0x00)))
  }

  @Test
  fun aMessageReadTwiceInOneSelectionCountsOnce() {
    sharing = CardCodec.toNdef(CardContent.Text("hi")).toByteArray()
    send(*selectApp)
    send(*selectNdef)
    readBinary(0, 0) // Le 0: everything, up to 256 bytes
    readBinary(0, 0)
    assertEquals(1, reads)
  }

  @Test
  fun aShareStoppedMidTapStillReadsConsistently() {
    val message = CardCodec.toNdef(CardContent.Text("first")).toByteArray()
    sharing = message
    send(*selectApp)
    sharing = CardCodec.toNdef(CardContent.Text("second, and longer")).toByteArray()
    send(*selectNdef)
    val file = data(readBinary(0, 0))
    assertArrayEquals(message, file.copyOfRange(2, file.size))
  }

  @Test
  fun badCommandsGetTheirStatusWords() {
    sharing = byteArrayOf(1, 2, 3)
    send(*selectApp)
    send(*selectNdef)
    assertEquals("6B00", status(readBinary(100, 1))) // past the end
    assertEquals("6982", status(send(0x00, 0xD6, 0x00, 0x00, 0x01, 0x00))) // writing
    assertEquals("6D00", status(send(0x00, 0xCA, 0x00, 0x00, 0x00))) // unknown instruction
    assertEquals("6E00", status(send(0x90, 0xB0, 0x00, 0x00, 0x02))) // unknown class
    assertEquals("6700", status(send(0x00, 0xA4))) // too short
    assertEquals("6A82", status(send(0x00, 0xA4, 0x00, 0x0C, 0x02, 0xE1, 0x05))) // no such file
  }

  @Test
  fun leavingTheFieldForgetsTheSelection() {
    sharing = byteArrayOf(1)
    send(*selectApp)
    send(*selectNdef)
    tag.deactivate()
    assertEquals("6986", status(readBinary(0, 2)))
  }

  @Test
  fun theReadMessageParsesBack() {
    val content = CardContent.Wifi("Rumah Lim", "s3cret;pass", com.skylightlim.skylineqr.nfc.card.WifiSecurity.WPA_WPA2)
    sharing = CardCodec.toNdef(content).toByteArray()
    assertEquals(content, CardCodec.fromNdef(NdefMessage.parse(readLikeAPhone()))?.content)
  }
}
