package com.skylightlim.skylineqr.nfc.write

import android.nfc.FormatException
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.read.TagInspector
import com.skylightlim.skylineqr.nfc.read.TagReading
import com.skylightlim.skylineqr.nfc.tag.NdefTlv
import java.io.ByteArrayOutputStream
import java.io.IOException
import android.nfc.NdefMessage as AndroidNdefMessage

/**
 * Writing one card to a tag, in two taps: the first shows what is on the tag now, a second tap of
 * the same tag replaces it. Blank tags are formatted. After writing, the tag is read back to check.
 * Tags are never made read-only.
 */
class TagWriteSession(content: CardContent) {

  /** The bytes that will be written. */
  val message: ByteArray = CardCodec.toNdef(content).toByteArray()

  sealed interface Step {
    /** First tap of this tag: here is what it holds; tap it again to replace it. */
    data class Confirm(val current: TagReading) : Step

    /** Done. [verified] is false only when the tag couldn't be read back. */
    data class Written(val replaced: TagReading, val verified: Boolean) : Step

    data class Failed(val reason: String) : Step
  }

  private var armedTag: ByteArray? = null

  /** Handles one tap. Blocking: call it off the main thread. */
  fun onTag(tag: Tag): Step {
    val current = try {
      TagInspector.read(tag)
    } catch (e: IOException) {
      return Step.Failed(MOVED_AWAY)
    }
    problem(current)?.let {
      armedTag = null
      return Step.Failed(it)
    }
    if (armedTag == null || !armedTag.contentEquals(tag.id)) {
      armedTag = tag.id
      return Step.Confirm(current)
    }
    return try {
      val verified = write(tag)
      armedTag = null
      Step.Written(current, verified)
    } catch (e: TagLostException) {
      Step.Failed(MOVED_AWAY)
    } catch (e: IOException) {
      Step.Failed("Couldn't write the tag (${e.message ?: "it stopped answering"}). Tap it again to retry.")
    } catch (e: FormatException) {
      Step.Failed("The tag refused the card's format.")
    }
  }

  private fun problem(reading: TagReading): String? = when (reading) {
    is TagReading.NotShareable -> "This is a ${reading.chip}, which can't hold a card."
    is TagReading.Content -> if (!reading.writable) LOCKED else tooBig(reading.capacity)
    is TagReading.Empty -> if (!reading.writable) LOCKED else reading.capacity?.let(::tooBig)
  }

  private fun tooBig(capacity: Int) =
    if (message.size > capacity) "This card is ${message.size} bytes; the tag holds $capacity." else null

  private fun write(tag: Tag): Boolean {
    val android = AndroidNdefMessage(message)
    Ndef.get(tag)?.let { ndef ->
      return ndef.use {
        it.connect()
        it.writeNdefMessage(android)
        it.ndefMessage?.toByteArray()?.contentEquals(message) == true
      }
    }
    val formatable = NdefFormatable.get(tag) ?: throw IOException("this tag doesn't take NDEF content")
    formatable.use {
      it.connect()
      it.format(android)
    }
    return readBackUltralight(tag)
  }

  /** A formatted tag doesn't offer Ndef until it is tapped again, so check NTAG memory directly. */
  private fun readBackUltralight(tag: Tag): Boolean {
    val ultralight = MifareUltralight.get(tag) ?: return false
    return try {
      ultralight.connect()
      val memory = ByteArrayOutputStream()
      var page = 4
      while (memory.size() < message.size + 8 && page < 256) {
        memory.write(ultralight.readPages(page)) // four pages, 16 bytes
        page += 4
      }
      NdefTlv.findMessage(memory.toByteArray())?.contentEquals(message) == true
    } catch (e: IOException) {
      false
    } finally {
      runCatching { ultralight.close() }
    }
  }

  private companion object {
    const val MOVED_AWAY = "The tag moved away too soon. Hold it still against the phone's back and tap again."
    const val LOCKED = "This tag is locked, so it can't be written."
  }
}
