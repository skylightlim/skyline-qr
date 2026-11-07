package com.skylightlim.skylineqr.nfc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.skylightlim.skylineqr.nfc.card.CardStore
import com.skylightlim.skylineqr.nfc.share.ShareSession
import com.skylightlim.skylineqr.nfc.share.TagEmulationService
import java.io.File
import java.util.concurrent.Executors

/** Whether this phone can do NFC, and whether it is switched on. */
enum class NfcState {
  UNSUPPORTED,
  OFF,
  ON;

  companion object {
    fun of(context: Context): NfcState {
      val adapter = NfcAdapter.getDefaultAdapter(context) ?: return UNSUPPORTED
      return if (adapter.isEnabled) ON else OFF
    }

    /** Acting as a tag needs host card emulation as well as NFC. */
    fun canShare(context: Context) =
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    /** Opens the system screen where NFC is switched on. */
    fun settingsIntent() = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
}

/** The app's one card store, in its private files. */
object Cards {
  @Volatile private var store: CardStore? = null

  fun store(context: Context): CardStore =
    store ?: synchronized(this) {
      store ?: CardStore(File(context.applicationContext.filesDir, "skylineqr-nfc/cards.json")).also { store = it }
    }
}

/**
 * Switches an activity's NFC between its two jobs: acting as a tag (the default, so shares work
 * while it is open) and reading tags. A phone can't do both at once: while reading, Android turns
 * card emulation off, so starting to read pauses any share, and stopping brings the standing one
 * back.
 *
 * While the activity is in front, taps go to this app's tag even if another installed app answers
 * to the same application ID.
 *
 * Turning the reader on or off is a call into the NFC service that reconfigures the controller and
 * can hold its caller for a few hundred milliseconds. On the main thread that froze the NFC
 * button's press as it was let go (2026-11-07), so those calls run in order on a thread of their
 * own. Android allows that: unlike foreground dispatch, reader mode has no main-thread rule, and
 * the platform keeps the activity's reader flags itself across pause and resume.
 */
class NfcModes(private val activity: ComponentActivity) : DefaultLifecycleObserver {

  private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
  @Volatile private var onTag: ((Tag) -> Unit)? = null
  private var resumed = false

  init {
    activity.lifecycle.addObserver(this)
  }

  /** Reads tags and other phones; [onTag] runs on a binder thread, where blocking I/O is fine. */
  fun startReading(onTag: (Tag) -> Unit) {
    ShareSession.pause()
    this.onTag = onTag
    if (resumed) enableReader()
  }

  fun stopReading() {
    if (onTag == null) return
    onTag = null
    if (resumed) disableReader()
    ShareSession.resume()
  }

  override fun onResume(owner: LifecycleOwner) {
    resumed = true
    preferThisApp(true)
    if (onTag != null) enableReader()
  }

  override fun onPause(owner: LifecycleOwner) {
    resumed = false
    if (onTag != null) disableReader()
    preferThisApp(false)
  }

  private fun enableReader() {
    val adapter = adapter ?: return
    calls.execute { runCatching { adapter.enableReaderMode(activity, { tag -> onTag?.invoke(tag) }, READER_FLAGS, null) } }
  }

  private fun disableReader() {
    val adapter = adapter ?: return
    calls.execute { runCatching { adapter.disableReaderMode(activity) } }
  }

  private fun preferThisApp(prefer: Boolean) {
    val adapter = adapter ?: return
    if (!NfcState.canShare(activity)) return
    runCatching {
      val emulation = CardEmulation.getInstance(adapter)
      if (prefer) {
        emulation.setPreferredService(activity, ComponentName(activity, TagEmulationService::class.java))
      } else {
        emulation.unsetPreferredService(activity)
      }
    }
  }

  private companion object {
    /** One thread for every reader switch, so they reach the NFC service in the order they were made. */
    val calls = Executors.newSingleThreadExecutor { Thread(it, "SkylineQR-NFC").apply { isDaemon = true } }

    const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
      NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
  }
}
