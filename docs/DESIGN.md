# SkylineQR design

Settled on 2026-10-20. This is the contract the milestones build toward; change it here first.

## The idea

A link becomes a 3D city that folds flat into the link's own scannable QR code. Every building
stands on a QR module, so the city and the code are the same grid: dark modules are buildings,
light modules are streets and plazas. Tap to switch between the two.

The same link, identity scope and generator version always build the same city, on every device,
with no server involved.

## Product

- A portfolio demo and an open-source MIT library.
- Modules:
  - `skylineqr-core`: pure Kotlin/JVM. Link parsing, identity, QR matrix, city planner.
  - `skylineqr-compose`: Android library. WebGPU renderer, `SkylineQrCode` composable, 2D fallback.
  - `app`: the demo. Control panel, sound, sharing, App Links.
- Distribution: public GitHub repo `skylightlim/skyline-qr`, built by JitPack. Maven Central once
  androidx.webgpu leaves alpha.

## Code provenance

- Cherry Blossom (Enzo Manuel Mangano) is **ideas only**. Its licence forbids redistributing or
  porting its code, so nothing is copied or translated from it.
- every-qrcode is MIT. Code may be ported with its notice kept in `NOTICE`.

## Rendering

- androidx.webgpu (currently 1.0.0-alpha06, Dawn over Vulkan) with WGSL shaders. The GLES 3.0
  renderer is removed once the WebGPU renderer reaches parity.
- If WebGPU fails to start, a flat 2D QR drawn with Compose takes its place.
- Perspective camera (45° field of view) that narrows to near-orthographic for the flat view.
- 4× MSAA. 60 fps on the Pixel 6a: continuous while traffic moves, paused in the background, redraw
  on change only when idle in the flat view.

## QR and identity

- Error correction M, at most symbol version 6 (41 × 41). Longer links get a friendly
  "link too long" error rather than a code too dense to scan.
- The payload is the typed link, only normalized the way a browser would (scheme, lowercase host,
  default port, dot segments, percent-encoding). It is never rewritten in a way that changes where
  a scan lands.
- Identity scope: `SITE` (default; every page on a host shares a city, `www.` ignored) or `URL`
  (the page without fragment or tracking parameters).
- `generatorVersion` pins the recipe. Version 1 is a draft until the first public release; from
  then on its golden fingerprints are frozen and visual changes add a new version.
- Presentation (time, weather, city preset, camera) never enters the identity.
- The flat view must scan in every time and weather combination, proven by an on-device test that
  decodes a rendered snapshot. Scanning the 3D view is a bonus, not a requirement.
- Flat ink (2026-10-20 revision, `FlatColours.kt`): each dark module keeps its tower's own colour
  in the hour's light, and every ink has exactly the same brightness, 0.29, under ZXing's
  (R + 2G + B) / 4, so to ZXing the code is one ink on paper. Inside a solid dark area at a phone
  screen's module size, HybridBinarizer's threshold sits just above the darkest ink nearby, and any
  lighter ink beside it reads as paper, however small the difference (found at 29 and 42 px a
  module). The inks differ only in hue, within 0.02 under the camera's BT.601 and 0.07 under
  Rec. 709. The function patterns (finders, timing, alignment, format) all take the palette's most
  colourful family, because a scanner that loses one finder loses the code. A JVM test scans every
  recipe, fixed hour and two-hourly clock time, and weather, from 3.4 to 42 px a module, under both
  ZXing's formula and BT.601.

## The city

- Finder patterns: a podium ring with a squat core block, so the corners stay readable and leave
  the skyline's height to the middle.
- Generic city colours are seeded per site: one of eight curated recipes of four materials plus a
  traffic paint (`Materials.kt`). The KL preset uses fixed materials so its landmarks stay
  recognisable.
- Shadows are cast live from a 2048² sun shadow map, so they follow the clock; the planner no
  longer bakes any light into the city.
- The app always builds Kuala Lumpur; there is no city switch (decided 2026-10-20). The library
  keeps a `CityPreset` with KL as the default and the generic city for other apps; it is carried
  in share links and never part of identity.
- Time: Auto (local clock, equator hours: sunrise about 07:10, sunset about 19:20, blended) or a
  fixed dawn, day, dusk or night. The app offers only Now (Auto), Day and Night (decided
  2026-10-20): Now already passes through dawn and dusk. The sun rises behind the city and sets in front of the camera,
  so dusk lights the facades you see; the moon hands over across two hours of twilight.
- Seen from above, most of the screen is the air below the horizon, so that backdrop is a deeper
  blend of horizon and zenith, not the raw horizon colour. The status bar icons follow its
  brightness.
- Weather: clear, rain, haze or thunderstorm. Manual only, no network. All weather fades out in the
  flat view. The app offers Clear, Storm and Haze (decided 2026-10-20); a storm already rains.
- Storm (2026-10-20 revision): a strike in nearly every 3.4 s spell, flickering three times and
  lighting the whole city, the towers round the bolt most. Each strike draws a jagged white-blue
  bolt from high above onto one of the tallest towers on the camera's side. About half of them,
  while no other is rebuilding, destroy it and any tower within 1.2 modules: a fireball bursts off
  the roof, burning fragments fly and shrink away, the towers crumble to smouldering stubs with
  smoke rising, and grow back after 3.2 s. All of it runs on the seconds since the strike, like
  the meteor; folding to the code cancels it.
- Haze (2026-10-20 revision): thick. It deepens with distance and drifts across the city in banks
  that grow denser with height, so they swallow stretches of the skyline, the landmarks' upper
  floors first, and let them go again.
- Traffic (2026-10-20 revision): cars loop through the streets keeping left, as in Malaysia, on
  routes from left-hand wall-following over the street grid with dead ends pruned, so nobody ever
  turns round; corners are rounded and headings turn smoothly. A bus runs a ring road one module
  outside the code. A helicopter (cabin, glass nose, tail boom, fin, skids, spinning rotors)
  patrols a seeded ellipse among the towers, below the landmarks' tops and clear of them, slowing
  almost to a hover once a lap; a plane (fuselage, wings, engines, tail) circles wide above.

## Kuala Lumpur preset

- Three landmarks, named generically in code and UI: twin towers, telecom tower, supertall.
- Placed by the seed inside the grid, never in the four corners, on all-dark module footprints:
  supertall 3 × 3, twin towers two 2 × 2 blocks one module apart (the skybridge spans the street
  between), telecom tower 1 × 1 or 2 × 2. Smaller footprints are tried when none fits. They lean
  toward the middle and stand at least about 5 modules apart.
- True relative heights (supertall > twin towers > telecom tower); ordinary towers capped at about
  60 % of the twin towers.
- Built in code from boxes that can turn about their axis and taper toward the top (two extra
  instance floats, yaw and taper). A square plus the same square turned 45° gives the twin towers'
  eight-pointed star plan; stacked tapering boxes give setbacks, crowns and spires. They exist only
  in the 3D view and shrink away in the flat view; the dark modules under them stay two-floor
  towers, so the code is unchanged.
- Supertall (2026-10-20 revision, after photographs and Henryer Robinson's CC BY "PNB 118" model on
  Sketchfab, used as a visual reference only): a slender prism of deep blue glass about seven times
  as tall as it is wide, narrowing only a little, in three segments that fold at two bands. In
  each, a 45° square inside the square pushes its corners out through the faces toward one end and
  lies flush at the other, alternately, so the faces carry long triangles meeting in diamonds. The
  crown slopes up in six steps toward one side, narrowing to about half its width, and the spire, a
  fifth of the height, rises from that high edge, thicker over its first third.
- Telecom tower (2026-10-20 revision, after a photograph): a white shaft flaring at its foot, a
  collar, a copper bowl that flares like a trumpet, four tiers of dark windows under white ledges
  widening upward like a spinning top, a dark band and the white observation deck (its roof at
  335 m of 421), a cylinder with two discs, and a lattice mast in red and white bands thinning to a
  needle. The head is drawn at 8 % of the tower's height, a little larger than life. Round sections
  are four squares turned a sixteenth of a turn apart; the tower and the hill have no window panes.
- The KL landmark palette has eight colours: steel, glass, concrete, the hill's green, copper, red,
  smoked glass and the supertall's blue glass.
- Heights scale with the code: twin towers at 0.42 × modules (at least 10), the others in true
  proportion.
- A monorail that follows the road: it starts on the longest street and turns onto the longest
  street at each end, up to twice, with curved beam at every bend, pillars on the street below,
  and a station at each end. A three-car train bends through the curves and shuttles between the
  stations, easing in and out and waiting at each.

## Interaction

- The morph is quick, as quick as the ICQR tree, but the blocks land late (2026-10-20 revision):
  progress runs at one steady pace for 0.9 s; the camera is overhead by 0.5 s on a sine ease; the
  fold starts after 70 ms and runs to the end, each block easing through its own 45 % of it,
  vehicles and beacons first, then the paving, the towers by height with a scattered few late, and
  the landmarks last. So blocks keep dropping into their modules after the camera has arrived.
  Raising reverses it, so the skyline rises first. A reversal mid-way takes only as long as the
  way back.
- The flat code carries the city and the hour: every dark module is its tower's colour in the
  hour's light (near white by day, rose at dawn, amber at dusk, moonlight blue at night; rain and
  storm grey it, haze warms it), and its light modules are a pale tint of the sky, each a shade
  lighter or darker, on a rounded card one module wider than the plate, against a deeper colour of
  the same sky. Paper stays at luma 0.8 or more.
- Tap: flatten or raise. Drag: orbit (3D only, limited yaw and elevation). Pinch: zoom, clamped.
  Double-tap: reset the camera. The flat view locks the camera square to the screen.
- Long-press (3D only): a meteor strikes where you press. Streak, flash, shake and rumble; towers
  within about 6 modules are thrown; debris fades in the air and never piles on the code; a glowing
  crater; the city rebuilds inward from the rim. About 5 seconds. Landmarks are destroyed and
  rebuilt like everything else.
- Haptics on the morph, the long-press and the impact.
- Android's "Remove animations" setting gives an instant morph, no meteor and calmer rain. TalkBack
  labels describe the code and the double-tap action.

## Demo app

- Controls after the ICQR tree's (2026-10-20 revision): no sheet, just frosted tiles that float over
  the city, tinted by the sky. At the top the name and the city's code, with licences behind (i).
  At the bottom a hint pill (what a tap does next, or what is wrong with the link), the link field
  with the share button beside it, then Now / Day / Night as one segmented tile, a weather tile
  that opens its four choices, and the sound toggle. Icons are ported from Lucide (ISC), not the
  Material icon set, which an unshrunk release would carry whole.
- No shadow under the floating plate (removed 2026-10-20).
- Sound: CC0 loops (day traffic, night insects, rain) crossfaded by time and weather, and
  one-shots (thunder a random beat after each flash, the meteor's whoosh and impact). Muted by
  default; muted or in the background, nothing plays. Nothing religious. The library reports the
  moments through `SkylineQrCode(onEvent = …)` and plays no sound itself.
- Share image: the flat code, or a 3D postcard with the code inset in a corner.
- Share links: compact base64url App Links on `skylightlim.github.io` (the user-site repo serves
  `/.well-known/assetlinks.json`). The page shows a 2D QR, the city's ID code and a link to the
  GitHub Releases APK.
- The default link is the repo, `https://github.com/skylightlim/skyline-qr`.

## Library API (target)

```kotlin
SkylineQrCode(
  url, modifier,
  city = CityPreset.Generic, time = TimeOfDay.Auto, weather = Weather.Clear,
  identityScope = IdentityScope.SITE, generatorVersion = GeneratorVersion.CURRENT,
  state = rememberSkylineQrState(),   // flat/3D, snapshot(), resetCamera()
  haptics = true,
)
```

## Milestones

Each ends with an install on the Pixel 6a, never an emulator.

- **M0 Foundation** (done 2026-10-20): git, provenance audit, module split, EC M with the version 6 cap, identity
  scopes and generator version, MIT and NOTICE, this document, CI.
- **M1 WebGPU parity** (done 2026-10-20): `render/` on androidx.webgpu and WGSL, 2D fallback,
  GLES removed, on-device scan and frame-rate tests.
- **M2 City** (done 2026-10-20): finder podiums, seeded materials, time and weather, a live sun
  shadow map, rain, haze, lightning, window panes, gestures, haptics, calm mode, TalkBack.
- **M3 KL** (done 2026-10-20): landmarks, monorail, KL materials.
- **M4 Meteor** (done 2026-10-20): picked by a ray through the real camera, a 0.9 s fall, then
  debris on closed-form parabolas in the vertex shader (no simulation state), a charred crater with
  embers, and a rim-first rebuild; a haptic thud at the moment of impact.
- **M5 Sound** (done 2026-10-20): six CC0 recordings from OpenGameArt (direct downloads, so no
  account was needed), crossfaded loops plus SoundPool one-shots, driven by `onEvent`.
- **M6 Release**: share links, the Pages site, JitPack `v1.0.0`, GitHub Releases APK; generator
  version 1 frozen. Prepared 2026-10-20 and verified locally (share-link format, app links, page,
  postcard, signed release APK, JitPack POMs, licences screen); the public steps (repo public,
  `skylightlim.github.io`, tag and release) wait for the go-ahead.

No fixed deadline. If one appears, M5 and M6 slip first.
