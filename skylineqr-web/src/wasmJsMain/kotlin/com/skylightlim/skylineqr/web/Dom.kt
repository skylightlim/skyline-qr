@file:OptIn(ExperimentalWasmJsInterop::class)

package com.skylightlim.skylineqr.web

/** The little of the page the Kotlin side touches, by element id; the rest is in page.js. */
internal object Dom {
  const val DOWN = 0
  const val MOVE = 1
  const val UP = 2
  const val CANCEL = 3

  fun value(id: String): String = jsValue(id)
  fun setValue(id: String, value: String) = jsSetValue(id, value)
  fun setText(id: String, text: String) = jsSetText(id, text)
  fun setHidden(id: String, hidden: Boolean) = jsSetHidden(id, hidden)
  fun setPressed(id: String, pressed: Boolean) = jsSetPressed(id, pressed)
  fun setLabel(id: String, label: String) = jsSetLabel(id, label)
  fun onClick(id: String, action: () -> Unit) = jsOnClick(id, action)
  fun onInput(id: String, action: () -> Unit) = jsOnInput(id, action)
  fun onPointer(id: String, action: (Int, Int, Double, Double) -> Unit) = jsOnPointer(id, action)
  fun onWheel(id: String, action: (Double) -> Unit) = jsOnWheel(id, action)
  fun onResize(action: () -> Unit) = jsOnResize(action)
  fun everySecond(action: () -> Unit) = jsEverySecond(action)
  fun after(millis: Int, action: () -> Unit): Int = jsAfter(millis, action)
  fun cancel(timer: Int) = jsCancel(timer)
  fun reducedMotion(): Boolean = jsReducedMotion()
  fun setTheme(color: String, light: Boolean) = jsSetTheme(color, light)

  /** Takes the loading poster away (page.js); gpu.js does it itself once the city has drawn. */
  fun loaded() = jsLoaded()
  fun vibrate(pattern: IntArray) = jsVibrate(pattern.joinToString(","))

  /** The canvas's size in device pixels. */
  fun canvasSize(id: String): Pair<Int, Int> = jsCanvasWidth(id) to jsCanvasHeight(id)

  /** The element's size in CSS pixels, which pointer events use. */
  fun canvasCssSize(id: String): Pair<Float, Float> = jsCssWidth(id).toFloat() to jsCssHeight(id).toFloat()

  fun codeBegin(modules: Int, paper: String) = jsCodeBegin(modules, paper)
  fun codeModule(col: Int, row: Int, color: String) = jsCodeModule(col, row, color)
  fun codeDraw(canvas: String, quiet: Int) = jsCodeDraw(canvas, quiet)
}

@JsFun("(id) => document.getElementById(id).value")
private external fun jsValue(id: String): String

@JsFun("(id, v) => { document.getElementById(id).value = v; }")
private external fun jsSetValue(id: String, value: String)

@JsFun("(id, t) => { document.getElementById(id).textContent = t; }")
private external fun jsSetText(id: String, text: String)

@JsFun("(id, h) => { document.getElementById(id).hidden = h; }")
private external fun jsSetHidden(id: String, hidden: Boolean)

@JsFun("(id, p) => { document.getElementById(id).setAttribute('aria-pressed', p ? 'true' : 'false'); }")
private external fun jsSetPressed(id: String, pressed: Boolean)

@JsFun("(id, l) => { document.getElementById(id).setAttribute('aria-label', l); }")
private external fun jsSetLabel(id: String, label: String)

@JsFun("(id, f) => { document.getElementById(id).addEventListener('click', () => f()); }")
private external fun jsOnClick(id: String, action: () -> Unit)

@JsFun("(id, f) => { document.getElementById(id).addEventListener('input', () => f()); }")
private external fun jsOnInput(id: String, action: () -> Unit)

@JsFun("(id, f) => SkylinePage.onPointer(id, f)")
private external fun jsOnPointer(id: String, action: (Int, Int, Double, Double) -> Unit)

@JsFun("(id, f) => SkylinePage.onWheel(id, f)")
private external fun jsOnWheel(id: String, action: (Double) -> Unit)

@JsFun("(f) => SkylinePage.onResize(f)")
private external fun jsOnResize(action: () -> Unit)

@JsFun("(f) => { setInterval(() => f(), 1000); }")
private external fun jsEverySecond(action: () -> Unit)

@JsFun("(ms, f) => setTimeout(() => f(), ms)")
private external fun jsAfter(millis: Int, action: () -> Unit): Int

@JsFun("(t) => { if (t >= 0) clearTimeout(t); }")
private external fun jsCancel(timer: Int)

@JsFun("() => matchMedia('(prefers-reduced-motion: reduce)').matches")
private external fun jsReducedMotion(): Boolean

@JsFun("(c, light) => SkylinePage.setTheme(c, light)")
private external fun jsSetTheme(color: String, light: Boolean)

@JsFun("() => SkylinePage.loaded()")
private external fun jsLoaded()

@JsFun("(p) => { if (navigator.vibrate) navigator.vibrate(p.split(',').map(Number)); }")
private external fun jsVibrate(pattern: String)

@JsFun("(id) => document.getElementById(id).width")
private external fun jsCanvasWidth(id: String): Int

@JsFun("(id) => document.getElementById(id).height")
private external fun jsCanvasHeight(id: String): Int

@JsFun("(id) => document.getElementById(id).clientWidth")
private external fun jsCssWidth(id: String): Double

@JsFun("(id) => document.getElementById(id).clientHeight")
private external fun jsCssHeight(id: String): Double

@JsFun("(n, paper) => SkylinePage.codeBegin(n, paper)")
private external fun jsCodeBegin(modules: Int, paper: String)

@JsFun("(c, r, color) => SkylinePage.codeModule(c, r, color)")
private external fun jsCodeModule(col: Int, row: Int, color: String)

@JsFun("(canvas, quiet) => SkylinePage.codeDraw(canvas, quiet)")
private external fun jsCodeDraw(canvas: String, quiet: Int)
