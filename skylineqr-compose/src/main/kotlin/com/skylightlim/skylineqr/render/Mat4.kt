package com.skylightlim.skylineqr.render

import kotlin.math.tan

/** The few 4x4 helpers the city renderer needs, column-major like WGSL's mat4x4f. */
internal object Mat4 {

  /** Right-handed perspective onto WebGPU's clip space, where depth runs 0 (near) to 1 (far). */
  fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float, shiftY: Float = 0f): FloatArray {
    val f = 1f / tan(Math.toRadians(fovYDeg.toDouble() / 2).toFloat())
    val m = FloatArray(16)
    m[0] = f / aspect
    m[5] = f
    m[9] = -shiftY // lens shift: moves the view up or down without tilting the camera
    m[10] = far / (near - far)
    m[11] = -1f
    m[14] = far * near / (near - far)
    return m
  }

  /** Orthographic box onto WebGPU's clip space, depth 0 at [near] and 1 at [far]. */
  fun orthographic(halfWidth: Float, halfHeight: Float, near: Float, far: Float): FloatArray {
    val m = FloatArray(16)
    m[0] = 1f / halfWidth
    m[5] = 1f / halfHeight
    m[10] = 1f / (near - far)
    m[14] = near / (near - far)
    m[15] = 1f
    return m
  }

  fun lookAt(
    ex: Float, ey: Float, ez: Float,
    tx: Float, ty: Float, tz: Float,
    ux: Float, uy: Float, uz: Float,
  ): FloatArray {
    var fx = tx - ex
    var fy = ty - ey
    var fz = tz - ez
    val fl = 1f / kotlin.math.sqrt(fx * fx + fy * fy + fz * fz)
    fx *= fl; fy *= fl; fz *= fl

    var sx = fy * uz - fz * uy
    var sy = fz * ux - fx * uz
    var sz = fx * uy - fy * ux
    val sl = 1f / kotlin.math.max(1e-6f, kotlin.math.sqrt(sx * sx + sy * sy + sz * sz))
    sx *= sl; sy *= sl; sz *= sl

    val rx = sy * fz - sz * fy
    val ry = sz * fx - sx * fz
    val rz = sx * fy - sy * fx

    val m = FloatArray(16)
    m[0] = sx; m[4] = sy; m[8] = sz
    m[1] = rx; m[5] = ry; m[9] = rz
    m[2] = -fx; m[6] = -fy; m[10] = -fz
    m[12] = -(sx * ex + sy * ey + sz * ez)
    m[13] = -(rx * ex + ry * ey + rz * ez)
    m[14] = fx * ex + fy * ey + fz * ez
    m[15] = 1f
    return m
  }

  /** result = a * b, both column-major. */
  fun multiply(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(16)
    for (c in 0 until 4) {
      for (r in 0 until 4) {
        out[c * 4 + r] =
          a[r] * b[c * 4] +
            a[4 + r] * b[c * 4 + 1] +
            a[8 + r] * b[c * 4 + 2] +
            a[12 + r] * b[c * 4 + 3]
      }
    }
    return out
  }
}
