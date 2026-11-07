package com.skylightlim.skylineqr.ui.cards

import android.app.Application
import android.nfc.Tag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skylightlim.skylineqr.nfc.Cards
import com.skylightlim.skylineqr.nfc.NfcState
import com.skylightlim.skylineqr.nfc.card.Card
import com.skylightlim.skylineqr.nfc.card.CardCodec
import com.skylightlim.skylineqr.nfc.card.CardContent
import com.skylightlim.skylineqr.nfc.card.Draft
import com.skylightlim.skylineqr.nfc.read.TagInspector
import com.skylightlim.skylineqr.nfc.read.TagReading
import com.skylightlim.skylineqr.nfc.share.ShareSession
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The card popup and tap to share, from the skylineqr-nfc demo's flows: the popup making a new
 * card, which can read a tag or scan a QR code into it; the share in progress; and what the
 * snackbar says. Reading puts the phone in reader mode, which the activity switches.
 */
class CardsViewModel(app: Application) : AndroidViewModel(app) {

  /**
   * The card popup. [reading] while it waits for a tag to fill it from, [scanning] while the camera
   * is up in it (one or the other, never both); [fills] counts the drafts a tag or code has put in it, so the form starts again from
   * each, and [from] says where the last came from.
   */
  data class Popup(val draft: Draft, val reading: Reading? = null, val scanning: Boolean = false, val fills: Int = 0, val from: String? = null)

  /** The popup reading a tag: waiting, or what it found when that can't make a card. */
  data class Reading(val found: TagReading? = null, val error: String? = null)

  /** A line for the snackbar, with one action on what it reports, if any. */
  class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

  private val store = Cards.store(app)

  /** The saved cards, the profile first: the city's picker offers the links. */
  val cards: StateFlow<List<Card>> = store.cards

  val share: StateFlow<ShareSession.State> = ShareSession.state

  private val _nfc = MutableStateFlow(NfcState.of(app))
  val nfc: StateFlow<NfcState> = _nfc.asStateFlow()

  private val _popup = MutableStateFlow<Popup?>(null)
  val popup: StateFlow<Popup?> = _popup.asStateFlow()

  /** Reading a tag needs the phone in reader mode; nothing else does. */
  val wantsReader: StateFlow<Boolean> = _popup.map { it?.reading != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

  private val _message = MutableStateFlow<Message?>(null)
  val message: StateFlow<Message?> = _message.asStateFlow()

  fun refreshNfc() {
    val was = _nfc.value
    _nfc.value = NfcState.of(getApplication())
    // NFC switched on since: the city's link starts going out.
    if (was != NfcState.ON && _nfc.value == NfcState.ON) shown?.let { shareAlways(it.first, it.second) }
  }

  /** The city's link, as [shareAlways] last had it. */
  private var shown: Pair<String, String>? = null

  /**
   * Shares the link the city shows by tap, all the time (2026-11-07, at the user's request): no
   * time limit, through every read, while the app's process lives. A one-off share goes first and
   * hands back to it. Silent when NFC is off or missing; [refreshNfc] retries.
   */
  fun shareAlways(title: String, url: String) {
    shown = title to url
    ShareSession.shareAlways(getApplication(), Card(id = CITY_CARD, title = title, content = CardContent.Link(url)))
  }

  fun messageShown() {
    _message.value = null
  }

  // Sharing

  fun share(card: Card) {
    _message.value =
      when (ShareSession.start(getApplication(), card)) {
        ShareSession.StartResult.STARTED -> null
        ShareSession.StartResult.NFC_OFF -> "Turn NFC on first."
        ShareSession.StartResult.NFC_UNSUPPORTED -> "This phone can't act as an NFC tag."
        ShareSession.StartResult.TOO_LARGE -> "This card is too big to share by tap."
      }?.let { Message(it) }
  }

  fun stopSharing() = ShareSession.stop()

  // The popup

  fun addCard() {
    _popup.value = Popup(Draft("", CardContent.Link("")))
  }

  /** From the Share menu. */
  fun editDraft(draft: Draft) {
    _popup.value = Popup(draft)
  }

  /** Saves the popup's card, closes it, and says so with its share one tap away. */
  fun save(title: String, content: CardContent) {
    try {
      val saved = store.add(title, content)
      _popup.value = null
      // A payment code isn't handed over by tap: payment apps don't take one that way.
      _message.value =
        if (content is CardContent.Payment) Message("Added “${saved.title}”. Pick it under the city to show it.")
        else Message("Added “${saved.title}”", action = "Share by tap", onAction = { share(saved) })
    } catch (e: IllegalArgumentException) {
      _message.value = e.message?.let { Message(it) }
    }
  }

  fun close() {
    _popup.value = null
  }

  /** Removes a card at once (the profile can't be); the snackbar's Undo puts it back where it was. */
  fun remove(card: Card) {
    val sharing = ShareSession.state.value
    if (sharing is ShareSession.State.Sharing && sharing.card.id == card.id) ShareSession.stop()
    val index = store.cards.value.indexOfFirst { it.id == card.id }
    store.delete(card.id)
    _message.value = Message("Removed “${card.title}”", action = "Undo", onAction = { store.restore(card, index) })
  }

  // Reading a tag into the popup

  fun readTag() = popup { it.copy(reading = Reading(), scanning = false) }

  fun stopReading() = popup { it.copy(reading = null) }

  /**
   * A tag or phone was tapped while the popup reads. Runs on the reader's binder thread. What a
   * card can be made of fills the popup; anything else shows there, and the next tap reads again.
   */
  fun onTag(tag: Tag) {
    if (_popup.value?.reading == null) return
    val found =
      try {
        TagInspector.read(tag)
      } catch (e: IOException) {
        null
      }
    val draft = (found as? TagReading.Content)?.draft
    popup {
      when {
        it.reading == null -> it
        draft != null -> it.copy(draft = draft, reading = null, fills = it.fills + 1, from = "Filled in from the tag")
        found != null -> it.copy(reading = Reading(found))
        else -> it.copy(reading = Reading(error = "The tag moved away too soon. Hold it still and try again."))
      }
    }
  }

  // Scanning a QR code into the popup

  fun scan() = popup { it.copy(scanning = true, reading = null) }

  fun stopScanning() = popup { it.copy(scanning = false) }

  fun onScanned(text: String) =
    popup { it.copy(draft = CardCodec.fromScannedText(text), scanning = false, fills = it.fills + 1, from = "Filled in from the QR code") }

  private companion object {
    /** The id of the city's link while it is shared; it is no saved card. */
    const val CITY_CARD = "city"
  }

  /** Changes the popup, if it is still open: a tag can be read just as it closes. */
  private fun popup(change: (Popup) -> Popup) = _popup.update { it?.let(change) }
}
