package com.skylightlim.skylineqr.nfc

import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.CardRules
import com.skylightlim.skylineqr.nfc.card.CardStore
import com.skylightlim.skylineqr.nfc.card.Draft
import com.skylightlim.skylineqr.nfc.card.WifiSecurity
import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardsTest {

  @get:Rule val folder = TemporaryFolder()

  // Codec

  @Test
  fun everyKindOfCardSurvivesNfc() {
    listOf(
      CardContent.Link("https://www.instagram.com/chenyuulim/"),
      CardContent.Link("tel:+60123456789"),
      CardContent.Text("Hai semua!\nLine two"),
      CardContent.Contact("Lim Chen Yu", "+60123456789", "cy@example.com", "UM", "https://example.com"),
      CardContent.Wifi("Rumah", "password1", WifiSecurity.WPA_WPA2),
      CardContent.Wifi("Cafe", "", WifiSecurity.OPEN),
    ).forEach { content ->
      val bytes = CardCodec.toNdef(content).toByteArray()
      assertEquals(content, CardCodec.fromNdef(NdefMessage.parse(bytes))?.content)
    }
  }

  @Test
  fun qrCodesUseTheFormatsCamerasKnow() {
    assertEquals("https://example.com", CardCodec.qrText(CardContent.Link("https://example.com")))
    assertEquals("WIFI:T:SAE;S:Net;P:password1;;", CardCodec.qrText(CardContent.Wifi("Net", "password1", WifiSecurity.WPA3)))
    assertTrue(CardCodec.qrText(CardContent.Contact("Lim")).startsWith("BEGIN:VCARD\r\nVERSION:3.0"))
  }

  @Test
  fun scannedCodesBecomeTheRightKindOfCard() {
    assertEquals(CardContent.Wifi("Net", "password1", WifiSecurity.WPA_WPA2), CardCodec.fromScannedText("WIFI:S:Net;T:WPA;P:password1;;").content)
    assertEquals("Lim Chen Yu", (CardCodec.fromScannedText("MECARD:N:Lim,Chen Yu;;").content as CardContent.Contact).name)
    assertEquals(Draft("example.com", CardContent.Link("https://example.com/x")), CardCodec.fromScannedText("https://example.com/x"))
    assertEquals(CardContent.Link("https://www.um.edu.my"), CardCodec.fromScannedText("www.um.edu.my").content)
    assertEquals(CardContent.Text("Join us at https://example.com"), CardCodec.fromScannedText("Join us at https://example.com").content)
  }

  @Test
  fun sharedTextFindsTheLink() {
    assertEquals(
      Draft("Check this out", CardContent.Link("https://example.com/post")),
      CardCodec.fromSharedText("Check this out: https://example.com/post."),
    )
    assertEquals(
      Draft("instagram.com", CardContent.Link("https://www.instagram.com/p/abc/?igsh=xyz")),
      CardCodec.fromSharedText("https://www.instagram.com/p/abc/?igsh=xyz"),
    )
    assertEquals("Article", CardCodec.fromSharedText("https://example.com", subject = "Article").title)
    assertEquals(CardContent.Text("just words"), CardCodec.fromSharedText("just words").content)
  }

  // Rules

  @Test
  fun linksGetASchemeAndALowerCaseOne() {
    assertEquals("https://instagram.com/chenyuulim", CardRules.normalizeLink(" instagram.com/chenyuulim "))
    assertEquals("https://Example.com/A", CardRules.normalizeLink("HTTPS://Example.com/A"))
    assertEquals("tel:+60123", CardRules.normalizeLink("TEL:+60123"))
    assertEquals("https://localhost:8080/x", CardRules.normalizeLink("localhost:8080/x"))
  }

  @Test
  fun wifiRulesFollowWpa() {
    fun problem(ssid: String, password: String, security: WifiSecurity = WifiSecurity.WPA_WPA2) =
      CardRules.problem(CardContent.Wifi(ssid, password, security))
    assertNull(problem("Net", "12345678"))
    assertNotNull(problem("Net", "1234567"))
    assertNotNull(problem("Net", "x".repeat(64)))
    assertNull(problem("Net", "ab".repeat(32))) // a 64-digit hex key
    assertNull(problem("Cafe", "", WifiSecurity.OPEN))
    assertNotNull(problem("", "12345678"))
    assertNotNull(problem("网".repeat(11), "12345678")) // 33 bytes
    assertNotNull(CardRules.warning(CardContent.Wifi("Net", "12345678", WifiSecurity.WPA3)))
  }

  @Test
  fun titlesComeFromTheContent() {
    assertEquals("instagram.com", CardRules.suggestedTitle(CardContent.Link("https://www.instagram.com/chenyuulim/")))
    assertEquals("+60123", CardRules.suggestedTitle(CardContent.Link("tel:+60123")))
    assertEquals("Rumah", CardRules.suggestedTitle(CardContent.Wifi("Rumah")))
    assertEquals("UM", CardRules.suggestedTitle(CardContent.Contact(organization = "UM")))
  }

  // Store

  private fun store() = CardStore(folder.root.resolve("cards.json"))

  @Test
  fun aNewStoreHasTheProfileCard() {
    val profile = store().cards.value.single()
    assertTrue(profile.isProfile)
    assertEquals(CardContent.Link(CardStore.DEFAULT_PROFILE_URL), profile.content)
  }

  @Test
  fun newCardsGoAfterTheProfileAndPersist() {
    val store = store()
    val older = store.add("Home", CardContent.Wifi("Rumah", "password1"))
    val newer = store.add("", CardContent.Link("um.edu.my"))
    assertEquals(listOf("profile", newer.id, older.id), store.cards.value.map { it.id })
    assertEquals("um.edu.my", newer.title)
    assertEquals(CardContent.Link("https://um.edu.my"), newer.content)
    assertEquals(store.cards.value, store().cards.value)
  }

  @Test
  fun theProfileStaysAndStaysALink() {
    val store = store()
    store.delete("profile")
    assertEquals("profile", store.profile.id)
    assertThrows(IllegalArgumentException::class.java) {
      store.update(store.profile.copy(content = CardContent.Text("no")))
    }
    store.update(store.profile.copy(title = "IG", content = CardContent.Link("instagram.com/someone")))
    assertEquals(CardContent.Link("https://instagram.com/someone"), store().profile.content)
  }

  @Test
  fun aDeletedCardComesBackWhereItWas() {
    val store = store()
    val older = store.add("Older", CardContent.Text("one"))
    val newer = store.add("Newer", CardContent.Text("two"))
    store.delete(older.id)
    store.restore(older, 2)
    assertEquals(listOf("profile", newer.id, older.id), store().cards.value.map { it.id })
    store.restore(older, 1)
    store.restore(store.profile, 1)
    assertEquals(listOf("profile", newer.id, older.id), store.cards.value.map { it.id })
  }

  @Test
  fun invalidCardsAreNotSaved() {
    assertThrows(IllegalArgumentException::class.java) { store().add("x", CardContent.Wifi("Net", "short")) }
  }

  @Test
  fun aBrokenFileIsSetAsideNotLost() {
    val file = folder.root.resolve("cards.json")
    file.writeText("{ not json")
    assertEquals(listOf("profile"), CardStore(file).cards.value.map { it.id })
    assertTrue(folder.root.listFiles()!!.any { it.name.startsWith("cards.json.broken-") })
  }
}
