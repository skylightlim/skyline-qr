package com.skylightlim.skylineqr.nfc.share

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.skylightlim.skylineqr.nfc.Cards
import com.skylightlim.skylineqr.nfc.NfcState
import com.skylightlim.skylineqr.nfc.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The "Share profile" Quick Settings tile: one tap starts handing over the profile card, another
 * stops it. On the lock screen it asks for the unlock first, since taps are only answered unlocked.
 */
class ShareProfileTileService : TileService() {

  private var listening: CoroutineScope? = null

  override fun onStartListening() {
    super.onStartListening()
    listening = MainScope().also { scope ->
      scope.launch { ShareSession.state.collect { render(it) } }
    }
  }

  override fun onStopListening() {
    listening?.cancel()
    listening = null
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()
    if (isLocked) unlockAndRun(::toggle) else toggle()
  }

  private fun toggle() {
    val profile = Cards.store(this).profile
    val state = ShareSession.state.value
    if (state is ShareSession.State.Sharing && state.card.id == profile.id) {
      ShareSession.stop()
      return
    }
    when (ShareSession.start(this, profile)) {
      ShareSession.StartResult.NFC_OFF -> openNfcSettings()
      else -> Unit
    }
  }

  private fun render(state: ShareSession.State) {
    val tile = qsTile ?: return
    val profile = Cards.store(this).profile
    val sharing = state is ShareSession.State.Sharing && state.card.id == profile.id
    tile.label = getString(R.string.skylineqr_nfc_tile_label)
    tile.state = when {
      !NfcState.canShare(this) -> Tile.STATE_UNAVAILABLE
      sharing -> Tile.STATE_ACTIVE
      else -> Tile.STATE_INACTIVE
    }
    if (Build.VERSION.SDK_INT >= 29) {
      tile.subtitle = if (sharing) getString(R.string.skylineqr_nfc_tile_sharing) else profile.title
    }
    tile.updateTile()
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun openNfcSettings() {
    val intent = NfcState.settingsIntent()
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}
