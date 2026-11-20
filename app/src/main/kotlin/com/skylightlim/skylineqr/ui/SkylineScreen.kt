@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.LongPress
import com.skylightlim.skylineqr.compose.SkylineQrCode
import com.skylightlim.skylineqr.compose.rememberSkylineQrState
import com.skylightlim.skylineqr.core.PaymentCode
import com.skylightlim.skylineqr.nfc.card.Card
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val DEFAULT_URL = "https://www.instagram.com/chenyuulim/"

/**
 * The city. [cards] are the saved cards, whose links the picker offers as the city (2026-11-07: it
 * replaced the link field; the profile's link comes first; [onRemove] takes a card off it). [onAdd]
 * is the new-card button beside the picker, and [popup] the card popup it opens, drawn in the
 * panel's colours. [onShow] hears which link the city shows, to share it by tap all the time.
 * [sharing] is the title of a card shared for a while by tap, which the hint shows with a way to
 * stop. [snackbar] speaks at the top of the panel, covering none of it. The app's own button opens
 * the settings card, and holding it, or each new [tours] (the home screen's Tour shortcut), starts
 * the tour, during which the panel steps aside (2026-11-09), as it does for the timelapse (2026-11-20).
 */
@Composable
fun SkylineScreen(
  tours: Int = 0,
  cards: List<Card> = emptyList(),
  sharing: String? = null,
  onStopSharing: () -> Unit = {},
  onRemove: (Card) -> Unit = {},
  onShow: (title: String, url: String) -> Unit = { _, _ -> },
  onAdd: () -> Unit = {},
  snackbar: SnackbarHostState = remember { SnackbarHostState() },
  popup: @Composable () -> Unit = {},
) {
  val context = LocalContext.current

  // The profile's link to begin with, and to go back to when the card shown is removed.
  val profileUrl = cards.firstNotNullOfOrNull { (it.content as? CardContent.Link)?.url } ?: DEFAULT_URL
  var url by rememberSaveable { mutableStateOf(profileUrl) }
  var time by rememberSaveable { mutableStateOf(TimeOfDay.AUTO) }
  var weather by rememberSaveable { mutableStateOf(Weather.CLEAR) }
  val settings = remember(context) { AppSettings(context) }
  var settingsOpen by rememberSaveable { mutableStateOf(false) }
  val qr = rememberSkylineQrState()
  // Only whether the camera has moved, so a drag doesn't redraw the panel on every frame.
  val cameraMoved by remember(qr) { derivedStateOf { qr.cameraMoved } }

  // The home screen's Tour shortcut: once the first city is built, so there is something to fly round.
  LaunchedEffect(tours) {
    if (tours == 0) return@LaunchedEffect
    snapshotFlow { qr.identity }.first { it != null }
    qr.startTour()
  }

  // The link the city shows is the one the phone hands over by tap. A payment code's city
  // (2026-11-07) hands over the profile instead: payment apps don't take a code by tap.
  val paying = PaymentCode.isPaymentCode(url)
  val shownTitle = cards.firstOrNull { it.city() == url }?.title ?: url
  val profileTitle = cards.firstOrNull { it.isProfile }?.title ?: profileUrl
  LaunchedEffect(url, shownTitle, paying, profileUrl, profileTitle) {
    if (paying) onShow(profileTitle, profileUrl) else onShow(shownTitle, url)
  }

  // The panel takes its tint from the sky it sits under; on Auto the sky follows the clock.
  var clock by remember { mutableStateOf(Atmospheres.clockMinute()) }
  LaunchedEffect(time) {
    while (time == TimeOfDay.AUTO) {
      delay(60_000)
      clock = Atmospheres.clockMinute()
    }
  }
  val air = Atmospheres.at(time, weather, clock)

  val darkPanel = air.night > 0.5f || weather == Weather.STORM
  val sky = Color(air.skyHorizon[0], air.skyHorizon[1], air.skyHorizon[2])
  // Status bar icons sit on the backdrop in the city, and on the code's paper in the flat code.
  val behindBars = if (qr.flat) air.paper else Atmospheres.backdrop(air)
  val lightBehindBars = 0.2126f * behindBars[0] + 0.7152f * behindBars[1] + 0.0722f * behindBars[2] > 0.5f
  val view = LocalView.current
  SideEffect {
    val window = (view.context as? Activity)?.window ?: return@SideEffect
    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBehindBars
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    SkylineQrCode(
      url = url,
      city = CityPreset.KUALA_LUMPUR,
      time = time,
      weather = weather,
      state = qr,
      modifier = Modifier.fillMaxSize(),
      billboard = settings.signs,
      clouds = settings.clouds,
      longPress = settings.longPress,
    )

    SkylineQRTheme(dark = darkPanel, sky = sky) {
      popup()
      if (settingsOpen) {
        SettingsCard(
          settings,
          onTour = {
            settingsOpen = false
            qr.startTour()
          },
          // Day to night in fifteen seconds; reaching night, the time bar shows Night, where it ends.
          onTimelapse = {
            settingsOpen = false
            qr.startTimelapse { completed -> if (completed) time = TimeOfDay.NIGHT }
          },
          onDismiss = { settingsOpen = false },
        )
      }

      // The tour and the timelapse have the screen to themselves: the panel sinks away and comes
      // back as they end.
      val motion = MaterialTheme.motionScheme
      AnimatedVisibility(
        !qr.touring && !qr.timelapsing,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(motion.defaultSpatialSpec()) { it / 3 } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it / 3 } + fadeOut(motion.fastEffectsSpec()),
      ) {
        Column(
          modifier =
            Modifier
              // A new hour or weather's colours spread out from the control that was touched.
              .themeReveal(time to weather)
              // A phone's width; on a tablet or a phone on its side the panel stays that wide, centred.
              .widthIn(max = PanelWidth)
              .fillMaxWidth()
              .navigationBarsPadding()
              .padding(horizontal = 16.dp, vertical = 12.dp)
              // Taps between the controls never reach the city behind.
              .pointerInput(Unit) { detectTapGestures {} },
          verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          SnackbarHost(snackbar)
          val error = qr.error
          val hold = if (settings.longPress == LongPress.FIREWORKS) "hold for fireworks" else "hold for a meteor"
          // Once the camera has moved, the hint is the button that puts it back. It is not a
          // double-tap on the city, so a single tap there never waits to see whether a second follows.
          HintPill(
            when {
              error != null -> Hint.Problem(error.message.orEmpty())
              sharing != null -> Hint.Act("Stop sharing “$sharing”", SkylineIcons.Nfc, onStopSharing)
              qr.flat -> Hint.Say("Tap the code to see the city")
              cameraMoved -> Hint.Act("Put the camera back", SkylineIcons.Reset, qr::resetCamera)
              else -> Hint.Say("Tap for the QR code · $hold")
            }
          )

          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardPicker(
              cards,
              url,
              onPick = { url = it },
              onRemove = { card ->
                if (card.city() == url) url = profileUrl
                onRemove(card)
              },
              modifier = Modifier.weight(1f),
            )
            AddButton(onClick = onAdd)
          }

          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Labels alone: with icons too, "Now" and "Night" cut off once the font is larger.
            PillChoices(TIMES, time, { time = it }, label = { it.label }, modifier = Modifier.weight(1f))
            MenuButton(weather, WEATHERS, { weather = it }, icon = { it.icon }, label = { it.label }, description = "Weather")
            AppButton(onClick = { settingsOpen = true }, onLongClick = qr::startTour)
          }
        }
      }
    }
  }
}

/** Now follows the clock through dawn and dusk; the fixed choices are just day and night. */
private val TIMES = listOf(TimeOfDay.AUTO, TimeOfDay.DAY, TimeOfDay.NIGHT)

/** A storm already rains; the app offers no rain of its own. */
private val WEATHERS = listOf(Weather.CLEAR, Weather.STORM, Weather.HAZE)

private val TimeOfDay.label
  get() =
    when (this) {
      TimeOfDay.AUTO -> "Now"
      TimeOfDay.DAWN -> "Dawn"
      TimeOfDay.DAY -> "Day"
      TimeOfDay.DUSK -> "Dusk"
      TimeOfDay.NIGHT -> "Night"
    }

private val Weather.label
  get() =
    when (this) {
      Weather.CLEAR -> "Clear"
      Weather.RAIN -> "Rain"
      Weather.HAZE -> "Haze"
      Weather.STORM -> "Storm"
    }

private val Weather.icon
  get() =
    when (this) {
      Weather.CLEAR -> SkylineIcons.Day
      Weather.RAIN -> SkylineIcons.Rain
      Weather.HAZE -> SkylineIcons.Haze
      Weather.STORM -> SkylineIcons.Storm
    }
