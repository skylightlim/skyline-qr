package com.skylightlim.skylineqr.nfc.card

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Contacts as vCard 3.0 (RFC 2426): sent over NFC as a `text/vcard` record, which Android offers to
 * save as a contact, and used as QR text, which phone cameras understand too.
 */
object VCard {

  const val MIME = "text/vcard"
  val MIME_TYPES = arrayOf("text/vcard", "text/x-vcard")

  private const val TEXT_SPECIAL = "\\,;"

  fun encode(contact: CardContent.Contact): String {
    val name = contact.name.trim()
    // FN is required; without a name, show whatever identifies the contact best.
    val formatted = name.ifEmpty {
      listOf(contact.organization, contact.phone, contact.email).map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    }
    val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
    // One name field, kept whole as the given name: splitting it into family and given names
    // would guess wrong for names written family-first.
    lines += "N:;${text(name)};;;"
    lines += "FN:${text(formatted)}"
    if (contact.organization.isNotBlank()) lines += "ORG:${text(contact.organization.trim())}"
    if (contact.phone.isNotBlank()) lines += "TEL;TYPE=CELL:${oneLine(contact.phone.trim())}"
    if (contact.email.isNotBlank()) lines += "EMAIL;TYPE=INTERNET:${text(contact.email.trim())}"
    if (contact.website.isNotBlank()) lines += "URL:${oneLine(contact.website.trim())}"
    lines += "END:VCARD"
    return lines.joinToString(separator = "\r\n", postfix = "\r\n", transform = ::fold)
  }

  fun parse(text: String): CardContent.Contact? {
    var source = text
    // vCard 2.1 quoted-printable values end a line with "=" to continue it.
    if (source.contains("QUOTED-PRINTABLE", ignoreCase = true)) source = source.replace(Regex("=\r?\n"), "")
    val lines = source.replace(Regex("\r?\n[ \t]"), "").lines()
    if (lines.none { it.trim().equals("BEGIN:VCARD", ignoreCase = true) }) return null

    var formattedName: String? = null
    var structuredName: String? = null
    var phone: String? = null
    var email: String? = null
    var organization: String? = null
    var website: String? = null
    for (line in lines) {
      val colon = line.indexOf(':')
      if (colon <= 0) continue
      val head = line.substring(0, colon).split(';')
      val property = head[0].substringAfterLast('.').uppercase()
      val params = head.drop(1).map { it.uppercase() }
      var value = line.substring(colon + 1)
      if (params.any { it == "ENCODING=QUOTED-PRINTABLE" || it == "QUOTED-PRINTABLE" }) {
        val charset = params.firstOrNull { it.startsWith("CHARSET=") }?.substringAfter('=')
        value = decodeQuotedPrintable(value, charset)
      }
      when (property) {
        "FN" -> formattedName = formattedName ?: Escapes.unescape(value, newlines = true).trim()
        "N" -> structuredName = structuredName ?: structured(value)
        "TEL" -> phone = phone ?: value.trim()
        "EMAIL" -> email = email ?: Escapes.unescape(value).trim()
        "ORG" -> organization = organization ?: Escapes.unescape(Escapes.split(value, ';').first()).trim()
        "URL" -> website = website ?: Escapes.unescape(value).trim()
      }
    }
    val contact = CardContent.Contact(
      name = formattedName?.takeIf(String::isNotEmpty) ?: structuredName.orEmpty(),
      phone = phone.orEmpty(),
      email = email.orEmpty(),
      organization = organization.orEmpty(),
      website = website.orEmpty(),
    )
    return contact.takeUnless { it.isEmpty }
  }

  /** "Family;Given;Additional;Prefix;Suffix" read back as one display name. */
  private fun structured(value: String): String {
    val parts = Escapes.split(value, ';').map { Escapes.unescape(it).trim() }
    fun part(index: Int) = parts.getOrElse(index) { "" }
    return listOf(part(3), part(1), part(2), part(0), part(4)).filter(String::isNotEmpty).joinToString(" ")
  }

  private fun text(value: String) = Escapes.escape(value, TEXT_SPECIAL).replace("\r\n", "\\n").replace("\n", "\\n")

  private fun oneLine(value: String) = value.replace(Regex("[\r\n]+"), " ")

  /** Lines longer than 75 octets continue on the next line after a space (RFC 2425, 5.8.1). */
  private fun fold(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
    val out = StringBuilder()
    var octets = 0
    var i = 0
    while (i < line.length) {
      val codePoint = line.codePointAt(i)
      val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
      if (octets + size > 75) {
        out.append("\r\n ")
        octets = 1
      }
      out.appendCodePoint(codePoint)
      octets += size
      i += Character.charCount(codePoint)
    }
    return out.toString()
  }

  private fun decodeQuotedPrintable(value: String, charset: String?): String {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
      val c = value[i]
      val hex = if (c == '=' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
      if (hex != null) {
        bytes.write(hex)
        i += 3
      } else {
        bytes.write(c.code)
        i++
      }
    }
    val cs = charset?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return bytes.toString(cs.name())
  }
}

/** Contacts in the shorter MECARD format, which some QR generators use: `MECARD:N:Lim,Chen Yu;TEL:…;;`. */
object MeCard {

  fun parse(text: String): CardContent.Contact? {
    if (!text.startsWith("MECARD:", ignoreCase = true)) return null
    var name: String? = null
    var phone: String? = null
    var email: String? = null
    var organization: String? = null
    var website: String? = null
    for (field in Escapes.split(text.substring(7), ';')) {
      val key = field.substringBefore(':', "").uppercase()
      val raw = field.substringAfter(':', "")
      when (key) {
        // "Family,Given": written in that order, which is also how Chinese and Malay names read.
        "N" -> name = name ?: Escapes.split(raw, ',').joinToString(" ") { Escapes.unescape(it).trim() }.trim()
        "TEL" -> phone = phone ?: Escapes.unescape(raw).trim()
        "EMAIL" -> email = email ?: Escapes.unescape(raw).trim()
        "ORG" -> organization = organization ?: Escapes.unescape(raw).trim()
        "URL" -> website = website ?: Escapes.unescape(raw).trim()
      }
    }
    return CardContent.Contact(name.orEmpty(), phone.orEmpty(), email.orEmpty(), organization.orEmpty(), website.orEmpty())
      .takeUnless { it.isEmpty }
  }
}
