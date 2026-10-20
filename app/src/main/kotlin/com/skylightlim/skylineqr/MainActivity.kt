package com.skylightlim.skylineqr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.skylightlim.skylineqr.core.ShareLink
import com.skylightlim.skylineqr.core.SharedCity
import com.skylightlim.skylineqr.core.UnsupportedVersionException
import com.skylightlim.skylineqr.theme.SkylineQRTheme
import com.skylightlim.skylineqr.ui.SkylineScreen

class MainActivity : ComponentActivity() {

  /** A city opened from a share link: on launch, or while the app is already open. */
  private var shared by mutableStateOf<SharedCity?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    shared = sharedCity(intent)
    setContent { SkylineQRTheme { SkylineScreen(shared) } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    sharedCity(intent)?.let { shared = it }
  }

  private fun sharedCity(intent: Intent?): SharedCity? {
    val link = intent?.data?.toString() ?: return null
    return try {
      ShareLink.decode(link)
    } catch (e: UnsupportedVersionException) {
      Toast.makeText(this, "This city needs a newer Skyline QR", Toast.LENGTH_LONG).show()
      null
    }
  }
}
