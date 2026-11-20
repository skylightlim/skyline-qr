# The city tour

The tour is a camera flight over the city: it lifts off the usual view, stops by each sight in
turn, meets the plane and the Kuala Lumpur flag it tows, lands back on the usual view, and the city
then folds into its QR code. It is the same every time for the same city. It was added on
2026-11-09 at the user's request.

You can change the tour by editing **one file, `TourScript.kt`**, without knowing the rest of the
project. This README is everything you need for that.

## The files in this folder

| File | What it is | Edit it? |
| --- | --- | --- |
| `TourScript.kt` | The shot list: which sights, in what order, how long to each, how each is framed. | **Yes**: this is the tour. |
| `Tour.kt` | The machinery that turns the shot list into a camera path. | Only to add a new kind of sight (see the end). |
| `README.md` | This file. | Keep it in step with what you change. |

Tests: `skylineqr-compose/src/test/kotlin/com/skylightlim/skylineqr/render/tour/TourTest.kt`.

## How a tour is flown

```
0 s          LIFT_OFF        +seconds   +seconds  ...  +seconds (plane)  +LANDING        +0.85 s
the city's   first stop      next stop                 the plane         the city's      folded
own view                                                                 own view again  into the code
```

1. It starts exactly where the city's camera is. The camera is far out (about 140 modules on a
   phone held upright), so the first leg is a dive and gets more time: `LIFT_OFF`.
2. It reaches each stop of `SHOTS` in turn, each `seconds` after the one before. A stop is a moment
   when the camera is at a chosen place looking at a chosen sight. It does not pause there; it flows
   through on a smooth curve.
3. It lands `LANDING` seconds after the last stop, exactly where the city's own camera is then.
4. The app then folds the city into its code, which takes 0.85 s.

With today's script, a Kuala Lumpur tour takes 16.4 s plus 0.85 s for the fold. That is 8 sights on
the ground and the plane, or 7 when the city has no monorail.

These parts are handled outside this folder, so you don't need to deal with them:

- The renderer restarts the city's clock at the start, so cars, the bus, the monorail, the plane
  and the clouds are always in the same places.
- The sky turns clear, by day or by night, and the person's own sky comes back afterwards.
- The panel hides, any touch stops the tour, and the city folds at the end.

That code lives in `render/CityRenderer.kt` (`beginTour`, `tourPose`), `compose/SkylineQrCode.kt`
(`startTour`) and the app.

## The world, in numbers

- **Units**: distances and heights are in *modules*. A module is one square of the QR code, and
  the city stands one building per module. A code is 21 to 41 modules across, and links of normal
  length are usually 29 to 33.
- The city stands on a square *plate* centred on the origin: x runs left to right across the code,
  z runs top to bottom, and y is up.
- The camera usually stands toward the code's bottom right. That is the city's "own view", the one
  the tour starts and ends on.
- Heights scale with the code: the twin towers are 0.42 × the code's width (at least 10). Merdeka
  118 is 1.5 times that, and Menara KL 0.93 times. Ordinary towers are much lower.

## The sights

| `Sight` | What the camera looks at | Height it is framed by | Which cities have it |
| --- | --- | --- | --- |
| `SUPERTALL` | Merdeka 118 | its real height | Kuala Lumpur |
| `TELECOM_TOWER` | Menara KL | its real height | Kuala Lumpur |
| `TWIN_TOWERS` | the point between the two towers | their height | Kuala Lumpur |
| `MONORAIL` | the train, followed as it moves | 1 | Kuala Lumpur with a monorail line |
| `GARDEN` | the botanical garden and monument (top left corner) | 3 | every city* |
| `SQUARE` | the independence square and flag (bottom left corner) | 3.5 | every city* |
| `FOUNTAIN` | the mall's crystal fountain (top right corner) | the fountain's top, about 2.5 | Kuala Lumpur; elsewhere the top right corner |
| `CROSSING` | Bukit Bintang's crossing and the McDonald's | 2 | Kuala Lumpur |
| `TALLEST_TOWER` | the tallest ordinary tower | its height | only cities *without* the landmarks |
| `PLANE` | the plane and its flag, from outside its circle | n/a | every city |

\* In the generic city (without the landmarks), the corners are plain podiums, but the camera
still goes there.

A sight the city doesn't have is skipped, and so is its time. The landmarks stand in different
places for every link, and so does the monorail line.

## A shot

```kotlin
Shot(Sight.FOUNTAIN, seconds = 1.5f, distance = 8.4f, above = 1f, lookAt = 0.7f, from = USUAL_SIDE)
```

| Setting | Meaning | Default |
| --- | --- | --- |
| `sight` | What it looks at (table above). | required |
| `seconds` | Time to get here from the stop before. Ignored for the first stop, which comes at `LIFT_OFF`. | 1.5 |
| `distance` | How far the camera stands from the sight across the ground. Leave it out to have it worked out from the sight's height: `NEAR + PER_HEIGHT × height`, kept between `CLOSEST` and `FARTHEST`. For `PLANE`, how far outside the plane's circle. | worked out |
| `above` | How much higher than the point it aims at the camera stands, as a share of `distance`: 0.55 looks down at about 30°, 1.0 at 45°, a negative number looks up. Never less than `LOWEST_ABOVE` on the ground. | 0.55 |
| `lookAt` | Which part of the sight to aim at: 0 its foot, 1 its top. Not used for `PLANE`, which is aimed between the plane and the middle of its flag. | 0.55 |
| `from` | Which side of the sight the camera stands on, as a compass bearing in degrees on the code read as a map (north at the top): 0 north, 90 east, 180 south, 270 west. `USUAL_SIDE` (145) is the side the city's own camera is on. Leave it out to stand straight out from the city's middle, which suits tall sights and ones that face outward. Not used for `PLANE`, which is always seen from straight outside its circle. | straight out |

Even when `distance` is short, the camera never comes nearer the city's middle than the plate's
edge plus `OUTSIDE`. That keeps it out of the buildings. A sight near the middle, or a `from` that
points into the city, is therefore seen from further away than `distance`.

Two framings that needed this, both in today's script:

- **The fountain** stands on the plaza in front of the mall, and the plaza faces the city. Seen
  from straight outside, the mall hides it, so its shot uses `from = USUAL_SIDE` and `above = 1`,
  looking down over the mall's cubes.
- **The crossing** is low among the towers. With the default `above` the towers in front hide it,
  so its shot uses `above = 1.3`.

**Check every framing on the filmstrip (below)**: the tests can't tell whether a sight is hidden.

## The settings for the whole tour

| Setting | Meaning | Now |
| --- | --- | --- |
| `SHOTS` | The stops. | 10 listed |
| `ROUND_THE_CITY` | `true`: ground sights are flown in the order they stand round the city, one way round, starting from the nearest, so the camera never doubles back (the list's order doesn't matter, except for `PLANE`, which keeps its place). `false`: flown exactly as listed. | `true` |
| `LIFT_OFF` | Seconds to the first stop. | 2.0 |
| `LANDING` | Seconds from the last stop back to the city's own view. | 2.2 |
| `NEAR`, `PER_HEIGHT`, `CLOSEST`, `FARTHEST` | How a shot without a `distance` is framed (see above). | 5, 0.75, 6, 17 |
| `OUTSIDE` | How far outside the plate's edge the camera always stays. | 1.5 |
| `LOWEST_ABOVE` | The least height above what it looks at, for sights on the ground. | 3 |

## Rules the tests check

`TourTest` checks these for several links in both the Kuala Lumpur and the generic city. An edit
that breaks one fails the build:

- **Under 20 seconds** with the 0.85 s fold (the user's limit). Add up `LIFT_OFF`, every stop's
  `seconds` but the first's, and `LANDING`, then add 0.85.
- It **starts and lands exactly** on the city's own view. Don't change how the first and last
  stops are made in `Tour.kt`.
- The camera is **never inside the city** (always outside half the code's width, and above the
  ground) and **never jumps**.
- **Every Kuala Lumpur sight is visited**, and the plane comes last. If you remove a sight or move
  the plane on purpose, change `everySightTheCityHasIsVisitedAndThePlaneComesLast` to match.
- **Same city, same tour.** Never use random numbers or the clock in the script.

## Checking an edit

1. **Tests**, from the project root:

   ```bash
   ./gradlew :skylineqr-compose:testDebugUnitTest --tests '*TourTest'
   ```

2. **Look at it without a phone**: a filmstrip on the desktop GPU, one picture every half second.
   This needs Python with `pip install wgpu numpy pillow`.

   ```bash
   SKYLINEQR_FRAMES=/tmp/tour SKYLINEQR_SIZE=1080x2400 SKYLINEQR_TOUR=0.5 \
     ./gradlew :skylineqr-compose:testDebugUnitTest --tests '*FrameDumpTest' --rerun
   python tools/preview.py /tmp/tour /tmp/tour/out     # tour-00.png, tour-01.png, ...
   cat /tmp/tour/tour.txt                             # its length and the sights in the order flown
   ```

   Add `SKYLINEQR_TOUR_SKY=night` for the night tour, and `SKYLINEQR_URL=https://…` for another
   link's city. The desktop compiles the shaders with a different compiler from the phone's, so the
   phone has the last word.

3. **On the phone**: build and install the app, then hold the app's button (bottom right), or tap
   it and choose "Take the tour", or long-press the app's icon on the home screen and choose Tour.

## Recipes

- **Slower, more time at each sight**: raise `seconds` on the shots (2.0 instead of 1.5). Keep the
  total under 20 s.
- **A closer look at one sight**: give it a `distance` (say 6), and `above = 0.4` for a flatter
  view, if nothing stands in front of it.
- **See a sight from another side**: `from = 270f` looks at it from the west (the code's left).
- **The plane closer, or further**: its `distance` is how far outside its circle the camera stands
  (14 now). Closer than about 10, the plane fills the screen and its flag goes out of view.
- **A view from overhead**: `above = 1.5` or more.
- **Skip a sight**: delete its line, and drop it from the test's list.
- **Visit a sight twice**: list it twice. With `ROUND_THE_CITY` on, the two stops end up next to
  each other, so it is better to turn that off and write the order out yourself.
- **Your own order**: set `ROUND_THE_CITY = false` and list the shots in the order to fly them. The
  camera always takes the short way round between two stops.
- **Plane first**: move the `PLANE` line to the top.

## Adding a new kind of sight

1. Add it to `enum class Sight` in `TourScript.kt`, with a line saying what it is.
2. In `Tour.kt`, `place()`, say where it is: `still(x, z, height)` for something that stays put, or
   `moving(mover, height)` for a vehicle from `city.movers`. Where things are comes from the `City`
   the planner built: `city.landmarks` (the KL landmarks' footprints), `city.fountain`,
   `city.crossing`, `city.movers`, `city.plateHalf`, `city.modules`.
3. Add a `Shot` for it to `SHOTS`, and a row to the sights table above.
