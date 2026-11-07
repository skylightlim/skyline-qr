# Tap to share (skylineqr-nfc) — design

Tap-to-share and NFC tag writing for SkylineQR. Built first as a separate project
(`~/Projects/skylineqr-nfc`) and merged here on 6 Nov 2026; on 7 Nov that folder was folded in
and trashed, its history kept as `refs/archive/skylineqr-nfc`, its bare demo app dropped. The
library is the `skylineqr-nfc` module, and its screens are the app's card popup (`app/…/ui/cards`). The goal: tap
someone's phone and their phone opens your Instagram, or a Wi-Fi network, contact or note you saved.

Decided on 6 Nov 2026, after looking at how Tap Tap Tap (com.thangman22.taptaptap) behaves. Nothing
here is ported from that app: everything is written from the public specifications listed below.

## What it does

- **Cards.** A list of saved cards. Your profile card (a link to your Instagram) is always first;
  it can be edited but not deleted, and stays a link. Other cards are links, text, contacts (name,
  phone, email, organisation, website), Wi-Fi networks (Open, WPA/WPA2, WPA3) or payment codes.
  A payment card (2026-11-07) holds an EMVCo payment QR, such as a DuitNow one, exactly as it was
  scanned or read: a scanned code or a tag's text whose fields and CRC-16 check out becomes one
  (`PaymentQr`, a copy of skylineqr-core's `PaymentCode` so this library needs nothing from that
  one), titled "DuitNow" for a Malaysian code. It can't be typed, it can't be edited (the popup
  shows who it pays in place of the fields), and a damaged one is refused. It is offered as a city,
  but its save gives no "Share by tap", since payment apps don't take a code by tap. Kept in one
  JSON file in the app's private storage.
- **Share by tap.** The phone acts as an NFC tag carrying one card. The other phone needs no app:
  Android and iPhone read it as a tag and open the link. Android also offers to join a Wi-Fi
  network or save a contact; iPhones only act on links (use a QR code for the rest).
- **Read.** Only after pressing NFC in the card popup: what a tag or another phone holds fills in
  a new card. Access, transport and bank cards show "No shareable content" and their chip
  type; nothing is ever copied (Android couldn't act as those cards anyway).
- **Write.** Any card onto a tag (NTAG215 in practice), in two taps: the first shows what's on the
  tag, the second replaces it. Blank tags are formatted. The tag is read back to check. Tags are
  never made read-only. (In the library only since 2026-11-07: the app has no way to it now.)
- **Scan.** QR codes with the camera (ML Kit's bundled model: offline, no Play services). The code
  opens as a pre-filled card to check before saving.
- **The city's link is shared all the time** (2026-11-07, at the user's request): whatever the
  picker shows is handed over by tap with no time limit and through every read, while the app's
  process lives and the phone is unlocked; it follows the picker, pauses while the popup reads a
  tag, and starts once NFC is switched on. `ShareSession.shareAlways` holds it as the standing
  card.
- **Start a one-off share** from the snackbar right after adding a card, or the profile from the
  "Share profile" Quick Settings tile. It goes ahead of the city's link, keeps the old rules (a
  second after the first read, or two minutes idle) and hands back to the link when it ends. The Android Share menu opens links and text in the card popup.
- **Not here:** Wear OS, widgets, launcher shortcuts.

## Rules

- A share is answered only while the phone is unlocked (`requireDeviceUnlock="true"`). The tile asks
  to unlock first.
- A share ends 1.3 s after the first successful read (a setting, on by default), after 2 minutes
  without a read, or when stopped. State is in memory only.
- Read, share and write are separate modes. Reading turns the phone into a reader, and Android turns
  card emulation off while it reads, so starting to read ends a share.
- Only the system's NFC service can reach the tag service (`BIND_NFC_SERVICE`). No other app can
  start a share: there is no exported way in.
- Card contents are never logged.

## How it works

```
skylineqr-nfc/                    the library (merge this into SkylineQR)
  ndef/      Ndef.kt              NDEF records and messages as bytes          NFC Forum NDEF 1.0
             Records.kt           URI, Text and Smart Poster records          NFC Forum URI/Text/SP RTDs
  card/      Card.kt              Card, CardContent (link/text/contact/wifi), Draft
             CardCodec.kt         cards ⇄ NDEF, cards → QR text, scanned/shared text → drafts
             CardRules.kt         validation, link tidying, titles
             CardStore.kt         the JSON file, profile-first, atomic writes
             WifiFormats.kt       WSC credential (NFC) and WIFI: strings (QR)  Wi-Fi Simple Configuration 2.0
             ContactFormats.kt    vCard 3.0 and MECARD                         RFC 2426 / RFC 2425
  tag/       Type4TagEmulator.kt  the emulated tag's APDU handling             NFC Forum Type 4 Tag 2.0, ISO 7816-4
             NdefTlv.kt           finding the NDEF TLV in NTAG memory          NFC Forum Type 2 Tag 1.0
  share/     ShareSession.kt      what is shared, timers, the vibration on read
             TagEmulationService  HostApduService → Type4TagEmulator
             ShareProfileTile…    the Quick Settings tile
  read/      TagInspector.kt      what a tapped tag holds; chip names (NTAG via GET_VERSION, SAK)
  write/     TagWriteSession.kt   the two-tap write with read-back
  scan/      QrScanner.kt         CameraX + ML Kit
  Nfc.kt                          NfcState, Cards (the store), NfcModes (reader vs tag per activity)
app/…/ui/cards/                   the app's part: CardsViewModel (the demo's flows), CardEditor
                                  (the card popup, reading a tag inside it), QrScan (the
                                  camera and its viewfinder, inside the popup too)
```

Everything under `ndef/`, `card/` and `tag/` is plain Kotlin and tested on the JVM
(`./gradlew :skylineqr-nfc:testDebugUnitTest`). The tests play a phone's reader against the
emulated tag, check the bytes of each format against the specs, and cover the bugs found in Tap Tap
Tap: links over 254 bytes, WEP/WPA3 security codes, `;` and `:` inside Wi-Fi names and passwords.

### The emulated tag

The reader selects the NDEF tag application (AID `D2760000850101`), reads the capability container
(file E103: mapping version 2.0, MLe 255, NDEF file E104, read free, write never), then reads the
NDEF file: two length bytes and the message. The message is fixed when the application is selected,
so one tap always reads one consistent message. When nothing is shared the tag doesn't answer to its
AID, so the other phone sees no tag. A read that reaches the file's last byte counts as a successful
tap (once per selection, and reads within 1 s count once).

### Wi-Fi

NFC uses the Wi-Fi Alliance's WSC credential (`application/vnd.wfa.wsc`): SSID, auth type, encryption
type, key, MAC FF:FF:FF:FF:FF:FF, plus the WSC 2.0 version extension. WPA/WPA2 goes out as WPA2-PSK
with AES. The format predates WPA3, so WPA3 goes out as WPA2-PSK too: routers in WPA2/WPA3
transition mode accept it, WPA3-only routers may not, and the editor says so. QR text keeps WPA3 as
`T:SAE`, as Android writes it. WEP isn't offered.

## Two apps, one tag address

Every app that emulates an NDEF tag registers the same AID. With Tap Tap Tap still installed (kept
on purpose), Android has two candidates: while SkylineQR is in front, `NfcModes` makes it the
preferred service, so in-app shares always work. A share started from the tile with the app closed
may make Android ask "which app?" on the sharing phone, and that first tap fails. The old demo app
(`com.skylightlim.skylineqr.nfcdemo`) is a third candidate wherever it is still installed, so
uninstall it from the phones (its cards stay in it; SkylineQR keeps its own).

## In the app (Material 3 Expressive)

2026-11-07: the cards screen is gone, at the user's request. What is left is the card popup over
the city, the share it can start, and the city's card picker, which lists the link cards. Other
cards stay in their file (and the profile is what the tile shares), but the app no longer lists
them; link cards can be removed from the picker, but no card can be shared again later, written to
a tag or edited from it, and non-link cards can't be removed, and Stop after the first read
keeps whatever it was last set to. The library still has all of that, for when a way back is
wanted.

- The city's card picker, where its link field was, chooses which saved link card the city shows
  (the profile first), and the payment cards. Text, Wi-Fi and contact cards can't be cities, so it leaves them out. Each
  card but the profile has a trash button there; Undo in the snackbar brings it back
  (`CardStore.restore`).
- The city's new-card button (+), in its row of choices right under the share button and as
  wide, opens the card popup (after Tap Tap Tap's, without its colours): a card over the city in
  the panel's colours, not a page of its own. "New card" with two tonal buttons beside it, NFC and
  QR, to fill it in from a tag or another phone or from a QR code; what the card holds as a
  bar with a pill in Tap Tap Tap's order (Link, Text, Wi-Fi, Contact), like the time of day's; its fields (the
  title with no placeholder, each single-line field's keyboard action Next); the Wi-Fi security
  as another pill bar; Cancel and Add. Once a tag or a code has filled the card in, a line under
  "New card" says where from. (A line saying what NFC and QR are for stood there before, from an
  Apple-design pass; it was removed at the user's request on 2026-11-07.) Both buttons' icons pop
  under the finger. Turning the reader on and off runs on a thread of its own in `NfcModes`: on
  the main thread `enableReaderMode` held up the frame as NFC was let go, so its icon never moved
  as QR's did. Add
  buzzes when the card is saved; when something is missing it buzzes differently and shakes its
  head on a spring, and the problem rises in under the fields. The form never clips: a clip to an
  animating height had cut the bottom field off. A tap outside does not close it, so typing is not lost to a
  stray touch. The keyboard stays up between fields: Compose 1.12 hid it and showed it again on
  every change of field (issuetracker.google.com/530704636, a 1.12.0-beta01 regression), so the
  app lifts Compose UI and foundation to 1.13.0-rc01, which has the fix. On the 6a, switching
  fields now sends no hide at all (ImeTracker in logcat).
- Reading a tag happens inside the popup: NFC stays lit, and where the form was, an illustration
  in the theme's colours shows what to do, as Android's own settings do (2026-11-07, redrawn the
  same day at the user's request): the back of a Pixel in the middle of the drawing, its camera
  bar across the top and its keys down the side, and an NFC card with its coil of three turns and
  its chip springing up from the lower right to lie square on it; three waves ripple out one after
  another past both of the card's ends, and it draws away to come again, until something is
  tapped. The drawing is clipped to itself, so no wave
  reaches the words around it. A card's worth fills the form ("Filled in from the tag");
  anything else (empty, no shareable content, a card can't hold it) shows there instead, and the
  next tag tapped is read in turn. With NFC off it says so and offers to turn it on. Stop reading,
  NFC again or back return to the form, which keeps what was typed.
- QR scans inside the popup too (2026-11-07; it had been a full-screen page): QR stays lit, and
  the camera shows in a rounded square where the form was, under a viewfinder of four corner
  brackets only, in pure white lines. They spring in from the square's corners as it opens (the
  motion scheme's slow spatial spring), then breathe out a little and back once every three
  seconds while they look (2026-11-07, at the user's request: they had moved every 1.4 s), and,
  when a code is read, spring tighter round it with a confirming buzz for a
  third of a second before the form comes back filled in ("Filled in from the QR code"). Stop
  scanning, QR again or back return to the form. If the camera was refused, the square says so and
  offers to ask again, or, refused for good, to open the app's settings, and goes on once it is
  allowed there.
- Add saves the card, closes the popup and says so in a snackbar at the top of the panel, with
  Share by tap.
- While a card is being shared, the city's hint is a "Stop sharing “…”" button.
- The Share menu (links and text) opens the card popup.

## Testing on phones

The 9 Pro shares, the 6a reads (no iPhone available). Someone has to hold the phones and tags
together; the build, install and logs are scriptable.

Checked on the 6a on 6 Nov (one phone, nothing tapped together): the screens, the editor, a card's
code, Show as a city, and starting and stopping a share. On 7 Nov: the card popup, reading in it
(reader mode on and off), typing kept across a read, Add and its snackbar.

- [ ] Profile link: 9 Pro shares from the tile, 6a (unlocked, screen on) opens Instagram.
- [ ] A new card: Add, then Share by tap on the 9 Pro; the 6a opens it; Stop sharing in the hint.
- [ ] Auto-stop: the share ends about a second after the 6a reads it.
- [ ] Locked 9 Pro: the 6a reads nothing.
- [ ] Wi-Fi card: the 6a offers to join the network.
- [ ] Contact card: the 6a offers to save the contact.
- [ ] Text card: the 6a shows the text.
- [ ] Tile: "Share profile" starts and stops; on the lock screen it asks to unlock.
- [ ] NFC in the card popup on the 6a while the 9 Pro shares: the form fills in, Add saves it.
- [ ] Read a bank or student card in the popup: "No shareable content".
- [ ] Scan a Wi-Fi QR and a link QR: the right card opens.
- [ ] Share menu: share a link from Chrome to SkylineQR.
