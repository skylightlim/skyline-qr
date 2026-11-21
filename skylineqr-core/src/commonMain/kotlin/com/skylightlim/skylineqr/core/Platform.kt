package com.skylightlim.skylineqr.core

// The little the core needs from where it runs: the clock, its zone and IDNA.
internal object Platform {
  /** Milliseconds since 1970-01-01 UTC. */
  fun nowMillis(): Long = platformNowMillis()

  /** How far the local zone is ahead of UTC at [millis]. */
  fun zoneOffsetMillis(millis: Long): Int = platformZoneOffsetMillis(millis)

  /** A host name in ASCII (Punycode for any label that needs it); IllegalArgumentException if invalid. */
  fun idnToAscii(host: String): String = platformIdnToAscii(host)
}

internal expect fun platformNowMillis(): Long

internal expect fun platformZoneOffsetMillis(millis: Long): Int

internal expect fun platformIdnToAscii(host: String): String
