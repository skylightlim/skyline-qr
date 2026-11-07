package com.skylightlim.skylineqr.nfc

import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.CardRules
import com.skylightlim.skylineqr.nfc.card.CardStore
import com.skylightlim.skylineqr.nfc.card.PaymentQr
import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PaymentCardsTest {

  @get:Rule val folder = TemporaryFolder()

  /** A made-up DuitNow-style code that pays no one; never a real person's code in the repo. */
  private val code: String =
    run {
      fun field(tag: String, value: String) = tag + "%02d".format(value.length) + value
      val merchant = field("00", "A0000006150001") + field("01", "890053") + field("02", "001234567890")
      val body =
        field("00", "01") + field("01", "11") + field("26", merchant) + field("52", "0000") + field("53", "458") +
          field("58", "MY") + field("59", "TESTPAYEE") + field("60", "MY") + field("62", field("08", "TESTREF00")) + "6304"
      body + PaymentQr.crc(body)
    }

  @Test
  fun aScannedPaymentCodeBecomesAPaymentCardKeptExactly() {
    val draft = CardCodec.fromScannedText(code)
    assertEquals(CardContent.Payment(code), draft.content)
    assertEquals("DuitNow", draft.title)
    assertEquals("TESTPAYEE", (draft.content as CardContent.Payment).payee)
    assertEquals(draft.content, CardRules.normalized(draft.content))
    assertNull(CardRules.problem(draft.content))
    assertEquals("Payment QR · TESTPAYEE", CardRules.preview(draft.content))
  }

  @Test
  fun aBrokenCodeIsTextAndADamagedCardIsRefused() {
    val broken = code.dropLast(1) + if (code.last() == '0') "1" else "0"
    assertEquals(CardContent.Text(broken), CardCodec.fromScannedText(broken).content)
    assertNotNull(CardRules.problem(CardContent.Payment(broken)))
  }

  @Test
  fun aPaymentCardSurvivesNfcAndTheStore() {
    val content = CardContent.Payment(code)
    assertEquals(content, CardCodec.fromNdef(NdefMessage.parse(CardCodec.toNdef(content).toByteArray()))?.content)
    assertEquals(code, CardCodec.qrText(content))
    val file = folder.root.resolve("cards.json")
    CardStore(file).add("DuitNow", content)
    assertTrue(CardStore(file).cards.value.any { it.content == content })
  }
}
