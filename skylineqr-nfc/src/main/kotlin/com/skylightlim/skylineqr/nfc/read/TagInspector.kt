package com.skylightlim.skylineqr.nfc.read

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.Draft
import com.skylightlim.skylineqr.nfc.ndef.NdefFormatException
import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import com.skylightlim.skylineqr.nfc.ndef.Tnf
import java.io.IOException

/** What a tapped tag (or phone) holds. */
sealed interface TagReading {
  /** What kind of chip it is, for people: "NTAG215", "MIFARE Classic 1K", … */
  val chip: String

  /**
   * The tag holds an NDEF message. [draft] is what a card can make of it, or null when none of its
   * records is a link, text, contact or Wi-Fi network.
   */
  data class Content(
    override val chip: String,
    val draft: Draft?,
    val bytes: Int,
    val capacity: Int,
    val writable: Boolean,
  ) : TagReading

  /** An NDEF tag with nothing on it, or a blank tag that will be formatted when written. */
  data class Empty(override val chip: String, val capacity: Int?, val writable: Boolean) : TagReading

  /** An access, transport or payment card: no content to show, and nothing is copied. */
  data class NotShareable(override val chip: String) : TagReading
}

object TagInspector {

  /** Reads [tag]. Blocking: call it off the main thread (the reader callback's thread is fine). */
  @Throws(IOException::class)
  fun read(tag: Tag): TagReading {
    val chip = ChipNames.describe(tag)
    Ndef.get(tag)?.let { return readNdef(it, chip) }
    if (NdefFormatable.get(tag) != null) {
      return TagReading.Empty(chip, ChipNames.ntag(tag)?.let { it.userBytes - TLV_OVERHEAD }, writable = true)
    }
    return TagReading.NotShareable(chip)
  }

  private fun readNdef(ndef: Ndef, chip: String): TagReading = ndef.use {
    it.connect()
    val bytes = try {
      it.ndefMessage?.toByteArray()
    } catch (e: android.nfc.FormatException) {
      null
    }
    val message = bytes?.let { raw ->
      try {
        NdefMessage.parse(raw)
      } catch (e: NdefFormatException) {
        null
      }
    }
    when {
      bytes == null -> TagReading.Empty(chip, it.maxSize, it.isWritable)
      message != null && message.records.all { record -> record.tnf == Tnf.EMPTY } ->
        TagReading.Empty(chip, it.maxSize, it.isWritable)
      // A message this code can't parse still counts as content: writing would replace it.
      else -> TagReading.Content(chip, message?.let(CardCodec::fromNdef), bytes.size, it.maxSize, it.isWritable)
    }
  }

  /** NDEF TLV header (up to 4 bytes) plus the terminator TLV. */
  private const val TLV_OVERHEAD = 5
}

/** Names chips from their technologies, NFC-A SAK and, for NTAG, the GET_VERSION answer. */
internal object ChipNames {

  enum class Ntag(val label: String, val userBytes: Int) {
    NTAG213("NTAG213", 144),
    NTAG215("NTAG215", 504),
    NTAG216("NTAG216", 888),
  }

  fun describe(tag: Tag): String {
    val techs = tag.techList.map { it.substringAfterLast('.') }.toSet()
    ntag(tag)?.let { return it.label }
    MifareClassic.get(tag)?.let { return classic(it.size) }
    MifareUltralight.get(tag)?.let {
      return if (it.type == MifareUltralight.TYPE_ULTRALIGHT_C) "MIFARE Ultralight C" else "MIFARE Ultralight"
    }
    if ("IsoDep" in techs) {
      return if ("Ndef" in techs) "NFC Forum Type 4 tag or phone" else "Contactless smart card (ISO 14443-4)"
    }
    // Phones without NXP readers don't list MifareClassic, but the SAK still gives it away.
    NfcA.get(tag)?.let { nfcA ->
      when (nfcA.sak.toInt()) {
        0x08 -> return classic(MifareClassic.SIZE_1K)
        0x18 -> return classic(MifareClassic.SIZE_4K)
        0x09 -> return classic(MifareClassic.SIZE_MINI)
      }
    }
    return when {
      "NfcF" in techs -> "FeliCa"
      "NfcV" in techs -> "ISO 15693 (NFC-V) tag"
      "NfcB" in techs -> "ISO 14443-B tag"
      "NfcA" in techs -> "ISO 14443-A tag"
      else -> "Unknown chip"
    }
  }

  /** NTAG213/215/216, told apart by the storage size in their GET_VERSION answer. */
  fun ntag(tag: Tag): Ntag? {
    val ultralight = MifareUltralight.get(tag) ?: return null
    return try {
      ultralight.connect()
      val version = ultralight.transceive(byteArrayOf(0x60))
      val isNxpNtag = version.size >= 8 && version[1] == 0x04.toByte() && version[2] == 0x04.toByte()
      if (!isNxpNtag) {
        null
      } else {
        when (version[6].toInt() and 0xFF) {
          0x0F -> Ntag.NTAG213
          0x11 -> Ntag.NTAG215
          0x13 -> Ntag.NTAG216
          else -> null
        }
      }
    } catch (e: IOException) {
      null
    } finally {
      runCatching { ultralight.close() }
    }
  }

  private fun classic(size: Int) = when (size) {
    MifareClassic.SIZE_MINI -> "MIFARE Classic Mini"
    MifareClassic.SIZE_4K -> "MIFARE Classic 4K"
    MifareClassic.SIZE_2K -> "MIFARE Classic 2K"
    else -> "MIFARE Classic 1K"
  }
}
