@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui.cards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skylightlim.skylineqr.nfc.NfcState
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.CardKind
import com.skylightlim.skylineqr.nfc.card.CardRules
import com.skylightlim.skylineqr.nfc.card.WifiSecurity
import com.skylightlim.skylineqr.nfc.card.kind
import com.skylightlim.skylineqr.nfc.read.TagReading
import com.skylightlim.skylineqr.ui.PanelWidth
import com.skylightlim.skylineqr.ui.PillChoices
import com.skylightlim.skylineqr.ui.SkylineIcons
import com.skylightlim.skylineqr.ui.iconMotion
import com.skylightlim.skylineqr.ui.rise
import com.skylightlim.skylineqr.ui.springIn
import com.skylightlim.skylineqr.ui.springPress
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The card popup, a card over the city rather than a page of its own, as Tap Tap Tap makes a card
 * (its behaviour, not its code; and no colour). "New card" has two ways beside it to fill it in:
 * NFC, from a tag or another phone, and QR, from the camera. Under it, what the card holds as a
 * connected button group in Tap Tap Tap's order (link, text, Wi-Fi, contact), its fields, the Wi-Fi
 * security as another group, then Cancel and Add. A problem shows under the fields once Add has
 * been tried. The title has no placeholder: it had shown "Link" once focused, which read as the
 * wrong field.
 *
 * The keyboard stays up from one field to the next. Compose 1.12 dropped it and brought it back on
 * every change of field (issuetracker.google.com/530704636), so the app runs Compose UI and
 * foundation 1.13 (androidxComposeTextFix). The dialog sets the window's soft input mode itself
 * (adjust nothing) and the card pads itself above the keyboard. Each single-line field's action is
 * Next.
 *
 * It springs open out of its bottom corner, toward the new-card button it came from; its height
 * springs as fields come and go, text arriving rises into place, and every button dips under the
 * finger (see Motion.kt).
 *
 * Scanning a QR code happens inside the card too, the camera where the form was (see QrScan).
 *
 * Reading a tag happens inside the card: a loading indicator morphs where the form was until
 * something is tapped, then the form comes back filled in from it. Anything a card can't be made
 * of shows there instead, and the next tag tapped is read in turn. Back stops reading first.
 */
@Composable
internal fun CardEditor(viewModel: CardsViewModel, state: CardsViewModel.Popup) {
  val nfc by viewModel.nfc.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // The form starts again from each draft a tag or a QR code puts in; typing survives reading.
  val fills = state.fills
  val initial = state.draft.content
  var kind by rememberSaveable(fills) { mutableStateOf(initial.kind) }
  var title by rememberSaveable(fills) { mutableStateOf(state.draft.title) }
  var url by rememberSaveable(fills) { mutableStateOf((initial as? CardContent.Link)?.url.orEmpty()) }
  var text by rememberSaveable(fills) { mutableStateOf((initial as? CardContent.Text)?.text.orEmpty()) }
  val contact = initial as? CardContent.Contact
  var name by rememberSaveable(fills) { mutableStateOf(contact?.name.orEmpty()) }
  var phone by rememberSaveable(fills) { mutableStateOf(contact?.phone.orEmpty()) }
  var email by rememberSaveable(fills) { mutableStateOf(contact?.email.orEmpty()) }
  var organization by rememberSaveable(fills) { mutableStateOf(contact?.organization.orEmpty()) }
  var website by rememberSaveable(fills) { mutableStateOf(contact?.website.orEmpty()) }
  val wifi = initial as? CardContent.Wifi
  var ssid by rememberSaveable(fills) { mutableStateOf(wifi?.ssid.orEmpty()) }
  var password by rememberSaveable(fills) { mutableStateOf(wifi?.password.orEmpty()) }
  var security by rememberSaveable(fills) { mutableStateOf(wifi?.security ?: WifiSecurity.WPA_WPA2) }
  // A payment code only ever comes from a scan or a tag, and is kept exactly as it came.
  val code = (initial as? CardContent.Payment)?.code.orEmpty()
  var showPassword by rememberSaveable { mutableStateOf(false) }
  var tried by rememberSaveable(fills) { mutableStateOf(false) }

  val content: CardContent =
    when (kind) {
      CardKind.LINK -> CardContent.Link(url)
      CardKind.TEXT -> CardContent.Text(text)
      CardKind.CONTACT -> CardContent.Contact(name, phone, email, organization, website)
      CardKind.WIFI -> CardContent.Wifi(ssid, password, security)
      CardKind.PAYMENT -> CardContent.Payment(code)
    }
  val motion = MaterialTheme.motionScheme
  val problem = CardRules.problem(CardRules.normalized(content))
  val warning = CardRules.warning(content)
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  // Add shakes its head, on a spring kicked sideways, when something is missing.
  val shake = remember { Animatable(0f) }
  val save: () -> Unit = {
    tried = true
    if (problem == null) {
      haptics.performHapticFeedback(HapticFeedbackType.Confirm)
      viewModel.save(title, content)
    } else {
      haptics.performHapticFeedback(HapticFeedbackType.Reject)
      scope.launch {
        shake.snapTo(0f)
        shake.animateTo(0f, motion.fastSpatialSpec(), initialVelocity = SHAKE_VELOCITY)
      }
    }
  }
  val reading = state.reading
  val colours = MaterialTheme.colorScheme

  Dialog(
    // Back steps out of reading or scanning first, then closes the card.
    onDismissRequest = {
      when {
        reading != null -> viewModel.stopReading()
        state.scanning -> viewModel.stopScanning()
        else -> viewModel.close()
      }
    },
    // Typing is easily lost to a stray tap outside, so only Cancel and back close it.
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
  ) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, motion.defaultSpatialSpec()) }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
      Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colours.surfaceContainerHigh,
        modifier =
          Modifier.widthIn(max = PanelWidth).fillMaxWidth().graphicsLayer {
            // Out of the corner nearest the new-card button, never from nothing.
            val grown = OPENS_FROM + (1f - OPENS_FROM) * arrive.value
            scaleX = grown
            scaleY = grown
            alpha = arrive.value.coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 1f)
          },
      ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New card", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            // NFC stays lit while the card waits for a tag; tapping it again stops.
            if (nfc != NfcState.UNSUPPORTED) {
              val press = remember { MutableInteractionSource() }
              FilledTonalToggleButton(
                interactionSource = press,
                checked = reading != null,
                onCheckedChange = { if (it) viewModel.readTag() else viewModel.stopReading() },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.springPress().semantics { contentDescription = "Read an NFC tag" },
              ) {
                Label(SkylineIcons.ReadTag, "NFC", press)
              }
            }
            // QR stays lit while the camera is up in the card; tapping it again stops.
            val qrPress = remember { MutableInteractionSource() }
            FilledTonalToggleButton(
              interactionSource = qrPress,
              checked = state.scanning,
              onCheckedChange = { if (it) viewModel.scan() else viewModel.stopScanning() },
              contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
              modifier = Modifier.springPress().semantics { contentDescription = "Scan a QR code" },
            ) {
              Label(SkylineIcons.Scan, "QR", qrPress)
            }
          }

          // Once a tag or a code has filled the card in, where it came from, on its own line under
          // the heading so it never squeezes beside the buttons. Nothing before that (2026-11-07, at
          // the user's request: the line saying what NFC and QR are for is gone).
          state.from?.let { from ->
            AnimatedContent(
              targetState = from,
              transitionSpec = { rise(motion) },
              label = "card source",
              modifier = Modifier.springIn(),
            ) { line ->
              Text(line, style = MaterialTheme.typography.bodyMedium, color = colours.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            }
          }
          AnimatedContent(
            targetState = if (state.scanning) Body.Scan else if (reading != null) Body.Read(reading) else Body.Form,
            contentKey = { it::class },
            transitionSpec = {
              (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.94f))
                .togetherWith(fadeOut(motion.fastEffectsSpec()))
                .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            label = "card popup",
          ) { body ->
            if (body is Body.Scan) {
              QrScan(onResult = viewModel::onScanned)
            } else if (body is Body.Read) {
              TagRead(body.reading, nfc, onTurnOn = { context.startActivity(NfcState.settingsIntent()) })
            } else {
              // No clipping anywhere in the form: a clip to an animating height cut the bottom
              // field off while its spring ran (2026-11-07).
              Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A scanned payment code is no kind to switch from: it can't be typed back in.
                if (kind != CardKind.PAYMENT) {
                  PillChoices(
                    KINDS,
                    kind,
                    { kind = it },
                    label = { it.label },
                    containerColor = colours.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                  )
                }
                Field("Title", title, { title = it }, done = if (kind == CardKind.PAYMENT) save else null)
                // Another kind's fields fade in as they spring up from a little smaller, the old
                // ones fading fast, while the height between them springs; nothing is clipped.
                AnimatedContent(
                  targetState = kind,
                  transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f))
                      .togetherWith(fadeOut(motion.fastEffectsSpec()))
                      .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
                  },
                  label = "card fields",
                ) { shown ->
                  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (shown) {
                      CardKind.PAYMENT -> PaymentSummary(CardContent.Payment(code))
                      CardKind.LINK -> Field("Link", url, { url = it }, keyboard = KeyboardType.Uri, done = save)
                      CardKind.TEXT -> Field("Text", text, { text = it }, singleLine = false)
                      CardKind.CONTACT -> {
                        Field("Name", name, { name = it })
                        Field("Phone", phone, { phone = it }, keyboard = KeyboardType.Phone)
                        Field("Email", email, { email = it }, keyboard = KeyboardType.Email)
                        Field("Organisation", organization, { organization = it })
                        Field("Website", website, { website = it }, keyboard = KeyboardType.Uri, done = save)
                      }
                      CardKind.WIFI -> {
                        Field("Network name (SSID)", ssid, { ssid = it })
                        PillChoices(
                          WifiSecurity.entries,
                          security,
                          { security = it },
                          label = { it.label },
                          containerColor = colours.surfaceContainerHighest,
                          modifier = Modifier.fillMaxWidth(),
                        )
                        if (security != WifiSecurity.OPEN) {
                          OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                              val eye = remember { MutableInteractionSource() }
                              IconButton(onClick = { showPassword = !showPassword }, shapes = IconButtonDefaults.shapes(), modifier = Modifier.springPress(), interactionSource = eye) {
                                Icon(
                                  if (showPassword) SkylineIcons.Hide else SkylineIcons.Show,
                                  contentDescription = if (showPassword) "Hide password" else "Show password",
                                  modifier = Modifier.iconMotion(eye),
                                )
                              }
                            },
                            modifier = Modifier.fillMaxWidth().springIn(),
                          )
                        }
                      }
                    }
                  }
                }
                warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colours.tertiary, modifier = Modifier.springIn()) }
                if (tried && problem != null) Text(problem, style = MaterialTheme.typography.bodyMedium, color = colours.error, modifier = Modifier.springIn())
              }
            }
          }

          Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (reading != null) {
              TextButton(onClick = viewModel::stopReading, shapes = ButtonDefaults.shapes(), modifier = Modifier.springPress()) { Text("Stop reading") }
            } else if (state.scanning) {
              TextButton(onClick = viewModel::stopScanning, shapes = ButtonDefaults.shapes(), modifier = Modifier.springPress()) { Text("Stop scanning") }
            } else {
              TextButton(onClick = viewModel::close, shapes = ButtonDefaults.shapes(), modifier = Modifier.springPress()) { Text("Cancel") }
              Button(onClick = save, shapes = ButtonDefaults.shapes(), modifier = Modifier.graphicsLayer { translationX = shake.value }.springPress()) { Text("Add") }
            }
          }
        }
      }
    }
  }
}

/** What the card shows under its heading: the form, the tag reader, or the camera. */
private sealed interface Body {
  data object Form : Body

  data class Read(val reading: CardsViewModel.Reading) : Body

  data object Scan : Body
}

/** How small the card is as it starts to spring open. */
private const val OPENS_FROM = 0.85f

/**
 * A scanned payment code where a card's fields would be: who it pays, and that it is kept as it
 * was scanned, so the city's code pays the same. The code itself is never shown.
 */
@Composable
private fun PaymentSummary(payment: CardContent.Payment) {
  Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().springIn()) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      Icon(SkylineIcons.Scan, contentDescription = null)
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(CardRules.preview(payment), style = MaterialTheme.typography.titleSmall)
        Text(
          "Kept exactly as scanned, so the city's code pays the same.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** What a card can hold, in Tap Tap Tap's order. */
private val KINDS = listOf(CardKind.LINK, CardKind.TEXT, CardKind.WIFI, CardKind.CONTACT)

/** How hard Add is kicked sideways when something is missing, in pixels a second. */
private const val SHAKE_VELOCITY = 1800f

@Composable
private fun Label(icon: ImageVector, text: String, press: MutableInteractionSource) {
  Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize).iconMotion(press))
  Spacer(Modifier.size(ButtonDefaults.IconSpacing))
  Text(text)
}

/**
 * Where the form was while the card reads a tag: the morphing indicator until one is tapped; what
 * a tag held when no card can be made of it; or that NFC is off, with the way to turn it on.
 */
@Composable
private fun TagRead(reading: CardsViewModel.Reading, nfc: NfcState, onTurnOn: () -> Unit) {
  val motion = MaterialTheme.motionScheme
  // Waiting, a read, an error or NFC off: each springs in from a little smaller as the last fades.
  AnimatedContent(
    targetState = reading to nfc,
    contentKey = { (r, n) -> if (n == NfcState.OFF) "off" else if (r.error != null) "error ${r.error}" else if (r.found != null) "found ${r.found}" else "waiting" },
    transitionSpec = {
      (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.9f))
        .togetherWith(fadeOut(motion.fastEffectsSpec()))
        .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
    },
    label = "tag read",
  ) { (shown, shownNfc) ->
    Column(
      Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      val found = shown.found
      when {
        shownNfc == NfcState.OFF -> {
          Icon(SkylineIcons.Warning, contentDescription = null, modifier = Modifier.size(32.dp))
          Text("NFC is off. Turn it on to read a tag.", textAlign = TextAlign.Center)
          Button(onClick = onTurnOn, shapes = ButtonDefaults.shapes(), modifier = Modifier.springPress()) { Text("Turn on") }
        }
        shown.error != null -> {
          Icon(SkylineIcons.Warning, contentDescription = null, modifier = Modifier.size(32.dp))
          Text(shown.error, textAlign = TextAlign.Center)
        }
        found != null -> {
          Icon(SkylineIcons.ReadTag, contentDescription = null, modifier = Modifier.size(32.dp))
          Text(describe(found), textAlign = TextAlign.Center)
          Text(
            "No card can be made of this. Hold another tag to read it instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
        else -> Waiting("Hold a tag or another phone to the back of yours.")
      }
    }
  }
}

@Composable
private fun Field(
  label: String,
  value: String,
  onChange: (String) -> Unit,
  keyboard: KeyboardType = KeyboardType.Text,
  singleLine: Boolean = true,
  done: (() -> Unit)? = null,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = singleLine,
    minLines = if (singleLine) 1 else 3,
    shape = MaterialTheme.shapes.large,
    keyboardOptions =
      KeyboardOptions(keyboardType = keyboard, imeAction = if (done != null) ImeAction.Done else if (singleLine) ImeAction.Next else ImeAction.Default),
    keyboardActions = KeyboardActions(onDone = { done?.invoke() }),
    modifier = Modifier.fillMaxWidth(),
  )
}

/** What a tag holds, in a few lines: its chip, what is on it, and how full it is. */
internal fun describe(reading: TagReading): String =
  when (reading) {
    is TagReading.Content -> {
      val what = reading.draft?.let { "${it.content.kind.label}: ${CardRules.preview(it.content)}" } ?: "Something a card can't hold"
      "${reading.chip}\n$what\n${reading.bytes} of ${reading.capacity} bytes" + if (reading.writable) "" else " · locked"
    }
    is TagReading.Empty ->
      "${reading.chip}\nEmpty" + (reading.capacity?.let { " · holds $it bytes" } ?: "") + if (reading.writable) "" else " · locked"
    is TagReading.NotShareable -> "${reading.chip}\nNo shareable content"
  }

/** Waiting for a tag: the phone-and-card illustration over what to do. */
@Composable
internal fun Waiting(text: String) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    NfcTap(Modifier.size(width = 220.dp, height = 140.dp))
    Text(text, textAlign = TextAlign.Center)
  }
}

/**
 * Waiting for a tag (2026-11-07, redrawn at the user's request): the back of a Pixel in the middle
 * of the drawing, its camera bar across the top and its keys down the side, and an NFC card, its
 * coil of wire and the chip at its end showing, springing up from the lower right to lie square
 * on the phone's back on the motion scheme's slow spatial spring. Once it is there, waves ripple
 * out from where they meet past both of its ends, and the card draws away to come again.
 * Everything stays inside the drawing, clipped to it, so no wave ever reaches the words around
 * it. Drawn, so it follows the theme, light or dark.
 */
@Composable
private fun NfcTap(modifier: Modifier = Modifier) {
  val colours = MaterialTheme.colorScheme
  val motion = MaterialTheme.motionScheme
  val near = remember { Animatable(0f) }
  val waves = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    while (true) {
      near.animateTo(1f, motion.slowSpatialSpec())
      waves.snapTo(0f)
      waves.animateTo(1f, tween(WAVE_MILLIS, easing = LinearEasing))
      near.animateTo(0f, motion.defaultSpatialSpec())
      delay(TAP_REST_MILLIS)
    }
  }
  Canvas(modifier.clipToBounds()) {
    val h = size.height
    val line = 2.dp.toPx()

    // The phone from the back, upright in the middle.
    val pw = h * 0.5f
    val ph = h * 0.92f
    val px = (size.width - pw) / 2f
    val py = (h - ph) / 2f
    // Its keys, which are on its left seen from the back: the power key, then the volume rocker.
    for ((top, length) in listOf(0.22f to 0.09f, 0.35f to 0.16f)) {
      drawRoundRect(colours.outline, Offset(px - line * 1.3f, py + ph * top), Size(line * 2f, ph * length), CornerRadius(line))
    }
    val body = CornerRadius(pw * 0.2f)
    drawRoundRect(colours.surfaceContainerHighest, Offset(px, py), Size(pw, ph), body)
    drawRoundRect(colours.outline, Offset(px, py), Size(pw, ph), body, style = Stroke(line))
    // The camera bar: a pill across the top of the back, two lenses at one end and the flash at
    // the other.
    val barW = pw * 0.8f
    val barH = ph * 0.13f
    val barX = px + (pw - barW) / 2f
    val barY = py + ph * 0.08f
    drawRoundRect(colours.inverseSurface, Offset(barX, barY), Size(barW, barH), CornerRadius(barH / 2f))
    val lens = barH * 0.32f
    for (k in 0..1) {
      val at = Offset(barX + barH * 0.55f + k * barH * 0.95f, barY + barH / 2f)
      drawCircle(colours.outlineVariant, lens, at)
      drawCircle(colours.inverseSurface, lens * 0.55f, at)
    }
    drawCircle(colours.outlineVariant, lens * 0.38f, Offset(barX + barW - barH * 0.5f, barY + barH / 2f))

    // The card: away to the lower right, tilted, or lying square on the phone's back below the
    // camera bar, over the middle of it, where its NFC antenna is.
    val n = near.value
    val cw = pw * 1.05f
    val ch = cw / CARD_RATIO
    val cx = (size.width - cw) / 2f + size.width * 0.2f * (1f - n)
    val cy = py + ph * 0.54f - ch / 2f + h * 0.10f * (1f - n)
    val tilt = 14f * (1f - n)
    rotate(tilt, pivot = Offset(cx + cw / 2f, cy + ch / 2f)) {
      val corner = CornerRadius(ch * 0.12f)
      drawRoundRect(colours.tertiaryContainer, Offset(cx, cy), Size(cw, ch), corner)
      drawRoundRect(colours.tertiary, Offset(cx, cy), Size(cw, ch), corner, style = Stroke(line * 0.8f))
      // Its coil: three turns of wire round the card, inside its edge.
      for (k in 0..2) {
        val inset = ch * (0.13f + 0.075f * k)
        drawRoundRect(
          colours.tertiary,
          Offset(cx + inset, cy + inset),
          Size(cw - 2 * inset, ch - 2 * inset),
          CornerRadius((ch * 0.1f - inset * 0.3f).coerceAtLeast(line)),
          style = Stroke(line * 0.75f),
        )
      }
      // And the chip, across the turns near one end of the long lower side, where the coil's two
      // ends meet. (On the short side it read as a battery's terminal.)
      val chipW = ch * 0.24f
      val chipH = ch * 0.27f
      drawRoundRect(colours.tertiary, Offset(cx + cw * 0.2f, cy + ch - ch * 0.1f - chipH), Size(chipW, chipH), CornerRadius(line))
    }

    // The waves, once the card is there: three arcs rippling out one after another past each of
    // the card's ends, never over the card or the phone's keys, never past the drawing.
    val touch = ((n - 0.85f) / 0.15f).coerceIn(0f, 1f)
    if (touch > 0f) {
      val step = h * 0.085f
      for ((side, facing) in listOf(cx + cw + h * 0.04f to 0f, cx - h * 0.04f to 180f)) {
        val at = Offset(side, cy + ch / 2f)
        for (k in 0..2) {
          val pass = (waves.value * 1.6f - k * 0.3f).coerceIn(0f, 1f)
          val alpha = touch * sin(PI.toFloat() * pass)
          if (alpha <= 0.01f) continue
          val r = h * 0.1f + step * k
          drawArc(
            colours.primary.copy(alpha = alpha),
            startAngle = facing - WAVE_SWEEP / 2f,
            sweepAngle = WAVE_SWEEP,
            useCenter = false,
            topLeft = Offset(at.x - r, at.y - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(line * 1.3f, cap = StrokeCap.Round),
          )
        }
      }
    }
  }
}

/** A bank card's width over its height. */
private const val CARD_RATIO = 1.586f

/** How wide each wave's arc is, in degrees, and how long the ripple of all three takes. */
private const val WAVE_SWEEP = 80f
private const val WAVE_MILLIS = 1100

/** How long the card is away from the phone before it comes again. */
private const val TAP_REST_MILLIS = 600L
