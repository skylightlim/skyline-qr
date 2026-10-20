package com.skylightlim.skylineqr.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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
