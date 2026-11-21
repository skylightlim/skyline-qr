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
  - `app`: the demo. Control panel, settings card, tour, tap to share.
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
- 4× MSAA. On a 120 Hz screen it draws every other frame (2026-11-12), a steady 60; 60 fps on the Pixel 6a: continuous while traffic moves, paused in the background, redraw
  on change only when idle in the flat view.
- Speed (2026-11-21, at the user's request: "optimize the app and website so it run as smooth as
  possible"), none of it changing a pixel: the sky is drawn after the city, at the far plane, so
  the GPU shades only the sky the city leaves; the app starts the device and builds its pipelines
  as soon as the renderer exists, not once the surface arrives; the planner takes a fourteenth of
  the time on the JVM (cars on one loop share its stop lines, routes are walked without float `%`,
  the monorail's search without boxing), with TrafficMotionTest pinning every car's motion so the
  same link still moves the same traffic.

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
  at M.
  Its flat code was not tried with a banking app (the user's call too).
- `generatorVersion` pins the recipe. Version 1 is a draft until the first public release; from
  then on its golden fingerprints are frozen and visual changes add a new version.
- Presentation (time, weather, city preset, camera) never enters the identity.
- The flat view must scan in every time and weather combination, proven by an on-device test that
  decodes a rendered snapshot. Scanning the 3D view is a bonus, not a requirement.
- Flat colours (2026-11-12, `FlatColours.kt`, at the user's request: the 2026-11-07
  colours were "horrible" and the buildings did not match their code; colours from the chart at
  pantone-colours.com. Three picks that day: vivid ones were "too vibrant and saturated", muted
  ones wanted to be "a bit more lively and colorful" with "no brown", so these are of middling
  strength). Since 2026-11-20 without the magenta pair (513, 254), at the user's request: he tried
  color-hex.com's "city" palette and Holidu's Pantone colours of cities that day, then asked for
  these back without magenta. Fourteen Pantone inks: seven hues round the wheel, two shades each,
  red (703, 2033), rose (2048, 689), purple (520, 668), violet (5275, 534), blue (647, 653), teal
  (5473, 562) and green (363, 364). Each district takes one hue (Kuala Lumpur's gardens green, the
  mall blue like the twins where it was magenta, the square red, the twins blue, the supertall
  violet, the tower teal; the rose and the plum purple, nearest magenta, only as accents; another
  city's districts spread round the wheel); each
  dark module is a square of its district's hue in either shade, half and half at random, and
  about one in ten takes the hue beside its own. Kuala Lumpur's districts are sized so every hue
  they take covers the same share of the code outside the finders (each corner is the same size
  whatever its colour, and in the city it is lawn or the mall, not towers of its colour) (2026-11-20, at the user's request: blue, on two
  districts, had a third of it): each district's reach grows or shrinks toward its share, and the
  mall and the twins split blue's. Other cities keep their seeded districts (evened out, one
  recipe's code failed to scan on a stormy night at 42 px a module). A finder is one ink whole. **A tower is built of
  the ink its module takes**, so a district looks the same standing as folded. The inks are the
  same at every hour and in all weather; the hour tints only the paper, in four shades like paving.
  What makes it scan: HybridBinarizer reads a lighter ink beside a darker one as paper once they
  are far enough apart (12 levels failed at 42 px a module), so every ink is exactly 0.36 under
  ZXing's (R + 2G + B) / 4 and within 0.025 of 0.36 under BT.601, and any two can touch. (0.32
  until 2026-11-12, where most hues were dull; 0.40 was tried and a small code failed on a stormy
  night's paper.) Only colours near that plane fit: it runs through the greys, so muted colours
  of every hue do, vivid ones only from rose through blue to green. Each Pantone colour is moved
  the least distance onto it. A JVM test scans every recipe, hour and weather from 3.4 to 42 px a
  module under both formulas. Kuala Lumpur's arrangement was chosen by scanning: the version 3
  test code misses at 7.7 and 8.9 px a module whatever the inks, and a poor arrangement misses at
  8.6 too, which the sweep samples; check a new one at every tenth of a pixel from 7.5 to 9.5.

## The city

- Finder patterns: a podium ring with a squat core block, so the corners stay readable and leave
  the skyline's height to the middle.
- Generic city colours are seeded per site: one of eight curated recipes of four materials plus a
  traffic paint (`Materials.kt`). The KL preset uses fixed materials so its landmarks stay
  recognisable.
- Shadows are cast live from a 2048² sun shadow map, so they follow the clock; the planner no
  longer bakes any light into the city.
- The app always builds Kuala Lumpur; there is no city switch (decided 2026-10-20). The library
  keeps a `CityPreset` with KL as the default and the generic city for other apps; it is never
  part of identity.
- Time: Auto (local clock, equator hours: sunrise about 07:10, sunset about 19:20, blended) or a
  fixed dawn, day, dusk or night. The app offers only Now (Auto), Day and Night (decided
  2026-10-20): Now already passes through dawn and dusk. The sun rises behind the city and sets in front of the camera,
  so dusk lights the facades you see; the moon hands over across two hours of twilight. That is
  the light only. The sun's and the moon's discs in the sky (2026-11-07, at the user's request)
  stand as high as they really do in Now: `SunAndMoon` works out where they are over Kuala Lumpur
  at that instant (the Astronomical Almanac's low-precision formulas, checked against PyEphem to
  0.01 degrees for the sun and 0.4 for the moon), and the moon shows only when above the horizon
  and lit enough to see, and never with the sun (2026-11-12, at the user's request: both at once
  read as a mistake); it comes out as the sun goes down. Day and Night keep a sun on a fixed path that rises at
  07:10 and sets at 19:20, and a moon high in the east-south-east after dark, which rises straight
  up there as the sun sinks from six to about seventeen degrees below the horizon (2026-11-20; it
  used to appear there at once, which the timelapse showed as a pop). The discs hang on an
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
  almost to a hover once a lap; a plane (fuselage, wings, engines, tail) circles wide above. Over
  Kuala Lumpur (2026-11-09, at the user's request) the plane tows the city's flag on a line like
  an advertising banner, after the public-domain drawing on Wikimedia Commons: white with three
  thin red stripes above and three below a blue band, a yellow crescent round a star on the band
  near the hoist. It hangs from a spar in nine upright slices that turn with a wave running along
  it, still at the hoist and most at the fly. Its path is unchanged.
- Traffic lights (2026-10-21), in every city: up to six junctions the cars drive through, crossings
  first, at least four modules apart and clear of the corners. Each approach a car uses has a pole
  and head on its left kerb, a little larger than life, drawn with the traffic. A junction lets one
  axis go at a time: green 3 s, amber 0.8 s, all red 0.6 s, then the other axis, from a seeded
  point in the cycle. Cars stop for them: cruising toward a line, a car looks at its light where it
  would start braking; green, it drives on; amber or red, it eases to a halt at the line, waits for
  green and eases away. The schedule is built lazily forward from time 0, so a car's place is still
  a pure function of time; a car that starts closer to a red line than it can brake starts out
  waiting at it. Since 2026-11-09 (at the user's request: more lights where they are seen), up to
  three more stand on each of the ring road's two edges nearest the camera, the bottom and the
  right, where a street comes out onto it, and the bus stops for those (half a bus short of the
  line). Kuala Lumpur's crossing is a junction of its own (see below), larger than one module: a
  car stops where it comes into its modules, never between them. The monorail has no lights.
- Billboard (2026-10-21): `SkylineQrCode(billboard = …)` gives the city's screen its messages (the
  app's are the user's own, up to five, set in its settings card since 2026-11-09; they start as
  马大牛逼, tarumt #1, i need argon); none, no screen. Kuala Lumpur's stands on the roof
  of the mall's east block, set back behind its facade. Android's fonts draw each message into a row of a single-channel texture, as
  large as fits the screen down to half size, else full size; one that fits holds 3.2 s, a longer
  one waits 0.9 s at its start, scrolls through at a screen width per 1.8 s and waits at its end.
  Then the screen turns to the next like a tri-vision board, twelve slats flipping in a wave. Each
  message lights in a neon colour of its own and glows after dark. Presentation only: never in the
  identity. With animations off nothing scrolls or flips.
- The night sky (2026-11-09, at the user's request): the sky shader lights the top of the screen a
  little (the hour's zenith colour) and fades to black at the bottom, behind the panel, whatever
  the camera looks at, fading in through dusk; a lightning flash still lights it all. Stars twinkle
  in it: one or none in each cell of a grid over the sky by bearing and height, placed, sized and
  timed by the city's seed, a pixel or two across at any zoom, over the upper half of the screen
  (the camera looks down so far that the sky behind the city is mostly below the horizon), none in
  rain or a storm, few in haze, behind the clouds, and only in 3D. About every three seconds (random gaps of two to four)
  shooting stars cross the upper screen in a group of one to four (often one, now and then four;
  the user's call, "just like real life"), within a moment of each other, each streaking out of the
  group's radiant, a point above the top of the screen, a little off it each: thin streaks with
  fading tails, gone in about half a second, as a function of the seconds alone. Now stays real: on a night with no moon up there is
  no moon.
- Fireworks (2026-11-09, at the user's request), drawn as traffic (role 13, which the fountain's
  water shares): a shell climbs, slowing, and bursts into 40 sparks spread evenly over a sphere,
  which slow in the air, sag, fade and crackle at the end, each with a dimmer one a moment behind
  so it streaks. Festive colours, gold the most common (red, white, teal, magenta). All a function
  of the launch time. See Interaction for the long press, and Kuala Lumpur for its big nights.

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
  fills its finder four floors high (2026-11-12, taller at the user's request; its name was moved to a board on the roof that day and, at his request, back onto the band over the entrance): two blocks of champagne fins over white lattice with a band
  of glass, either side of a tall glass entrance under its canopy and lit name band; the crystal
  fountain (three rose-crystal bowls over a pool ringed by posts) on the plaza in front, the middle
  of its south side, which faces the city (2026-11-09, at the user's request: larger and more
  visible; the body behind the facade gave way so the plaza is two modules deep, and the fountain
  is twice as tall, 2.4, its bowls half as wide again, with a jet out of the top that falls back
  into the top bowl and a curtain of drops over each rim, pale by day and lit from within at night,
  drawn every frame by `Fountain`); the two blue residence towers behind on the roof (about
  40 % and 34 % of the twin towers); the billboard's screen on the east block's roof, 3.5 by 1.5 (2026-11-12, half as large again at the user's request), reaching out over the entrance. The square is
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
- Bukit Bintang's crossing (2026-11-09 revision, the user's call): on the code's alignment pattern,
  seven modules in from the bottom right, the corner nearest the camera's usual side, so it is
  always in front. The pattern's middle module has no building and is the crossing's middle; the
  middle of each side of its ring has none either and is an arm into the square. Those five dark
  modules are paved as road, a tile high, so they still fold into ink. Asphalt covers the three by
  three square and the arms, zebra stripes cross each arm where it meets the square, and both
  diagonals' zebras meet in the middle (the scramble, people crossing every way at once). Cars
  drive through wherever an arm meets a street beyond the pattern, stopping at its lights; a light
  stands at the mouth of every arm, so they change even where no car comes. The rest of the
  pattern's ring is low shops of one or two floors, so the camera sees in, and on its corner away
  from the camera stands the fast-food restaurant, two floors: a glass shop front under a red
  facade with a yellow trim, and a pole sign on its roof, a red board with a yellow M of two arches
  in five by five pixels on both faces, turned to the camera's usual side. No landmark stands on
  the pattern. A version 1 code has no alignment pattern: there the crossing is, as before, the lit
  junction nearest the mall's plaza that the monorail does not pass over and no landmark crowds,
  with the restaurant on its corner.
- Kuala Lumpur's big nights (2026-11-09, at the user's request), in Now only: on the eve of 31
  August and New Year's Eve from 20:00 to half past midnight, and on 1 February from 20:00 to
  midnight, shells go up from the landmarks' tops and the square, one every three quarters of a
  second, and in the ten minutes after midnight five times as often and larger (`Festivals`).
- The finders' inner gaps are never streets: no car drives round inside a finder.
- A monorail that joins the sights (2026-11-12, at the user's request: longer, connecting several
  places; from 2026-11-06 it was one street in plain view with two turns at most). From a street
  the camera sees, a bounded search grows the line at both ends through the streets, never into
  a finder corner, never touching or running beside itself, two modules at least between bends.
  The best line wins: a module in view counts one, a module behind a tower 0.35, each sight it
  passes within 3.5 modules of (landmarks, corners, the crossing) four, nearer the camera a
  little more. Lines come out 16 to 35 modules long, most of each in view (the test asks 60 %).
  Stations stand at both ends and beside each sight on the way, four modules apart at least, and
  the three-car train calls at them all, there and back. Curved beam at every bend, pillars on
  the street below.
- 2026-11-12, at the user's request:
  - No light by day. Windows, beacons, the mall's neon and name, the fountain and vehicles' lamps
    come on from dusk (night over 0.2) and are off by day; only traffic lights and the billboard,
    which are lit in life, show by day.
  - More detail on towers, all drawn in the shader: three kinds of facade by the tower's random
    (windows set in the wall, curtain-wall bands, tall windows between ribs), a shop front and
    lintel at the street, a coping, and on the roof a parapet, plant rooms with shadows, or a
    helipad on the tall ones.
  - Vehicles each have a paint of their own (eight: red, orange, yellow, white, blue, green,
    silver, black). After dark cars and buses show headlights and tail lights, buses, the train
    and the plane lit cabins. Four buses share the ring road.
  - Clouds are soft (second revision the same day: "make it looks like real cloud"; faceted boxes
    never did). A cloud is a heap of balls along a level base, billowing up in the middle; each
    ball is a box among the traffic's that a pipeline of its own (`cloudVertex`, `cloudFragment`)
    draws as the ball inside it, a ray per pixel, thick through the middle and thinning to nothing
    at a ragged edge, blended over the sky, lit from above and the sun's side.
  - Never the sun and the moon together.
  - The square flies Kuala Lumpur's own flag (white, three red stripes top and bottom, a dark
    blue band, the yellow crescent and star on it, after the flag on Wikimedia Commons); the
    monument's national flag has a crescent and star in pixels too. Their pixels wave with the
    cloth (a flag piece narrower than an eighth says so in its glow).
  - The billboard's letters change colour: a slow rainbow running across the screen.
  - The cars' blue and green are royal blue and grass green (the blue-green read as tiffany).
  - The mall's glass entrance stands a hair in front of the finder's core (they shared a plane
    and flickered behind the fountain).

## Interaction

- The morph is quick and smooth, like the ICQR tree: 0.85 s, the camera on one ease fitted to the
  tree's yaw (half way in 0.12 s, four fifths in 0.3 s, a long soft tail), the fold landed by
  60 %. Every building folds the same way (2026-11-12, at the user's request: "not all the cell
  morph with the same style"; before, towers sank a little scattered while landmarks, corners and
  the track shrank in the air). The whole city sinks into the ground together on one ease
  (smootherstep): towers, landmarks, corners and track alike, each point keeping its share of the
  height above it. The towers widen until they touch by 35 %, so the code reads while they stand.
  The landmarks, corners and track turn into the code as they come down (second revision the same
  day: grey roofs lay over the corners' modules until they were under, and the user wanted "the
  qrcode pixel to move into place" there as everywhere): every point of them takes the colour of
  the module under it, ink or paper, from the code packed into the frame uniform (`codeAt`), on
  the towers' wash. They end just under the ground, where the plate hides them, by 90 % of the
  fold. Traffic, beacons, the weather and the plane's banner shrink away over the first 30 %.
  Colours hardly change, since a tower is already its ink; the lighting washes out by 40 %.
- Raising from the code is its own choreography, not the fold played backwards (2026-11-06: that
  made the towers shoot up as the code's slabs and the landmarks pop in at the end). Over the same
  0.85 s, at one steady pace, with the camera leaving at once on the mirror of its ease: the city's
  colours come back and the towers narrow to their footprints by 30 %, while the landmarks'
  footprints spread out of the code. Everything grows up from the ground, the middle of the city
  a moment before the edges, and the higher a point stands the longer it takes (half the raise for
  the ground, all of it for the tallest), so the low city has settled while the tall towers still
  climb steadily, and a point higher up always stays above one lower down. Beacons, traffic and
  the lights come back over the last quarter. The finder modules under Kuala Lumpur's lawns and
  the mall's plaza sit just off the ground (2026-11-20, at the user's request: the square's red
  turned green too late), so the lawn rising from under the plate covers them as the colours come
  back; at the lawn's height they stood over it until the raise was half done. Leaving the code once its blocks are home takes the
  raise; a reversal mid-way keeps the way it is on and continues from where it is, without a jump.
- The flat code is the code alone (2026-11-12, at the user's request: no pale card on a darker
  screen): the whole screen turns the hour's paper, so the plate is lost in it and only the
  modules show. Paper stays at luma 0.8 or more; the status bar icons follow it.
- Tap: flatten or raise, at once. Drag: orbit (3D only, limited yaw and elevation). Pinch: zoom.
  Released mid-turn, the city runs on at the finger's speed and slows within about half a second
  (each axis on its own; none with "Remove animations"); a touch catches it. The limits are soft
  (2026-11-06, after Apple's fluid-interface guidance): pushed past one, the camera follows at half
  speed and less and less, then springs back on release. No double-tap (removed 2026-11-06): it made every tap wait out the double-tap timeout,
  about 300 ms, before the morph began. The app puts the camera back with `resetCamera()`. The
  flat view locks the camera square to the screen.
- Long-press (3D only), `SkylineQrCode(longPress = …)`: a meteor strikes where you press. Streak,
  flash, shake and rumble; towers within about 6 modules are thrown; debris fades in the air and
  never piles on the code; a glowing crater; the city rebuilds inward from the rim. About 5
  seconds. Landmarks are destroyed and rebuilt like everything else. Or, chosen in the app's
  settings (2026-11-09), fireworks: three shells a third of a second apart go up from where you
  press and burst above the skyline; on the sky they burst right there. A long press anywhere,
  clouds included, does it (the clouds' long-press menu of 2026-11-07 is gone).
- The tour (2026-11-09, at the user's request), `SkylineQrState.startTour()`: the camera flies past
  each sight in turn (the supertall, the telecom tower, the twin towers, the monorail's train, the
  garden, the square, the mall's fountain and the crossing; the generic city's tallest tower and
  corners), ordered round the city so it goes one way round, then meets the plane and its flag
  side on, and lands exactly where the city's own camera is, after which the city folds into its
  code: 16.4 s, and 0.85 s for the fold. Its shot list is its own file, written to be edited
  without the rest of the project: `skylineqr-compose/.../render/tour/TourScript.kt`, with a
  README.md beside it that explains every setting and how to check an edit, including a desktop
  filmstrip (`SKYLINEQR_TOUR=0.5` for `FrameDumpTest`). The fountain is seen from the usual side,
  high over the mall (straight outside, the mall hid it); the crossing from high up, over the
  towers in front. A folded code unfolds first. The renderer restarts its clock and clears
  anything falling or burning, so everything that moves is where it was last time, and the sky is
  clear, by day or by night as the sun is for the person's own choice, with the weather's shapes
  in place at once; their own hour and weather come back after. The camera goes round in
  cylindrical terms, so it never cuts across the city, on a spline that is still at either end and
  never overshoots a stop; its lens shift eases off and back. Any touch stops it and does nothing
  else; the camera eases back to the city's own view in 0.7 s. `touring` tells an app to hide its
  controls.
- Haptics on the morph, the long-press and the impact.
- Android's "Remove animations" setting gives an instant morph, no meteor or fireworks and calmer
  rain. TalkBack labels describe the code and the double-tap action.

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
  field, so links are no longer typed on the city) with the new-card button (+) beside it and as
  tall (2026-11-09, at the user's request: it had been in the row below, under share); then
  Now / Day / Night as one bar (2026-11-07, at
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
  weather marked by colour, shape and a check, and the app's own button: the launcher icon's
  monochrome blocks in a tonal button like the weather's (2026-11-09, at the user's request: it
  replaced share). A tap opens the settings card; holding it starts the tour. One family of rounded
  rectangles for the controls (2026-11-06): 16 dp corners on the time bar, the weather button, the
  app's button, the new-card button, the card picker and the hint. The pill marking the chosen
  time inside its bar is the one fully round shape (2026-11-07, at the user's request; on
  2026-11-06 he had turned down a pill beside square buttons, when the times were separate
  buttons). The popup's connected groups have 16 dp corners at their ends and 4 dp where their
  buttons meet, each rounding its inner corners a little while pressed. On a wide window (a tablet, a phone on its
  side) the panel and the card popup keep to 560 dp in the middle.
- The card picker shows the chosen card's title alone (2026-11-12, at the user's request; the link shows only in the list) and opens a menu of the saved cards
  that are links, the profile first (the city starts on it). The menu is exactly as wide as the
  picker (its rows are drawn by the app, since M3's menu items stop at 280 dp); the chosen card
  shows by its colour and shape, with no check (2026-11-07, at the user's request); every card but
  the profile has a trash button that removes it at once, the snackbar offering Undo, which puts
  it back where it was. Removing the card the city shows takes the city back to the profile.
  Payment cards are offered too (2026-11-07), showing who they pay under their title, never the
  code. While a payment code's city shows, tap to share keeps sending the profile's link; the
  panel is otherwise the same, + and the app's button included. Wi-Fi, text and contact cards are
  not links, so they are not offered. Picking one makes a new city with today's recipe and the
  site identity, as a typed link did.
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
  underneath, some with a tower or an anvil on top (since 2026-11-12 every cloud is a heap of soft
  balls; see the 2026-11-12 list). The moon's whole disc shows whenever it is up, the sun is
  down and any of it is lit; its glow says how much. The weather's shapes cast no shadow and
  shrink away with the traffic when the city folds into its code; with animations off they simply
  are where they belong.
- The settings card (2026-11-09, at the user's request), from the app's button, over the city like
  the card popup and springing open out of the same corner, in the panel's colours, no title:
  "Take the tour" first (it closes the card and starts it), with "Timelapse" beside it (a tonal
  button; the tour's stays filled and takes the room it needs), then what a long press on the city
  does (Meteor or Fireworks, a pill bar like the time's), the clouds' switch (off, every cloud, the
  clear sky's and a storm's deck, blows away downwind and lightning comes from high above; it was a
  long-press menu on a cloud from 2026-11-07), the sign board (a page of up to five messages, each
  shown on the mall's screen as it is typed, the last one not removable) and the licences (a page
  showing `assets/licenses.txt`). Pages slide in from the end; a tap anywhere outside the card
  closes it (2026-11-09, at the user's request; the dialog fills the window, so the platform's own
  tap-outside never fired), and back steps out of a page, then closes the card. All of it is kept on the phone (`AppSettings`). The
  hint says "hold for fireworks" when that is chosen.
- The tour from the app: holding the app's button, the card's first row, or the home screen's Tour
  shortcut (long-press the app's icon; `res/xml/shortcuts.xml`, action
  `com.skylightlim.skylineqr.TOUR`, which waits for the first city). The panel sinks away while it
  flies and comes back as it lands.
- The timelapse (2026-11-20, at the user's request: "run the city from day to night in 15
  seconds"), from the settings card's button beside the tour (`SkylineQrState.startTimelapse`,
  `render/Timelapse.kt`). The clock runs from Day's 12:30 to Night's 23:00 in fifteen seconds on
  the fixed hours' sun and moon, under the weather chosen, on one smooth curve that hurries
  through the afternoon (the shadows sweep round) and lingers over the sunset and the dusk (the
  windows come on, the stars come out, the moon rises), starting and ending at rest. The sky eases
  into it from the hour shown, as any change of hour does. The panel sinks away as for the tour;
  the camera stays where it is and can still be turned; a tap stops it and the sky eases back to
  the chosen hour. Reaching night, it ends exactly on Night's sky and the time bar shows Night.
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
  (2026-11-07): the + turns a quarter, the tour's plane lifts, the bin tips, "Put the camera back"
  and a page's back arrow rewind, the rest pop; a menu's chevron turns over while the menu is open.
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
- No share links (removed 2026-11-09 at the user's request, with the share button, `ShareLink`,
  the App Links filter and `site/`). The page published on `skylightlim.github.io` on 2026-11-06
  was left as it is; the app no longer opens its links. Text shared into the app still makes a
  new card.
- The default link is the user's Instagram, `https://www.instagram.com/chenyuulim/` (2026-11-06).

## Website

Added 2026-11-21, at the user's request ("make this app into a website").

- `skylineqr-web`: the demo app's screen in a browser, a static site on GitHub Pages. Kotlin/Wasm,
  not Kotlin/JS: Wasm keeps Float arithmetic in 32 bits like the JVM, and Kotlin/JS does not, so
  only Wasm plans the same city.
- `skylineqr-core` is Kotlin Multiplatform (JVM and Wasm). Nothing in it may need the JVM:
  `QrEncoder` is ZXing's encoder adapted into Kotlin and must choose exactly ZXing 3.5's symbol,
  mask included (`QrEncoderTest` compares them); `FdLibm` is fdlibm 5.3 in Kotlin and must return
  StrictMath's bits (`FdLibmTest`); the clock, its zone and IDNA come from `Platform`. In the
  browser IDNA is the URL parser's (UTS 46), so a rare host (ß, ς) may get a different identity
  than on the phone. `CrossPlatformCityTest` hashes whole cities and runs on the JVM and on Wasm.
- The renderer's platform-free files (WGSL, camera, frame layout, sky shapes, morph, fireworks,
  tour) are compiled into the website from skylineqr-compose's sources, not copied.
  `WebRenderer` is `CityRenderer` with the browser's plumbing (one thread, requestAnimationFrame)
  and gpu.js builds the same passes with the browser's WebGPU; a change to what CityRenderer draws
  or when belongs in both.
- The page (redesigned 2026-11-21, at the user's request, after the layout of tree.icqr.com in
  our own look): the city full screen, the wordmark top left and an about button top right; below,
  one wide link field with a square Tour button, then Now, Day and Night as three equal buttons and
  a square button for the rest (weather, what a long press does, the clouds, the timelapse, the
  sign board). Tapping the city folds it into its code. No share links, as in the app.
- Loading (2026-11-21): the page starts WebGPU before it plans the city, so the browser's GPU
  process works meanwhile, and index.html preloads the wasm (the build fills in its hashed name).
- WebGPU is tried only where it can't take the tab down: Firefox-based browsers (Zen among them)
  get the flat code unless opened with `?webgpu=1`, because their WebGPU crashed the page; a
  request that hangs, or a first frame that never finishes, also falls back. Without WebGPU, the flat code on a
  2D canvas in the flat city's colours (`FlatColours`), with a note. No tap to share: browsers
  can't emulate an NFC tag.

## Loading poster

Added 2026-11-21, at the user's request ("create a poster of kuala lumpur, put it as the loading
page before the city is loaded"). While the first city builds, the website and the app show a map
poster of Kuala Lumpur over everything, on its own paper so the whole poster shows on any screen,
with "Building the city…" above it. It goes when the first frame with a city has drawn (the app:
`SkylineQrState.loading`, which the renderer's first such frame ends; the website: gpu.js once
that frame's work is done), or when the flat code or an error takes its place, and stays at least
1.2 s so it never just flickers past, then fades over 0.6 s.

The poster is made by maptoposter (MIT; run, not copied) from OpenStreetMap data in its Monochrome
Blue theme, the app's blue: the centre between Merdeka Square and KLCC, 7 km, 9:16, 900 px wide
as WebP. `tools/poster/make_poster.sh` makes it, and the Poster workflow runs that on GitHub,
since it needs OpenStreetMap's Overpass API, and commits the result. The poster prints
"© OpenStreetMap contributors", as the ODbL asks; NOTICE credits both.

## Library API (target)

```kotlin
SkylineQrCode(
  url, modifier,
  city = CityPreset.Generic, time = TimeOfDay.Auto, weather = Weather.Clear,
  identityScope = IdentityScope.SITE, generatorVersion = GeneratorVersion.CURRENT,
  state = rememberSkylineQrState(),   // flat/3D, loading, snapshot(), resetCamera(), startTour()/stopTour()/touring
  haptics = true, billboard = listOf(…), clouds = true,
  longPress = LongPress.METEOR,       // or FIREWORKS
  onEvent = { … },                    // lightning and meteors, for sound
)
```

## Milestones

Each ends with an install on the user's phone (the Pixel 6a until 2026-11-07, the Pixel 9 Pro
since), never an emulator.

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
- **M6 Release**: JitPack `v1.0.0`, GitHub Releases APK; generator version 1 frozen. Prepared
  2026-10-20 and verified locally (signed release APK, JitPack POMs); the public steps (repo
  public, tag and release) wait for the go-ahead. Share links and their Pages site were part of it
  until 2026-11-09, when the user dropped them.

No fixed deadline. If one appears, M5 and M6 slip first.
