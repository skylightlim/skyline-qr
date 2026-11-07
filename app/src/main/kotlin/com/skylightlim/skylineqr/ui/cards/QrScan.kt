@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui.cards

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skylightlim.skylineqr.nfc.scan.QrScanner
import com.skylightlim.skylineqr.ui.springIn
import com.skylightlim.skylineqr.ui.springPress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * The QR scanner inside the card popup, where the form was (2026-11-07; it had been a page of its
 * own): the camera in a rounded square with a viewfinder of four white corner brackets over it,
 * and what to do under it. The brackets spring in from the square's own corners as the camera
 * opens, then breathe out and back once every three seconds as they look; once a
 * code is read they spring tighter round it, with a confirming buzz, for a moment before the form
 * comes back filled in. If the camera was refused it says so in the same square and offers to ask
 * again, or, refused for good, to open the app's settings, and goes on once it is allowed there.
 */
@Composable
internal fun QrScan(onResult: (String) -> Unit) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val haptics = LocalHapticFeedback.current
  fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  var granted by remember { mutableStateOf(allowed()) }
  var refused by remember { mutableStateOf(false) }
  val ask =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      granted = it
      refused = !it
    }
  LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
  // Back from Settings with the camera allowed there.
  LifecycleResumeEffect(Unit) {
    granted = allowed()
    onPauseOrDispose {}
  }

  // The first code read: the brackets close on it, then it fills the card.
  var found by remember { mutableStateOf<String?>(null) }
  val latest by rememberUpdatedState(onResult)
  LaunchedEffect(found) {
    val code = found ?: return@LaunchedEffect
    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    delay(LOCK_MILLIS)
    latest(code)
  }

  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large).background(Color.Black)) {
      if (granted) {
        CameraPreview(onResult = { if (found == null) found = it })
        Viewfinder(locked = found != null, modifier = Modifier.fillMaxSize())
      } else {
        Column(
          Modifier.align(Alignment.Center).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text("Scanning needs the camera.", color = Color.White, textAlign = TextAlign.Center)
          // Refused once, Android asks again; refused for good, only Settings can allow it.
          if (refused) {
            val again = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
            FilledTonalButton(
              onClick = {
                if (again) {
                  ask.launch(Manifest.permission.CAMERA)
                } else {
                  context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
              },
              shapes = ButtonDefaults.shapes(),
              modifier = Modifier.springPress().springIn(),
            ) {
              Text(if (again) "Allow the camera" else "Open settings")
            }
          }
        }
      }
    }
    Text(
      "Point the camera at a QR code",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.springIn(),
    )
  }
}

/**
 * Four corner brackets and nothing else, in pure white lines (2026-11-07). They open from the
 * square's corners on the motion scheme's slow spatial spring, overshooting a little before they
 * settle; then, while they look for a code, they breathe: out a little and back again, slowly, once
 * every [BREATH_MILLIS] and still in between (they had moved too often to the user's eye); once
 * [locked] they close in on the fast spatial spring.
 */
@Composable
private fun Viewfinder(locked: Boolean, modifier: Modifier = Modifier) {
  val motion = MaterialTheme.motionScheme
  val open = remember { Animatable(0f) }
  val reach = remember { Animatable(0f) }
  LaunchedEffect(Unit) { open.animateTo(1f, motion.slowSpatialSpec()) }
  LaunchedEffect(locked) {
    if (locked) {
      reach.animateTo(0f, motion.fastSpatialSpec())
      return@LaunchedEffect
    }
    delay(SEARCH_DELAY_MILLIS)
    while (true) {
      val started = withFrameMillis { it }
      reach.animateTo(1f, tween(BREATHE_MILLIS, easing = FastOutSlowInEasing))
      reach.animateTo(0f, tween(BREATHE_MILLIS, easing = FastOutSlowInEasing))
      delay((BREATH_MILLIS - (withFrameMillis { it } - started)).coerceAtLeast(0L))
    }
  }
  val lock by animateFloatAsState(if (locked) 1f else 0f, motion.fastSpatialSpec(), label = "lock")
  Canvas(modifier) {
    val side = size.minDimension
    val inset = side * (FROM + (REST - FROM) * open.value - REACH * reach.value + CLOSE * lock)
    val arm = side * ARM
    val bend = arm * BEND
    val brackets = Path()
    for (right in listOf(false, true)) {
      for (bottom in listOf(false, true)) {
        val x = if (right) size.width - inset else inset
        val y = if (bottom) size.height - inset else inset
        val dx = if (right) -1f else 1f
        val dy = if (bottom) -1f else 1f
        brackets.moveTo(x, y + dy * arm)
        brackets.lineTo(x, y + dy * bend)
        brackets.quadraticTo(x, y, x + dx * bend, y)
        brackets.lineTo(x + dx * arm, y)
      }
    }
    drawPath(brackets, Color.White, style = Stroke(STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

/** The brackets' inset as a share of the square: where they open from, rest, and how much tighter they close. */
private const val FROM = 0.04f
private const val REST = 0.16f
private const val CLOSE = 0.07f

/** How far the brackets reach out while they look for a code, as a share of the square. */
private const val REACH = 0.035f

/** How long the brackets settle before they start to look; then one breath every BREATH_MILLIS, each way taking BREATHE_MILLIS. */
private const val SEARCH_DELAY_MILLIS = 900L
private const val BREATH_MILLIS = 3000L
private const val BREATHE_MILLIS = 700

/** Each bracket's arm, as a share of the square, and how much of it bends round the corner. */
private const val ARM = 0.13f
private const val BEND = 0.4f

private val STROKE = 4.dp

/** How long the brackets hold a code before it fills the card. */
private const val LOCK_MILLIS = 320L

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
  val context = LocalContext.current
  val owner = LocalLifecycleOwner.current
  val latest by rememberUpdatedState(onResult)
  val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
  LaunchedEffect(preview) {
    val scanner = QrScanner.start(context, owner, preview.surfaceProvider) { latest(it) }
    try {
      awaitCancellation()
    } finally {
      scanner.close()
    }
  }
  AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}
