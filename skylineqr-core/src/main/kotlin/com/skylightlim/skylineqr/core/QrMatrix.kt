package com.skylightlim.skylineqr.core

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** The raw QR module grid, no quiet zone. `true` is a dark module. */
class QrMatrix(val size: Int, val dark: BooleanArray, val symbolVersion: Int) {

  fun isDark(col: Int, row: Int): Boolean =
    col in 0 until size && row in 0 until size && dark[row * size + col]

  val darkCount: Int
    get() = dark.count { it }

  /** What each module is for, row by row like [dark]. */
  val roles: Array<QrRole> by lazy { QrRoles.of(symbolVersion) }

  fun role(col: Int, row: Int): QrRole = roles[row * size + col]
}

/**
 * Encodes a link's payload with the QR profile of its generator version.
 *
 * Version 1: error correction M, at most symbol version 6 (41 x 41). Larger symbols become towers
 * too small to scan off a phone screen, so a longer link is refused rather than squeezed in.
 */
object QrEncoder {

  const val MAX_SYMBOL_VERSION = 6

  fun encode(payload: String, generatorVersion: Int = GeneratorVersion.CURRENT): QrMatrix {
    GeneratorVersion.resolve(generatorVersion)
    // Payloads are ASCII: percent-encoded links, which ZXing puts in byte mode without an ECI
    // header, or payment codes (digits and capitals), which it puts in alphanumeric mode, the
    // denser one, so a 125-character DuitNow code still fits version 6 at M.
    require(payload.all { it.code in 0x20..0x7e }) { "payload must be ASCII: $payload" }
    val code =
      try {
        Encoder.encode(payload, ErrorCorrectionLevel.M)
      } catch (e: WriterException) {
        throw LinkTooLongException()
      }
    if (code.version.versionNumber > MAX_SYMBOL_VERSION) throw LinkTooLongException()

    val matrix = code.matrix
    val dark = BooleanArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
      for (x in 0 until matrix.width) {
        dark[y * matrix.width + x] = matrix.get(x, y).toInt() == 1
      }
    }
    return QrMatrix(matrix.width, dark, code.version.versionNumber)
  }
}
