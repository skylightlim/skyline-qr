package com.skylightlim.skylineqr.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {

  @Test
  fun gridIsSquareAndRoundTrips() {
    val payload = Link.parse("https://github.com/skylightlim/skyline-qr").payload
    val matrix = QrEncoder.encode(payload)
    assertEquals(17 + 4 * matrix.symbolVersion, matrix.size)
    assertTrue(matrix.darkCount > matrix.size)
    assertEquals(payload, decode(matrix))
  }

  @Test
  fun capsAtSymbolVersionSix() {
    // Version 6 at error correction M holds 106 bytes; one more needs version 7.
    val fits = "https://example.com/" + "a".repeat(106 - 20)
    assertEquals(6, QrEncoder.encode(fits).symbolVersion)
    assertThrows(LinkTooLongException::class.java) { QrEncoder.encode(fits + "a") }
  }

  /** The pure Kotlin encoder must draw exactly ZXing's symbol, mask and all, for every payload. */
  @Test
  fun matchesZxing() {
    val rnd = java.util.SplittableRandom(7)
    val alnum = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    val payloads = ArrayList<String>()
    payloads += listOf("", "0", "A", "a", "https://example.com/", "HTTPS://EXAMPLE.COM/", "1234567890")
    for (len in 1..180) {
      payloads += String(CharArray(len) { (0x20 + rnd.nextInt(0x5f)).toChar() })
      payloads += String(CharArray(len) { alnum[rnd.nextInt(alnum.length)] })
      payloads += String(CharArray(len) { '0' + rnd.nextInt(10) })
      payloads += "https://" + String(CharArray(len) { 'a' + rnd.nextInt(26) }) + ".com/"
    }
    var compared = 0
    val mismatches = ArrayList<String>()
    for (p in payloads) {
      val zxing = runCatching { Encoder.encode(p, ErrorCorrectionLevel.M) }.getOrNull()
      if (zxing == null || zxing.version.versionNumber > QrEncoder.MAX_SYMBOL_VERSION) {
        assertThrows(p, LinkTooLongException::class.java) { QrEncoder.encode(p) }
        continue
      }
      val ours = QrEncoder.encode(p)
      assertEquals(p, zxing.version.versionNumber, ours.symbolVersion)
      val m = zxing.matrix
      val wrong = (0 until m.height * m.width).count { m.get(it % m.width, it / m.width).toInt() == 1 != ours.isDark(it % m.width, it / m.width) }
      if (wrong > 0) mismatches += "'$p' (v${ours.symbolVersion}, mask ${zxing.maskPattern}): $wrong modules"
      compared++
    }
    assertEquals(mismatches.take(8).joinToString("\n"), 0, mismatches.size)
    assertTrue(compared > 300)
  }

  /** Renders the modules with a quiet zone and reads them back with ZXing's own decoder. */
  private fun decode(matrix: QrMatrix): String {
    val scale = 4
    val quiet = 4
    val side = (matrix.size + 2 * quiet) * scale
    val pixels = IntArray(side * side) { i ->
      val col = (i % side) / scale - quiet
      val row = (i / side) / scale - quiet
      if (matrix.isDark(col, row)) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
    return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
  }
}
