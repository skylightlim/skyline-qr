package com.skylightlim.skylineqr.compose

/** Moments in the city an app may want to hear, delivered on the main thread as they happen. */
enum class SkylineEvent {
  /** A lightning strike flashes; thunder should follow a beat later. */
  LIGHTNING,
  /** A meteor starts its fall, 0.9 s before it lands. */
  METEOR_FALLING,
  /** The meteor hits the city. */
  METEOR_IMPACT,
}
