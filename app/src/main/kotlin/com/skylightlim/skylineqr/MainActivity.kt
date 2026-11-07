package com.skylightlim.skylineqr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.skylightlim.skylineqr.core.ShareLink
import com.skylightlim.skylineqr.core.SharedCity
import com.skylightlim.skylineqr.core.UnsupportedVersionException
import com.skylightlim.skylineqr.nfc.NfcModes
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import com.skylightlim.skylineqr.ui.SkylineApp
import com.skylightlim.skylineqr.ui.cards.CardsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  /** A city opened from a share link: on launch, or while the app is already open. */
  private var shared by mutableStateOf<SharedCity?>(null)

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
    shared = sharedCity(intent)
    if (savedInstanceState == null) receiveShare(intent)
    setContent { SkylineQRTheme { SkylineApp(shared, cards) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    sharedCity(intent)?.let { shared = it }
    receiveShare(intent)
  }

  override fun onResume() {
    super.onResume()
    cards.refreshNfc()
  }

  private fun sharedCity(intent: Intent?): SharedCity? {
    if (intent?.action != Intent.ACTION_VIEW) return null
    val link = intent.data?.toString() ?: return null
    return try {
      ShareLink.decode(link)
    } catch (e: UnsupportedVersionException) {
      Toast.makeText(this, "This city needs a newer Skyline QR", Toast.LENGTH_LONG).show()
      null
    }
  }

  /** Text from the Android Share menu opens in the card popup, over whatever is open, to check and save. */
  private fun receiveShare(intent: Intent?) {
    if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
    cards.editDraft(CardCodec.fromSharedText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT)))
  }
}
