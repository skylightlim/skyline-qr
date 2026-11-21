package com.skylightlim.skylineqr.city

import java.util.TimeZone

// The java.util.TimeZone forms the JVM and Android callers had before the core went multiplatform.

/** [Festivals.at] in [zone]. */
fun Festivals.at(millis: Long, zone: TimeZone): Int = at(millis, zone.getOffset(millis))

/** [Atmospheres.clockMinute] in [zone]. */
fun Atmospheres.clockMinute(nowMillis: Long, zone: TimeZone): Float = clockMinute(nowMillis, zone.getOffset(nowMillis))
