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
  - `app`: the demo. Control panel, share links, App Links.
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
- Light (2026-10-21, softened 2026-10-22): the city is drawn into a 4× MSAA RGBA16Float target,
  so whatever shines can be brighter than white, then a bloom chain (a bright pass over 1.05, six
  halving levels from half the screen, each added back with a tent filter; after Jimenez, SIGGRAPH
  2014) gives it a soft halo, and a composite rolls everything over 0.9 smoothly into the screen's
  white. In the flat code the composite passes the colours through exactly, so the code is
  untouched. Every light glows alike and gently, the way a lit sign does, on no tower in
  particular: lit panes (warm), beacons and the vehicles' lights, traffic lights, the billboard,
  the crystal fountain, the meteor and bolts. (The neon trims, neon panes, landmark edge lights and
  colour spill of 2026-10-21 were removed the next day.) `tools/preview.py` draws the same frames
  with the same WGSL on a desktop GPU.
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
- A payment code builds a city too (2026-11-07, at the user's request: his Touch 'n Go code).
  `PaymentCode` knows EMVCo's merchant-presented format, which Malaysia National QR (DuitNow) uses:
  fields from tag 00 to a CRC-16 in tag 63. `Link.parse` takes a whole code whose checksum
  matches as it is: its payload is the code exactly (only blank space around it is dropped), its
  identity the whole code (`pay:…`) in either scope, and `isPaymentCode` is set. Changing a single
  character breaks the checksum and the payment, so it is never rewritten. A DuitNow code is about
  125 characters of digits and capitals, which ZXing puts in alphanumeric mode, so it fits version 6
  at M. A payment code's city has no share link (`ShareLink.encode` refuses it, the user's call).
  Its flat code was not tried with a banking app (the user's call too).
- `generatorVersion` pins the recipe. Version 1 is a draft until the first public release; from
  then on its golden fingerprints are frozen and visual changes add a new version.
- Presentation (time, weather, city preset, camera) never enters the identity.
- The flat view must scan in every time and weather combination, proven by an on-device test that
  decodes a rendered snapshot. Scanning the 3D view is a bonus, not a requirement.
- Flat colours (2026-11-07 revision, `FlatColours.kt`, at the user's request: "look lively", after
  two many-coloured pictures of Kuala Lumpur; before, from 2026-11-01, one colour family after the
  ICQR tree): every part of the city has its own colour, as each building has in those pictures,
  and still no gradient (2026-10-31). Each district takes one of eight lively hues (Kuala Lumpur's
  gardens green, the mall magenta, the square purple, the twins teal, the supertall blue, the
  tower crimson; another city's districts spread round the wheel), and each dark module is a
  square of its district's hue in a full or a softer shade, half and half at random, so a
  district is a mosaic of its colour with ragged, interlocking borders and nothing drifting across
  it. About one module in ten takes the hue beside its own on the wheel instead (pink and violet
  appear only so). A finder is one ink whole, since its solid middle is the largest area with no
  paper in it. The hour and weather tint every ink by a fifth of their own ink. Every light module
  is a square of the hour's paper in one of four shades, like paving. What makes it scan: inside a
  solid dark area at a phone screen's module size, HybridBinarizer reads a lighter ink beside a
  darker one as paper once they are more than 24 levels apart (found at 29 and 42 px a module), and
  a block mostly of a darker ink pulls the threshold down sooner: 12 levels still failed at 42 px.
  So every ink is exactly 0.32 under ZXing's (R + 2G + B) / 4 and within 0.025 of 0.31 under the
  camera's BT.601 (12.75 levels at most between any two), and any two can touch. Those two
  brightnesses leave only a narrow plane of colours: crimson, pinks, magenta, purples, blue, teal
  and green fit, but orange and yellow are bright colours and come out brown, so they are left out;
  each hue is solved onto the plane by least squares, as close to itself as it can be. The paper's
  shades span under 0.04. A JVM test scans every recipe, fixed hour and two-hourly clock time, and
  weather, from 3.4 to 42 px a module, under both ZXing's formula and BT.601.

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
  so dusk lights the facades you see; the moon hands over across two hours of twilight. That is
  the light only. The sun's and the moon's discs in the sky (2026-11-07, at the user's request)
  stand as high as they really do in Now: `SunAndMoon` works out where they are over Kuala Lumpur
  at that instant (the Astronomical Almanac's low-precision formulas, checked against PyEphem to
  0.01 degrees for the sun and 0.4 for the moon), and the moon shows only when above the horizon
  and lit enough to see, pale by day. Day and Night keep a sun on a fixed path that rises at
  07:10 and sets at 19:20, and a moon high in the east-south-east after dark. The discs hang on an
  upright arc behind the city, square to the usual camera: on the horizon just over the far
  skyline, overhead near the top of the screen, rising on the screen's east side and setting on
  its west (`SkyParts.disc`).
- Seen from above, most of the screen is the air below the horizon, so that backdrop is a deeper
  blend of horizon and zenith, not the raw horizon colour. The status bar icons follow its
  brightness.
- Weather: clear, rain, haze or thunderstorm. Manual only, no network. All weather fades out in the
  flat view. The app offers Clear, Storm and Haze (decided 2026-10-20); a storm already rains.
- Storm (2026-10-20 revision): a strike in nearly every 3.4 s spell, flickering three times and
  lighting the whole city, the towers round the bolt most. Each strike draws a jagged white-blue
  bolt onto one of the tallest towers on the camera's side, out of the base of the storm cloud
  over it, which lights up from inside (2026-11-07, at the user's request: it had come from the
  empty sky above the deck). Lightning prefers a tower with a cloud close over it, and the deck is
  a jittered grid of 18 clouds so there are no wide gaps. About half of them,
  while no other is rebuilding, destroy it and any tower within 1.2 modules: a fireball bursts off
  the roof, burning fragments fly and shrink away, the towers crumble to smouldering stubs with
  smoke rising, and grow back after 3.2 s. All of it runs on the seconds since the strike, like
  the meteor; folding to the code cancels it.
- Haze (2026-10-20 revision): thick. It deepens with distance and drifts across the city in banks
  that grow denser with height, so they swallow stretches of the skyline, the landmarks' upper
  floors first, and let them go again. The banks ride the wind downwind. Since 2026-11-07 (at
  the user's request: no clouds as haze, and "some visual wind") it has no shapes of its own: out
  of clear air it rolls in from upwind over 4.5 s behind a thicker leading wall, hugging the
  ground so it runs ahead along the streets while the air above fills in behind, its edge
  billowing; and pale wind streaks blow through the city on its wind, each a thin rippling
  ribbon that grows in, crosses and thins away (drawn by the rain pipeline from instance
  `Wgsl.GUST_BASE`). Going, it thins away everywhere together. The sun shows through it as a dim
  orange disc.
- Traffic (2026-10-20 revision): cars loop through the streets keeping left, as in Malaysia, on
  routes from left-hand wall-following over the street grid with dead ends pruned, so nobody ever
  turns round; corners are rounded and headings turn smoothly. A bus runs a ring road one module
  outside the code. A helicopter (cabin, glass nose, tail boom, fin, skids, spinning rotors)
  patrols a seeded ellipse among the towers, below the landmarks' tops and clear of them, slowing
  almost to a hover once a lap; a plane (fuselage, wings, engines, tail) circles wide above.
- Traffic lights (2026-10-21), in every city: up to six junctions the cars drive through, crossings
  first, at least four modules apart and clear of the corners. Each approach a car uses has a pole
  and head on its left kerb, a little larger than life, drawn with the traffic. A junction lets one
  axis go at a time: green 3 s, amber 0.8 s, all red 0.6 s, then the other axis, from a seeded
  point in the cycle. Cars stop for them: cruising toward a line, a car looks at its light where it
  would start braking; green, it drives on; amber or red, it eases to a halt at the line, waits for
  green and eases away. The schedule is built lazily forward from time 0, so a car's place is still
  a pure function of time. The bus and the monorail have no lights.
- Billboard (2026-10-21): `SkylineQrCode(billboard = …)` gives the city's screen its messages (the
  app's: 马大牛逼, tarumt #1, i need argon); none, no screen. Kuala Lumpur's stands on the roof
  of the mall's east block, set back behind its facade. Android's fonts draw each message into a row of a single-channel texture, as
  large as fits the screen down to half size, else full size; one that fits holds 3.2 s, a longer
  one waits 0.9 s at its start, scrolls through at a screen width per 1.8 s and waits at its end.
  Then the screen turns to the next like a tri-vision board, twelve slats flipping in a wave. Each
  message lights in a neon colour of its own and glows after dark. Presentation only: never in the
  identity or share links. With animations off nothing scrolls or flips.

## Kuala Lumpur preset

- Three landmarks, named generically in code and UI: twin towers, telecom tower, supertall.
- Placed inside the grid, never in the four corners, on all-dark module footprints: supertall
  3 × 3, twin towers two 2 × 2 blocks one module apart (the skybridge spans the street between),
  telecom tower 1 × 1 or 2 × 2. Smaller footprints are taken when the larger ones stand too far
  from their place. They stand in a triangle as in the real city (2026-11-06 revision; before,
  they leaned toward the middle and from the camera often stood one behind another): the twin
  towers east of the telecom tower and the supertall south-south-west of it, each out on an arm of
  the cross the finder corners leave free, at least about 5 modules apart. From the camera's usual
  side (`CityPlanner.VIEW_YAW` and `VIEW_ELEVATION`, which the renderer's default camera uses)
  they read left to right supertall, telecom tower further back, twin towers, at least 1.5 modules
  apart across the screen, so none hides another. The planner tries the best two dozen places for
  the supertall and for the twins together and keeps the best three that do; a code with none
  takes them apart in any order, else just spaced out. The seed breaks ties.
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
- The finder corners (2026-10-21, reworked 2026-10-22), laid out like a map with north at the top
  of the code and named generically in code: the botanical garden and national monument at the top
  left, the mall on Bukit Bintang at the top right, the independence square at the bottom left.
  The garden, drawn larger than life so it reads across the city, is lawn with big rain trees,
  round trees, tall palms and hibiscus shrubs round the reflecting pool (the finder's gap) with a
  fountain at each corner, and on the island of the core the monument: bronze figures on a stepped
  granite plinth, the tallest raising the flag. The mall, after photographs of its 2024 facade,
  fills its finder three floors high: two blocks of champagne fins over white lattice with a band
  of glass, either side of a tall glass entrance under its canopy and lit name band; the crystal
  fountain (three rose-crystal bowls over a pool ringed by posts) on the plaza in front, the middle
  of its south side, which faces the city; the two blue residence towers behind on the roof (about
  40 % and 34 % of the twin towers); the billboard's screen on the east block's roof. The square is
  a lawn ring and a mown padang round the promenade, the flagpole in its middle, the old
  secretariat (brick, white bands, clock tower and copper onion domes) along the north side (it
  stands on the east of the real square; here it hides none of the lawn) and the club house along
  the west. Both flags wave: each is cut in eight pieces along its length that carry how far from
  the pole they hang, and the vertex shader runs a wave along the cloth, more toward the fly.
  Finder modules stand low (a lawn) under the garden and the square, and the mall's full height but
  for its plaza; the parts on top shrink away with the landmarks. The landmark palette has 32
  slots: the eight above, then leaf, lawn, water, bronze, brick, the flag's blue and yellow,
  granite, champagne (drawn with vertical fins), lattice (drawn in diamonds), the flag's red and
  white, and rose crystal.
- Bukit Bintang's crossing (2026-10-22): the junction with lights nearest the plaza in front of the
  mall that the monorail does not pass over and no landmark crowds becomes the crossing: dark
  asphalt on the junction and its arms, zebra stripes across every arm and along both diagonals
  (the scramble, people crossing every way at once). On its corner away from the camera a
  fast-food restaurant, two floors (its module's tower is lowered to fit): a glass shop front under
  a red facade with a yellow trim, and a pole sign on its roof, a red board with a yellow M of two
  arches in five by five pixels on both faces, turned to the camera's usual side.
- The finders' inner gaps are never streets: no car drives round inside a finder.
- A monorail that follows the road, in plain view (2026-11-06 revision; it used to take the
  longest street wherever it was, often at the back behind the landmarks): from a street it turns
  onto the longest street at each end, up to twice, with curved beam at every bend, pillars on the
  street below, and a station at each end. Only streets in view count: nothing between them and
  the camera on its usual side, no tower, landmark or the mall's towers, rises into the line of
  sight to the beam. Of the lines every such street starts, the longest wins, a module nearer the
  camera counting as half a module longer. A code with no line of six modules in view takes the
  longest street of all, as before. A three-car train bends through the curves and shuttles between the
  stations, easing in and out and waiting at each.

## Interaction

- The morph is quick and smooth, like the ICQR tree (2026-11-01 revision, measured from a screen
  recording of it on the Pixel 6a): 0.85 s. Once the colours have washed, only shapes move. The
  camera swings over the whole of it on one ease fitted to the tree's yaw: half way in 0.12 s, four
  fifths in 0.3 s, and the last few percent as a long soft tail. The fold starts at once and every
  block has landed by 60 %. The colours wash once, all together and early (by 40 % of the fold), so
  blocks have their final colours for most of their way down, with darker sides while they still
  stand. Traffic and beacons shrink away first, then the landmarks and the monorail, keeping their
  own colours. The towers widen until they touch by 35 % of the fold, so the code can be read while
  they stand, then sink together, a little scattered, easing in and settling softly (smootherstep).
- Raising from the code is its own choreography, not the fold played backwards (2026-11-06: that
  made the towers shoot up as the code's slabs and the landmarks pop in at the end). Over the same
  0.85 s, at one steady pace, with the camera leaving at once on the mirror of its ease: the city's
  colours come back and the towers narrow to their footprints by 30 %, while the landmarks'
  footprints spread out of the code. Everything grows up from the ground, the middle of the city
  a moment before the edges, and the higher a point stands the longer it takes (half the raise for
  the ground, all of it for the tallest), so the low city has settled while the tall towers still
  climb steadily, and a point higher up always stays above one lower down. Beacons, traffic and
  the lights come back over the last quarter. Leaving the code once its blocks are home takes the
  raise; a reversal mid-way keeps the way it is on and continues from where it is, without a jump.
- The flat code carries the city's districts, each in its own colour, and the hour and weather as
  a tint, in a mosaic of square modules on the hour's paper in four shades, the plate itself the paper, against a deeper colour of the same
  sky. Paper stays at luma 0.8 or more.
- Tap: flatten or raise, at once. Drag: orbit (3D only, limited yaw and elevation). Pinch: zoom.
  Released mid-turn, the city runs on at the finger's speed and slows within about half a second
  (each axis on its own; none with "Remove animations"); a touch catches it. The limits are soft
  (2026-11-06, after Apple's fluid-interface guidance): pushed past one, the camera follows at half
  speed and less and less, then springs back on release. No double-tap (removed 2026-11-06): it made every tap wait out the double-tap timeout,
  about 300 ms, before the morph began. The app puts the camera back with `resetCamera()`. The
  flat view locks the camera square to the screen.
- Long-press (3D only): a meteor strikes where you press. Streak, flash, shake and rumble; towers
  within about 6 modules are thrown; debris fades in the air and never piles on the code; a glowing
  crater; the city rebuilds inward from the rim. About 5 seconds. Landmarks are destroyed and
  rebuilt like everything else.
- Haptics on the morph, the long-press and the impact.
- Android's "Remove animations" setting gives an instant morph, no meteor and calmer rain. TalkBack
  labels describe the code and the double-tap action.

## Demo app

- Material 3 Expressive (2026-11-06, at the user's request; it replaced the 2026-10-20 frosted
  tiles after the ICQR tree's): `MaterialExpressiveTheme` with the expressive motion scheme, whose
  springs drive every shape morph, and the type scale's emphasized styles. Still no sheet: the
  controls float over the city on the theme's surface containers, which the sky tints (the scheme
  fills every colour role, so nothing falls back to the baseline purple). Nothing at the top
  (2026-11-07, at the user's request: the name, the city's code and the licences behind (i) went;
  the licences still ship in the APK as `assets/licenses.txt`). At the bottom the hint (a
  quiet label for what a tap does next; the link's problem in the error container, read out as it
  appears; while a card is shared by tap, a tonal "Stop sharing" button; once the camera has
  moved, a tonal "Put the camera back" button), changing with a fade
  while its size springs; the card picker (2026-11-07, at the user's request; it replaced the link
  field, so links are no longer typed on the city) with the share button beside it, the one control filled
  in the primary colour, square until pressed; then Now / Day / Night as one bar (2026-11-07, at
  the user's request; it had been a connected button group) cornered like the buttons beside it,
  with a pill in the secondary colour under the chosen time that moves whole to the one picked on
  the fast spatial spring, overshooting and bouncing back a little; at the bar's ends it can reach
  the edge but not leave it (2026-11-07: an edge-leading stretch was tried and turned down). The card popup's
  Link / Text / Wi-Fi / Contact and Wi-Fi security stay connected button groups, whose chosen
  colour is one highlight sliding on the default spatial spring, seen through each button's
  shape as it passes
  of toggle buttons with their labels alone (read out as radio buttons; with icons as well, "Now"
  and "Night" cut off at a larger font size), a weather button that opens an expressive menu,
  one group at least 168 dp wide, every row keeping room for the check, so it opens to the same
  side whichever weather is chosen (2026-11-07: M3 opens a menu from the anchor's start if it fits
  and from its end if not, and the check-sized change in width crossed that line, so the menu
  jumped left and right), with the same 4 dp round the chosen row on every side (2026-11-07: M3 pads a group 4 dp
  at the sides but 2 dp top and bottom, which read as a border of two thicknesses), the chosen
  weather marked by colour, shape and a check, and the new-card button (+)
  right under the share button and as wide, which opens the card popup over the city (2026-11-07;
  the cards button had been there since 2026-11-06, and the cards screen it opened is gone). One family of rounded
  rectangles for the controls (2026-11-06): 16 dp corners on the time bar, the weather button, the
  share button, the new-card button, the card picker and the hint. The pill marking the chosen
  time inside its bar is the one fully round shape (2026-11-07, at the user's request; on
  2026-11-06 he had turned down a pill beside square buttons, when the times were separate
  buttons). The popup's connected groups have 16 dp corners at their ends and 4 dp where their
  buttons meet, each rounding its inner corners a little while pressed. On a wide window (a tablet, a phone on its
  side) the panel and the card popup keep to 560 dp in the middle.
- The card picker shows the chosen card's title over its link and opens a menu of the saved cards
  that are links, the profile first (the city starts on it). The menu is exactly as wide as the
  picker (its rows are drawn by the app, since M3's menu items stop at 280 dp); the chosen card
  shows by its colour and shape, with no check (2026-11-07, at the user's request); every card but
  the profile has a trash button that removes it at once, the snackbar offering Undo, which puts
  it back where it was. Removing the card the city shows takes the city back to the profile.
  Payment cards are offered too (2026-11-07), showing who they pay under their title, never the
  code. While a payment code's city shows, the share button folds away and the picker takes its
  room, and tap to share keeps sending the profile's link. Wi-Fi, text and contact cards are not
  links, so they are not offered. A city opened from a share link shows that link in the
  picker until a card is picked. Picking one makes a new city with today's recipe and the site
  identity, as a typed link did.
- A change of hour or weather (2026-11-07, at the user's request) spreads out from the control
  that was touched instead of changing the panel all at once: the panel keeps a snapshot of its
  old colours and the new ones open over it in a circle from the touch, on the slow spatial spring
  (`themeReveal`). The city eases too: its light and sky over 1.5 s for an hour, 3 s for weather.
- Weather comes and goes (2026-11-07, at the user's request), blocky like the city and drifting
  on a wind toward +x: a storm's deck of dark clouds rolls in from upwind as the sky darkens, its
  rain begins once the clouds are mostly in and its lightning last, and leaving, the rain and
  lightning stop first; haze rolls in behind a front that crosses the plate, on wind streaks (see
  Haze above); the clear sky keeps five to eight white clouds (by the city's seed) spread evenly
  round it, each in its own slot along the wind and a golden-ratio step across from the last so
  none bunch up, and the sun and the moon where they stand (see Time above). Clouds never shrink
  or vanish (2026-11-07, at the user's request): when their weather goes they blow on downwind out
  of sight, carried right through the city if they were still arriving, and when it comes they
  blow in from upwind, in 3.2 s, each cloud travelling its own distance so the layer loosens as it
  goes. Every cloud has its own shape from the city's seed (2026-11-07, at the user's request): a
  flat-bottomed cluster of heaps side by side, tallest near the middle, as a cumulus, a long low
  row of lumps, a small puff, a big cumulus with one broad heap rising from it, or a ragged pair
  (heaps stacked into tiers were tried and read as stepped pyramids); a storm's are dark and heavy
  underneath, some with a tower or an anvil on top. The moon's whole disc shows whenever it is up
  and any of it is lit; its glow says how much. The weather's shapes cast no shadow and
  shrink away with the traffic when the city folds into its code; with animations off they simply
  are where they belong.
- Holding a cloud opens the clouds' setting at the finger (2026-11-07, at the user's request): a
  menu of one switch row, like the weather menu, kept on the phone. Off, every cloud (the clear
  sky's and a storm's deck) blows away downwind, lightning comes from high above, and the row says
  to hold the sun or the moon to bring them back, which opens the same menu. The library asks the
  renderer what of the sky lies under a long press (`SkylineQrCode(onSkyLongPress = …)`, a ray
  against the weather's boxes, a little larger than drawn); anything else still brings the meteor
  down. With the clouds off in a storm there is no sun or moon to hold until the sky clears.
- Springs for touch and text (2026-11-07, at the user's request, "so the app felt lively";
  `ui/Motion.kt`). Every button, toggle, bar and menu row dips the moment it is touched (0.94, or
  0.98 for the wide picker bar and menu rows) on the motion scheme's fast effects spring
  (critically damped, about 0.1 s) and springs back on release on its fast spatial spring, which
  overshoots a little. Only the drawing scales, never the touch area, and the press is watched
  after the control and any scroll have had it, so a stolen press springs back. Text that changes
  in place rises: the picker's card (in from below as the old one rises out, clipped to the bar),
  the hint (the old words blur, fade and shrink away as the new sharpen and grow in, while its
  size springs), and in the card popup the "Filled in
  from" line, the warning and the problem, the password field, and the tag reader's states. The
  popup springs open from 0.85 out of its bottom corner toward the new-card button, and its form's
  height springs as fields come and go. Compose runs springs at the system animation scale, so
  Remove animations turns each into an instant cut. Every button's icon answers its own press
  (2026-11-07): the + turns a quarter, the share arrow lifts, the bin tips, "Put the camera back"
  rewinds, the rest pop; a menu's chevron turns over while the menu is open.
- The launcher icon (2026-11-07, at the user's request, after a slanted skyline icon he showed; it
  had been flat for a few hours the same day): KL's landmarks as 3D blocks seen at an angle, each
  with two faces showing, the left slit with windows and the right solid, near-black on white with
  white seams where a nearer block stands in front of a farther one, on a slanted ground: Menara KL
  with its pod, the Petronas Twin Towers with their setbacks, spires and skybridge, Merdeka 118
  tapering into the tallest spire, and three lower blocks in front. `tools/launcher_icon.py` builds
  it from boxes in a little world, projects them and fits them into the adaptive icon's safe
  circle; the themed (monochrome) icon is the same blocks without the white. The old bitmap icons
  are gone: with minSdk 26 only the adaptive one shows.
- The keyboard: nothing on the city types any more, so the panel no longer moves for it; the card
  popup pads itself above it (see NFC.md).
- New cards in the card popup, and tap to share: the merged
  skylineqr-nfc, designed in [NFC.md](NFC.md). Icons are ported from Lucide (ISC), not the
  Material icon set, which an unshrunk release would carry whole.
- Expressive is public only in the Material 3 1.5 line so far (1.4.0, the BOM's, keeps it
  internal), so the app pins `material3` 1.5.0-rc01. That pulls Compose 1.12, which needs
  compileSdk 37 and Android Gradle Plugin 9.1 (on Gradle 9.4.1). The app lifts Compose UI and
  foundation further, to 1.13.0-rc01 (2026-11-07): 1.12 hides the keyboard and shows it again
  whenever focus moves between text fields (issuetracker.google.com/530704636). Drop
  `androidxComposeTextFix` once material3 brings a Compose with the fix. Only the app compiles against 37;
  the libraries stay on 36 and the BOM, and targetSdk stays 36.
- No shadow under the floating plate (removed 2026-10-20).
- No sound (removed 2026-11-06 at the user's request, with the six recordings and the toggle).
  The library still reports lightning and meteors through `SkylineQrCode(onEvent = …)` for an app
  that wants its own.
- Share: the button sends the link to this city and nothing else (2026-11-06; the code image and
  the postcard are gone).
- Share links: compact base64url App Links on `skylightlim.github.io` (the user-site repo serves
  `/.well-known/assetlinks.json`). The page shows a 2D QR, the city's ID code and a button to the
  link itself; while the repo is private it does not link to the APK or the repo, which would 404.
- The default link is the user's Instagram, `https://www.instagram.com/chenyuulim/` (2026-11-06).

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
- **M5 Sound** (done 2026-10-20, removed 2026-11-06): six CC0 recordings from OpenGameArt,
  crossfaded loops plus SoundPool one-shots, driven by `onEvent`; the user asked for no sound.
- **M6 Release**: share links, the Pages site, JitPack `v1.0.0`, GitHub Releases APK; generator
  version 1 frozen. Prepared 2026-10-20 and verified locally (share-link format, app links, page,
  postcard, signed release APK, JitPack POMs, licences screen); the public steps (repo public,
  `skylightlim.github.io`, tag and release) wait for the go-ahead.

No fixed deadline. If one appears, M5 and M6 slip first.
