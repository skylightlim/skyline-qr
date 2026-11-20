@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.skylightlim.skylineqr.nfc.card.Card
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.CardRules
import kotlin.math.abs

/*
 * The panel's controls, Material 3 Expressive (2026-11-06; before, frosted tiles after the ICQR
 * tree's): they float over the city with no sheet behind them, on the theme's surface containers,
 * which the sky tints. Nothing at the top since 2026-11-07. The hint, the card picker with the
 * new-card button beside it, then the hour, the weather menu and the app's own button, which opens
 * the settings card (2026-11-09: it replaced share, and + moved up beside the picker). Buttons
 * change shape as they are pressed and chosen, on the expressive motion scheme's springs.
 */

/** The card picker's height; the new-card button beside it matches. */
internal val FieldHeight = 56.dp

/** The row of choices: a touch target tall. */
internal val ChoiceHeight = 48.dp

/** The widest the panel gets: about a large phone's width, so a wide window doesn't stretch it. */
internal val PanelWidth = 560.dp

/**
 * Which saved card the city shows, where the link field was (2026-11-07): it reads like the field,
 * a rounded rectangle on the surface container with the card's title over its link, and opens a
 * menu of the cards that can be cities, the profile first, exactly as wide as the picker. The chosen
 * card shows by its colour and shape; every card but the profile has a trash button that removes
 * it (the snackbar offers Undo). M3's menu items stop at 280 dp, so the rows are drawn here.
 * Links and payment codes can be cities (a payment code's shows who it pays, never the code
 * itself); Wi-Fi, text and contact cards can't.
 */
@Composable
internal fun CardPicker(
  cards: List<Card>,
  url: String,
  onPick: (String) -> Unit,
  onRemove: (Card) -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by remember { mutableStateOf(false) }
  var width by remember { mutableStateOf(0.dp) }
  val density = LocalDensity.current
  val links = cards.mapNotNull { card -> card.city()?.let { card to it } }
  val picked = links.firstOrNull { it.second == url }?.first
  val title = picked?.title
  val under = picked?.let(::underTitle) ?: url
  val colours = MaterialTheme.colorScheme
  val motion = MaterialTheme.motionScheme
  Box(modifier.onSizeChanged { width = with(density) { it.width.toDp() } }) {
    Surface(
      onClick = { open = true },
      shape = RoundedCornerShape(Outer),
      color = colours.surfaceContainer,
      border = BorderStroke(1.dp, colours.outlineVariant),
      modifier =
        Modifier.springPress(WidePressDepth).fillMaxWidth().height(FieldHeight).semantics {
          role = Role.DropdownList
          contentDescription = "Card the city shows"
        },
    ) {
      Row(Modifier.padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Another card rises into the bar as the last one rises out of it.
        AnimatedContent(
          targetState = title to under,
          modifier = Modifier.weight(1f),
          transitionSpec = { rise(motion).using(SizeTransform(clip = true)) },
          contentAlignment = Alignment.CenterStart,
          label = "picked card",
        ) { (shownTitle, shownUrl) ->
          // The card's name alone (2026-11-12, at the user's request); the whole link shows in the
          // list. A card with no name shows its site.
          Text(
            shownTitle ?: shownUrl.substringAfter("://").substringBefore('/'),
            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
          )
        }
        Icon(SkylineIcons.More, contentDescription = null, tint = colours.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(18.dp).flipWhen(open))
      }
    }
    DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(width)) {
      DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(MenuInset)) {
        val menu = MenuDefaults.selectableItemColors()
        links.forEachIndexed { index, (card, link) ->
          val chosen = link == url
          val shapes = MenuDefaults.itemShape(index, links.size)
          Surface(
            onClick = {
              onPick(link)
              open = false
            },
            shape = if (chosen) shapes.selectedShape else shapes.shape,
            color = if (chosen) menu.selectedContainerColor else Color.Transparent,
            contentColor = if (chosen) menu.selectedTextColor else colours.onSurface,
            modifier = Modifier.springPress(WidePressDepth).fillMaxWidth().semantics { selected = chosen },
          ) {
            Row(Modifier.heightIn(min = FieldHeight).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                  underTitle(card),
                  style = MaterialTheme.typography.bodySmall,
                  color = if (chosen) LocalContentColor.current else colours.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              if (!card.isProfile) {
                val bin = remember { MutableInteractionSource() }
                IconButton(
                  interactionSource = bin,
                  onClick = {
                    open = false
                    onRemove(card)
                  },
                  shapes = IconButtonDefaults.shapes(),
                  modifier = Modifier.springPress(),
                ) {
                  Icon(SkylineIcons.Delete, contentDescription = "Remove “${card.title}”", modifier = Modifier.size(20.dp).iconMotion(bin, IconMotion.Tilt))
                }
              } else {
                // The profile can't be removed; the row keeps the same height and edge as the rest.
                Spacer(Modifier.size(48.dp))
              }
            }
          }
        }
      }
    }
  }
}

/** What a city of [this] card is built from: a link's URL or a payment code, exactly; null for any other card. */
internal fun Card.city(): String? =
  when (val content = content) {
    is CardContent.Link -> content.url
    is CardContent.Payment -> content.code
    else -> null
  }

/** The line under a card's title in the picker: a link's URL, or who a payment code pays. */
private fun underTitle(card: Card): String = if (card.content is CardContent.Payment) CardRules.preview(card.content) else card.city().orEmpty()

/** What the hint above the link shows. */
internal sealed interface Hint {
  /** What the next tap does. */
  data class Say(val text: String) : Hint

  /** What is wrong with the link; read out as it appears. */
  data class Problem(val text: String) : Hint

  /** The one action the hint offers, as a button. */
  class Act(val text: String, val icon: ImageVector, val onClick: () -> Unit) : Hint
}

/**
 * The hint: a quiet label for what a tap does next, the link's problem in the error colours, or a
 * tonal button for the one action it offers, all rounded rectangles like the controls under them.
 * A new hint morphs out of the old (2026-11-07): the old words blur, fade and shrink away while the
 * new ones sharpen and grow in over them, and the pill's size springs between the two.
 */
@Composable
internal fun HintPill(hint: Hint, modifier: Modifier = Modifier) {
  val motion = MaterialTheme.motionScheme
  AnimatedContent(
    targetState = hint,
    modifier = modifier,
    transitionSpec = {
      (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.88f))
        .togetherWith(fadeOut(motion.defaultEffectsSpec()) + scaleOut(motion.defaultSpatialSpec(), targetScale = 0.88f))
        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
    },
    contentAlignment = Alignment.Center,
    contentKey = {
      when (it) {
        is Hint.Say -> "say ${it.text}"
        is Hint.Problem -> "problem ${it.text}"
        is Hint.Act -> "act ${it.text}"
      }
    },
    label = "hint",
  ) { shown ->
    val colours = MaterialTheme.colorScheme
    val blur by transition.animateFloat(transitionSpec = { motion.defaultEffectsSpec() }, label = "hint blur") { if (it == EnterExitState.Visible) 0f else 1f }
    val morph = Modifier.graphicsLayer {
      val r = blur * HINT_BLUR.toPx()
      renderEffect = if (r > 0.5f) BlurEffect(r, r) else null
    }
    when (shown) {
      is Hint.Act -> {
        val press = remember { MutableInteractionSource() }
        FilledTonalButton(
          onClick = shown.onClick,
          shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.medium, pressedShape = MaterialTheme.shapes.small),
          contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
          modifier = morph.springPress(),
          interactionSource = press,
        ) {
          Icon(
            shown.icon,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize).iconMotion(press, if (shown.icon == SkylineIcons.Reset) IconMotion.Rewind else IconMotion.Pop),
          )
          Spacer(Modifier.size(ButtonDefaults.IconSpacing))
          Text(shown.text)
        }
      }
      is Hint.Problem ->
        Surface(
          shape = MaterialTheme.shapes.medium,
          color = colours.errorContainer,
          contentColor = colours.onErrorContainer,
          modifier = morph.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
          Label(shown.text)
        }
      is Hint.Say ->
        Surface(shape = MaterialTheme.shapes.medium, color = colours.surfaceContainer, contentColor = colours.onSurfaceVariant, modifier = morph) { Label(shown.text) }
    }
  }
}

@Composable
private fun Label(text: String) {
  Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** How far the hint's words blur as one morphs into the next. */
private val HINT_BLUR = 10.dp

/**
 * A new card, beside the card picker and as tall (2026-11-09: it was in the row of choices, under
 * share): a tonal button cornered like the picker. It opens the card popup over the city.
 */
@Composable
internal fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colours = MaterialTheme.colorScheme
  val press = remember { MutableInteractionSource() }
  FilledTonalIconButton(
    onClick = onClick,
    shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(Outer), pressedShape = IconButtonDefaults.mediumPressedShape),
    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colours.surfaceContainer, contentColor = colours.onSurface),
    modifier = modifier.springPress().size(FieldHeight),
    interactionSource = press,
  ) {
    Icon(SkylineIcons.Add, contentDescription = "New card", modifier = Modifier.size(22.dp).iconMotion(press, IconMotion.Turn))
  }
}

/**
 * One choice of a few as a connected button group: toggle buttons side by side in one family of
 * rounded rectangles, large corners at the group's two ends and small ones where the buttons meet
 * (2026-11-06: no fully round pill for the chosen one beside square ones). The chosen colour is one
 * highlight that slides to the button picked on the motion scheme's default spatial spring
 * (2026-11-07), overshooting a little; each button shows the part of it under its own shape, so it
 * takes each button's corners as it passes, and the labels turn as it arrives. Each rounds its
 * inner corners a little while pressed. Read out as radio buttons.
 */
@Composable
internal fun <T> ConnectedChoices(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  label: (T) -> String,
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  val colours = MaterialTheme.colorScheme
  val motion = MaterialTheme.motionScheme
  // Where the highlight is, counted in buttons from the start.
  val at by animateFloatAsState(options.indexOf(selected).coerceAtLeast(0).toFloat(), motion.defaultSpatialSpec(), label = "chosen")
  val gap = ButtonGroupDefaults.ConnectedSpaceBetween
  Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(gap)) {
    options.forEachIndexed { index, option ->
      val first = index == 0
      val last = index == options.lastIndex
      val press = remember { MutableInteractionSource() }
      val pressed by press.collectIsPressedAsState()
      val inner by animateDpAsState(if (pressed) InnerPressed else Inner, motion.fastSpatialSpec(), label = "inner corners")
      // How much of the highlight is on this button: its label turns with it.
      val share = (1f - abs(at - index)).coerceIn(0f, 1f)
      val content = lerp(colours.onSurface, colours.onSecondary, share)
      ToggleButton(
        checked = option == selected,
        onCheckedChange = { onSelect(option) },
        interactionSource = press,
        modifier =
          Modifier.weight(1f)
            .springPress()
            .height(ChoiceHeight)
            .semantics { role = Role.RadioButton }
            .drawBehind {
              val outline = corners(first, last, inner).createOutline(size, layoutDirection, this)
              drawOutline(outline, containerColor)
              // The highlight, wherever it is now, seen through this button's shape.
              val step = size.width + gap.toPx()
              val dx = (at - index) * step * if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
              if (abs(dx) < size.width) {
                clipPath(Path().apply { addOutline(outline) }) { drawRect(colours.secondary, topLeft = Offset(dx, 0f), size = size) }
              }
            },
        shapes = connectedShapes(first, last),
        colors =
          ToggleButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = content,
            checkedContainerColor = Color.Transparent,
            checkedContentColor = content,
          ),
        contentPadding = PaddingValues(horizontal = 8.dp),
      ) {
        Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/**
 * One choice of a few as a single bar (2026-11-07, at the user's request: the time of day, then the
 * card popup's kind and Wi-Fi security too): a
 * rounded rectangle cornered like the buttons beside it, its options' labels side by side, and a
 * pill in the secondary colour under the chosen one. The pill moves whole, both edges together, on
 * the scheme's fast spatial spring, so it overshoots the option picked and bounces back a little;
 * at the bar's ends it can reach the edge but not leave it. The labels turn as it arrives. Read out
 * as radio buttons.
 */
@Composable
internal fun <T> PillChoices(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  label: (T) -> String,
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  val colours = MaterialTheme.colorScheme
  val motion = MaterialTheme.motionScheme
  // Where the pill is, counted in options from the start.
  val at by animateFloatAsState(options.indexOf(selected).coerceAtLeast(0).toFloat(), motion.fastSpatialSpec(), label = "chosen")
  Row(
    modifier
      .height(ChoiceHeight)
      .selectableGroup()
      .drawBehind {
        drawRoundRect(containerColor, cornerRadius = CornerRadius(Outer.toPx()))
        val inset = PillInset.toPx()
        val each = (size.width - 2 * inset) / options.size
        val from = (inset + at * each).coerceIn(0f, size.width - each)
        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - from - each else from
        val height = size.height - 2 * inset
        drawRoundRect(colours.secondary, topLeft = Offset(left, inset), size = Size(each, height), cornerRadius = CornerRadius(height / 2))
      }
      .padding(PillInset),
  ) {
    options.forEachIndexed { index, option ->
      // How much of the pill is under this label: it turns with it.
      val share = (1f - abs(at - index)).coerceIn(0f, 1f)
      Box(
        Modifier.weight(1f)
          .fillMaxHeight()
          .springPress()
          .clip(CircleShape)
          .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          label(option),
          style = MaterialTheme.typography.labelLarge,
          color = lerp(colours.onSurface, colours.onSecondary, share),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 8.dp),
        )
      }
    }
  }
}

/** How far the pill sits inside its bar. */
private val PillInset = 4.dp

/** A button's corners in the group: [Outer] at the group's ends, [inner] where buttons meet. */
private fun corners(first: Boolean, last: Boolean, inner: Dp) =
  RoundedCornerShape(
    topStart = if (first) Outer else inner,
    bottomStart = if (first) Outer else inner,
    topEnd = if (last) Outer else inner,
    bottomEnd = if (last) Outer else inner,
  )

/**
 * The group's corners for the buttons' own ripple: [Inner] where they meet, rounder while pressed,
 * as the shape drawn behind each does.
 */
private fun connectedShapes(first: Boolean, last: Boolean): ToggleButtonShapes {
  val rest = corners(first, last, Inner)
  return ToggleButtonShapes(shape = rest, pressedShape = corners(first, last, InnerPressed), checkedShape = rest)
}

/** The weather menu's width, at least: fixed whichever weather is chosen, so it always opens to the same side. */
private val WeatherMenuWidth = 168.dp

/** The space between a menu's edge and its rows, the same on every side. */
internal val MenuInset = 4.dp

/** The panel's corner sizes: the card picker's, and the small ones inside a group. */
private val Outer = 16.dp
private val Inner = 4.dp
private val InnerPressed = 10.dp

/**
 * A button showing the [current] choice that opens a menu of all of them: a tonal icon button with
 * a chevron, cornered like the button group beside it; the menu one group, the chosen item marked by its colour,
 * its shape and a check.
 */
@Composable
internal fun <T> MenuButton(
  current: T,
  options: List<T>,
  onSelect: (T) -> Unit,
  icon: (T) -> ImageVector,
  label: (T) -> String,
  description: String,
  modifier: Modifier = Modifier,
) {
  var open by remember { mutableStateOf(false) }
  val colours = MaterialTheme.colorScheme
  val press = remember { MutableInteractionSource() }
  Box(modifier) {
    FilledTonalIconButton(
      onClick = { open = true },
      interactionSource = press,
      shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(Outer), pressedShape = IconButtonDefaults.mediumPressedShape),
      colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colours.surfaceContainer, contentColor = colours.onSurface),
      modifier =
        Modifier.springPress().size(width = 64.dp, height = ChoiceHeight).semantics {
          role = Role.DropdownList
          stateDescription = label(current)
        },
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon(current), contentDescription = description, modifier = Modifier.size(22.dp).iconMotion(press))
        Icon(SkylineIcons.More, contentDescription = null, tint = colours.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp).size(14.dp).flipWhen(open))
      }
    }
    // M3 opens a menu from the anchor's start if it fits there and from its end if not; a menu whose
    // width followed the choice crossed that line and opened on the other side. Now it can't.
    DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = WeatherMenuWidth)) {
      // The same frame on every side of the chosen row: M3 pads a group 4 dp at the sides but 2 dp
      // at the top and bottom, which read as a border of two thicknesses.
      DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1), contentPadding = PaddingValues(MenuInset)) {
        options.forEachIndexed { index, option ->
          val chosen = option == current
          val row = remember { MutableInteractionSource() }
          SelectableDropdownMenuItem(
            interactionSource = row,
            selected = chosen,
            onClick = {
              onSelect(option)
              open = false
            },
            text = { Text(label(option)) },
            shapes = MenuDefaults.itemShape(index, options.size),
            modifier = Modifier.springPress(WidePressDepth),
            leadingIcon = { Icon(icon(option), contentDescription = null, modifier = Modifier.iconMotion(row)) },
            selectedLeadingIcon = { Icon(icon(option), contentDescription = null, modifier = Modifier.iconMotion(row)) },
            // Every row keeps room for the check, so the menu is as wide whichever is chosen.
            trailingContent = { Box(Modifier.size(20.dp)) { if (chosen) Icon(SkylineIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp)) } },
          )
        }
      }
    }
  }
}
