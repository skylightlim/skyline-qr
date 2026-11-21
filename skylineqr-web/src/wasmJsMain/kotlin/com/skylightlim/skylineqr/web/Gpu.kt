@file:OptIn(ExperimentalWasmJsInterop::class)

package com.skylightlim.skylineqr.web

/** The WebGPU side of [com.skylightlim.skylineqr.render.WebRenderer], in gpu.js. */
internal object Gpu {

  private var frame: JsAny? = null
  private var traffic: JsAny? = null

  fun start(
    source: String,
    post: String,
    frameFloats: Int,
    shadowSize: Int,
    trafficFloats: Int,
    cubeMesh: FloatArray,
    cubeIndices: ShortArray,
    atlasWidth: Int,
    atlasHeight: Int,
    onReady: () -> Unit,
    onFail: (String) -> Unit,
  ) {
    frame = newF32(frameFloats)
    traffic = newF32(trafficFloats)
    val mesh = copy(cubeMesh, cubeMesh.size)
    val indices = newU16(cubeIndices.size)
    for (i in cubeIndices.indices) setU16(indices, i, cubeIndices[i].toInt())
    gpuStart(source, post, frameFloats, shadowSize, trafficFloats, mesh, indices, atlasWidth, atlasHeight, onReady, onFail)
  }

  fun resize(width: Int, height: Int) = gpuResize(width, height)

  fun uploadCity(instances: FloatArray, floats: Int) = gpuUploadCity(copy(instances, floats), floats)

  fun writeFrame(values: FloatArray) {
    val target = frame ?: return
    fill(target, values, values.size)
    gpuWriteFrame(target)
  }

  fun writeTraffic(values: FloatArray, floats: Int) {
    val target = traffic ?: return
    fill(target, values, floats)
    gpuWriteTraffic(target, floats)
  }

  fun draw(
    cityCount: Int,
    moving: Int,
    drops: Int,
    gusts: Int,
    fold: Float,
    clear: FloatArray,
    post: FloatArray,
    gustVertices: Int,
    gustBase: Int,
    indices: Int,
  ) = gpuDraw(cityCount, moving, drops, gusts, fold, clear[0], clear[1], clear[2], post[0], post[1], post[2], post[3], gustVertices, gustBase, indices)

  private fun copy(values: FloatArray, floats: Int): JsAny = newF32(floats).also { fill(it, values, floats) }

  /** Four floats a call: crossing into JavaScript costs more than the copy. */
  private fun fill(target: JsAny, values: FloatArray, floats: Int) {
    var i = 0
    while (i + 4 <= floats) {
      set4(target, i, values[i], values[i + 1], values[i + 2], values[i + 3])
      i += 4
    }
    while (i < floats) {
      set4(target, i, values[i], 0f, 0f, 0f)
      i++
    }
  }
}

@JsFun("(n) => new Float32Array(n)")
private external fun newF32(n: Int): JsAny

@JsFun("(n) => new Uint16Array(n)")
private external fun newU16(n: Int): JsAny

@JsFun("(a, i, v) => { a[i] = v; }")
private external fun setU16(a: JsAny, i: Int, v: Int)

// Past the end, typed arrays ignore the writes, so the tail can write all four.
@JsFun("(a, i, x, y, z, w) => { a[i] = x; a[i + 1] = y; a[i + 2] = z; a[i + 3] = w; }")
private external fun set4(a: JsAny, i: Int, x: Float, y: Float, z: Float, w: Float)

@JsFun("(s, p, ff, ss, tf, mesh, idx, aw, ah, ready, fail) => SkylineGpu.start(s, p, ff, ss, tf, mesh, idx, aw, ah, ready, fail)")
private external fun gpuStart(
  source: String, post: String, frameFloats: Int, shadowSize: Int, trafficFloats: Int, mesh: JsAny, indices: JsAny,
  atlasWidth: Int, atlasHeight: Int, ready: () -> Unit, fail: (String) -> Unit,
)

@JsFun("(w, h) => SkylineGpu.resize(w, h)")
private external fun gpuResize(width: Int, height: Int)

@JsFun("(a, n) => SkylineGpu.uploadCity(a, n)")
private external fun gpuUploadCity(data: JsAny, floats: Int)

@JsFun("(a) => SkylineGpu.writeFrame(a)")
private external fun gpuWriteFrame(data: JsAny)

@JsFun("(a, n) => SkylineGpu.writeTraffic(a, n)")
private external fun gpuWriteTraffic(data: JsAny, floats: Int)

@JsFun("(c, m, d, g, f, r, gr, b, p0, p1, p2, p3, gv, gb, ni) => SkylineGpu.draw(c, m, d, g, f, r, gr, b, p0, p1, p2, p3, gv, gb, ni)")
private external fun gpuDraw(
  cityCount: Int, moving: Int, drops: Int, gusts: Int, fold: Float, r: Float, g: Float, b: Float,
  p0: Float, p1: Float, p2: Float, p3: Float, gustVertices: Int, gustBase: Int, indices: Int,
)

@JsFun("(text, w, row, rows, view) => SkylineGpu.drawAtlas(text, w, row, rows, view)")
internal external fun gpuDrawAtlas(text: String, width: Int, row: Int, rows: Int, view: Float): String
