package com.skylightlim.skylineqr.nfc.card

import com.skylightlim.skylineqr.nfc.ndef.NdefMessage
import com.skylightlim.skylineqr.nfc.ndef.NdefRecord
import com.skylightlim.skylineqr.nfc.ndef.SmartPoster
import com.skylightlim.skylineqr.nfc.ndef.TextRecord
import com.skylightlim.skylineqr.nfc.ndef.UriRecord

/** Cards to and from the formats they travel in: NDEF for NFC, plain text for QR codes and sharing. */
object CardCodec {

  /** The NDEF message a tapped phone or a written tag receives. */
  fun toNdef(content: CardContent): NdefMessage = when (content) {
    is CardContent.Link -> NdefMessage(UriRecord.create(content.url))
    is CardContent.Text -> NdefMessage(TextRecord.create(content.text))
    is CardContent.Contact -> NdefMessage(NdefRecord.mime(VCard.MIME, VCard.encode(content).toByteArray(Charsets.UTF_8)))
    is CardContent.Wifi -> NdefMessage(NdefRecord.mime(WscCredential.MIME, WscCredential.encode(content)))
    is CardContent.Payment -> NdefMessage(TextRecord.create(content.code))
  }

  /** The text a QR code of this card should carry. */
  fun qrText(content: CardContent): String = when (content) {
    is CardContent.Link -> content.url
    is CardContent.Text -> content.text
    is CardContent.Contact -> VCard.encode(content)
    is CardContent.Wifi -> WifiQr.encode(content)
    is CardContent.Payment -> content.code
  }

  /** The first record in [message] that a card can hold, or null if there is none. */
  fun fromNdef(message: NdefMessage): Draft? {
    for (record in message.records) {
      SmartPoster.parse(record)?.let { poster ->
        val content = CardContent.Link(poster.uri)
        return Draft(poster.title?.takeIf(String::isNotBlank) ?: CardRules.suggestedTitle(content), content)
      }
      val content = UriRecord.parse(record)?.let(CardContent::Link)
        ?: TextRecord.parse(record)?.let(::textOrPayment)
        ?: record.takeIf { it.isMime(*VCard.MIME_TYPES) }?.let { VCard.parse(it.payload.toString(Charsets.UTF_8)) }
        ?: record.takeIf { it.isMime(WscCredential.MIME) }?.let { WscCredential.decode(it.payload) }
        ?: record.takeIf { it.isMime("text/plain") }?.let { textOrPayment(it.payload.toString(Charsets.UTF_8)) }
      if (content != null) return Draft(CardRules.suggestedTitle(content), content)
    }
    return null
  }

  /** A scanned QR code: a payment code, Wi-Fi, a contact, a link if the whole code is one, or else text. */
  fun fromScannedText(text: String): Draft {
    val trimmed = text.trim()
    val content = structured(trimmed)
      ?: trimmed.takeIf(::isWholeLink)?.let { CardContent.Link(CardRules.normalizeLink(it)) }
      ?: CardContent.Text(trimmed)
    return Draft(CardRules.suggestedTitle(content), content)
  }

  /**
   * Text from the Android Share menu. Apps usually send a link with some words around it
   * ("Check this out https://…"), so the first link wins and the words become the title.
   */
  fun fromSharedText(text: String, subject: String? = null): Draft {
    val trimmed = text.trim()
    structured(trimmed)?.let { return Draft(subject?.trim()?.ifEmpty { null } ?: CardRules.suggestedTitle(it), it) }
    val match = LINK_IN_TEXT.find(trimmed)
    if (match == null) {
      val content = if (isWholeLink(trimmed)) CardContent.Link(CardRules.normalizeLink(trimmed)) else CardContent.Text(trimmed)
      return Draft(subject?.trim()?.ifEmpty { null } ?: CardRules.suggestedTitle(content), content)
    }
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
    val content = CardContent.Link(url)
    val words = trimmed.replace(match.value, " ").replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', '|', '·')
    val title = subject?.trim()?.ifEmpty { null } ?: words.ifEmpty { null } ?: CardRules.suggestedTitle(content)
    return Draft(title, content)
  }

  private val LINK_IN_TEXT = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)

  /** Text from a tag, unless the whole of it is a payment code. */
  private fun textOrPayment(text: String): CardContent = text.trim().takeIf(PaymentQr::isPaymentCode)?.let(CardContent::Payment) ?: CardContent.Text(text)

  private fun structured(text: String): CardContent? = when {
    PaymentQr.isPaymentCode(text) -> CardContent.Payment(text)
    text.startsWith("WIFI:", ignoreCase = true) -> WifiQr.parse(text)
    text.startsWith("BEGIN:VCARD", ignoreCase = true) -> VCard.parse(text)
    text.startsWith("MECARD:", ignoreCase = true) -> MeCard.parse(text)
    else -> null
  }

  private fun isWholeLink(text: String): Boolean {
    if (text.isEmpty() || text.any(Char::isWhitespace)) return false
    val lower = text.lowercase()
    return Regex("^[a-z][a-z0-9+.-]*://.+").matches(lower) ||
      listOf("tel:", "mailto:", "sms:", "smsto:", "geo:").any(lower::startsWith) ||
      lower.startsWith("www.")
  }
}
