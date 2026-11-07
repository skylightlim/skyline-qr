package com.skylightlim.skylineqr.nfc.card

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A saved card: something the phone can hand over by tap, write to a tag, or show as a QR code. */
@Serializable
data class Card(
  val id: String,
  val title: String,
  val content: CardContent,
  /** The profile card is always first, can be edited but not deleted, and is always a link. */
  val isProfile: Boolean = false,
)

/** What a card holds. */
@Serializable
sealed interface CardContent {

  @Serializable
  @SerialName("link")
  data class Link(val url: String) : CardContent

  @Serializable
  @SerialName("text")
  data class Text(val text: String) : CardContent

  @Serializable
  @SerialName("contact")
  data class Contact(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val organization: String = "",
    val website: String = "",
  ) : CardContent {
    val isEmpty get() = listOf(name, phone, email, organization, website).all { it.isBlank() }
  }

  /**
   * A payment QR code, such as a DuitNow one (2026-11-07), kept exactly as it was scanned: a
   * single changed character breaks its checksum and the payment. It only ever arrives from a scan
   * or a tag, never typed.
   */
  @Serializable
  @SerialName("payment")
  data class Payment(val code: String) : CardContent {
    /** Who it pays, as the code names them, or null. */
    val payee: String? get() = PaymentQr.payee(code)
  }

  @Serializable
  @SerialName("wifi")
  data class Wifi(
    val ssid: String,
    val password: String = "",
    val security: WifiSecurity = WifiSecurity.WPA_WPA2,
  ) : CardContent
}

@Serializable
enum class WifiSecurity(val label: String) {
  @SerialName("open") OPEN("Open"),
  @SerialName("wpa") WPA_WPA2("WPA/WPA2"),
  @SerialName("wpa3") WPA3("WPA3"),
}

/** A card not yet saved: what a read tag, a scanned QR code or the Share menu produced. */
data class Draft(val title: String, val content: CardContent)

enum class CardKind(val label: String) { LINK("Link"), TEXT("Text"), CONTACT("Contact"), WIFI("Wi-Fi"), PAYMENT("Payment") }

val CardContent.kind: CardKind
  get() = when (this) {
    is CardContent.Link -> CardKind.LINK
    is CardContent.Text -> CardKind.TEXT
    is CardContent.Contact -> CardKind.CONTACT
    is CardContent.Wifi -> CardKind.WIFI
    is CardContent.Payment -> CardKind.PAYMENT
  }
