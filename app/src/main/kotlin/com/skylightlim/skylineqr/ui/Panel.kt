package com.skylightlim.skylineqr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The panel's controls, after the ICQR tree's: frosted tiles that float over the city with no
 * sheet behind them. A hint pill, the link with the share button beside it, then the choices.
 */

/** Corners of every tile. */
internal val TileShape = RoundedCornerShape(14.dp)

/** A tile's height: the link field's own, so a row of tiles lines up with it. */
internal val FieldHeight = 56.dp
internal val TileHeight = 48.dp

/**
 * The frost every control sits on. A SurfaceView cannot be blurred from above, so the frost is the
 * scheme's surface tinted by the sky it floats in.
 */
@Composable
internal fun frost(sky: Color, dark: Boolean): Color =
  lerp(MaterialTheme.colorScheme.surface, sky, if (dark) 0.16f else 0.12f).copy(alpha = 0.92f)

@Composable
private fun edge() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))

/** The app's name and the city's code at the top, as ICQR shows its logo, and the licences behind (i). */
@Composable
internal fun TopBar(code: String?, onSky: Color, onInfo: () -> Unit, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("Skyline QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = onSky)
      Text(
        text = code ?: "------",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = onSky,
        modifier =
          Modifier.padding(top = 4.dp).clip(CircleShape).background(onSky.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 2.dp),
      )
    }
    IconButton(onClick = onInfo, modifier = Modifier.align(Alignment.CenterEnd)) {
      Icon(SkylineIcons.Info, contentDescription = "Open-source licences", tint = onSky)
    }
  }
}

/** What a tap does next, or what is wrong with the link. */
@Composable
internal fun HintPill(text: String, error: Boolean, fill: Color, modifier: Modifier = Modifier) {
  Surface(
    shape = CircleShape,
    color = fill,
    contentColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    border = edge(),
    modifier = modifier,
  ) {
    Text(
      text,
      style = MaterialTheme.typography.labelMedium,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
    )
  }
}

/** Choices in one frosted tile, the chosen one lifted, like ICQR's seasons. */
@Composable
internal fun <T> Segments(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  fill: Color,
  icon: (T) -> ImageVector,
  label: (T) -> String,
  modifier: Modifier = Modifier,
) {
  val colours = MaterialTheme.colorScheme
  Row(
    modifier
      .height(TileHeight)
      .clip(TileShape)
      .background(fill)
      .border(edge(), TileShape)
      .padding(4.dp)
      .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    for (option in options) {
      val chosen = option == selected
      val ink = if (chosen) colours.onSecondaryContainer else colours.onSurfaceVariant
      Row(
        Modifier.weight(1f)
          .fillMaxHeight()
          .clip(RoundedCornerShape(10.dp))
          .background(if (chosen) colours.secondaryContainer else Color.Transparent)
          .selectable(selected = chosen, onClick = { onSelect(option) }, role = Role.RadioButton),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(icon(option), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label(option), style = MaterialTheme.typography.labelLarge, color = ink)
      }
    }
  }
}

/**
 * A square tile with one icon; [accent] fills it with the primary colour, as ICQR's share button,
 * and [menu] adds a small chevron for a tile that opens a list.
 */
@Composable
internal fun TileButton(
  icon: ImageVector,
  description: String,
  fill: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = TileHeight,
  accent: Boolean = false,
  menu: Boolean = false,
) {
  val colours = MaterialTheme.colorScheme
  Surface(
    onClick = onClick,
    shape = TileShape,
    color = if (accent) colours.primary else fill,
    contentColor = if (accent) colours.onPrimary else colours.onSurface,
    border = if (accent) null else edge(),
    modifier = modifier.size(size),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
      if (menu) {
        Icon(
          SkylineIcons.More,
          contentDescription = null,
          tint = colours.onSurfaceVariant,
          modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 5.dp).size(10.dp),
        )
      }
    }
  }
}
