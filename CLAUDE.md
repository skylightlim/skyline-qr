# SkylineQR

Read `docs/DESIGN.md` before changing behaviour; it is the agreed contract. `docs/NFC.md` covers
tap to share (`skylineqr-nfc`). The city tour is edited in
`skylineqr-compose/src/main/kotlin/com/skylightlim/skylineqr/render/tour/TourScript.kt`; the
README.md beside it is all the context that needs.

## Build and test

- `./gradlew test allTests lint assembleDebug` runs every unit test (the core's on the JVM and on
  Wasm), lint and the demo build.
  `FlatColoursTest` scans hundreds of codes and takes about ten minutes; while iterating, run the
  other suites with `--tests` and leave it for the end.
- Core logic lives in `skylineqr-core` (Kotlin Multiplatform: JVM and Wasm, sources in
  `src/commonMain`) so it tests fast without a device and the website runs it too. No JVM APIs in
  `commonMain`; platform needs go through `core/Platform.kt`.
- The website is `skylineqr-web` (Kotlin/Wasm and the browser's WebGPU; see DESIGN.md, Website).
  `./gradlew :skylineqr-web:wasmJsBrowserDevelopmentRun` serves it. It compiles the renderer's
  platform-free files straight from skylineqr-compose, so they must stay free of Android and JVM
  APIs; `WebRenderer.kt` and `gpu.js` mirror `CityRenderer.kt`, so keep them in step. Headless
  Chromium here shows WebGPU canvases blank: read frames back with copyTextureToBuffer instead.
- WGSL errors surface on the device as a `ValidationException` in logcat (tag `SkylineQR`) and the
  view falls back to the 2D code. WGSL reserves some ordinary words (`meta`, `from`). Check shader
  edits on the desktop first: compile `Wgsl.SOURCE` and `Wgsl.POST` with wgpu-py (naga), filling in
  the `${…}` templates.
- Without the phone, `tools/preview.py` draws the frames `FrameDumpTest` writes (set
  `SKYLINEQR_FRAMES` to a directory) with the app's own WGSL on a desktop GPU. It needs
  `pip install wgpu numpy pillow`. Naga, not Tint, compiles the WGSL there, so the phone still has
  the last word.
- `./gradlew :skylineqr-compose:connectedDebugAndroidTest` renders and scans real frames on the phone.
  Add `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep the snapshots it saves
  under `/sdcard/Android/data/com.skylightlim.skylineqr.compose.test/files/`; uninstall that test app
  afterwards.
- The app is Material 3 Expressive on `material3` 1.5.0-rc01, ahead of the BOM (1.4.0 keeps
  Expressive internal). That needs compileSdk 37 for the app and AGP 9.1 on Gradle 9.4.1. The app
  also lifts Compose UI and foundation to 1.13.0-rc01 (`androidxComposeTextFix`), because 1.12
  drops the keyboard between text fields; remove that once material3 pulls in 1.13.
- Release builds are signed with `~/.android/skylineqr-release.jks`; its passwords are in
  `~/.android/skylineqr-release.properties`. Neither is in the repo, and losing them means a new
  app identity. Release and debug builds have different signatures, so switching between them on
  the phone needs `adb uninstall com.skylightlim.skylineqr` first.

## The phone

- Check a change on the user's Pixel 9 Pro (`adb install -r app/build/outputs/apk/debug/app-debug.apk`),
  never in an emulator. If it is not connected, say so. Pass `-s` when more than one device shows.
- Checking tap to share takes two phones held together (the 9 Pro shares, the Pixel 6a reads), so
  ask the user to hold them; never log card contents.

## Rules

- **No code from Enzo Manuel Mangano's demos repo** (Cherry Blossom QR). Its licence forbids
  redistributing or porting it. Ideas are fine; code, constants and shader text are not.
- every-qrcode is MIT: port freely, but note what was adapted in `NOTICE`.
- Determinism: the planner uses `FdLibm` (StrictMath's bits, in Kotlin) for transcendental
  functions and integer hashes, never `Math`/`kotlin.math` or `Random`, so the same link builds the
  same city on the JVM, on Android and in the browser (`CrossPlatformCityTest`).
- `GeneratorFingerprintTest` guards generator version 1. v1.0.0 (2026-11-20) was the first public
  release, so version 1 is frozen: a deliberate planner change (districts, inks, heights) adds a
  new generator version and leaves version 1 and its hashes alone.
- Commits are authored as `skylightlim <chenyulim105@gmail.com>`.
