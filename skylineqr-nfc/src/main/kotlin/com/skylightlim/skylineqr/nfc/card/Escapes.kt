package com.skylightlim.skylineqr.nfc.card

/** Backslash escaping as used by the WIFI:, MECARD: and vCard text formats. */
internal object Escapes {

  /** Splits on [separator]s that aren't escaped. The parts keep their escapes. */
  fun split(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        c == '\\' && i + 1 < text.length -> {
          part.append(c).append(text[i + 1])
          i += 2
          continue
        }
        c == separator -> {
          parts += part.toString()
          part.clear()
        }
        else -> part.append(c)
      }
      i++
    }
    parts += part.toString()
    return parts
  }

  /** Drops each escaping backslash. With [newlines], `\n` and `\N` mean a line break (vCard). */
  fun unescape(text: String, newlines: Boolean = false): String {
    if ('\\' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '\\' && i + 1 < text.length) {
        val next = text[i + 1]
        out.append(if (newlines && (next == 'n' || next == 'N')) '\n' else next)
        i += 2
      } else {
        out.append(c)
        i++
      }
    }
    return out.toString()
  }

  /** Puts a backslash before every character in [special]. */
  fun escape(text: String, special: String): String = buildString(text.length) {
    for (c in text) {
      if (c in special) append('\\')
      append(c)
    }
  }
}
