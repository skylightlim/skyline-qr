package com.skylightlim.skylineqr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrRolesTest {

  @Test
  fun everyVersionAccountsForEveryModule() {
    for (version in 1..QrEncoder.MAX_SYMBOL_VERSION) {
      val roles = QrRoles.of(version)
      val size = 17 + 4 * version
      assertEquals(size * size, roles.size)
      assertEquals(3 * 49, roles.count { it == QrRole.FINDER })
      assertEquals(if (version == 1) 0 else 25, roles.count { it == QrRole.ALIGNMENT })
      assertEquals(1, roles.count { it == QrRole.FIXED_DARK })
    }
  }

  @Test
  fun functionPatternsAreWhereTheEncoderDrewThem() {
    val matrix = QrEncoder.encode(Link.parse("https://github.com/skylightlim/skyline-qr").payload)
    for (row in 0 until matrix.size) {
      for (col in 0 until matrix.size) {
        when (matrix.role(col, row)) {
          QrRole.SEPARATOR -> assertTrue("separator at $col,$row", !matrix.isDark(col, row))
          QrRole.FIXED_DARK -> assertTrue("fixed dark at $col,$row", matrix.isDark(col, row))
          QrRole.TIMING -> assertEquals("timing at $col,$row", (col + row) % 2 == 0, matrix.isDark(col, row))
          else -> {}
        }
      }
    }
    // The finder cores are solid 3 x 3 dark blocks.
    for (row in 2..4) for (col in 2..4) assertTrue(matrix.isDark(col, row))
  }
}
