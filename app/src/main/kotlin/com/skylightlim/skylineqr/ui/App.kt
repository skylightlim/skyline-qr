package com.skylightlim.skylineqr.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skylightlim.skylineqr.core.SharedCity
import com.skylightlim.skylineqr.nfc.share.ShareSession
import com.skylightlim.skylineqr.ui.cards.CardEditor
import com.skylightlim.skylineqr.ui.cards.CardsViewModel

/**
 * The app: the city, showing whichever saved link card its picker chooses; the card popup over it
 * (from its new-card button or the Share menu); and the snackbar that says what was done. A card being shared by tap shows in the city's hint, which
 * stops it. (2026-11-07: the cards screen is gone; the popup is the only way in.)
 */
@Composable
fun SkylineApp(shared: SharedCity?, cards: CardsViewModel) {
  val share by cards.share.collectAsStateWithLifecycle()
  val saved by cards.cards.collectAsStateWithLifecycle()
  val popup by cards.popup.collectAsStateWithLifecycle()
  val message by cards.message.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(message) {
    val shown = message ?: return@LaunchedEffect
    // With an action it stays long enough to reach for.
    val result = snackbar.showSnackbar(shown.text, actionLabel = shown.action, duration = if (shown.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
    if (result == SnackbarResult.ActionPerformed) shown.onAction?.invoke()
    cards.messageShown()
  }

  SkylineScreen(
    shared = shared,
    cards = saved,
    // The city's own link is always out; only a share for a while gets a Stop.
    sharing = (share as? ShareSession.State.Sharing)?.takeUnless { it.always }?.card?.title,
    onStopSharing = cards::stopSharing,
    onRemove = cards::remove,
    onShow = cards::shareAlways,
    onAdd = cards::addCard,
    snackbar = snackbar,
    popup = { popup?.let { CardEditor(cards, it) } },
  )
}
