@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The clouds' setting (2026-11-07, at the user's request), opened by holding a cloud, or the sun
 * or the moon, and shown [at] the finger: a menu of one row, the clouds' switch, like the weather
 * menu. Off, the clouds blow away downwind and the row says how to find it again, since with them
 * gone only the sun or the moon can open it; on, they blow back in. Null [at] closes it; it stays
 * open after a switch, so the clouds can be seen to go, until a tap outside or back.
 */
@Composable
internal fun SkyMenu(at: Offset?, clouds: Boolean, onClouds: (Boolean) -> Unit, onDismiss: () -> Unit) {
  // Where it was last opened, so it closes where it stood.
  var anchor by remember { mutableStateOf(Offset.Zero) }
  if (at != null) anchor = at
  Box(Modifier.offset { IntOffset(anchor.x.roundToInt(), anchor.y.roundToInt()) }.size(1.dp)) {
    DropdownMenuPopup(expanded = at != null, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = SkyMenuWidth, max = SkyMenuWidth)) {
      DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), contentPadding = PaddingValues(MenuInset)) {
        val press = remember { MutableInteractionSource() }
        Row(
          Modifier.fillMaxWidth()
            .springPress(WidePressDepth)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = clouds, interactionSource = press, indication = ripple(), role = Role.Switch, onValueChange = onClouds)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Icon(SkylineIcons.Cloud, contentDescription = null, modifier = Modifier.iconMotion(press))
          Column(Modifier.weight(1f)) {
            Text("Clouds", style = MaterialTheme.typography.bodyLarge)
            AnimatedVisibility(!clouds) {
              Text(
                "Hold the sun or the moon to bring them back",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
          }
          // The row is the control; the switch only shows its state.
          Switch(checked = clouds, onCheckedChange = null)
        }
      }
    }
  }
}

/** The clouds' menu is wide enough for its line about bringing them back to fit on two lines. */
private val SkyMenuWidth = 264.dp
