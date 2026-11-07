package com.skylightlim.skylineqr.nfc.card

import java.io.ByteArrayOutputStream

/**
 * Wi-Fi credentials over NFC: the Wi-Fi Simple Configuration (WSC) credential that the Wi-Fi
 * Alliance defined for NFC handover, sent as an `application/vnd.wfa.wsc` record. Android's NFC
 * service reads it and offers to join the network.
 *
 * The format predates WPA3, so a WPA3 card goes out as WPA2-PSK with AES. Routers in WPA2/WPA3
 * transition mode (most home routers) accept that; WPA3-only networks may refuse it.
 */
object WscCredential {

  const val MIME = "application/vnd.wfa.wsc"

  private const val VERSION = 0x104A
  private const val CREDENTIAL = 0x100E
  private const val NETWORK_INDEX = 0x1026
  private const val SSID = 0x1045
  private const val AUTH_TYPE = 0x1003
  private const val ENCRYPTION_TYPE = 0x100F
  private const val NETWORK_KEY = 0x1027
  private const val MAC_ADDRESS = 0x1020
  private const val VENDOR_EXTENSION = 0x1049

  private const val AUTH_OPEN = 0x0001
  private const val AUTH_WPA_PSK = 0x0002
  private const val AUTH_WPA2_PSK = 0x0020
  private const val ENCRYPTION_NONE = 0x0001
  private const val ENCRYPTION_AES = 0x0008

  /** The Wi-Fi Alliance vendor extension carrying Version2 = 2.0, which WSC 2.0 asks for. */
  private val WFA_VERSION_2 = byteArrayOf(0x00, 0x37, 0x2A, 0x00, 0x01, 0x20)

  fun encode(wifi: CardContent.Wifi): ByteArray {
    val open = wifi.security == WifiSecurity.OPEN
    val credential = ByteArrayOutputStream().apply {
      attribute(NETWORK_INDEX, byteArrayOf(1))
      attribute(SSID, wifi.ssid.toByteArray(Charsets.UTF_8))
      attribute(AUTH_TYPE, short(if (open) AUTH_OPEN else AUTH_WPA2_PSK))
      attribute(ENCRYPTION_TYPE, short(if (open) ENCRYPTION_NONE else ENCRYPTION_AES))
      attribute(NETWORK_KEY, if (open) ByteArray(0) else wifi.password.toByteArray(Charsets.UTF_8))
      // The access point's address isn't known; all ones means "any".
      attribute(MAC_ADDRESS, ByteArray(6) { 0xFF.toByte() })
    }.toByteArray()
    return ByteArrayOutputStream().apply {
      attribute(VERSION, byteArrayOf(0x10))
      attribute(CREDENTIAL, credential)
      attribute(VENDOR_EXTENSION, WFA_VERSION_2)
    }.toByteArray()
  }

  /** The first credential in a WSC payload, or null if there is none. */
  fun decode(payload: ByteArray): CardContent.Wifi? {
    val credential = attributes(payload)[CREDENTIAL] ?: return null
    val fields = attributes(credential)
    val ssid = fields[SSID]?.toString(Charsets.UTF_8) ?: return null
    val auth = fields[AUTH_TYPE]?.takeIf { it.size == 2 }?.let { (it[0].toInt() and 0xFF shl 8) or (it[1].toInt() and 0xFF) }
    val key = fields[NETWORK_KEY]?.toString(Charsets.UTF_8).orEmpty()
    val personal = auth != null && auth and (AUTH_WPA_PSK or AUTH_WPA2_PSK) != 0
    val security = if (personal || key.isNotEmpty()) WifiSecurity.WPA_WPA2 else WifiSecurity.OPEN
    return CardContent.Wifi(ssid, if (security == WifiSecurity.OPEN) "" else key, security)
  }

  private fun ByteArrayOutputStream.attribute(type: Int, value: ByteArray) {
    require(value.size <= 0xFFFF)
    write(short(type))
    write(short(value.size))
    write(value)
  }

  private fun short(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

  /** Type → first value of the type-length-value attributes in [bytes]; stops at a malformed one. */
  private fun attributes(bytes: ByteArray): Map<Int, ByteArray> {
    val found = mutableMapOf<Int, ByteArray>()
    var at = 0
    while (at + 4 <= bytes.size) {
      val type = (bytes[at].toInt() and 0xFF shl 8) or (bytes[at + 1].toInt() and 0xFF)
      val length = (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)
      at += 4
      if (length > bytes.size - at) break
      found.putIfAbsent(type, bytes.copyOfRange(at, at + length))
      at += length
    }
    return found
  }
}

/**
 * Wi-Fi in QR codes: the `WIFI:T:WPA;S:name;P:password;;` format that phone cameras understand.
 * `\ ; , : "` in values are escaped with a backslash. WPA3 is written `T:SAE`, as Android does.
 */
object WifiQr {

  private const val SPECIAL = "\\;,:\""

  fun encode(wifi: CardContent.Wifi): String = buildString {
    append("WIFI:T:")
    append(
      when (wifi.security) {
        WifiSecurity.OPEN -> "nopass"
        WifiSecurity.WPA_WPA2 -> "WPA"
        WifiSecurity.WPA3 -> "SAE"
      },
    )
    append(";S:").append(Escapes.escape(wifi.ssid, SPECIAL)).append(';')
    if (wifi.security != WifiSecurity.OPEN) append("P:").append(Escapes.escape(wifi.password, SPECIAL)).append(';')
    append(';')
  }

  fun parse(text: String): CardContent.Wifi? {
    if (!text.startsWith("WIFI:", ignoreCase = true)) return null
    var ssid: String? = null
    var password = ""
    var type = ""
    for (field in Escapes.split(text.substring(5), ';')) {
      val key = field.substringBefore(':', "").uppercase()
      val value = field.substringAfter(':', "")
      when (key) {
        "S" -> ssid = unquote(value)
        "P" -> password = unquote(value)
        "T" -> type = Escapes.unescape(value).uppercase()
      }
    }
    val name = ssid ?: return null
    val security = when {
      type == "SAE" || type == "WPA3" -> WifiSecurity.WPA3
      type == "NOPASS" || (type.isEmpty() && password.isEmpty()) -> WifiSecurity.OPEN
      else -> WifiSecurity.WPA_WPA2
    }
    return CardContent.Wifi(name, if (security == WifiSecurity.OPEN) "" else password, security)
  }

  /** Some generators wrap a value in quotes so it isn't read as hex; drop them. */
  private fun unquote(raw: String): String {
    val inner = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') && !raw.endsWith("\\\"")) {
      raw.substring(1, raw.length - 1)
    } else {
      raw
    }
    return Escapes.unescape(inner)
  }
}
