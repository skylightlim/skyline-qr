package com.skylightlim.skylineqr.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The panel's few icons, ported from Lucide 0.460 (ISC, see NOTICE) as 24-unit stroke paths, so the
 * app does not carry the whole Material icon set. Icon() tints them like any other.
 */
internal object SkylineIcons {

  val Day =
    icon(
      "Day", circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
      "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
    )

  val Rain = icon("Rain", "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M16 14v6", "M8 14v6", "M12 16v6")

  val Haze =
    icon(
      "Haze", "m5.2 6.2 1.4 1.4", "M2 13h2", "M20 13h2", "m17.4 7.6 1.4-1.4", "M22 17H2", "M22 21H2", "M16 13a4 4 0 0 0-8 0",
      "M12 5V2.5",
    )

  val Storm = icon("Storm", "M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973", "m13 12-3 5h4l-3 5")

  /** Lucide's cloud: the clouds' setting. */
  val Cloud = icon("Cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")

  val Share = icon("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6L12 2L8 6", "M12 2V15")

  val More = icon("More", "m6 9 6 6 6-6")

  /** Lucide's check: the chosen item in a menu. */
  val Check = icon("Check", "M20 6 9 17l-5-5")

  // The card popup and tap to share.

  /** Lucide's nfc: tap to share. */
  val Nfc =
    icon("Nfc", "M6 8.32a7.43 7.43 0 0 1 0 7.36", "M9.46 6.21a11.76 11.76 0 0 1 0 11.58", "M12.91 4.1a15.91 15.91 0 0 1 .01 15.8", "M16.37 2a20.16 20.16 0 0 1 0 20")

  /** Lucide's smartphone-nfc: read a tag. */
  val ReadTag =
    icon("ReadTag", rect(2f, 6f, 7f, 12f, 1f), "M13 8.32a7.43 7.43 0 0 1 0 7.36", "M16.46 6.21a11.76 11.76 0 0 1 0 11.58", "M19.91 4.1a15.91 15.91 0 0 1 .01 15.8")

  /** Lucide's scan-line: scan a QR code. */
  val Scan = icon("Scan", "M3 7V5a2 2 0 0 1 2-2h2", "M17 3h2a2 2 0 0 1 2 2v2", "M21 17v2a2 2 0 0 1-2 2h-2", "M7 21H5a2 2 0 0 1-2-2v-2", "M7 12h10")

  /** Lucide's plus: a new card. */
  val Add = icon("Add", "M5 12h14", "M12 5v14")

  /** Lucide's trash-2: remove a card. */
  val Delete = icon("Delete", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11v6", "M14 11v6")

  /** Lucide's eye and eye-off: show or hide a Wi-Fi password. */
  val Show = icon("Show", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", circle(12f, 12f, 3f))
  val Hide =
    icon(
      "Hide", "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49", "M14.084 14.158a3 3 0 0 1-4.242-4.242",
      "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143", "m2 2 20 20",
    )

  /** Lucide's triangle-alert: something wrong. */
  val Warning = icon("Warning", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")

  /** Lucide's rotate-ccw: the camera going back to where it was. */
  val Reset = icon("Reset", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")

  private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
      .apply {
        for (path in paths) {
          addPath(
            addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
          )
        }
      }
      .build()

  /** An SVG rect with corners of radius [r] as a path. */
  private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

  /** An SVG circle as a path: two half arcs. */
  private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
}
