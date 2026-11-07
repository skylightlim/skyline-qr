package com.skylightlim.skylineqr.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentCodeTest {

  /**
   * A made-up DuitNow-style static code, shaped like a real one (the same fields and lengths, 125
   * characters) but paying no one: never a real person's code in the repo.
   */
  private fun code(payee: String = "TESTPAYEE", account: String = "001234567890"): String {
    fun field(tag: String, value: String) = tag + "%02d".format(value.length) + value
    val merchant = field("00", "A0000006150001") + field("01", "890053") + field("02", account)
    val body =
      field("00", "01") + field("01", "11") + field("26", merchant) + field("52", "0000") + field("53", "458") +
        field("58", "MY") + field("59", payee) + field("60", "MY") + field("62", field("08", "TESTREF00")) + "6304"
    return body + PaymentCode.crc(body)
  }

  @Test
  fun theChecksumIsCrc16CcittFalse() {
    assertEquals("29B1", PaymentCode.crc("123456789"))
  }

  @Test
  fun aWholeCodeWithItsChecksumIsAPaymentCode() {
    assertTrue(PaymentCode.isPaymentCode(code()))
    assertEquals("TESTPAYEE", PaymentCode.payee(code()))
  }

  @Test
  fun oneChangedCharacterIsNoLongerOne() {
    val good = code()
    val broken = good.replaceRange(30, 31, if (good[30] == '1') "2" else "1")
    assertFalse(PaymentCode.isPaymentCode(broken))
    assertFalse(PaymentCode.isPaymentCode(good.dropLast(1)))
    assertFalse(PaymentCode.isPaymentCode("https://example.com/"))
    assertFalse(PaymentCode.isPaymentCode("0002"))
  }

  @Test
  fun aPaymentCodeBuildsACityKeptExactly() {
    val code = code()
    val link = Link.parse(" $code\n")
    assertTrue(link.isPaymentCode)
    assertEquals(code, link.payload)
    assertEquals("TESTPAYEE", link.displayHost)
    // Its own city, and another code another.
    assertNotEquals(CityIdentity.of(code).seed, CityIdentity.of(code(account = "009999999999")).seed)
    assertFalse(Link.parse("https://example.com/").isPaymentCode)
  }

  @Test
  fun aDuitNowLengthCodeFitsTheCityAndScansBackUnchanged() {
    val code = code()
    assertEquals(125, code.length)
    val matrix = QrEncoder.encode(code)
    assertTrue("version ${matrix.symbolVersion}", matrix.symbolVersion <= QrEncoder.MAX_SYMBOL_VERSION)
    assertEquals(code, scan(matrix))
  }

  @Test
  fun aPaymentCodesCityHasNoShareLink() {
    assertThrows(IllegalArgumentException::class.java) { ShareLink.encode(SharedCity(code())) }
  }

  /** What ZXing reads off [matrix] drawn in black on white, 8 pixels a module, with a quiet zone. */
  private fun scan(matrix: QrMatrix): String {
    val scale = 8
    val side = (matrix.size + 8) * scale
    fun dark(x: Int, y: Int): Boolean {
      val col = x / scale - 4
      val row = y / scale - 4
      return col in 0 until matrix.size && row in 0 until matrix.size && matrix.dark[row * matrix.size + col]
    }
    val source =
      object : LuminanceSource(side, side) {
        override fun getRow(y: Int, row: ByteArray?) = ByteArray(width) { x -> if (dark(x, y)) 0 else -1 }

        override fun getMatrix() = ByteArray(width * height) { i -> if (dark(i % width, i / width)) 0 else -1 }
      }
    return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
  }
}
