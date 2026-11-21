package com.skylightlim.skylineqr.core

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Adapted from fdlibm 5.3 (k_sin.c, k_cos.c, k_tan.c, e_rem_pio2.c, s_sin.c, s_cos.c, s_tan.c,
 * s_atan.c, e_atan2.c, e_hypot.c, e_pow.c, s_scalbn.c):
 *
 *   Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 *   Developed at SunSoft, a Sun Microsystems, Inc. business.
 *   Permission to use, copy, modify, and distribute this
 *   software is freely granted, provided that this notice
 *   is preserved.
 */

/**
 * The transcendental functions the planner needs, bit for bit what `java.lang.StrictMath` returns
 * (which is fdlibm 5.3 on the JVM and on Android), in plain Kotlin so the browser gets the same
 * bits (2026-11-21, for the website). `FdLibmTest` holds every function to StrictMath on the JVM.
 *
 * Arguments to sin, cos and tan beyond 2^19 pi/2 (about 823,550) fall back to kotlin.math, which
 * may differ in the last bit; the planner's angles are all far smaller.
 */
object FdLibm {

  private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()
  private fun lo(x: Double): Int = x.toRawBits().toInt()
  private fun fromWords(hi: Int, lo: Int): Double = Double.fromBits((hi.toLong() shl 32) or (lo.toLong() and 0xffffffffL))
  private fun withHi(x: Double, hi: Int): Double = fromWords(hi, lo(x))
  private fun withLo(x: Double, lo: Int): Double = fromWords(hi(x), lo)

  // k_sin.c
  private const val S1 = -1.66666666666666324348e-01
  private const val S2 = 8.33333333332248946124e-03
  private const val S3 = -1.98412698298579493134e-04
  private const val S4 = 2.75573137070700676789e-06
  private const val S5 = -2.50507602534068634195e-08
  private const val S6 = 1.58969099521155010221e-10

  private fun kernelSin(x: Double, y: Double, iy: Int): Double {
    val ix = hi(x) and 0x7fffffff
    if (ix < 0x3e400000) {
      if (x.toInt() == 0) return x
    }
    val z = x * x
    val v = z * x
    val r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)))
    return if (iy == 0) x + v * (S1 + z * r) else x - ((z * (0.5 * y - v * r) - y) - v * S1)
  }

  // k_cos.c
  private const val C1 = 4.16666666666666019037e-02
  private const val C2 = -1.38888888888741095749e-03
  private const val C3 = 2.48015872894767294178e-05
  private const val C4 = -2.75573143513906633035e-07
  private const val C5 = 2.08757232129817482790e-09
  private const val C6 = -1.13596475577881948265e-11

  private fun kernelCos(x: Double, y: Double): Double {
    val ix = hi(x) and 0x7fffffff
    if (ix < 0x3e400000) {
      if (x.toInt() == 0) return 1.0
    }
    val z = x * x
    val r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))))
    if (ix < 0x3FD33333) return 1.0 - (0.5 * z - (z * r - x * y))
    val qx = if (ix > 0x3fe90000) 0.28125 else fromWords(ix - 0x00200000, 0)
    val hz = 0.5 * z - qx
    val a = 1.0 - qx
    return a - (hz - (z * r - x * y))
  }

  // k_tan.c
  private val T = doubleArrayOf(
    3.33333333333334091986e-01,
    1.33333333333201242699e-01,
    5.39682539762260521377e-02,
    2.18694882948595424599e-02,
    8.86323982359930005737e-03,
    3.59207910759131235356e-03,
    1.45620945432529025516e-03,
    5.88041240820264096874e-04,
    2.46463134818469906812e-04,
    7.81794442939557092300e-05,
    7.14072491382608190305e-05,
    -1.85586374855275456654e-05,
    2.59073051863633712884e-05,
  )
  private const val PIO4 = 7.85398163397448278999e-01
  private const val PIO4LO = 3.06161699786838301793e-17

  private fun kernelTan(x0: Double, y0: Double, iy: Int): Double {
    var x = x0
    var y = y0
    val hx = hi(x)
    val ix = hx and 0x7fffffff
    if (ix < 0x3e300000) {
      if (x.toInt() == 0) {
        if (((ix or lo(x)) or (iy + 1)) == 0) return 1.0 / abs(x)
        if (iy == 1) return x
        val w = x + y
        val z = withLo(w, 0)
        val v = y - (z - x)
        val a = -1.0 / w
        val t = withLo(a, 0)
        val s = 1.0 + t * z
        return t + a * (s + t * v)
      }
    }
    if (ix >= 0x3FE59428) {
      if (hx < 0) {
        x = -x
        y = -y
      }
      val z = PIO4 - x
      val w = PIO4LO - y
      x = z + w
      y = 0.0
    }
    val z = x * x
    var w = z * z
    var r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))))
    var v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))))
    val s = z * x
    r = y + z * (s * (r + v) + y)
    r += T[0] * s
    w = x + r
    if (ix >= 0x3FE59428) {
      v = iy.toDouble()
      return (1 - ((hx shr 30) and 2)).toDouble() * (v - 2.0 * (x - (w * w / (w + v) - r)))
    }
    if (iy == 1) return w
    val z2 = withLo(w, 0)
    v = r - (z2 - x)
    val a = -1.0 / w
    val t = withLo(a, 0)
    val s2 = 1.0 + t * z2
    return t + a * (s2 + t * v)
  }

  // e_rem_pio2.c, without the huge-argument path.
  private const val INVPIO2 = 6.36619772367581382433e-01
  private const val PIO2_1 = 1.57079632673412561417e+00
  private const val PIO2_1T = 6.07710050650619224932e-11
  private const val PIO2_2 = 6.07710050630396597660e-11
  private const val PIO2_2T = 2.02226624879595063154e-21
  private const val PIO2_3 = 2.02226624871116645580e-21
  private const val PIO2_3T = 8.47842766036889956997e-32
  private val NPIO2_HW = intArrayOf(
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
    0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
    0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
    0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
    0x404858EB, 0x404921FB,
  )

  /** x - n pi/2 as y[0] + y[1]; returns n. Only for |x| between pi/4 and 2^19 pi/2. */
  private fun remPio2(x: Double, y: DoubleArray): Int {
    val hx = hi(x)
    val ix = hx and 0x7fffffff
    if (ix < 0x4002d97c) {
      if (hx > 0) {
        var z = x - PIO2_1
        if (ix != 0x3ff921fb) {
          y[0] = z - PIO2_1T
          y[1] = (z - y[0]) - PIO2_1T
        } else {
          z -= PIO2_2
          y[0] = z - PIO2_2T
          y[1] = (z - y[0]) - PIO2_2T
        }
        return 1
      } else {
        var z = x + PIO2_1
        if (ix != 0x3ff921fb) {
          y[0] = z + PIO2_1T
          y[1] = (z - y[0]) + PIO2_1T
        } else {
          z += PIO2_2
          y[0] = z + PIO2_2T
          y[1] = (z - y[0]) + PIO2_2T
        }
        return -1
      }
    }
    var t = abs(x)
    val n = (t * INVPIO2 + 0.5).toInt()
    val fn = n.toDouble()
    var r = t - fn * PIO2_1
    var w = fn * PIO2_1T
    if (n < 32 && ix != NPIO2_HW[n - 1]) {
      y[0] = r - w
    } else {
      val j = ix shr 20
      y[0] = r - w
      var i = j - ((hi(y[0]) shr 20) and 0x7ff)
      if (i > 16) {
        t = r
        w = fn * PIO2_2
        r = t - w
        w = fn * PIO2_2T - ((t - r) - w)
        y[0] = r - w
        i = j - ((hi(y[0]) shr 20) and 0x7ff)
        if (i > 49) {
          t = r
          w = fn * PIO2_3
          r = t - w
          w = fn * PIO2_3T - ((t - r) - w)
          y[0] = r - w
        }
      }
    }
    y[1] = (r - y[0]) - w
    if (hx < 0) {
      y[0] = -y[0]
      y[1] = -y[1]
      return -n
    }
    return n
  }

  private const val MEDIUM = 0x413921fb

  fun sin(x: Double): Double {
    val ix = hi(x) and 0x7fffffff
    if (ix <= 0x3fe921fb) return kernelSin(x, 0.0, 0)
    if (ix >= 0x7ff00000) return x - x
    if (ix > MEDIUM) return kotlin.math.sin(x)
    val y = DoubleArray(2)
    return when (remPio2(x, y) and 3) {
      0 -> kernelSin(y[0], y[1], 1)
      1 -> kernelCos(y[0], y[1])
      2 -> -kernelSin(y[0], y[1], 1)
      else -> -kernelCos(y[0], y[1])
    }
  }

  fun cos(x: Double): Double {
    val ix = hi(x) and 0x7fffffff
    if (ix <= 0x3fe921fb) return kernelCos(x, 0.0)
    if (ix >= 0x7ff00000) return x - x
    if (ix > MEDIUM) return kotlin.math.cos(x)
    val y = DoubleArray(2)
    return when (remPio2(x, y) and 3) {
      0 -> kernelCos(y[0], y[1])
      1 -> -kernelSin(y[0], y[1], 1)
      2 -> -kernelCos(y[0], y[1])
      else -> kernelSin(y[0], y[1], 1)
    }
  }

  fun tan(x: Double): Double {
    val ix = hi(x) and 0x7fffffff
    if (ix <= 0x3fe921fb) return kernelTan(x, 0.0, 1)
    if (ix >= 0x7ff00000) return x - x
    if (ix > MEDIUM) return kotlin.math.tan(x)
    val y = DoubleArray(2)
    val n = remPio2(x, y)
    return kernelTan(y[0], y[1], 1 - ((n and 1) shl 1))
  }

  // s_atan.c
  private val ATANHI = doubleArrayOf(4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01, 1.57079632679489655800e+00)
  private val ATANLO = doubleArrayOf(2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, 6.12323399573676603587e-17)
  private val AT = doubleArrayOf(
    3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01,
    -1.11111104054623557880e-01, 9.09088713343650656196e-02, -7.69187620504482999495e-02,
    6.66107313738753120669e-02, -5.83357013379057348645e-02, 4.97687799461593236017e-02,
    -3.65315727442169155270e-02, 1.62858201153657823623e-02,
  )

  fun atan(x0: Double): Double {
    var x = x0
    val hx = hi(x)
    val ix = hx and 0x7fffffff
    val id: Int
    if (ix >= 0x44100000) {
      if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lo(x) != 0)) return x + x
      return if (hx > 0) ATANHI[3] + ATANLO[3] else -ATANHI[3] - ATANLO[3]
    }
    if (ix < 0x3fdc0000) {
      if (ix < 0x3e200000) return x
      id = -1
    } else {
      x = abs(x)
      if (ix < 0x3ff30000) {
        if (ix < 0x3fe60000) {
          id = 0
          x = (2.0 * x - 1.0) / (2.0 + x)
        } else {
          id = 1
          x = (x - 1.0) / (x + 1.0)
        }
      } else {
        if (ix < 0x40038000) {
          id = 2
          x = (x - 1.5) / (1.0 + 1.5 * x)
        } else {
          id = 3
          x = -1.0 / x
        }
      }
    }
    val z = x * x
    val w = z * z
    val s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))))
    val s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))))
    if (id < 0) return x - x * (s1 + s2)
    val r = ATANHI[id] - ((x * (s1 + s2) - ATANLO[id]) - x)
    return if (hx < 0) -r else r
  }

  // e_atan2.c
  private const val TINY = 1.0e-300
  private const val PI_O_4 = 7.8539816339744827900E-01
  private const val PI_O_2 = 1.5707963267948965580E+00
  private const val PI = 3.1415926535897931160E+00
  private const val PI_LO = 1.2246467991473531772E-16

  fun atan2(y: Double, x: Double): Double {
    val hx = hi(x)
    val ix = hx and 0x7fffffff
    val lx = lo(x)
    val hy = hi(y)
    val iy = hy and 0x7fffffff
    val ly = lo(y)
    if ((ix or ((lx or -lx) ushr 31)) > 0x7ff00000 || (iy or ((ly or -ly) ushr 31)) > 0x7ff00000) return x + y
    if (((hx - 0x3ff00000) or lx) == 0) return atan(y)
    val m = ((hy shr 31) and 1) or ((hx shr 30) and 2)
    if ((iy or ly) == 0) {
      return when (m) {
        0, 1 -> y
        2 -> PI + TINY
        else -> -PI - TINY
      }
    }
    if ((ix or lx) == 0) return if (hy < 0) -PI_O_2 - TINY else PI_O_2 + TINY
    if (ix == 0x7ff00000) {
      return if (iy == 0x7ff00000) {
        when (m) {
          0 -> PI_O_4 + TINY
          1 -> -PI_O_4 - TINY
          2 -> 3.0 * PI_O_4 + TINY
          else -> -3.0 * PI_O_4 - TINY
        }
      } else {
        when (m) {
          0 -> 0.0
          1 -> -0.0
          2 -> PI + TINY
          else -> -PI - TINY
        }
      }
    }
    if (iy == 0x7ff00000) return if (hy < 0) -PI_O_2 - TINY else PI_O_2 + TINY
    val k = (iy - ix) shr 20
    val z = if (k > 60) PI_O_2 + 0.5 * PI_LO else if (hx < 0 && k < -60) 0.0 else atan(abs(y / x))
    return when (m) {
      0 -> z
      1 -> -z
      2 -> PI - (z - PI_LO)
      else -> (z - PI_LO) - PI
    }
  }

  // e_hypot.c
  fun hypot(x: Double, y: Double): Double {
    var ha = hi(x) and 0x7fffffff
    var hb = hi(y) and 0x7fffffff
    var a: Double
    var b: Double
    if (hb > ha) {
      a = y
      b = x
      val j = ha
      ha = hb
      hb = j
    } else {
      a = x
      b = y
    }
    a = withHi(a, ha)
    b = withHi(b, hb)
    if ((ha - hb) > 0x3c00000) return a + b
    var k = 0
    if (ha > 0x5f300000) {
      if (ha >= 0x7ff00000) {
        var w = a + b
        if (((ha and 0xfffff) or lo(a)) == 0) w = a
        if (((hb xor 0x7ff00000) or lo(b)) == 0) w = b
        return w
      }
      ha -= 0x25800000
      hb -= 0x25800000
      k += 600
      a = withHi(a, ha)
      b = withHi(b, hb)
    }
    if (hb < 0x20b00000) {
      if (hb <= 0x000fffff) {
        if ((hb or lo(b)) == 0) return a
        val t1 = fromWords(0x7fd00000, 0)
        b *= t1
        a *= t1
        k -= 1022
      } else {
        ha += 0x25800000
        hb += 0x25800000
        k -= 600
        a = withHi(a, ha)
        b = withHi(b, hb)
      }
    }
    var w = a - b
    if (w > b) {
      val t1 = fromWords(ha, 0)
      val t2 = a - t1
      w = sqrt(t1 * t1 - (b * (-b) - t2 * (a + t1)))
    } else {
      a += a
      val y1 = fromWords(hb, 0)
      val y2 = b - y1
      val t1 = fromWords(ha + 0x00100000, 0)
      val t2 = a - t1
      w = sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b)))
    }
    if (k != 0) {
      val t1 = withHi(1.0, hi(1.0) + (k shl 20))
      return t1 * w
    }
    return w
  }

  // e_pow.c
  private val BP = doubleArrayOf(1.0, 1.5)
  private val DP_H = doubleArrayOf(0.0, 5.84962487220764160156e-01)
  private val DP_L = doubleArrayOf(0.0, 1.35003920212974897128e-08)
  private const val TWO53 = 9007199254740992.0
  private const val HUGE = 1.0e300
  private const val L1 = 5.99999999999994648725e-01
  private const val L2 = 4.28571428578550184252e-01
  private const val L3 = 3.33333329818377432918e-01
  private const val L4 = 2.72728123808534006489e-01
  private const val L5 = 2.30660745775561754067e-01
  private const val L6 = 2.06975017800338417784e-01
  private const val P1 = 1.66666666666666019037e-01
  private const val P2 = -2.77777777770155933842e-03
  private const val P3 = 6.61375632143793436117e-05
  private const val P4 = -1.65339022054652515390e-06
  private const val P5 = 4.13813679705723846039e-08
  private const val LG2 = 6.93147180559945286227e-01
  private const val LG2_H = 6.93147182464599609375e-01
  private const val LG2_L = -1.90465429995776804525e-09
  private const val OVT = 8.0085662595372944372e-17
  private const val CP = 9.61796693925975554329e-01
  private const val CP_H = 9.61796700954437255859e-01
  private const val CP_L = -7.02846165095275826516e-09
  private const val IVLN2 = 1.44269504088896338700e+00
  private const val IVLN2_H = 1.44269502162933349609e+00
  private const val IVLN2_L = 1.92596299112661746887e-08

  fun pow(x: Double, y: Double): Double {
    val hx = hi(x)
    val lx = lo(x)
    val hy = hi(y)
    val ly = lo(y)
    var ix = hx and 0x7fffffff
    val iy = hy and 0x7fffffff

    if ((iy or ly) == 0) return 1.0
    if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lx != 0) || iy > 0x7ff00000 || (iy == 0x7ff00000 && ly != 0)) return x + y

    var yisint = 0
    if (hx < 0) {
      if (iy >= 0x43400000) {
        yisint = 2
      } else if (iy >= 0x3ff00000) {
        val k = (iy shr 20) - 0x3ff
        if (k > 20) {
          val j = ly ushr (52 - k)
          if ((j shl (52 - k)) == ly) yisint = 2 - (j and 1)
        } else if (ly == 0) {
          val j = iy shr (20 - k)
          if ((j shl (20 - k)) == iy) yisint = 2 - (j and 1)
        }
      }
    }

    if (ly == 0) {
      if (iy == 0x7ff00000) {
        return if (((ix - 0x3ff00000) or lx) == 0) y - y
        else if (ix >= 0x3ff00000) (if (hy >= 0) y else 0.0)
        else (if (hy < 0) -y else 0.0)
      }
      if (iy == 0x3ff00000) return if (hy < 0) 1.0 / x else x
      if (hy == 0x40000000) return x * x
      if (hy == 0x3fe00000) {
        if (hx >= 0) return sqrt(x)
      }
    }

    var ax = abs(x)
    if (lx == 0) {
      if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
        var z = ax
        if (hy < 0) z = 1.0 / z
        if (hx < 0) {
          if (((ix - 0x3ff00000) or yisint) == 0) z = (z - z) / (z - z)
          else if (yisint == 1) z = -z
        }
        return z
      }
    }

    var n = (hx shr 31) + 1
    if ((n or yisint) == 0) return (x - x) / (x - x)

    var s = 1.0
    if ((n or (yisint - 1)) == 0) s = -1.0

    val t1: Double
    val t2: Double
    if (iy > 0x41e00000) {
      if (iy > 0x43f00000) {
        if (ix <= 0x3fefffff) return if (hy < 0) HUGE * HUGE else TINY * TINY
        if (ix >= 0x3ff00000) return if (hy > 0) HUGE * HUGE else TINY * TINY
      }
      if (ix < 0x3fefffff) return if (hy < 0) s * HUGE * HUGE else s * TINY * TINY
      if (ix > 0x3ff00000) return if (hy > 0) s * HUGE * HUGE else s * TINY * TINY
      val t = ax - 1.0
      val w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25))
      val u = IVLN2_H * t
      val v = t * IVLN2_L - w * IVLN2
      t1 = withLo(u + v, 0)
      t2 = v - (t1 - u)
    } else {
      n = 0
      if (ix < 0x00100000) {
        ax *= TWO53
        n -= 53
        ix = hi(ax)
      }
      n += (ix shr 20) - 0x3ff
      val j = ix and 0x000fffff
      ix = j or 0x3ff00000
      val k: Int
      if (j <= 0x3988E) {
        k = 0
      } else if (j < 0xBB67A) {
        k = 1
      } else {
        k = 0
        n += 1
        ix -= 0x00100000
      }
      ax = withHi(ax, ix)

      var u = ax - BP[k]
      var v = 1.0 / (ax + BP[k])
      val ss = u * v
      val sH = withLo(ss, 0)
      var tH = fromWords(((ix shr 1) or 0x20000000) + 0x00080000 + (k shl 18), 0)
      var tL = ax - (tH - BP[k])
      val sL = v * ((u - sH * tH) - sH * tL)
      var s2 = ss * ss
      var r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))))
      r += sL * (sH + ss)
      s2 = sH * sH
      tH = withLo(3.0 + s2 + r, 0)
      tL = r - ((tH - 3.0) - s2)
      u = sH * tH
      v = sL * tH + tL * ss
      val pH = withLo(u + v, 0)
      val pL = v - (pH - u)
      val zH = CP_H * pH
      val zL = CP_L * pH + pL * CP + DP_L[k]
      val t = n.toDouble()
      t1 = withLo(((zH + zL) + DP_H[k]) + t, 0)
      t2 = zL - (((t1 - t) - DP_H[k]) - zH)
    }

    val y1 = withLo(y, 0)
    val pL = (y - y1) * t1 + y * t2
    var pH = y1 * t1
    var z = pL + pH
    var j = hi(z)
    var i = lo(z)
    if (j >= 0x40900000) {
      if (((j - 0x40900000) or i) != 0) return s * HUGE * HUGE
      if (pL + OVT > z - pH) return s * HUGE * HUGE
    } else if ((j and 0x7fffffff) >= 0x4090cc00) {
      if (((j - 0xc090cc00.toInt()) or i) != 0) return s * TINY * TINY
      if (pL <= z - pH) return s * TINY * TINY
    }

    i = j and 0x7fffffff
    var k = (i shr 20) - 0x3ff
    n = 0
    if (i > 0x3fe00000) {
      n = j + (0x00100000 shr (k + 1))
      k = ((n and 0x7fffffff) shr 20) - 0x3ff
      val t = fromWords(n and (0x000fffff shr k).inv(), 0)
      n = ((n and 0x000fffff) or 0x00100000) shr (20 - k)
      if (j < 0) n = -n
      pH -= t
    }
    val t = withLo(pL + pH, 0)
    val u = t * LG2_H
    val v = (pL - (t - pH)) * LG2 + t * LG2_L
    z = u + v
    val w = v - (z - u)
    val tt = z * z
    val tt1 = z - tt * (P1 + tt * (P2 + tt * (P3 + tt * (P4 + tt * P5))))
    val r = (z * tt1) / (tt1 - 2.0) - (w + z * w)
    z = 1.0 - (r - z)
    j = hi(z)
    j += (n shl 20)
    z = if ((j shr 20) <= 0) scalbn(z, n) else withHi(z, hi(z) + (n shl 20))
    return s * z
  }

  // s_scalbn.c
  private const val TWO54 = 1.80143985094819840000e+16
  private const val TWOM54 = 5.55111512312578270212e-17

  private fun scalbn(x0: Double, n: Int): Double {
    var x = x0
    var hx = hi(x)
    val lx = lo(x)
    var k = (hx and 0x7ff00000) shr 20
    if (k == 0) {
      if ((lx or (hx and 0x7fffffff)) == 0) return x
      x *= TWO54
      hx = hi(x)
      k = ((hx and 0x7ff00000) shr 20) - 54
      if (n < -50000) return TINY * x
    }
    if (k == 0x7ff) return x + x
    k += n
    if (k > 0x7fe) return HUGE * (if (x < 0) -HUGE else HUGE)
    if (k > 0) return withHi(x, (hx and 0x800fffff.toInt()) or (k shl 20))
    if (k <= -54) {
      return if (n > 50000) HUGE * (if (x < 0) -HUGE else HUGE) else TINY * (if (x < 0) -TINY else TINY)
    }
    k += 54
    x = withHi(x, (hx and 0x800fffff.toInt()) or (k shl 20))
    return x * TWOM54
  }
}
