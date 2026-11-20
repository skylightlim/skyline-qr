package com.skylightlim.skylineqr.render.tour

/*
 * THE CITY TOUR'S SHOT LIST.
 *
 * Edit this file to change the tour: which sights it visits, how long it takes to reach each,
 * and how each is framed. README.md in this folder explains every setting, with recipes, and
 * how to check an edit (`./gradlew :skylineqr-compose:testDebugUnitTest --tests '*TourTest'`).
 * Tour.kt flies whatever is written here; an edit to this file never needs a change there.
 *
 * Units: distances and heights are in modules (one module is one square of the QR code, and the
 * city stands one building per module). Times are in seconds.
 */

/** What a stop of the tour looks at. Where each one is, and how tall, is in README.md. */
internal enum class Sight {
  /** Merdeka 118, Kuala Lumpur's supertall. */
  SUPERTALL,

  /** Menara KL, the telecom tower. */
  TELECOM_TOWER,

  /** The Petronas Twin Towers, looked at between the two. */
  TWIN_TOWERS,

  /** The monorail's train, followed as it moves. */
  MONORAIL,

  /** The botanical garden and the national monument, the code's top left corner. */
  GARDEN,

  /** The independence square and its flag, the bottom left corner. */
  SQUARE,

  /** The mall's crystal fountain, in front of the mall at the top right corner. */
  FOUNTAIN,

  /** Bukit Bintang's crossing and the restaurant, on the alignment pattern near the bottom right. */
  CROSSING,

  /** The tallest ordinary tower: only in a city without the landmarks (the generic city). */
  TALLEST_TOWER,

  /** The plane and the flag it tows, seen side on from outside its circle as it goes by. */
  PLANE,
}

/**
 * One stop of the tour.
 *
 * @property sight what it looks at. A sight the city does not have is skipped, with its time.
 * @property seconds how long the camera takes to get here from the stop before. The first stop
 *   comes [TourScript.LIFT_OFF] seconds in instead.
 * @property distance how far the camera stands from the sight, across the ground. Null works it
 *   out from the sight's height (see [TourScript.NEAR]). For the plane: how far outside its circle.
 * @property above how much higher than the point it looks at the camera stands, as a share of
 *   [distance]: 0.55 looks down at about 30 degrees. Negative looks up. Never less than
 *   [TourScript.LOWEST_ABOVE] for a sight on the ground.
 * @property lookAt which part of the sight the camera aims at: 0 its foot, 1 its top. Not used
 *   for the plane, which is aimed between itself and the middle of its flag.
 * @property from which side of the sight the camera stands on, as a compass bearing in degrees
 *   on the code as a map (north at its top): 0 north, 90 east, 180 south, 270 west. The city's
 *   own view stands at about [USUAL_SIDE]. Null: straight out from the city's middle, which suits
 *   a sight that faces outward or stands tall. Not used for the plane.
 */
internal class Shot(
  val sight: Sight,
  val seconds: Float = 1.5f,
  val distance: Float? = null,
  val above: Float = 0.55f,
  val lookAt: Float = 0.55f,
  val from: Float? = null,
)

/** The compass bearing the city's own camera stands at: south-south-east of the middle. */
internal const val USUAL_SIDE = 145f

internal object TourScript {

  /** The stops. With [ROUND_THE_CITY] on, their order here matters only for the plane. */
  val SHOTS: List<Shot> =
    listOf(
      Shot(Sight.SUPERTALL),
      Shot(Sight.TELECOM_TOWER),
      Shot(Sight.TWIN_TOWERS),
      Shot(Sight.MONORAIL),
      Shot(Sight.GARDEN, lookAt = 0.4f),
      Shot(Sight.SQUARE, lookAt = 0.45f),
      // The fountain stands on the plaza in front of the mall, which faces the city: seen from
      // straight outside, the mall hides it, so from the usual side, high over the mall's cubes.
      Shot(Sight.FOUNTAIN, distance = 8.4f, above = 1f, lookAt = 0.7f, from = USUAL_SIDE),
      // The crossing is low among towers: from high up, or the towers in front hide it.
      Shot(Sight.CROSSING, distance = 7f, above = 1.3f, lookAt = 0.15f),
      Shot(Sight.TALLEST_TOWER),
      // Far enough out to see the whole flag it tows go by, a little below it.
      Shot(Sight.PLANE, seconds = 1.7f, distance = 14f, above = -0.15f),
    )

  /**
   * True: the sights on the ground are flown in the order they stand round the city, one way
   * round, starting with whichever is nearest to where the camera starts, so the camera never
   * doubles back; the landmarks stand in different places for every link. The plane keeps its
   * place in [SHOTS]. False: everything is flown exactly as listed.
   */
  const val ROUND_THE_CITY = true

  /** Seconds from the city's own view to the first stop. It dives in from far out, so give it room. */
  const val LIFT_OFF = 2.0f

  /** Seconds from the last stop back to the city's own view. The city then folds into its code (0.85 s more). */
  const val LANDING = 2.2f

  /**
   * How far the camera stands from a sight whose [Shot.distance] is null: [NEAR] plus [PER_HEIGHT]
   * for each module of the sight's height, kept between [CLOSEST] and [FARTHEST].
   */
  const val NEAR = 5f
  const val PER_HEIGHT = 0.75f
  const val CLOSEST = 6f
  const val FARTHEST = 17f

  /**
   * The camera never comes nearer the city's middle than the edge of its plate plus this many
   * modules, so it never flies through buildings; between stops it goes round, not across.
   */
  const val OUTSIDE = 1.5f

  /** The camera stands at least this many modules higher than what it looks at (the plane excepted). */
  const val LOWEST_ABOVE = 3f
}
