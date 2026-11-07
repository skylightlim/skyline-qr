@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.skylightlim.skylineqr.compose.SkylineQrCode
import com.skylightlim.skylineqr.compose.rememberSkylineQrState
import com.skylightlim.skylineqr.core.GeneratorVersion
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.PaymentCode
import com.skylightlim.skylineqr.core.ShareLink
import com.skylightlim.skylineqr.core.SharedCity
import com.skylightlim.skylineqr.nfc.card.Card
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import kotlinx.coroutines.delay

private const val DEFAULT_URL = "https://www.instagram.com/chenyuulim/"

/**
 * The city. [shared] is a city someone sent as a share link, opened in the app. [cards] are the
 * saved cards, whose links the picker offers as the city (2026-11-07: it replaced the link field;
 * the profile's link comes first; [onRemove] takes a card off it). [onAdd] is the new-card button under share, and [popup] the card
 * popup it opens, drawn in the panel's colours. [onShow] hears which link the city shows, to share it by tap all the time. [sharing] is the title of a card shared for a while by
 * tap, which the hint shows with a way to stop. [snackbar] speaks at the top of the panel, covering
 * none of it.
 */
@Composable
fun SkylineScreen(
  shared: SharedCity? = null,
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
  // Whether the clouds show (2026-11-07): kept on the phone, since it is a setting, not a view.
  val settings = remember(context) { context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE) }
  var clouds by remember { mutableStateOf(settings.getBoolean(CLOUDS, true)) }
  // Where a long press on a cloud, the sun or the moon opened the clouds' menu; null while closed.
  var skyMenu by remember { mutableStateOf<Offset?>(null) }
  // A shared city keeps the recipe and the scope it was made with.
  var generatorVersion by rememberSaveable { mutableIntStateOf(GeneratorVersion.CURRENT) }
  var identityScope by rememberSaveable { mutableStateOf(IdentityScope.SITE) }
  val qr = rememberSkylineQrState()
  // Only whether the camera has moved, so a drag doesn't redraw the panel on every frame.
  val cameraMoved by remember(qr) { derivedStateOf { qr.cameraMoved } }

  // Open each share link once; rotating the phone must not undo what was changed since.
  var opened by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(shared) {
    val city = shared ?: return@LaunchedEffect
    val link = ShareLink.encode(city)
    if (link == opened) return@LaunchedEffect
    opened = link
    url = city.url
    time = city.time
    weather = city.weather
    generatorVersion = city.generatorVersion
    identityScope = city.scope
    qr.flat = false
  }
  // The link the city shows is the one the phone hands over by tap. A payment code's city
  // (2026-11-07) hands over the profile instead: payment apps don't take a code by tap, and it has
  // no share link either.
  val paying = PaymentCode.isPaymentCode(url)
  val shownTitle = cards.firstOrNull { it.city() == url }?.title ?: url
  val profileTitle = cards.firstOrNull { it.isProfile }?.title ?: profileUrl
  LaunchedEffect(url, shownTitle, paying, profileUrl, profileTitle) {
    if (paying) onShow(profileTitle, profileUrl) else onShow(shownTitle, url)
  }

  // Another link starts a new city, made with today's recipe.
  LaunchedEffect(url) {
    if (shared == null || url != shared.url) generatorVersion = GeneratorVersion.CURRENT
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
  // Status bar icons sit on the backdrop in the city, and on the deeper surround in the flat code.
  val behindBars = if (qr.flat) Atmospheres.surround(air) else Atmospheres.backdrop(air)
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
      identityScope = identityScope,
      generatorVersion = generatorVersion,
      state = qr,
      modifier = Modifier.fillMaxSize(),
      billboard = BILLBOARD,
      clouds = clouds,
      onSkyLongPress = { _, at -> skyMenu = at },
    )

    SkylineQRTheme(dark = darkPanel, sky = sky) {
      popup()
      SkyMenu(
        skyMenu,
        clouds,
        onClouds = {
          clouds = it
          settings.edit().putBoolean(CLOUDS, it).apply()
        },
        onDismiss = { skyMenu = null },
      )

      Column(
        modifier =
          Modifier.align(Alignment.BottomCenter)
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
        // Once the camera has moved, the hint is the button that puts it back. It is not a
        // double-tap on the city, so a single tap there never waits to see whether a second follows.
        HintPill(
          when {
            error != null -> Hint.Problem(error.message.orEmpty())
            sharing != null -> Hint.Act("Stop sharing “$sharing”", SkylineIcons.Nfc, onStopSharing)
            qr.flat -> Hint.Say("Tap the code to see the city")
            cameraMoved -> Hint.Act("Put the camera back", SkylineIcons.Reset, qr::resetCamera)
            else -> Hint.Say("Tap for the QR code · hold for a meteor")
          }
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CardPicker(
            cards,
            url,
            onPick = {
              url = it
              identityScope = IdentityScope.SITE
            },
            onRemove = { card ->
              if (card.city() == url) {
                url = profileUrl
                identityScope = IdentityScope.SITE
              }
              onRemove(card)
            },
            modifier = Modifier.weight(1f),
          )
          // Share sends the link to this city and nothing else: it opens this very city in the app.
          // A payment code's city has none, so the button folds away and the picker takes its room.
          AnimatedVisibility(!paying) {
            ShareButton(
              "Share a link to this city",
              onClick = {
                val city = SharedCity(url, generatorVersion, identityScope, CityPreset.KUALA_LUMPUR, time, weather)
                runCatching { ShareLink.encode(city) }.getOrNull()?.let { shareText(context, it) }
              },
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          // Labels alone: with icons too, "Now" and "Night" cut off once the font is larger.
          PillChoices(TIMES, time, { time = it }, label = { it.label }, modifier = Modifier.weight(1f))
          MenuButton(weather, WEATHERS, { weather = it }, icon = { it.icon }, label = { it.label }, description = "Weather")
          AddButton(onClick = onAdd)
        }
      }
    }
  }
}

/** Where the app keeps its settings, and the clouds' key there. */
private const val SETTINGS = "skylineqr"
private const val CLOUDS = "clouds"

/** Now follows the clock through dawn and dusk; the fixed choices are just day and night. */
private val TIMES = listOf(TimeOfDay.AUTO, TimeOfDay.DAY, TimeOfDay.NIGHT)

/** A storm already rains; the app offers no rain of its own. */
private val WEATHERS = listOf(Weather.CLEAR, Weather.STORM, Weather.HAZE)

/** What the screen on Bukit Bintang's mall shows, in turn. */
private val BILLBOARD = listOf("马大牛逼", "tarumt #1", "i need argon")

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

private fun shareText(context: Context, link: String) {
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, link)
    }
  context.startActivity(Intent.createChooser(intent, "Share this city"))
}
