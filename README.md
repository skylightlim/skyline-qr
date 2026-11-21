# Skyline QR

A link becomes a 3D city that folds flat into its own scannable QR code. Every building stands on a
QR module, so the skyline and the code are the same grid: tap the city and it settles into the code,
tap again and it rises back up.

**Try it in your browser: [skylightlim.github.io/skyline-qr](https://skylightlim.github.io/skyline-qr/)**, nothing to install.

Kotlin, Jetpack Compose, and WebGPU with WGSL shaders through
[androidx.webgpu](https://developer.android.com/jetpack/androidx/releases/webgpu) (alpha). Where
WebGPU cannot start, a flat 2D code takes its place, so the link always scans.

> Early work in progress. See [docs/DESIGN.md](docs/DESIGN.md) for where this is going.

## Get it

The website is at [skylightlim.github.io/skyline-qr](https://skylightlim.github.io/skyline-qr/).
It draws the city in any browser with WebGPU (Chrome, Edge, and recent Safari and Firefox); other
browsers show the flat code. The demo app's APK is on the
[Releases](https://github.com/skylightlim/skyline-qr/releases) page.
The library comes through [JitPack](https://jitpack.io/#skylightlim/skyline-qr):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }
// build.gradle.kts
implementation("com.github.skylightlim.skyline-qr:skylineqr-compose:v1.0.0")
```

The app needs Android 8.0+ and a phone with Vulkan.

## Modules

| Module | What it is |
| --- | --- |
| `skylineqr-core` | Pure Kotlin. Link parsing, identity, the QR matrix and the city planner. |
| `skylineqr-compose` | The renderer and the `SkylineQrCode` composable. Brings the core along. |
| `skylineqr-nfc` | Tap to share: the phone as an NFC tag carrying a saved card, tag reading and writing, and a QR scanner. See [docs/NFC.md](docs/NFC.md). |
| `app` | The demo app. |
| `skylineqr-web` | The website: the demo in a browser, with Kotlin/Wasm and WebGPU. |

```kotlin
SkylineQrCode(
  url = "https://example.com",
  modifier = Modifier.fillMaxSize(),
  city = CityPreset.KUALA_LUMPUR,  // the default: three landmarks and the monorail; or GENERIC
  time = TimeOfDay.AUTO,      // follows the phone's clock; or DAWN, DAY, DUSK, NIGHT
  weather = Weather.CLEAR,    // or RAIN, HAZE, STORM
  longPress = LongPress.METEOR,  // or FIREWORKS
)
```

Tap to fold the city into its code and back. In the city, drag to turn it, pinch to zoom, and
long-press to bring a meteor down (it rebuilds itself) or to let off fireworks;
`state.resetCamera()` puts the camera back, `state.startTour()` flies it past every landmark
and folds the city into its code, the same every time, and `state.startTimelapse()` runs the city
from midday to night in fifteen seconds. Time and weather only light the city; they
never change it. `onEvent` reports lightning and meteors, so an app can add sound of its own; the
demo is silent.

The demo app adds a settings card (the tour and the timelapse, what a long press does, the clouds,
the sign board's messages, the licences) and tap to share over NFC. It has no share links (removed 2026-11-09).

The same link always builds the same city. By default every page on one site shares a city while
each QR still points at its own page; pass `identityScope = IdentityScope.URL` to give every page
its own. A city you save should record `GeneratorVersion.CURRENT` and pass it back as
`generatorVersion`, so it never changes when the generator improves.

## Website

`skylineqr-web` is the demo in a browser, live at [skylightlim.github.io/skyline-qr](https://skylightlim.github.io/skyline-qr/): the
same link box, city, sky, weather, tour and timelapse. `.github/workflows/pages.yml` publishes it on
GitHub Pages on every push to main. It is Kotlin/Wasm, so the
core plans exactly the city the phone does (`CrossPlatformCityTest` checks it on the JVM and on
Wasm), and the renderer draws it with the browser's WebGPU and the app's own WGSL. Browsers without
WebGPU get the flat code on a 2D canvas, which still scans. Tap to share stays in the app: browsers
can't act as an NFC tag.

To work on it:

```bash
./gradlew :skylineqr-web:wasmJsBrowserDevelopmentRun   # a dev server with the site
./gradlew :skylineqr-web:wasmJsBrowserDistribution     # the site, in skylineqr-web/build/dist/wasmJs/productionExecutable
```

## Building

```bash
./gradlew test allTests assembleDebug
./gradlew :skylineqr-compose:connectedDebugAndroidTest   # on a connected phone
```

The device tests render real WebGPU frames, read them back and scan them with ZXing. Needs JDK 17+
and the Android SDK (compileSdk 37 for the app, 36 for the libraries). minSdk is 26.

## Credits

Inspired by [Every QR Code](https://github.com/AlbertAZ1992/every-qrcode) (MIT; its link parsing and
versioning are adapted here), Enzo Manuel Mangano's
[Cherry Blossom QR Code](https://reactiive.io/demos/cherry-blossom-qrcode) (idea only) and
[ICQR Magic Tree](https://tree.icqr.com). Details in [NOTICE](NOTICE).

## License

[MIT](LICENSE)
