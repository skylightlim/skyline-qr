@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.skylightlim.skylineqr.core

@JsFun("() => Date.now()")
private external fun dateNow(): Double

@JsFun("(ms) => -new Date(ms).getTimezoneOffset() * 60000")
private external fun offsetAt(millis: Double): Double

/** The browser's own IDNA (UTS 46) through the URL parser; "" if it refuses the host. */
@JsFun("(host) => { try { return new URL('http://' + host + '/').hostname } catch (e) { return '' } }")
private external fun urlHostname(host: String): String

internal actual fun platformNowMillis(): Long = dateNow().toLong()

internal actual fun platformZoneOffsetMillis(millis: Long): Int = offsetAt(millis.toDouble()).toInt()

// java.net.IDN follows IDNA 2003 and the browser UTS 46, which agree on everyday names; for a rare
// few (ß, ς, some symbols) a site's identity, and so its city, may differ from the app's.
internal actual fun platformIdnToAscii(host: String): String =
  if (host.all { it.code < 0x80 }) host
  else urlHostname(host).ifEmpty { throw IllegalArgumentException("invalid host: $host") }
