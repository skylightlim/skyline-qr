package com.skylightlim.skylineqr.core

/** The raw QR module grid, no quiet zone. `true` is a dark module. */
class QrMatrix(val size: Int, val dark: BooleanArray, val symbolVersion: Int) {

  fun isDark(col: Int, row: Int): Boolean =
    col in 0 until size && row in 0 until size && dark[row * size + col]

  val darkCount: Int
    get() = dark.count { it }

  /** What each module is for, row by row like [dark]. */
  val roles: Array<QrRole> by lazy { QrRoles.of(symbolVersion) }

  fun role(col: Int, row: Int): QrRole = roles[row * size + col]
}

/**
 * Encodes a link's payload with the QR profile of its generator version.
 *
 * Version 1: error correction M, at most symbol version 6 (41 x 41). Larger symbols become towers
 * too small to scan off a phone screen, so a longer link is refused rather than squeezed in.
 *
 * Pure Kotlin since the website (2026-11-21), so the browser draws the same code: an adaptation of
 * ZXing's encoder (Apache 2.0, see NOTICE) cut down to what version 1 uses. It must choose exactly
 * what ZXing 3.5's `Encoder.encode(payload, M)` chose, mode and mask included; `QrEncoderTest`
 * holds it to ZXing on the JVM.
 */
object QrEncoder {

  const val MAX_SYMBOL_VERSION = 6

  fun encode(payload: String, generatorVersion: Int = GeneratorVersion.CURRENT): QrMatrix {
    GeneratorVersion.resolve(generatorVersion)
    return withMask(payload, -1)
  }

  /** The symbol with the given [mask], or the one with the lowest penalty for -1. */
  internal fun withMask(payload: String, mask: Int): QrMatrix {
    // Payloads are ASCII: percent-encoded links, which go in byte mode without an ECI header, or
    // payment codes (digits and capitals), which go in alphanumeric mode, the denser one, so a
    // 125-character DuitNow code still fits version 6 at M.
    require(payload.all { it.code in 0x20..0x7e }) { "payload must be ASCII: $payload" }
    val mode = Mode.of(payload)
    val data = Bits()
    when (mode) {
      Mode.NUMERIC -> appendNumeric(payload, data)
      Mode.ALPHANUMERIC -> appendAlphanumeric(payload, data)
      Mode.BYTE -> for (c in payload) data.append(c.code, 8)
    }
    // Versions 1 to 9 share the character count's width, so the first version that fits is
    // the one ZXing's two-step recommendation lands on.
    val bitsNeeded = 4 + mode.countBits + data.size
    val version = (1..MAX_SYMBOL_VERSION).firstOrNull { (bitsNeeded + 7) / 8 <= Version.of(it).dataBytes }
      ?: throw LinkTooLongException()
    val v = Version.of(version)

    val bits = Bits()
    bits.append(mode.bits, 4)
    bits.append(payload.length, mode.countBits)
    bits.appendAll(data)
    terminate(bits, v.dataBytes)
    val codewords = interleave(bits.toBytes(), v)

    val size = 17 + 4 * version
    var best: ByteArray? = null
    var bestPenalty = Int.MAX_VALUE
    for (candidate in if (mask >= 0) mask..mask else 0..7) {
      val m = build(codewords, version, size, candidate)
      val penalty = penalty(m, size)
      if (penalty < bestPenalty) {
        bestPenalty = penalty
        best = m
      }
    }
    val chosen = best!!
    return QrMatrix(size, BooleanArray(size * size) { chosen[it] == ONE }, version)
  }

  private const val EMPTY: Byte = -1
  private const val ZERO: Byte = 0
  private const val ONE: Byte = 1

  private enum class Mode(val bits: Int, val countBits: Int) {
    NUMERIC(0x1, 10),
    ALPHANUMERIC(0x2, 9),
    BYTE(0x4, 8);

    companion object {
      /** ZXing's choice: alphanumeric if any character needs it, numeric if all are digits. */
      fun of(text: String): Mode {
        var numeric = false
        var alphanumeric = false
        for (c in text) {
          if (c in '0'..'9') numeric = true
          else if (ALNUM.indexOf(c) >= 0) alphanumeric = true
          else return BYTE
        }
        return if (alphanumeric) ALPHANUMERIC else if (numeric) NUMERIC else BYTE
      }
    }
  }

  private const val ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

  /** Symbol versions 1 to 6 at error correction M: blocks and error correction bytes per block. */
  private class Version(val number: Int, val totalBytes: Int, val ecPerBlock: Int, val blocks: Int, val alignment: IntArray) {
    val dataBytes get() = totalBytes - ecPerBlock * blocks

    companion object {
      private val ALL = arrayOf(
        Version(1, 26, 10, 1, intArrayOf()),
        Version(2, 44, 16, 1, intArrayOf(6, 18)),
        Version(3, 70, 26, 1, intArrayOf(6, 22)),
        Version(4, 100, 18, 2, intArrayOf(6, 26)),
        Version(5, 134, 24, 2, intArrayOf(6, 30)),
        Version(6, 172, 16, 4, intArrayOf(6, 34)),
      )

      fun of(number: Int) = ALL[number - 1]
    }
  }

  private class Bits {
    private var bytes = ByteArray(32)
    var size = 0
      private set

    operator fun get(i: Int) = (bytes[i ushr 3].toInt() shr (7 - (i and 7))) and 1 == 1

    fun append(value: Int, count: Int) {
      for (i in count - 1 downTo 0) appendBit((value ushr i) and 1 == 1)
    }

    fun appendBit(bit: Boolean) {
      if (size ushr 3 >= bytes.size) bytes = bytes.copyOf(bytes.size * 2)
      if (bit) bytes[size ushr 3] = (bytes[size ushr 3].toInt() or (0x80 ushr (size and 7))).toByte()
      size++
    }

    fun appendAll(other: Bits) {
      for (i in 0 until other.size) appendBit(other[i])
    }

    fun toBytes(): ByteArray = bytes.copyOf(size ushr 3)
  }

  private fun appendNumeric(text: String, bits: Bits) {
    var i = 0
    while (i < text.length) {
      val n = minOf(3, text.length - i)
      bits.append(text.substring(i, i + n).toInt(), when (n) { 3 -> 10; 2 -> 7; else -> 4 })
      i += n
    }
  }

  private fun appendAlphanumeric(text: String, bits: Bits) {
    var i = 0
    while (i < text.length) {
      val a = ALNUM.indexOf(text[i])
      if (i + 1 < text.length) {
        bits.append(a * 45 + ALNUM.indexOf(text[i + 1]), 11)
        i += 2
      } else {
        bits.append(a, 6)
        i++
      }
    }
  }

  /** Up to four zero bits, zeros to the byte, then the pad bytes 0xEC and 0x11 in turn. */
  private fun terminate(bits: Bits, dataBytes: Int) {
    val capacity = dataBytes * 8
    repeat(4) { if (bits.size < capacity) bits.appendBit(false) }
    while (bits.size and 7 != 0) bits.appendBit(false)
    var pad = 0
    while (bits.size < capacity) {
      bits.append(if (pad % 2 == 0) 0xEC else 0x11, 8)
      pad++
    }
  }

  /** Splits the data into blocks, adds each one's Reed-Solomon bytes and interleaves them. */
  private fun interleave(data: ByteArray, v: Version): ByteArray {
    val group2 = v.totalBytes % v.blocks
    val group1 = v.blocks - group2
    val data1 = v.dataBytes / v.blocks
    val dataBlocks = ArrayList<ByteArray>()
    val ecBlocks = ArrayList<ByteArray>()
    var offset = 0
    for (b in 0 until v.blocks) {
      val n = if (b < group1) data1 else data1 + 1
      val block = data.copyOfRange(offset, offset + n)
      offset += n
      dataBlocks += block
      ecBlocks += ReedSolomon.remainder(block, v.ecPerBlock)
    }
    val out = ByteArray(v.totalBytes)
    var k = 0
    for (i in 0 until dataBlocks.maxOf { it.size }) for (block in dataBlocks) if (i < block.size) out[k++] = block[i]
    for (i in 0 until v.ecPerBlock) for (block in ecBlocks) out[k++] = block[i]
    return out
  }

  private object ReedSolomon {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
      var x = 1
      for (i in 0 until 255) {
        exp[i] = x
        log[x] = i
        x = x shl 1
        if (x >= 256) x = x xor 0x11D
      }
      for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    /** The generator (x - a^0)(x - a^1)...(x - a^(degree-1)), highest power first. */
    private fun generator(degree: Int): IntArray {
      var g = intArrayOf(1)
      for (i in 0 until degree) {
        val next = IntArray(g.size + 1)
        for (j in g.indices) {
          next[j] = next[j] xor g[j]
          next[j + 1] = next[j + 1] xor mul(g[j], exp[i])
        }
        g = next
      }
      return g
    }

    fun remainder(data: ByteArray, ecBytes: Int): ByteArray {
      val g = generator(ecBytes)
      val r = IntArray(ecBytes)
      for (b in data) {
        val factor = (b.toInt() and 0xff) xor r[0]
        for (j in 0 until ecBytes - 1) r[j] = r[j + 1] xor mul(g[j + 1], factor)
        r[ecBytes - 1] = mul(g[ecBytes], factor)
      }
      return ByteArray(ecBytes) { r[it].toByte() }
    }
  }

  private val FINDER = arrayOf("1111111", "1000001", "1011101", "1011101", "1011101", "1000001", "1111111")
  private val ALIGNMENT = arrayOf("11111", "10001", "10101", "10001", "11111")
  private val FORMAT_COORDINATES = arrayOf(
    intArrayOf(8, 0), intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(8, 3), intArrayOf(8, 4), intArrayOf(8, 5),
    intArrayOf(8, 7), intArrayOf(8, 8), intArrayOf(7, 8), intArrayOf(5, 8), intArrayOf(4, 8), intArrayOf(3, 8),
    intArrayOf(2, 8), intArrayOf(1, 8), intArrayOf(0, 8),
  )

  /** The whole symbol with [mask], row by row: function patterns, format bits, then the data. */
  private fun build(codewords: ByteArray, version: Int, size: Int, mask: Int): ByteArray {
    val m = ByteArray(size * size) { EMPTY }
    fun set(x: Int, y: Int, dark: Boolean) { m[y * size + x] = if (dark) ONE else ZERO }
    fun empty(x: Int, y: Int) = m[y * size + x] == EMPTY

    for ((fx, fy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
      for (y in 0 until 7) for (x in 0 until 7) set(fx + x, fy + y, FINDER[y][x] == '1')
    }
    for (x in 0 until 8) {
      set(x, 7, false)
      set(size - 8 + x, 7, false)
      set(x, size - 8, false)
    }
    for (y in 0 until 7) {
      set(7, y, false)
      set(size - 8, y, false)
      set(7, size - 7 + y, false)
    }
    set(8, size - 8, true)
    val centres = Version.of(version).alignment
    for (cy in centres) for (cx in centres) {
      if (!empty(cx, cy)) continue
      for (y in 0 until 5) for (x in 0 until 5) set(cx - 2 + x, cy - 2 + y, ALIGNMENT[y][x] == '1')
    }
    for (i in 8 until size - 8) {
      val dark = (i + 1) % 2 == 1
      if (empty(i, 6)) set(i, 6, dark)
      if (empty(6, i)) set(6, i, dark)
    }

    // Format: error correction M (00) and the mask, a BCH(15,5) code, masked with 0x5412.
    val info = mask
    var bch = info shl 10
    for (bit in 14 downTo 10) if ((bch ushr bit) and 1 == 1) bch = bch xor (0x537 shl (bit - 10))
    val format = ((info shl 10) or bch) xor 0x5412
    for (i in 0 until 15) {
      val dark = (format ushr i) and 1 == 1
      val (x1, y1) = FORMAT_COORDINATES[i]
      set(x1, y1, dark)
      if (i < 8) set(size - i - 1, 8, dark) else set(8, size - 7 + (i - 8), dark)
    }

    // Data: two columns at a time from the bottom right, up and down in turn, skipping column 6.
    val total = codewords.size * 8
    var index = 0
    var direction = -1
    var x = size - 1
    var y = size - 1
    while (x > 0) {
      if (x == 6) x--
      while (y in 0 until size) {
        for (i in 0..1) {
          val xx = x - i
          if (!empty(xx, y)) continue
          var bit = false
          if (index < total) {
            bit = (codewords[index ushr 3].toInt() shr (7 - (index and 7))) and 1 == 1
            index++
          }
          if (maskBit(mask, xx, y)) bit = !bit
          set(xx, y, bit)
        }
        y += direction
      }
      direction = -direction
      y += direction
      x -= 2
    }
    check(index == total) { "not all data bits placed" }
    return m
  }

  private fun maskBit(mask: Int, x: Int, y: Int): Boolean {
    val v = when (mask) {
      0 -> (y + x) and 1
      1 -> y and 1
      2 -> x % 3
      3 -> (y + x) % 3
      4 -> ((y / 2) + (x / 3)) and 1
      5 -> (y * x and 1) + (y * x % 3)
      6 -> ((y * x and 1) + (y * x % 3)) and 1
      else -> ((y * x % 3) + ((y + x) and 1)) and 1
    }
    return v == 0
  }

  /** ZXing's mask penalty: runs, 2 x 2 blocks, finder-like patterns and the dark balance. */
  private fun penalty(m: ByteArray, size: Int): Int {
    fun at(x: Int, y: Int) = m[y * size + x]
    var total = 0
    // Rule 1: five or more in a row, across and down.
    for (horizontal in listOf(true, false)) {
      for (i in 0 until size) {
        var run = 0
        var prev: Byte = EMPTY
        for (j in 0 until size) {
          val bit = if (horizontal) at(j, i) else at(i, j)
          if (bit == prev) run++
          else {
            if (run >= 5) total += 3 + (run - 5)
            run = 1
            prev = bit
          }
        }
        if (run >= 5) total += 3 + (run - 5)
      }
    }
    // Rule 2: 2 x 2 blocks of one colour.
    var blocks = 0
    for (y in 0 until size - 1) for (x in 0 until size - 1) {
      val v = at(x, y)
      if (v == at(x + 1, y) && v == at(x, y + 1) && v == at(x + 1, y + 1)) blocks++
    }
    total += 3 * blocks
    // Rule 3: 1011101 with four light modules on either side.
    // A run that would reach past the edge does not count as light (ZXing's isWhiteHorizontal).
    fun lightAcross(y: Int, from: Int, to: Int): Boolean {
      if (from < 0 || to > size) return false
      for (x in from until to) if (at(x, y) == ONE) return false
      return true
    }
    fun lightDown(x: Int, from: Int, to: Int): Boolean {
      if (from < 0 || to > size) return false
      for (y in from until to) if (at(x, y) == ONE) return false
      return true
    }
    val pattern = byteArrayOf(1, 0, 1, 1, 1, 0, 1)
    var finders = 0
    for (y in 0 until size) for (x in 0 until size) {
      if (x + 6 < size && (0 until 7).all { at(x + it, y) == pattern[it] } &&
        (lightAcross(y, x - 4, x) || lightAcross(y, x + 7, x + 11))) finders++
      if (y + 6 < size && (0 until 7).all { at(x, y + it) == pattern[it] } &&
        (lightDown(x, y - 4, y) || lightDown(x, y + 7, y + 11))) finders++
    }
    total += 40 * finders
    // Rule 4: ten points for each 5% the dark share strays from half.
    val dark = m.count { it == ONE }
    val cells = size * size
    total += kotlin.math.abs(dark * 2 - cells) * 10 / cells * 10
    return total
  }
}
