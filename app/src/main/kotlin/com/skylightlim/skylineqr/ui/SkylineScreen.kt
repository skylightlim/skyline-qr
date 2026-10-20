package com.skylightlim.skylineqr.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.CityPreset
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.SkylineQrCode
import com.skylightlim.skylineqr.compose.rememberSkylineQrState
import com.skylightlim.skylineqr.core.GeneratorVersion
import com.skylightlim.skylineqr.core.IdentityScope
import com.skylightlim.skylineqr.core.ShareLink
import com.skylightlim.skylineqr.core.SharedCity
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_URL = "https://github.com/skylightlim/skyline-qr"

/** The demo. [shared] is a city someone sent as a share link, opened in the app. */
@Composable
fun SkylineScreen(shared: SharedCity? = null) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val scope = rememberCoroutineScope()

  var url by rememberSaveable { mutableStateOf(DEFAULT_URL) }
  // The city rebuilds a beat after typing stops, not on every keystroke.
  var settledUrl by rememberSaveable { mutableStateOf(DEFAULT_URL) }
  var time by rememberSaveable { mutableStateOf(TimeOfDay.AUTO) }
  var weather by rememberSaveable { mutableStateOf(Weather.CLEAR) }
  // A shared city keeps the recipe and the scope it was made with.
  var generatorVersion by rememberSaveable { mutableIntStateOf(GeneratorVersion.CURRENT) }
  var identityScope by rememberSaveable { mutableStateOf(IdentityScope.SITE) }
  val qr = rememberSkylineQrState()

  // Open each share link once; rotating the phone must not undo what was changed since.
  var opened by rememberSaveable { mutableStateOf<String?>(null) }
  LaunchedEffect(shared) {
    val city = shared ?: return@LaunchedEffect
    val link = ShareLink.encode(city)
    if (link == opened) return@LaunchedEffect
    opened = link
    url = city.url
    settledUrl = city.url
    time = city.time
    weather = city.weather
    generatorVersion = city.generatorVersion
    identityScope = city.scope
    qr.flat = false
  }
  // Typing a new link starts a new city, made with today's recipe.
  LaunchedEffect(settledUrl) {
    if (shared == null || settledUrl != shared.url) generatorVersion = GeneratorVersion.CURRENT
  }

  LaunchedEffect(url) {
    delay(200)
    settledUrl = url
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

  // Sound is off until asked for, and pauses whenever the app is not on screen.
  var muted by rememberSaveable { mutableStateOf(true) }
  val sound = remember { CitySound(context) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> sound.foreground(true)
        Lifecycle.Event.ON_STOP -> sound.foreground(false)
        else -> {}
      }
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      sound.close()
    }
  }
  // Read here, not only inside the button's label, so a toggle recomposes this scope and runs the
  // effect below.
  val silent = muted
  SideEffect {
    sound.muted = silent
    sound.mix(air)
  }
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

  var licences by remember { mutableStateOf(false) }
  if (licences) Licences(onDismiss = { licences = false })

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    SkylineQrCode(
      url = settledUrl,
      city = CityPreset.KUALA_LUMPUR,
      time = time,
      weather = weather,
      identityScope = identityScope,
      generatorVersion = generatorVersion,
      state = qr,
      modifier = Modifier.fillMaxSize(),
      onEvent = sound::play,
    )

    SkylineQRTheme(dark = darkPanel) {
      val fill = frost(sky, darkPanel)
      val onSky = if (lightBehindBars) Color(0xFF1C1F26) else Color(0xFFF1F3F8)
      TopBar(code = qr.identity?.code, onSky = onSky, onInfo = { licences = true }, modifier = Modifier.align(Alignment.TopCenter))

      Column(
        modifier =
          Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // Taps between the controls clear the keyboard; they never reach the city behind.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        val error = qr.error
        HintPill(
          text =
            when {
              error != null -> error.message.orEmpty()
              qr.flat -> "Tap the code to see the city"
              qr.cameraMoved -> "Double-tap to put the camera back"
              else -> "Tap for the QR code · hold for a meteor"
            },
          error = error != null,
          fill = fill,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            isError = error != null,
            placeholder = { Text("Paste a link") },
            shape = TileShape,
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fill,
                unfocusedContainerColor = fill,
                errorContainerColor = fill,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
              ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f),
          )
          Box {
            var menu by remember { mutableStateOf(false) }
            TileButton(SkylineIcons.Share, "Share", fill, onClick = { menu = true }, size = FieldHeight, accent = true)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text("The code") },
                onClick = {
                  menu = false
                  scope.launch {
                    val bitmap = qr.snapshot() ?: return@launch
                    shareImage(context, withContext(Dispatchers.IO) { saveShot(context, bitmap, "skyline-qr-code") })
                  }
                },
              )
              DropdownMenuItem(
                text = { Text("A postcard of the city") },
                enabled = !qr.flat,
                onClick = {
                  menu = false
                  scope.launch {
                    val city = qr.snapshot(flat = false) ?: return@launch
                    val code = qr.codeBitmap() ?: return@launch
                    val host = qr.identity?.link?.displayHost.orEmpty()
                    val card = withContext(Dispatchers.Default) { postcard(city, code, host) }
                    shareImage(context, withContext(Dispatchers.IO) { saveShot(context, card, "skyline-qr-postcard") })
                  }
                },
              )
              DropdownMenuItem(
                text = { Text("A link to this city") },
                onClick = {
                  menu = false
                  val city = SharedCity(settledUrl, generatorVersion, identityScope, CityPreset.KUALA_LUMPUR, time, weather)
                  shareText(context, runCatching { ShareLink.encode(city) }.getOrNull() ?: return@DropdownMenuItem)
                },
              )
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Segments(TIMES, time, { time = it }, fill, icon = { it.icon }, label = { it.label }, modifier = Modifier.weight(1f))
          Box {
            var menu by remember { mutableStateOf(false) }
            TileButton(weather.icon, "Weather: ${weather.label}", fill, onClick = { menu = true }, menu = true)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              for (option in WEATHERS) {
                val chosen = option == weather
                DropdownMenuItem(
                  text = { Text(option.label, color = if (chosen) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                  leadingIcon = {
                    Icon(
                      option.icon,
                      contentDescription = null,
                      tint = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(20.dp),
                    )
                  },
                  onClick = {
                    weather = option
                    menu = false
                  },
                )
              }
            }
          }
          TileButton(if (muted) SkylineIcons.SoundOff else SkylineIcons.SoundOn, if (muted) "Sound off" else "Sound on", fill, onClick = { muted = !muted })
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

private val TimeOfDay.icon
  get() =
    when (this) {
      TimeOfDay.AUTO -> SkylineIcons.Now
      TimeOfDay.NIGHT -> SkylineIcons.Night
      else -> SkylineIcons.Day
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

private fun saveShot(context: Context, bitmap: Bitmap, name: String): Uri {
  val directory = File(context.cacheDir, "shots").apply { mkdirs() }
  val file = File(directory, "$name.png")
  FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun shareText(context: Context, link: String) {
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, link)
    }
  context.startActivity(Intent.createChooser(intent, "Share this city"))
}

/** The licences of everything the app is built from, bundled as one text asset. */
@Composable
private fun Licences(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    title = { Text("Open-source licences") },
    text = {
      Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
  )
}

private fun shareImage(context: Context, uri: Uri) {
  val intent =
    Intent(Intent.ACTION_SEND).apply {
      type = "image/png"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  context.startActivity(Intent.createChooser(intent, "Share skyline code"))
}
