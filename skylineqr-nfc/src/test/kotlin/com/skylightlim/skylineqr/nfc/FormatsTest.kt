package com.skylightlim.skylineqr.nfc

import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.MeCard
import com.skylightlim.skylineqr.nfc.card.VCard
import com.skylightlim.skylineqr.nfc.card.WifiQr
import com.skylightlim.skylineqr.nfc.card.WifiSecurity
import com.skylightlim.skylineqr.nfc.card.WscCredential
import com.skylightlim.skylineqr.nfc.tag.NdefTlv
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

  // Wi-Fi over NFC

  @Test
  fun wpaCredentialsAreWpa2PskWithAes() {
    val payload = WscCredential.encode(CardContent.Wifi("Rumah", "password1", WifiSecurity.WPA_WPA2))
    val text = hex(payload)
    assertTrue(text.startsWith("104A000110" + "100E")) // version 1.0, then the credential
    assertTrue("SSID", text.contains("10450005" + hex("Rumah".toByteArray())))
    assertTrue("auth WPA2-PSK", text.contains("100300020020"))
    assertTrue("encryption AES", text.contains("100F00020008"))
    assertTrue("key", text.contains("10270009" + hex("password1".toByteArray())))
    assertTrue("any access point", text.contains("10200006FFFFFFFFFFFF"))
    assertTrue("WSC 2.0", text.endsWith("10490006" + "00372A000120"))
  }

  @Test
  fun wpa3GoesOutAsWpa2AndOpenAsOpen() {
    val wpa3 = hex(WscCredential.encode(CardContent.Wifi("Net", "password1", WifiSecurity.WPA3)))
    assertTrue(wpa3.contains("100300020020"))
    val open = hex(WscCredential.encode(CardContent.Wifi("Cafe", "ignored", WifiSecurity.OPEN)))
    assertTrue(open.contains("100300020001"))
    assertTrue(open.contains("100F00020001"))
    assertTrue(open.contains("10270000"))
  }

  @Test
  fun wscRoundTrips() {
    val wpa = CardContent.Wifi("Lim; \"Home\" 你好", "p@ss;w,rd:\\", WifiSecurity.WPA_WPA2)
    assertEquals(wpa, WscCredential.decode(WscCredential.encode(wpa)))
    val open = CardContent.Wifi("Cafe", "", WifiSecurity.OPEN)
    assertEquals(open, WscCredential.decode(WscCredential.encode(open)))
    assertNull(WscCredential.decode(byteArrayOf(0x10, 0x4A, 0x00, 0x01, 0x10)))
  }

  // Wi-Fi in QR codes

  @Test
  fun wifiQrEscapesSpecialCharacters() {
    val wifi = CardContent.Wifi("My;Net", "pa:ss\\word\"", WifiSecurity.WPA_WPA2)
    assertEquals("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss\\\\word\\\";;", WifiQr.encode(wifi))
    assertEquals(wifi, WifiQr.parse(WifiQr.encode(wifi)))
  }

  @Test
  fun wifiQrReadsWhatOtherPhonesWrite() {
    assertEquals(
      CardContent.Wifi("Office 5G", "hunter22", WifiSecurity.WPA3),
      WifiQr.parse("WIFI:S:Office 5G;T:SAE;P:hunter22;H:false;;"),
    )
    assertEquals(CardContent.Wifi("Cafe", "", WifiSecurity.OPEN), WifiQr.parse("WIFI:T:nopass;S:Cafe;;"))
    assertEquals(CardContent.Wifi("Cafe", "", WifiSecurity.OPEN), WifiQr.parse("WIFI:S:Cafe;;"))
    assertEquals(CardContent.Wifi("12345", "abcdefgh", WifiSecurity.WPA_WPA2), WifiQr.parse("WIFI:S:\"12345\";P:abcdefgh;;"))
    // An SSID with "P:" in it is not mistaken for the password, as a regex search would.
    assertEquals(CardContent.Wifi("AP:1", "secret99", WifiSecurity.WPA_WPA2), WifiQr.parse("WIFI:S:AP\\:1;T:WPA;P:secret99;;"))
    assertNull(WifiQr.parse("WIFI:T:WPA;P:x;;"))
    assertNull(WifiQr.parse("https://example.com"))
  }

  // Contacts

  @Test
  fun vCardsAreVersion3WithEscapes() {
    val card = VCard.encode(CardContent.Contact("Lim, Chen Yu", "+60 12-345 6789", "cy@example.com", "UM; FSKTM", "https://example.com"))
    assertEquals(
      "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;Lim\\, Chen Yu;;;\r\nFN:Lim\\, Chen Yu\r\nORG:UM\\; FSKTM\r\n" +
        "TEL;TYPE=CELL:+60 12-345 6789\r\nEMAIL;TYPE=INTERNET:cy@example.com\r\nURL:https://example.com\r\nEND:VCARD\r\n",
      card,
    )
  }

  @Test
  fun longLinesFoldWithoutSplittingCharacters() {
    val name = "林".repeat(40) // 3 bytes each
    val card = VCard.encode(CardContent.Contact(name = name))
    card.split("\r\n").forEach { line -> assertTrue(line, line.toByteArray().size <= 75) }
    assertEquals(name, VCard.parse(card)?.name)
  }

  @Test
  fun vCardsRoundTrip() {
    val contact = CardContent.Contact("Lim Chen Yu", "+60123456789", "cy@example.com", "Universiti Malaya", "https://instagram.com/chenyuulim")
    assertEquals(contact, VCard.parse(VCard.encode(contact)))
    val noName = CardContent.Contact(phone = "+60123456789")
    assertEquals(noName, VCard.parse(VCard.encode(noName))?.copy(name = ""))
  }

  @Test
  fun vCardsFromOtherAppsAreRead() {
    val apple = "BEGIN:VCARD\nVERSION:3.0\nN:Lim;Chen Yu;;;\nFN:Chen Yu Lim\nitem1.TEL;type=pref:012-345 6789\n" +
      "item2.URL;type=pref:https://example.com\nORG:UM;FSKTM\nEND:VCARD"
    assertEquals(CardContent.Contact("Chen Yu Lim", "012-345 6789", "", "UM", "https://example.com"), VCard.parse(apple))
    val old = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=9E=97;=E6=99=A8=\r\n=E5=AE=87\r\nEND:VCARD"
    assertEquals("晨宇 林", VCard.parse(old)?.name)
    assertNull(VCard.parse("hello"))
  }

  @Test
  fun meCardsAreRead() {
    assertEquals(
      CardContent.Contact("Lim Chen Yu", "+60123456789", "cy@example.com", "", "https://example.com/a;b"),
      MeCard.parse("MECARD:N:Lim,Chen Yu;TEL:+60123456789;EMAIL:cy@example.com;URL:https\\://example.com/a\\;b;;"),
    )
    assertNull(MeCard.parse("MECARD:;;"))
  }

  // Tag memory

  @Test
  fun theNdefTlvIsFoundPastControlTlvs() {
    val message = byteArrayOf(0xD1.toByte(), 0x01, 0x01, 0x54, 0x00)
    val memory = byteArrayOf(0x01, 0x03, 0xA0.toByte(), 0x0C, 0x34, 0x00, 0x03, 0x05) + message + byteArrayOf(0xFE.toByte(), 0, 0)
    assertArrayEquals(message, NdefTlv.findMessage(memory))
    val long = ByteArray(300) { 7 }
    assertArrayEquals(long, NdefTlv.findMessage(byteArrayOf(0x03, 0xFF.toByte(), 0x01, 0x2C) + long))
    assertNull(NdefTlv.findMessage(byteArrayOf(0xFE.toByte(), 0x03, 0x01, 0x00)))
    assertNull(NdefTlv.findMessage(byteArrayOf(0x03, 0x10, 0x00)))
  }
}
