package com.skylightlim.skylineqr.nfc.tag

/**
 * A read-only NFC Forum Type 4 Tag (Type 4 Tag Operation 2.0), answering a reader's APDUs.
 *
 * The reader selects the NDEF tag application, reads the capability container (file E103) to learn
 * the NDEF file's size and access rights, then reads the NDEF file (E104): two length bytes (NLEN)
 * followed by the message. Writes are refused.
 *
 * [currentMessage] gives the NDEF message to hand over, or null while nothing is being shared; the
 * tag then doesn't answer to its application ID at all, so a reader sees no tag. The message is
 * fixed when the reader selects the application, so one read always sees one consistent message.
 *
 * Not thread-safe: Android calls a HostApduService on its main thread, one APDU at a time.
 */
class Type4TagEmulator(private val currentMessage: () -> ByteArray?) {

  /** Called once per selection, when the reader has read the NDEF file to its last byte. */
  var onMessageRead: (() -> Unit)? = null

  private var ndefFile: ByteArray? = null
  private var selected: ByteArray? = null
  private var selectedIsNdef = false
  private var reported = false

  fun process(apdu: ByteArray): ByteArray {
    if (apdu.size < 4) return sw(0x67, 0x00) // wrong length
    if (apdu[0] != 0x00.toByte()) return sw(0x6E, 0x00) // class not supported
    return when (apdu[1].toInt() and 0xFF) {
      INS_SELECT -> select(apdu)
      INS_READ_BINARY -> readBinary(apdu)
      INS_UPDATE_BINARY -> sw(0x69, 0x82) // security status not satisfied: the tag is read-only
      else -> sw(0x6D, 0x00) // instruction not supported
    }
  }

  /** The reader left the field or picked another application. */
  fun deactivate() {
    ndefFile = null
    selected = null
    selectedIsNdef = false
    reported = false
  }

  private fun select(apdu: ByteArray): ByteArray {
    val data = commandData(apdu) ?: return sw(0x67, 0x00)
    return when (apdu[2].toInt() and 0xFF) {
      0x04 -> selectApplication(data)
      0x00 -> selectFile(data)
      else -> sw(0x6A, 0x86) // incorrect P1-P2
    }
  }

  private fun selectApplication(aid: ByteArray): ByteArray {
    deactivate()
    val message = currentMessage()
    if (!aid.contentEquals(NDEF_APPLICATION_AID) || message == null || message.size > MAX_MESSAGE_SIZE) {
      return sw(0x6A, 0x82) // not found
    }
    ndefFile = byteArrayOf((message.size ushr 8).toByte(), message.size.toByte()) + message
    return sw(0x90, 0x00)
  }

  private fun selectFile(id: ByteArray): ByteArray {
    val file = ndefFile
    if (file == null || id.size != 2) return sw(0x6A, 0x82)
    when ((id[0].toInt() and 0xFF shl 8) or (id[1].toInt() and 0xFF)) {
      CC_FILE_ID -> {
        selected = capabilityContainer(file.size)
        selectedIsNdef = false
      }
      NDEF_FILE_ID -> {
        selected = file
        selectedIsNdef = true
        reported = false
      }
      else -> {
        selected = null
        return sw(0x6A, 0x82)
      }
    }
    return sw(0x90, 0x00)
  }

  private fun readBinary(apdu: ByteArray): ByteArray {
    val file = selected ?: return sw(0x69, 0x86) // no current file
    val p1 = apdu[2].toInt() and 0xFF
    if (p1 and 0x80 != 0) return sw(0x6A, 0x86) // short file id addressing isn't offered
    val offset = (p1 shl 8) or (apdu[3].toInt() and 0xFF)
    // Le of 0, or none at all, asks for as much as fits: 256 bytes in a short APDU.
    val wanted = when (apdu.size) {
      4 -> 256
      5 -> (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }
      else -> return sw(0x67, 0x00)
    }
    if (offset > file.size) return sw(0x6B, 0x00) // offset outside the file
    val count = minOf(wanted, file.size - offset)
    if (selectedIsNdef && count > 0 && offset + count == file.size && !reported) {
      reported = true
      onMessageRead?.invoke()
    }
    return file.copyOfRange(offset, offset + count) + sw(0x90, 0x00)
  }

  companion object {
    /** The NDEF tag application (Type 4 Tag 2.0, section 5.4.2). */
    val NDEF_APPLICATION_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

    /** The capability container allows NDEF files up to 0x7FFF bytes, two of them the length. */
    const val MAX_MESSAGE_SIZE = 0x7FFF - 2

    /** Most bytes a reader should ask for in one READ BINARY. */
    private const val MLE = 0x00FF

    private const val INS_SELECT = 0xA4
    private const val INS_READ_BINARY = 0xB0
    private const val INS_UPDATE_BINARY = 0xD6
    private const val CC_FILE_ID = 0xE103
    private const val NDEF_FILE_ID = 0xE104

    private fun capabilityContainer(ndefFileSize: Int) = byteArrayOf(
      0x00, 0x0F, // CCLEN: 15 bytes
      0x20, // mapping version 2.0
      (MLE ushr 8).toByte(), MLE.toByte(), // MLe
      0x00, 0x01, // MLc: nothing is ever written
      0x04, 0x06, // NDEF File Control TLV
      0xE1.toByte(), 0x04, // its file id
      (ndefFileSize ushr 8).toByte(), ndefFileSize.toByte(), // maximum NDEF file size
      0x00, // read access: free
      0xFF.toByte(), // write access: none
    )

    /** The command data after Lc, or null if the APDU's length doesn't add up. */
    private fun commandData(apdu: ByteArray): ByteArray? {
      if (apdu.size <= 5) return ByteArray(0)
      val lc = apdu[4].toInt() and 0xFF
      if (lc == 0 || (apdu.size != 5 + lc && apdu.size != 6 + lc)) return null
      return apdu.copyOfRange(5, 5 + lc)
    }

    private fun sw(sw1: Int, sw2: Int) = byteArrayOf(sw1.toByte(), sw2.toByte())
  }
}
