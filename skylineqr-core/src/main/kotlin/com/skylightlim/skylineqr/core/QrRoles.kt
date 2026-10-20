package com.skylightlim.skylineqr.core

/** What a module of a QR symbol is for. */
enum class QrRole { DATA, REMAINDER, FINDER, SEPARATOR, TIMING, ALIGNMENT, FORMAT, FIXED_DARK }

/**
 * The role of every module, so a city can build the three finder patterns differently from the
 * data around them and keep landmarks off them.
 *
 * Ported from every-qrcode's qr-roles.ts (MIT, see NOTICE), for symbol versions 1 to 6, the range
 * [QrEncoder] produces.
 */
object QrRoles {

  /** Codewords per symbol version, which the data traversal has to account for exactly. */
  private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172)

  private val ALIGNMENT_CENTERS =
    arrayOf(intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34))

  fun of(symbolVersion: Int): Array<QrRole> {
    require(symbolVersion in 1..QrEncoder.MAX_SYMBOL_VERSION) { "symbol version $symbolVersion" }
    val size = 17 + 4 * symbolVersion
    val roles = arrayOfNulls<QrRole>(size * size)
    fun set(col: Int, row: Int, role: QrRole) {
      roles[row * size + col] = role
    }
    fun setIfEmpty(col: Int, row: Int, role: QrRole) {
      if (roles[row * size + col] == null) roles[row * size + col] = role
    }

    for ((startCol, startRow) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
      for (row in 0 until 7) for (col in 0 until 7) set(startCol + col, startRow + row, QrRole.FINDER)
    }
    for (offset in 0 until 8) {
      setIfEmpty(offset, 7, QrRole.SEPARATOR)
      setIfEmpty(7, offset, QrRole.SEPARATOR)
      setIfEmpty(size - 8 + offset, 7, QrRole.SEPARATOR)
      setIfEmpty(size - 8, offset, QrRole.SEPARATOR)
      setIfEmpty(offset, size - 8, QrRole.SEPARATOR)
      setIfEmpty(7, size - 8 + offset, QrRole.SEPARATOR)
    }
    for (c in 8..size - 9) {
      setIfEmpty(c, 6, QrRole.TIMING)
      setIfEmpty(6, c, QrRole.TIMING)
    }
    val centers = ALIGNMENT_CENTERS[symbolVersion]
    for (centerRow in centers) {
      for (centerCol in centers) {
        if (roles[centerRow * size + centerCol] != null) continue
        for (row in centerRow - 2..centerRow + 2) for (col in centerCol - 2..centerCol + 2) set(col, row, QrRole.ALIGNMENT)
      }
    }
    for (c in 0..5) {
      setIfEmpty(8, c, QrRole.FORMAT)
      setIfEmpty(c, 8, QrRole.FORMAT)
    }
    setIfEmpty(8, 7, QrRole.FORMAT)
    setIfEmpty(8, 8, QrRole.FORMAT)
    setIfEmpty(7, 8, QrRole.FORMAT)
    for (col in size - 8 until size) setIfEmpty(col, 8, QrRole.FORMAT)
    for (row in size - 7 until size) setIfEmpty(8, row, QrRole.FORMAT)
    set(8, size - 8, QrRole.FIXED_DARK)

    // Everything left is data, in the zigzag order the encoder fills it; the tail is remainder bits.
    val positions = ArrayList<Int>()
    var upward = true
    var right = size - 1
    while (right >= 1) {
      if (right == 6) right -= 1
      for (step in 0 until size) {
        val row = if (upward) size - 1 - step else step
        for (col in intArrayOf(right, right - 1)) {
          val index = row * size + col
          if (roles[index] == null) positions += index
        }
      }
      upward = !upward
      right -= 2
    }
    val codewordBits = TOTAL_CODEWORDS[symbolVersion] * 8
    check(positions.size >= codewordBits && positions.size - codewordBits <= 7) {
      "QR role placement does not match version $symbolVersion"
    }
    positions.forEachIndexed { order, index ->
      roles[index] = if (order < codewordBits) QrRole.DATA else QrRole.REMAINDER
    }
    return Array(roles.size) { roles[it]!! }
  }
}
