package com.skylightlim.skylineqr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.skylightlim.skylineqr.R

/** The poster's paper and ink: maptoposter's Monochrome Blue theme (tools/poster). */
private val PosterPaper = Color(0xFFF5F8FA)
private val PosterInk = Color(0xFF1A3A5C)

/**
 * A map poster of Kuala Lumpur (tools/poster, from OpenStreetMap; the credit is printed on it) over
 * everything while the first city builds. The poster's paper fills the screen round it, so the
 * whole poster shows on any shape of screen. Touches stop here.
 */
@Composable
fun LoadingPoster(modifier: Modifier = Modifier) {
  Box(
    modifier
      .fillMaxSize()
      .background(PosterPaper)
      .pointerInput(Unit) { detectTapGestures {} }
      .clearAndSetSemantics { contentDescription = "Building the city" },
  ) {
    Image(
      painterResource(R.drawable.kl_poster),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize(),
    )
    Text(
      "Building the city…",
      modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
      color = PosterInk.copy(alpha = 0.6f),
      style = MaterialTheme.typography.labelLarge,
    )
  }
}
