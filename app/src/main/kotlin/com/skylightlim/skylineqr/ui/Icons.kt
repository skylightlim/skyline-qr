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

  val Now = icon("Now", circle(12f, 12f, 10f), "M12 6V12H16.5")

  val Day =
    icon(
      "Day", circle(12f, 12f, 4f), "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41",
      "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41",
    )

  val Night = icon("Night", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z")

  val Rain = icon("Rain", "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242", "M16 14v6", "M8 14v6", "M12 16v6")

  val Haze =
    icon(
      "Haze", "m5.2 6.2 1.4 1.4", "M2 13h2", "M20 13h2", "m17.4 7.6 1.4-1.4", "M22 17H2", "M22 21H2", "M16 13a4 4 0 0 0-8 0",
      "M12 5V2.5",
    )

  val Storm = icon("Storm", "M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973", "m13 12-3 5h4l-3 5")

  private const val SPEAKER =
    "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z"

  val SoundOn = icon("Sound on", SPEAKER, "M16 9a5 5 0 0 1 0 6", "M19.364 18.364a9 9 0 0 0 0-12.728")

  val SoundOff = icon("Sound off", SPEAKER, "M22 9L16 15", "M16 9L22 15")

  val Share = icon("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6L12 2L8 6", "M12 2V15")

  val Info = icon("Info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")

  val More = icon("More", "m6 9 6 6 6-6")

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

  /** An SVG circle as a path: two half arcs. */
  private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
}
