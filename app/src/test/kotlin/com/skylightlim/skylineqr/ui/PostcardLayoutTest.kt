package com.skylightlim.skylineqr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The postcard keeps its code whole and readable, whatever the code's size. */
class PostcardLayoutTest {

  /** Every QR version, 1 (21 modules) to 40 (177). */
  private val sizes = (1..40).map { 17 + 4 * it }

  @Test
  fun theCodeIsWholeOnTheCardAtItsOwnSize() {
    for (modules in sizes) {
      val layout = Postcard.layout(modules)
      val code = layout.code
      assertEquals("$modules: square", code.width, code.height)
      assertEquals("$modules: whole pixels a module", (modules + 2 * Postcard.QUIET) * layout.modulePx, code.width)
      assertTrue("$modules: at least ${Postcard.MIN_MODULE_PX} px a module", layout.modulePx >= Postcard.MIN_MODULE_PX)
      assertTrue("$modules: on the card", code.left >= 0 && code.top >= 0 && code.right <= Postcard.WIDTH && code.bottom <= Postcard.HEIGHT)
    }
  }

  @Test
  fun nothingCoversTheCodeOrItsQuietZone() {
    for (modules in sizes) {
      val layout = Postcard.layout(modules)
      val code = layout.code
      for (other in listOf(layout.photo, layout.words)) {
        val apart = other.right <= code.left || other.bottom <= code.top
        assertTrue("$modules: $other overlaps the code $code", apart)
      }
      assertTrue("$modules: the photo is above the words", layout.photo.bottom <= layout.words.top)
    }
  }

  @Test
  fun thePhotoAndWordsKeepUsefulRoom() {
    for (modules in sizes) {
      val layout = Postcard.layout(modules)
      assertTrue("$modules: photo ${layout.photo}", layout.photo.height >= 600 && layout.photo.width >= 900)
      assertTrue("$modules: words ${layout.words}", layout.words.width >= 300 && layout.words.height >= 200)
    }
  }

  @Test
  fun anEverydayLinkGetsABigCode() {
    // A short link is version 2 or 3: about 9 px a module, comfortably scanned off a screen.
    assertTrue(Postcard.layout(25).modulePx >= 9)
    assertTrue(Postcard.layout(29).modulePx >= 8)
  }
}
