package com.skylightlim.skylineqr.core

import java.net.IDN
import java.util.TimeZone

internal actual fun platformNowMillis(): Long = System.currentTimeMillis()

internal actual fun platformZoneOffsetMillis(millis: Long): Int = TimeZone.getDefault().getOffset(millis)

internal actual fun platformIdnToAscii(host: String): String = IDN.toASCII(host)
