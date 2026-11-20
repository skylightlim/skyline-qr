package com.skylightlim.skylineqr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.skylightlim.skylineqr.nfc.NfcModes
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import com.skylightlim.skylineqr.ui.SkylineApp
import com.skylightlim.skylineqr.ui.cards.CardsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  /** Counts the home screen's Tour shortcut being used: each new one starts the tour. */
  private var tours by mutableIntStateOf(0)

  private val cards: CardsViewModel by viewModels()

  /** The phone's NFC: an NDEF tag while the app is in front, a reader while the card popup reads one. */
  private lateinit var nfc: NfcModes

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    nfc = NfcModes(this)
    lifecycleScope.launch {
      cards.wantsReader.collect { reading -> if (reading) nfc.startReading(cards::onTag) else nfc.stopReading() }
    }
    if (savedInstanceState == null) receive(intent)
    setContent { SkylineQRTheme { SkylineApp(tours, cards) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    receive(intent)
  }

  override fun onResume() {
    super.onResume()
    cards.refreshNfc()
  }

  private fun receive(intent: Intent?) {
    when (intent?.action) {
      ACTION_TOUR -> tours++
      // Text from the Android Share menu opens in the card popup, over whatever is open, to check and save.
      Intent.ACTION_SEND -> {
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        cards.editDraft(CardCodec.fromSharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT)))
      }
    }
  }

  companion object {
    /** The home screen shortcut's action (res/xml/shortcuts.xml). */
    const val ACTION_TOUR = "com.skylightlim.skylineqr.TOUR"
  }
}
