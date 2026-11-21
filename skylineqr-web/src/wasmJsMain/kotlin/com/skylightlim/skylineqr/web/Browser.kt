@file:OptIn(ExperimentalWasmJsInterop::class)

package com.skylightlim.skylineqr.web

/** The page's clocks, frames and console. */
internal object Browser {
  fun nanos(): Long = (performanceNow() * 1e6).toLong()

  fun millis(): Long = dateNow().toLong()

  fun nextFrame(onFrame: (Long) -> Unit) = requestFrame { onFrame((it * 1e6).toLong()) }

  fun log(message: String) = consoleError(message)
}

@JsFun("() => performance.now()")
private external fun performanceNow(): Double

@JsFun("() => Date.now()")
private external fun dateNow(): Double

@JsFun("(f) => requestAnimationFrame(f)")
private external fun requestFrame(onFrame: (Double) -> Unit)

@JsFun("(m) => console.error(m)")
private external fun consoleError(message: String)
