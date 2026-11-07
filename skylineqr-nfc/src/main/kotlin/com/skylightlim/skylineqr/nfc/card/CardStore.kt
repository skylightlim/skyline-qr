package com.skylightlim.skylineqr.nfc.card

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The saved cards, kept in one small JSON file. The profile card is always first and always there;
 * new cards go right after it, newest first.
 *
 * Writes go to a temporary file that then replaces the real one, so a crash mid-write can't leave
 * half a file. A file that can't be read is set aside as `<name>.broken-<time>`, not overwritten.
 */
class CardStore(private val file: File, private val defaultProfile: Card = DEFAULT_PROFILE) {

  init {
    require(defaultProfile.isProfile && defaultProfile.content is CardContent.Link)
  }

  private val lock = Any()
  private val _cards = MutableStateFlow(load())
  val cards: StateFlow<List<Card>> = _cards.asStateFlow()

  val profile: Card get() = _cards.value.first()

  fun get(id: String): Card? = _cards.value.firstOrNull { it.id == id }

  fun add(title: String, content: CardContent): Card {
    val tidy = checked(content)
    val card = Card(UUID.randomUUID().toString(), title.trim().ifEmpty { CardRules.suggestedTitle(tidy) }, tidy)
    change { it.take(1) + card + it.drop(1) }
    return card
  }

  /** Replaces the card with [card]'s id. The profile stays the profile, and stays a link. */
  fun update(card: Card): Card {
    val existing = requireNotNull(get(card.id)) { "No card ${card.id}" }
    val tidy = checked(card.content)
    require(!existing.isProfile || tidy is CardContent.Link) { "The profile card is a link" }
    val updated = card.copy(
      title = card.title.trim().ifEmpty { CardRules.suggestedTitle(tidy) },
      content = tidy,
      isProfile = existing.isProfile,
    )
    change { list -> list.map { if (it.id == card.id) updated else it } }
    return updated
  }

  /** Deletes a card. The profile card can't be deleted; asking does nothing. */
  fun delete(id: String) {
    change { list -> list.filterNot { it.id == id && !it.isProfile } }
  }

  /**
   * Puts a deleted [card] back at [index] in the list (after the profile, whatever the index), for
   * undo. A card that is already there, or the profile, is left as it is.
   */
  fun restore(card: Card, index: Int) {
    if (card.isProfile) return
    change { list -> if (list.any { it.id == card.id }) list else list.toMutableList().apply { add(index.coerceIn(1, size), card) } }
  }

  private fun checked(content: CardContent): CardContent {
    val tidy = CardRules.normalized(content)
    CardRules.problem(tidy)?.let { throw IllegalArgumentException(it) }
    return tidy
  }

  private fun change(edit: (List<Card>) -> List<Card>) = synchronized(lock) {
    val next = withProfile(edit(_cards.value))
    save(next)
    _cards.value = next
  }

  /** Exactly one profile card, first. */
  private fun withProfile(cards: List<Card>): List<Card> {
    val profile = cards.firstOrNull { it.isProfile && it.content is CardContent.Link } ?: defaultProfile
    return listOf(profile) + cards.filter { !it.isProfile }
  }

  private fun load(): List<Card> {
    if (!file.exists()) return listOf(defaultProfile)
    return try {
      withProfile(json.decodeFromString<CardFile>(file.readText()).cards)
    } catch (e: SerializationException) {
      setAside()
    } catch (e: IllegalArgumentException) {
      setAside()
    } catch (e: IOException) {
      setAside()
    }
  }

  private fun setAside(): List<Card> {
    file.renameTo(File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}"))
    return listOf(defaultProfile)
  }

  private fun save(cards: List<Card>) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(json.encodeToString(CardFile.serializer(), CardFile(cards = cards)))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  @Serializable
  private data class CardFile(val version: Int = 1, val cards: List<Card>)

  companion object {
    const val DEFAULT_PROFILE_URL = "https://www.instagram.com/chenyuulim/"

    val DEFAULT_PROFILE = Card(
      id = "profile",
      title = "Instagram",
      content = CardContent.Link(DEFAULT_PROFILE_URL),
      isProfile = true,
    )

    private val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      encodeDefaults = true
    }
  }
}
