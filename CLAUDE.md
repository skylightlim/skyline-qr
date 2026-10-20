# SkylineQR

Read `docs/DESIGN.md` before changing behaviour; it is the agreed contract.

## Build and test

- `./gradlew test assembleDebug` runs every unit test and builds the demo.
- Core logic lives in `skylineqr-core` (pure JVM) so it tests fast without a device.
- `./gradlew :skylineqr-compose:connectedDebugAndroidTest` renders and scans real frames on the phone.
  Add `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep the snapshots it saves
  under `/sdcard/Android/data/com.skylightlim.skylineqr.compose.test/files/`; uninstall that test app
  afterwards.
- WGSL errors surface on the device as a `ValidationException` in logcat (tag `SkylineQR`) and the
  view falls back to the 2D code. Note WGSL reserves some ordinary words (`meta` is one).
- Release builds are signed with `~/.android/skylineqr-release.jks`; its passwords are in
  `~/.android/skylineqr-release.properties`. Neither is in the repo, and losing them means a new
  app identity and new App Link fingerprints. Release and debug builds have different signatures,
  so switching between them on the phone needs `adb uninstall com.skylightlim.skylineqr` first.
- `site/` is the share-link site; it is published from the `skylightlim/skylightlim.github.io`
  repo. `node site/city/city.test.cjs` checks it computes the same city codes as the app.
- Check a change on the user's Pixel 6a (`adb install -r app/build/outputs/apk/debug/app-debug.apk`),
  never in an emulator. If the phone is not connected, say so.

## Rules

- **No code from Enzo Manuel Mangano's demos repo** (Cherry Blossom QR). Its licence forbids
  redistributing or porting it. Ideas are fine; code, constants and shader text are not.
- every-qrcode is MIT: port freely, but note what was adapted in `NOTICE`.
- Determinism: the planner uses `StrictMath` for transcendental functions and integer hashes, never
  `Math`/`kotlin.math` or `Random`, so the same link builds the same city on the JVM and on Android.
- `GeneratorFingerprintTest` guards generator version 1. Until the first public release it may be
  updated for a deliberate planner change; after that, add a new generator version instead.
- Commits are authored as `skylightlim <chenyulim105@gmail.com>`.
