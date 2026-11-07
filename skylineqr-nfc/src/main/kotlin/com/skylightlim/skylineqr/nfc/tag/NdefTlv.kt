package com.skylightlim.skylineqr.nfc.tag

/**
 * The TLV blocks in a Type 2 tag's data area (NTAG and MIFARE Ultralight memory from page 4 on), as
 * NFC Forum Type 2 Tag Operation 1.0 lays them out. Used to check a freshly formatted tag by reading
 * its raw memory back.
 */
object NdefTlv {

  private const val NULL = 0x00
  private const val NDEF_MESSAGE = 0x03
  private const val TERMINATOR = 0xFE

  /** The bytes of the first NDEF Message TLV in [memory], or null if there is none. */
  fun findMessage(memory: ByteArray): ByteArray? {
    var at = 0
    while (at < memory.size) {
      val type = memory[at].toInt() and 0xFF
      when (type) {
        NULL -> at++
        TERMINATOR -> return null
        else -> {
          if (at + 1 >= memory.size) return null
          var length = memory[at + 1].toInt() and 0xFF
          var start = at + 2
          if (length == 0xFF) {
            if (at + 3 >= memory.size) return null
            length = (memory[at + 2].toInt() and 0xFF shl 8) or (memory[at + 3].toInt() and 0xFF)
            start = at + 4
          }
          if (length > memory.size - start) return null
          if (type == NDEF_MESSAGE) return memory.copyOfRange(start, start + length)
          at = start + length
        }
      }
    }
    return null
  }
}
