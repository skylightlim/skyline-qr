@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.skylightlim.skylineqr.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.skylightlim.skylineqr.R
import com.skylightlim.skylineqr.city.CityColours
import com.skylightlim.skylineqr.compose.LongPress

/**
 * What the app keeps on the phone (2026-11-09): whether the clouds show, what a long press on the
 * city does, the sign board's messages and, since 2026-11-22, the city's colours. Settings, not
 * views, so they outlive the app.
 */
internal class AppSettings(context: Context) {
  private val prefs = context.getSharedPreferences("skylineqr", Context.MODE_PRIVATE)

  var clouds by mutableStateOf(prefs.getBoolean(CLOUDS, true))
    private set

  var colours by mutableStateOf(CityColours.entries.firstOrNull { it.name == prefs.getString(COLOURS, null) } ?: CityColours.COLOURFUL)
    private set

  var longPress by mutableStateOf(LongPress.entries.firstOrNull { it.name == prefs.getString(LONG_PRESS, null) } ?: LongPress.METEOR)
    private set

  var signs by mutableStateOf(prefs.getString(SIGNS, null)?.split('\n') ?: DEFAULT_SIGNS)
    private set

  fun clouds(on: Boolean) {
    clouds = on
    prefs.edit().putBoolean(CLOUDS, on).apply()
  }

  fun colours(choice: CityColours) {
    colours = choice
    prefs.edit().putString(COLOURS, choice.name).apply()
  }

  fun longPress(choice: LongPress) {
    longPress = choice
    prefs.edit().putString(LONG_PRESS, choice.name).apply()
  }

  fun signs(messages: List<String>) {
    signs = messages
    prefs.edit().putString(SIGNS, messages.joinToString("\n")).apply()
  }

  companion object {
    private const val CLOUDS = "clouds"
    private const val COLOURS = "colours"
    private const val LONG_PRESS = "longPress"
    private const val SIGNS = "signs"

    /** What the screen on Bukit Bintang's mall shows until it is changed. */
    val DEFAULT_SIGNS = listOf("马大牛逼", "tarumt #1", "i need argon")

    /** The sign board takes up to five messages, each short enough to fit its texture. */
    const val MAX_SIGNS = 5
    const val SIGN_LENGTH = 24
  }
}

/**
 * The app's own button (2026-11-09, at the user's request, where share was): the app's icon in a
 * tonal button like the weather's. A tap opens the settings card; a long press starts the tour.
 */
@Composable
internal fun AppButton(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
  val colours = MaterialTheme.colorScheme
  val press = remember { MutableInteractionSource() }
  val haptics = LocalHapticFeedback.current
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = colours.surfaceContainer,
    contentColor = colours.onSurface,
    modifier =
      modifier
        .springPress()
        .size(width = FieldHeight, height = ChoiceHeight)
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(
          interactionSource = press,
          indication = ripple(),
          onClick = onClick,
          onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
          },
        )
        .semantics {
          role = Role.Button
          contentDescription = "Settings"
          onLongClick(label = "Take the tour") {
            onLongClick()
            true
          }
        },
  ) {
    Box(contentAlignment = Alignment.Center) {
      // The launcher icon's monochrome layer: the blocks fill about half of its square.
      Icon(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null, modifier = Modifier.size(44.dp).iconMotion(press))
    }
  }
}

/** The settings card's pages. */
private enum class Page { MAIN, SIGNS, LICENCES }

/**
 * The settings card (2026-11-09, at the user's request), over the city like the new-card popup and
 * opening out of the same corner: take the tour or the timelapse, the city's colours, what a long press does, the clouds, the sign
 * board's messages (a page of their own) and the licences (another). No title: the tour comes
 * first. A tap anywhere outside the card closes it; back steps out of a page first.
 */
@Composable
internal fun SettingsCard(settings: AppSettings, onTour: () -> Unit, onTimelapse: () -> Unit, onDismiss: () -> Unit) {
  var page by rememberSaveable { mutableStateOf(Page.MAIN) }
  val motion = MaterialTheme.motionScheme
  val colours = MaterialTheme.colorScheme
  Dialog(
    onDismissRequest = { if (page != Page.MAIN) page = Page.MAIN else onDismiss() },
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, motion.defaultSpatialSpec()) }
    // The dialog's window is the whole screen, so the platform's own tap-outside never fires: a tap
    // anywhere off the card closes it here (2026-11-09, at the user's request). The card takes its
    // own taps, so one on its empty space does nothing.
    Box(
      Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }.safeDrawingPadding().padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colours.surfaceContainerHigh,
        modifier =
          Modifier.widthIn(max = PanelWidth).fillMaxWidth().pointerInput(Unit) { detectTapGestures {} }.graphicsLayer {
            // Out of the corner nearest the app's button, never from nothing.
            val grown = OPENS_FROM + (1f - OPENS_FROM) * arrive.value
            scaleX = grown
            scaleY = grown
            alpha = arrive.value.coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 1f)
          },
      ) {
        AnimatedContent(
          targetState = page,
          transitionSpec = {
            // A page slides in from the end and back out to it.
            val forward = targetState != Page.MAIN
            (slideInHorizontally(motion.defaultSpatialSpec()) { if (forward) it / 4 else -it / 4 } + fadeIn(motion.defaultEffectsSpec()))
              .togetherWith(slideOutHorizontally(motion.fastSpatialSpec()) { if (forward) -it / 4 else it / 4 } + fadeOut(motion.fastEffectsSpec()))
              .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
          },
          label = "settings page",
        ) { shown ->
          when (shown) {
            Page.MAIN -> MainPage(settings, onTour, onTimelapse, open = { page = it })
            Page.SIGNS -> SignsPage(settings, back = { page = Page.MAIN })
            Page.LICENCES -> LicencesPage(back = { page = Page.MAIN })
          }
        }
      }
    }
  }
}

@Composable
private fun MainPage(settings: AppSettings, onTour: () -> Unit, onTimelapse: () -> Unit, open: (Page) -> Unit) {
  Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // The tour, and beside it the timelapse (2026-11-20, at the user's request): the city from day
    // to night in fifteen seconds. The tour stays the filled one and takes the room it needs.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      val tour = remember { MutableInteractionSource() }
      Button(
        onClick = onTour,
        interactionSource = tour,
        shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large, pressedShape = MaterialTheme.shapes.medium),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.weight(1f).heightIn(min = FieldHeight).springPress(WidePressDepth),
      ) {
        Icon(SkylineIcons.Tour, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).iconMotion(tour, IconMotion.Lift))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Take the tour", style = MaterialTheme.typography.titleMedium)
      }
      val lapse = remember { MutableInteractionSource() }
      FilledTonalButton(
        onClick = onTimelapse,
        interactionSource = lapse,
        shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large, pressedShape = MaterialTheme.shapes.medium),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.heightIn(min = FieldHeight).springPress(),
      ) {
        Icon(SkylineIcons.Timelapse, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).iconMotion(lapse))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Timelapse", style = MaterialTheme.typography.titleMedium)
      }
    }
    // The city's colours (2026-11-22, at the user's request): the colourful inks or quiet grey blue.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("City colours", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
      PillChoices(
        CityColours.entries,
        settings.colours,
        settings::colours,
        label = { if (it == CityColours.COLOURFUL) "Colourful" else "Grey blue" },
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Long press on the city", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
      PillChoices(
        LongPress.entries,
        settings.longPress,
        settings::longPress,
        label = { if (it == LongPress.METEOR) "Meteor" else "Fireworks" },
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
      )
    }
    SettingRow(SkylineIcons.Cloud, "Clouds", toggle = settings.clouds, onToggle = settings::clouds)
    val shown = settings.signs.count { it.isNotBlank() }
    SettingRow(SkylineIcons.Board, "Sign board", note = if (shown == 1) "1 message" else "$shown messages", onClick = { open(Page.SIGNS) })
    SettingRow(SkylineIcons.Licences, "Licences", onClick = { open(Page.LICENCES) })
  }
}

/** One row of the card: an icon, a label and its note, and a switch ([toggle]) or a chevron to a page ([onClick]). */
@Composable
private fun SettingRow(
  icon: ImageVector,
  label: String,
  note: String? = null,
  toggle: Boolean? = null,
  onToggle: (Boolean) -> Unit = {},
  onClick: () -> Unit = {},
) {
  val press = remember { MutableInteractionSource() }
  val colours = MaterialTheme.colorScheme
  val action =
    if (toggle != null) Modifier.toggleable(value = toggle, interactionSource = press, indication = ripple(), role = Role.Switch, onValueChange = onToggle)
    else Modifier.combinedClickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
  Surface(shape = MaterialTheme.shapes.large, color = colours.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().springPress(WidePressDepth)) {
    Row(
      Modifier.clip(MaterialTheme.shapes.large).then(action).heightIn(min = FieldHeight).padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).iconMotion(press))
      Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colours.onSurfaceVariant)
      }
      // The row is the control; the switch only shows its state.
      if (toggle != null) Switch(checked = toggle, onCheckedChange = null)
      else Icon(SkylineIcons.Forward, contentDescription = null, tint = colours.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
  }
}

/** A page's heading, with the way back. */
@Composable
private fun PageHeading(title: String, back: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    val press = remember { MutableInteractionSource() }
    IconButton(onClick = back, interactionSource = press, shapes = IconButtonDefaults.shapes(), modifier = Modifier.springPress()) {
      Icon(SkylineIcons.Back, contentDescription = "Back", modifier = Modifier.iconMotion(press, IconMotion.Rewind))
    }
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
  }
}

/**
 * The sign board's messages (2026-11-09, at the user's request): up to [AppSettings.MAX_SIGNS],
 * shown one after another on the mall's screen. Each change shows at once; the last one can't be
 * removed. They stay on the phone.
 */
@Composable
private fun SignsPage(settings: AppSettings, back: () -> Unit) {
  val messages = remember { mutableStateListOf<String>().apply { addAll(settings.signs) } }
  fun save() = settings.signs(messages.toList())
  Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PageHeading("Sign board", back)
    messages.forEachIndexed { i, message ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        OutlinedTextField(
          value = message,
          onValueChange = {
            messages[i] = it.replace('\n', ' ').take(AppSettings.SIGN_LENGTH)
            save()
          },
          singleLine = true,
          label = { Text("Message ${i + 1}") },
          keyboardOptions = KeyboardOptions(imeAction = if (i < messages.size - 1) ImeAction.Next else ImeAction.Done),
          modifier = Modifier.weight(1f).springIn(),
        )
        val bin = remember { MutableInteractionSource() }
        IconButton(
          onClick = {
            messages.removeAt(i)
            save()
          },
          enabled = messages.size > 1,
          interactionSource = bin,
          shapes = IconButtonDefaults.shapes(),
          modifier = Modifier.padding(start = 4.dp).springPress(),
        ) {
          Icon(SkylineIcons.Delete, contentDescription = "Remove message ${i + 1}", modifier = Modifier.size(20.dp).iconMotion(bin, IconMotion.Tilt))
        }
      }
    }
    if (messages.size < AppSettings.MAX_SIGNS) {
      val add = remember { MutableInteractionSource() }
      FilledTonalButton(
        onClick = {
          messages += ""
          save()
        },
        interactionSource = add,
        shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large, pressedShape = MaterialTheme.shapes.medium),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.padding(start = 8.dp).springPress(),
      ) {
        Icon(SkylineIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).iconMotion(add, IconMotion.Turn))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Add message")
      }
    }
  }
}

/** The licences the app ships (assets/licenses.txt), as a page of the card. */
@Composable
private fun LicencesPage(back: () -> Unit) {
  val context = LocalContext.current
  val text = remember { runCatching { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }.getOrDefault("") }
  Column(Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    PageHeading("Licences", back)
    Box(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(start = 8.dp)) {
      Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** How small the card starts as it opens. */
private const val OPENS_FROM = 0.85f
