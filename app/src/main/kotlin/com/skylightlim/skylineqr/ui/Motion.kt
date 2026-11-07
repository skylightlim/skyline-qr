@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlinx.coroutines.launch

/*
 * The app's springs for touch and text (2026-11-07, at the user's request: "so the app felt
 * lively"). Every control dips under the finger and springs back; text that appears rises into
 * place. Both run on the expressive motion scheme's springs, so they match the shape morphs the
 * buttons already do. Compose runs springs at the system's animation scale, so with Remove
 * animations on each one is an instant cut: the dip still shows, it just doesn't travel.
 */

/** How far a control dips: buttons and toggles. */
internal const val PressDepth = 0.94f

/** How far a wide control dips, a bar or a menu row, so its edges move about as far as a button's. */
internal const val WidePressDepth = 0.98f

/**
 * A press answered the moment the finger lands: the control shrinks to [depth] on the scheme's
 * fast effects spring (critically damped, about a tenth of a second, so nothing wobbles under the
 * finger) and springs back on release on its fast spatial spring, which overshoots a little on the
 * way out. Only the drawing scales, never the touch area, so a tap near an edge still lands. It
 * watches the touch after the control and any scroll have had it, so a press a scroll takes over
 * springs back at once.
 */
internal fun Modifier.springPress(depth: Float = PressDepth): Modifier = composed {
  val motion = MaterialTheme.motionScheme
  val dip = remember { Animatable(1f) }
  val scope = rememberCoroutineScope()
  pointerInput(depth) {
      awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        scope.launch { dip.animateTo(depth, motion.fastEffectsSpec()) }
        waitForUpOrCancellation(pass = PointerEventPass.Final)
        scope.launch { dip.animateTo(1f, motion.fastSpatialSpec()) }
      }
    }
    .drawWithContent {
      val s = dip.value
      scale(s) { this@drawWithContent.drawContent() }
    }
}

/**
 * Text, or a field, arriving: it fades in as it rises the last few points into place on the
 * scheme's default spatial spring, settling with a touch of overshoot. Runs once, when it first
 * appears.
 */
internal fun Modifier.springIn(): Modifier = composed {
  val motion = MaterialTheme.motionScheme
  val arrive = remember { Animatable(0f) }
  LaunchedEffect(Unit) { arrive.animateTo(1f, motion.defaultSpatialSpec()) }
  graphicsLayer {
    alpha = arrive.value.coerceIn(0f, 1f)
    translationY = (1f - arrive.value) * RISE.toPx()
  }
}

/** How far arriving text rises. */
private val RISE = 10.dp

/**
 * Text giving way to other text in the same place: the new rises in from below on the default
 * spatial spring as the old rises away and fades on the fast effects springs, so leaving is quicker
 * than arriving.
 */
internal fun rise(motion: MotionScheme): ContentTransform =
  (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec()))
    .togetherWith(slideOutVertically(motion.fastEffectsSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))

/** How an icon answers its button's press (2026-11-07, at the user's request). */
internal enum class IconMotion {
  /** Shrinks under the finger and springs back past its size. */
  Pop,
  /** A quarter turn, as a plus becomes an x and back. */
  Turn,
  /** A full turn backward, for putting something back. */
  Rewind,
  /** Rises a little, as an arrow going out. */
  Lift,
  /** Tips over, as a bin lid. */
  Tilt,
}

/**
 * An icon moving with its button's press, read from [source], the button's own interaction
 * source: into the motion on the scheme's fast spatial spring and out of it on the default one,
 * which overshoots, so every icon answers a tap in its own way while the button dips around it.
 */
internal fun Modifier.iconMotion(source: InteractionSource, kind: IconMotion = IconMotion.Pop): Modifier = composed {
  val pressed by source.collectIsPressedAsState()
  val motion = MaterialTheme.motionScheme
  val t by animateFloatAsState(if (pressed) 1f else 0f, if (pressed) motion.fastSpatialSpec() else motion.defaultSpatialSpec(), label = "icon")
  graphicsLayer {
    when (kind) {
      IconMotion.Pop -> {
        scaleX = 1f - 0.2f * t
        scaleY = 1f - 0.2f * t
      }
      IconMotion.Turn -> rotationZ = 90f * t
      IconMotion.Rewind -> rotationZ = -360f * t
      IconMotion.Lift -> translationY = -LIFT.toPx() * t
      IconMotion.Tilt -> {
        rotationZ = -16f * t
        translationY = -LIFT.toPx() * 0.5f * t
      }
    }
  }
}

/** How far a lifting icon rises. */
private val LIFT = 3.dp

/** A menu's chevron, turned upside down while its menu is [open], on the default spatial spring. */
internal fun Modifier.flipWhen(open: Boolean): Modifier = composed {
  val turn by animateFloatAsState(if (open) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
  graphicsLayer { rotationZ = turn }
}

/**
 * A change of theme spreading out from where the finger came down (2026-11-07, at the user's
 * request: "propagate from the time bar element, not change the whole screen at once"). Whenever
 * [theme] changes, what this draws keeps showing the colours it had, from a snapshot of its last
 * frame, while the new ones open over it in a circle from the last touch on the motion scheme's
 * slow spatial spring. Between changes it draws as usual and keeps the snapshot fresh.
 */
internal fun Modifier.themeReveal(theme: Any): Modifier = composed {
  val motion = MaterialTheme.motionScheme
  val snapshot = rememberGraphicsLayer()
  val progress = remember { Animatable(1f) }
  val reveal = remember { RevealState() }
  val current by rememberUpdatedState(theme)
  LaunchedEffect(theme) {
    if (reveal.drawn == null || reveal.drawn == theme) return@LaunchedEffect
    reveal.target = theme
    progress.snapTo(0f)
    progress.animateTo(1f, motion.slowSpatialSpec())
    reveal.drawn = theme
  }
  pointerInput(Unit) {
      awaitEachGesture { reveal.origin = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
    }
    .drawWithContent {
      val key = current
      if (reveal.drawn == null || reveal.drawn == key) {
        snapshot.record { this@drawWithContent.drawContent() }
        reveal.drawn = key
        drawLayer(snapshot)
      } else {
        drawLayer(snapshot)
        val o = reveal.origin
        val far = maxOf(hypot(o.x, o.y), hypot(size.width - o.x, o.y), hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y))
        val r = far * (if (reveal.target == key) progress.value else 0f).coerceAtLeast(0f)
        clipPath(Path().apply { addOval(Rect(o, r)) }) { this@drawWithContent.drawContent() }
      }
    }
}

/** What [themeReveal] last drew in full, what it is revealing, and from where. */
private class RevealState {
  var drawn: Any? = null
  var target: Any? = null
  var origin = Offset.Zero
}

