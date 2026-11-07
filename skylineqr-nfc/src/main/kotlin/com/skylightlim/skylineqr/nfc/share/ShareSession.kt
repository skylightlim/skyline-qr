package com.skylightlim.skylineqr.nfc.share

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import com.skylightlim.skylineqr.nfc.NfcState
import com.skylightlim.skylineqr.nfc.card.Card
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.tag.Type4TagEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the phone's emulated tag is handing over, if anything.
 *
 * A one-off share ([start]) ends when the user stops it, [AUTO_STOP_DELAY_MS] after the first
 * successful read (if [ShareSettings.stopAfterRead] is on), or after [IDLE_TIMEOUT_MS] without a
 * read. The standing card ([shareAlways], 2026-11-07: the city's link) is shared whenever nothing
 * else is, with no time limit and through every read; a one-off share goes first and hands back to
 * it when it ends. Reading tags turns card emulation off, so [pause] and [resume] bracket it. Taps
 * are only answered while the phone is unlocked (`requireDeviceUnlock` in the service's XML).
 *
 * The state lives in memory: if Android kills the app's process, the share simply ends.
 */
object ShareSession {

  const val AUTO_STOP_DELAY_MS = 1_300L
  const val IDLE_TIMEOUT_MS = 120_000L

  /** A reader that reads the file twice in one tap counts once. */
  private const val READ_DEBOUNCE_MS = 1_000L

  sealed interface State {
    data object Idle : State

    /** [always] for the standing card, which nothing but another share ends. */
    data class Sharing(val card: Card, val reads: Int = 0, val always: Boolean = false) : State
  }

  enum class StartResult { STARTED, NFC_UNSUPPORTED, NFC_OFF, TOO_LARGE }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state.asStateFlow()

  @Volatile private var message: ByteArray? = null
  private var timer: Job? = null
  private var lastReadAt = 0L
  private var appContext: Context? = null
  @Volatile private var standing: Card? = null
  private var paused = false

  /** A one-off share of [card], ahead of the standing one. */
  fun start(context: Context, card: Card): StartResult = begin(context, card, always = false)

  /**
   * Makes [card] the standing share, or none for null. It starts at once unless a one-off share or
   * a tag read is under way, in which case it follows when that ends.
   */
  fun shareAlways(context: Context, card: Card?): StartResult {
    standing = card
    appContext = context.applicationContext
    val now = _state.value
    if (paused || (now is State.Sharing && !now.always)) return StartResult.STARTED
    if (card == null) {
      idle()
      return StartResult.STARTED
    }
    return begin(context, card, always = true)
  }

  /** Nothing is shared while the phone reads tags; a one-off share ends. */
  fun pause() {
    paused = true
    idle()
  }

  /** Reading is over: the standing card, if there is one, is shared again. */
  fun resume() {
    paused = false
    stop()
  }

  private fun begin(context: Context, card: Card, always: Boolean): StartResult {
    if (!NfcState.canShare(context)) return StartResult.NFC_UNSUPPORTED
    when (NfcState.of(context)) {
      NfcState.UNSUPPORTED -> return StartResult.NFC_UNSUPPORTED
      NfcState.OFF -> return StartResult.NFC_OFF
      NfcState.ON -> Unit
    }
    val bytes = CardCodec.toNdef(card.content).toByteArray()
    if (bytes.size > Type4TagEmulator.MAX_MESSAGE_SIZE) return StartResult.TOO_LARGE
    appContext = context.applicationContext
    message = bytes
    lastReadAt = 0L
    _state.value = State.Sharing(card, always = always)
    if (always) {
      timer?.cancel()
      timer = null
    } else {
      stopAfter(IDLE_TIMEOUT_MS)
    }
    return StartResult.STARTED
  }

  /** Ends a one-off share; the standing card, if there is one, takes over again. */
  fun stop() {
    val next = standing
    val context = appContext
    if (!paused && next != null && context != null && begin(context, next, always = true) == StartResult.STARTED) return
    idle()
  }

  private fun idle() {
    timer?.cancel()
    timer = null
    message = null
    _state.value = State.Idle
  }

  /** The NDEF message to hand over, or null while nothing is shared. */
  internal fun currentMessage(): ByteArray? = message

  /** A reader got the whole message. Called on the main thread by [TagEmulationService]. */
  internal fun onMessageRead() {
    val sharing = _state.value as? State.Sharing ?: return
    val now = SystemClock.elapsedRealtime()
    if (lastReadAt != 0L && now - lastReadAt < READ_DEBOUNCE_MS) return
    lastReadAt = now
    _state.value = sharing.copy(reads = sharing.reads + 1)
    val context = appContext ?: return
    vibrate(context)
    if (sharing.always) return
    stopAfter(if (ShareSettings.get(context).stopAfterRead.value) AUTO_STOP_DELAY_MS else IDLE_TIMEOUT_MS)
  }

  private fun stopAfter(millis: Long) {
    timer?.cancel()
    timer = scope.launch {
      delay(millis)
      stop()
    }
  }

  private fun vibrate(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
      context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Vibrator::class.java)
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    val effect = if (Build.VERSION.SDK_INT >= 29) {
      VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    } else {
      VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    vibrator.vibrate(effect)
  }
}

/** Share preferences, kept in SharedPreferences. */
class ShareSettings private constructor(private val prefs: SharedPreferences) {

  private val _stopAfterRead = MutableStateFlow(prefs.getBoolean(KEY_STOP_AFTER_READ, true))

  /** Whether a share ends shortly after the first phone reads it. On by default. */
  val stopAfterRead: StateFlow<Boolean> = _stopAfterRead.asStateFlow()

  fun setStopAfterRead(enabled: Boolean) {
    prefs.edit { putBoolean(KEY_STOP_AFTER_READ, enabled) }
    _stopAfterRead.value = enabled
  }

  companion object {
    private const val KEY_STOP_AFTER_READ = "stop_after_read"

    @Volatile private var instance: ShareSettings? = null

    fun get(context: Context): ShareSettings =
      instance ?: synchronized(this) {
        instance ?: ShareSettings(
          context.applicationContext.getSharedPreferences("skylineqr_nfc", Context.MODE_PRIVATE),
        ).also { instance = it }
      }
  }
}
