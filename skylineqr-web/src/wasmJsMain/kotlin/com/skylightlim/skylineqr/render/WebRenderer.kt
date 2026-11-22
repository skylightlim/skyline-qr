package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityColours
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.Festivals
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Vehicles
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.SkylineEvent
import com.skylightlim.skylineqr.render.tour.Tour
import com.skylightlim.skylineqr.web.Gpu
import com.skylightlim.skylineqr.web.Browser
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The website's renderer: skylineqr-compose's CityRenderer for the browser (2026-11-21). The city,
 * the sky, the weather, the meteor, the bolts, fireworks, the tour and the timelapse are the same
 * code, line for line where it can be; what changes is the plumbing. The browser has one thread,
 * so nothing is posted between threads, frames come from requestAnimationFrame, and the WebGPU
 * calls themselves are in gpu.js ([Gpu]), which builds the same passes from the same WGSL.
 *
 * Keep the two in step: a change to what CityRenderer draws or when belongs here too.
 */
internal class WebRenderer(
  /** Runs if WebGPU cannot start or stops working. */
  private val onFailure: (String) -> Unit,
  /** Runs when lightning strikes or a meteor falls or lands. */
  private val onEvent: (SkylineEvent) -> Unit = {},
) {

  var city: City? = null
    set(value) {
      if (field === value) return
      field = value
      wake()
    }

  var time: TimeOfDay = TimeOfDay.AUTO
    set(value) {
      if (field == value) return
      field = value
      wake()
    }

  var weather: Weather = Weather.CLEAR
    set(value) {
      if (field == value) return
      field = value
      wake()
    }

  /** Where the person has turned and zoomed the camera. The renderer glides to it. */
  var view: View = View()
    set(value) {
      field = value
      wake()
    }

  /** Whether the weather's clouds show; see CityRenderer.clouds. */
  var clouds = true
    set(value) {
      if (field == value) return
      field = value
      wake()
    }

  /** Which inks the towers and the code take; see CityRenderer.colours. */
  var colours = CityColours.COLOURFUL
    set(value) {
      if (field == value) return
      field = value
      wake()
    }

  /** The browser asks for reduced motion: no lightning, gentler rain, an instant morph. */
  var calm = false

  /** The billboard's messages, shown on the city's screen if it has one; none, no screen. */
  var billboard: List<String> = emptyList()
    set(value) {
      if (field == value) return
      field = value
      wake()
    }

  private var ready = false
  private var width = 0
  private var height = 0
  private var uploaded: City? = null
  private var failed = false
  private var looping = false
  private var lastFrame = 0L
  private var seconds = 0f
  private var shown = View()
  private var lastPose: Pose? = null
  /** When the current meteor appeared, in [seconds]; negative while none is falling. */
  private var meteorAt = -1f
  private var meteorX = 0f
  private var meteorZ = 0f
  private var landed = false
  private var lastStrike = -1
  /** Where the current flash's bolt comes down: a tall tower's roof, x, z and height. */
  private var boltX = 0f
  private var boltZ = 0f
  private var boltTop = 0f
  private var boltAimed = false
  /** When the last bolt that destroys hit, in [seconds]; negative while the city is whole. */
  private var damageAt = -1f
  private var damageX = 0f
  private var damageZ = 0f
  private var damageTop = 0f
  private var damageStrike = 0
  /** Tall towers lightning goes for, x, z and height each, for [rodsFor]. */
  private var rods = FloatArray(0)
  private var rodsFor: City? = null
  /** The light and the air as last shown, which [atmosphere] eases toward [target]. */
  private var atmosphere: Atmosphere? = null
  private var target: Atmosphere? = null
  private var shownFor: Pair<TimeOfDay?, Weather>? = null
  private var blendFrom: Atmosphere? = null
  private var blendAt = 0f
  private var blendFor = 1f

  /** See [weatherSky]; [skyStarted] is false until the first frame takes the weather as it is. */
  private val shapes = SkyParts.Amounts()
  private var skyStarted = false
  /** How far haze's front has rolled across the plate as it comes, 0 to 1. */
  private var hazeFront = 1f
  /** The wind haze blows in on, 0 to 1: how many streaks of it show. */
  private var wind = 0f
  /** The storm cloud the current bolt leaves; -1 when it comes from high above. */
  private var boltCloud = -1

  /** Fireworks (2026-11-09): a long press's volleys, and Kuala Lumpur's own on its big nights. */
  private val fireworks = Fireworks()
  private var presses = 0
  private var festival = Festivals.NONE
  private var festivalAt = 0L
  private var nextShell = 0f
  private var shells = 0
  /** Where the big nights' fireworks go up from, x, top and z each, for [sitesFor]. */
  private var sites = FloatArray(0)
  private var sitesFor: City? = null

  /**
   * The tour (2026-11-09): waiting for the city to stand, flying, and the clear day or night it
   * flies under, whatever the person chose; after one is stopped, the camera eases from where it
   * left off ([leftTour], at [leftAt]) back to the city's own view.
   */
  private var tour: Tour? = null
  private var tourPending = false
  private var tourSky: Pair<TimeOfDay, Weather>? = null
  private var tourEnd: ((Boolean) -> Unit)? = null
  private var leftTour: Pose? = null
  private var leftAt = 0f
  /** Where the camera was as a tour began (its clock restarts, so its sway would jump), eased out of over [ENTER]. */
  private var enteredFrom: Pose? = null

  /**
   * The timelapse (2026-11-20, at the user's request): when it began on the renderer's clock, or -1
   * while none runs, and who hears how it ended.
   */
  private var lapseAt = -1f
  private var lapseEnd: ((Boolean) -> Unit)? = null
  private var atmosphereFor: Pair<TimeOfDay?, Weather>? = null
  private var atmosphereAt = 0L
  private val morph = Morph()
  private val frame = FloatArray(Wgsl.FRAME_FLOATS)
  private var board = Billboard.NONE
  private var boardFor: List<String> = emptyList()
  private val screen = FloatArray(Billboard.FLOATS)
  private val traffic = FloatArray(MAX_PARTS * CityPlanner.STRIDE)
  private val pa = FloatArray(4)

  /** Starts WebGPU on the page's canvas; [onFailure] hears if it cannot. */
  fun start(width: Int, height: Int) {
    this.width = width
    this.height = height
    Gpu.start(
      Wgsl.SOURCE, Wgsl.POST, Wgsl.FRAME_FLOATS, Wgsl.SHADOW_MAP_SIZE, MAX_PARTS * CityPlanner.STRIDE,
      FrameData.CUBE_MESH, FrameData.CUBE_INDICES, BillboardText.WIDTH, BillboardText.ROW * BillboardText.ROWS,
      onReady = {
        ready = true
        Gpu.resize(this.width, this.height)
        wake()
      },
      onFail = ::failNow,
    )
  }

  fun resize(width: Int, height: Int) {
    this.width = width
    this.height = height
    if (ready) {
      Gpu.resize(width, height)
      wake()
    }
  }

  /** [instantly] skips the morph, as the postcard does when it stands the city up for its photo. */
  fun setFlat(flat: Boolean, instantly: Boolean = false) {
    if (calm || instantly) morph.finish(flat) else morph.aim(flat, Browser.nanos())
    // A meteor, a bolt or fireworks only ever happen over the city, never the code.
    if (flat) {
      meteorAt = -1f
      damageAt = -1f
      fireworks.clear()
    }
    wake()
  }

  /**
   * A long-press at ([u], [v]) on screen, 0..1 from the top left: a meteor falls on the city there.
   * Ignored when flat, with animations off, or while one is already falling.
   */
  fun strike(u: Float, v: Float) {
    run {
      val city = city ?: return@run
      val pose = lastPose ?: return@run
      if (calm || meteorAt >= 0f || morph.heading > 0f) return@run
      val hit = pose.ground(u, v) ?: return@run
      val reach = city.modules / 2f - 0.5f
      meteorX = hit[0].coerceIn(-reach, reach)
      meteorZ = hit[2].coerceIn(-reach, reach)
      meteorAt = seconds
      landed = false
      onEvent(SkylineEvent.METEOR_FALLING)
      wake()
    }
  }

  /**
   * A long press at ([u], [v]) on screen, 0..1 from the top left, with fireworks chosen: three
   * shells go up from the ground there and burst above the skyline; a press on the sky bursts
   * them right there. Ignored when flat or with animations off.
   */
  fun fireworks(u: Float, v: Float) {
    run {
      val city = city ?: return@run
      val pose = lastPose ?: return@run
      if (calm || morph.heading > 0f) return@run
      val reach = city.modules / 2f - 0.5f
      val hit = pose.ground(u, v)
      val bx: Float
      val by: Float
      val bz: Float
      if (hit != null) {
        bx = hit[0].coerceIn(-reach, reach)
        bz = hit[2].coerceIn(-reach, reach)
        by = max(city.maxHeight * 0.7f, 7f) + 2f
      } else {
        // On the sky: where the ray crosses the upright plane through the city's middle, square to the camera.
        val dir = pose.ray(u, v)
        val along = dir[0] * pose.fwd[0] + dir[2] * pose.fwd[2]
        val t = if (along > 1e-3f) -(pose.eye[0] * pose.fwd[0] + pose.eye[2] * pose.fwd[2]) / along else 30f
        val edge = city.plateHalf + 2f
        bx = (pose.eye[0] + dir[0] * t).coerceIn(-edge, edge)
        by = (pose.eye[1] + dir[1] * t).coerceIn(5f, city.maxHeight + 14f)
        bz = (pose.eye[2] + dir[2] * t).coerceIn(-edge, edge)
      }
      fireworks.volley(seconds, bx.coerceIn(-reach, reach), bz.coerceIn(-reach, reach), bx, by, bz, presses++)
      wake()
    }
  }

  /**
   * Starts the tour: once the city stands (a folded code unfolds first), the renderer's clock
   * restarts so it is the same every time, the sky turns clear, by day or by night as the sun is,
   * and the camera flies [Tour] (its shot list is render/tour/TourScript.kt). [onEnd] runs on the main thread when it lands (true) or is
   * stopped or replaced (false).
   */
  fun startTour(onEnd: (completed: Boolean) -> Unit) {
    run {
      if (lapseAt >= 0f) endTimelapse(completed = false)
      tourEnd?.let { old -> old(false) }
      tourEnd = onEnd
      tour = null
      tourPending = true
      wake()
    }
  }

  /** Stops the tour, if one is on its way or flying: the camera eases back to the city's own view. */
  fun stopTour() {
    run {
      if (tour != null || tourPending) endTour(completed = false)
      wake()
    }
  }

  /**
   * Starts the timelapse ([Timelapse]): the clock runs from the middle of the day to night in
   * fifteen seconds, under the person's weather, wherever the camera is. The sky eases into it
   * from the hour shown, as a change of hour does. At the end the renderer stays at Night; [onEnd]
   * runs on the main thread when it gets there (true) or is stopped or replaced (false), when the
   * city eases back to its own hour.
   */
  fun startTimelapse(onEnd: (completed: Boolean) -> Unit) {
    run {
      if (tour != null || tourPending) endTour(completed = false)
      lapseEnd?.let { old -> old(false) }
      lapseEnd = onEnd
      lapseAt = seconds
      wake()
    }
  }

  /** Stops the timelapse, if one runs: the sky eases back to the hour the person chose. */
  fun stopTimelapse() {
    run {
      if (lapseAt >= 0f) endTimelapse(completed = false)
      wake()
    }
  }

  /** Ends a timelapse that has run its length. */
  private fun timelapse() {
    if (lapseAt >= 0f && seconds - lapseAt >= Timelapse.LENGTH) endTimelapse(completed = true)
  }

  private fun endTimelapse(completed: Boolean) {
    lapseAt = -1f
    // It ends exactly on Night's sky, so hold it there; the app follows by choosing Night.
    if (completed) time = TimeOfDay.NIGHT
    val done = lapseEnd
    lapseEnd = null
    done?.invoke(completed)
  }

  private fun wake() {
    if (looping || failed || !ready || width <= 0 || height <= 0) return
    looping = true
    Browser.nextFrame(::onFrame)
  }

  private fun onFrame(nanos: Long) {
    looping = false
    // On a 120 Hz screen draw every other frame, as on the phone.
    if (nanos - lastFrame in 1 until 10_000_000L) wake() else if (drawFrame(nanos)) wake()
  }

  private fun failNow(message: String) {
    if (failed) return
    failed = true
    Browser.log("SkylineQR: WebGPU renderer stopped: $message")
    onFailure(message)
  }

  /**
   * The light and the air the hour and weather ask for. Auto follows the clock, and the real sun and
   * moon over Kuala Lumpur, so it is refreshed every second.
   */
  private fun targetAtmosphere(nanos: Long): Atmosphere {
    val key = sky()
    // The timelapse's clock moves every frame, on the fixed hours' sun and moon.
    val hour = key.first ?: return Atmospheres.at(TimeOfDay.AUTO, key.second, Timelapse.minute(seconds - lapseAt))
    val cached = target
    if (cached != null && key == atmosphereFor && (key.first != TimeOfDay.AUTO || nanos - atmosphereAt < 1_000_000_000L)) {
      return cached
    }
    val now = Browser.millis()
    return Atmospheres.at(hour, key.second, Atmospheres.clockMinute(now), nowMillis = now).also {
      target = it
      atmosphereFor = key
      atmosphereAt = nanos
    }
  }

  /**
   * The hour and weather shown: the person's, the tour's clear day or night, or, with no hour,
   * the timelapse's moving clock under the person's weather.
   */
  private fun sky(): Pair<TimeOfDay?, Weather> = tourSky ?: ((if (lapseAt >= 0f) null else time) to weather)

  /**
   * The light and the air as shown (2026-11-07): a change of hour eases in over [TIME_EASE]
   * seconds, a change of weather over [WEATHER_EASE] in stages. Into rain or a storm the sky darkens
   * first, the rain begins once the clouds are mostly in and the lightning last; out of it the rain
   * and lightning stop first and the sky clears after. With animations off it changes at once.
   */

  private fun atmosphere(nanos: Long): Atmosphere {
    val goal = targetAtmosphere(nanos)
    val key = sky()
    val before = shownFor
    if (before == null || calm) {
      shownFor = key
      atmosphere = goal
      return goal
    }
    if (key != before) {
      blendFrom = atmosphere ?: goal
      blendAt = seconds
      blendFor = if (key.second != before.second) WEATHER_EASE else TIME_EASE
      shownFor = key
      // Haze rolls in from upwind each time it comes out of clear air, rather than appearing
      // everywhere at once. Going, it thins away everywhere together.
      if (key.second == Weather.HAZE && (blendFrom?.haze ?: 0f) < 0.05f) hazeFront = 0f
    }
    val from = blendFrom ?: return goal.also { atmosphere = it }
    val e = (seconds - blendAt) / blendFor
    if (e >= 1f) {
      blendFrom = null
      atmosphere = goal
      return goal
    }
    fun span(start: Float, end: Float) = smooth(((e - start) / (end - start)).coerceIn(0f, 1f))
    val raining = goal.rain > from.rain
    val air = Atmospheres.blend(
      from,
      goal,
      light = span(0f, 1f),
      rain = if (raining) span(0.45f, 1f) else span(0f, 0.3f),
      wet = if (raining) span(0.5f, 1f) else span(0f, 1f),
      haze = span(0f, 1f),
      storm = if (goal.storm > from.storm) span(0.75f, 1f) else span(0f, 0.2f),
    )
    atmosphere = air
    return air
  }

  /**
   * Where each weather's own sky is (see [SkyParts.Amounts]): the clear sky's clouds and a storm's
   * deck blow in from upwind when their weather comes and on downwind when it goes, or when
   * [clouds] is turned off, at a steady pace; the sun and the moon and the deck's darkness ease a
   * little behind the light; haze rolls in at a steady wind's pace, its wind streaks a little ahead
   * of it.
   */
  private fun weatherSky(dt: Float) {
    val weather = sky().second
    val fairWanted = clouds && weather == Weather.CLEAR
    val deckWanted = clouds && (weather == Weather.STORM || weather == Weather.RAIN)
    val shine = if (weather == Weather.CLEAR || weather == Weather.HAZE) 1f else 0f
    val dark = if (weather == Weather.RAIN) 0.7f else 1f
    val gusts = if (weather == Weather.HAZE) 1f else 0f
    if (!skyStarted || calm) {
      skyStarted = true
      shapes.fair = if (fairWanted) 0f else 1f
      shapes.deck = if (deckWanted) 0f else 1f
      shapes.shine = shine
      shapes.overcast = dark
      wind = gusts
      hazeFront = 1f
      return
    }
    fun toward(now: Float, to: Float) = now + (to - now) * (1f - exp(-dt / (if (to > now) SKY_IN else SKY_OUT)))
    shapes.fair = SkyParts.travel(shapes.fair, fairWanted, dt)
    shapes.deck = SkyParts.travel(shapes.deck, deckWanted, dt)
    shapes.shine = toward(shapes.shine, shine)
    shapes.overcast = toward(shapes.overcast, dark)
    wind = toward(wind, gusts)
    hazeFront = min(1f, hazeFront + dt / HAZE_IN)
  }

  /** Draws one frame. Returns whether another one is needed. */
  private fun drawFrame(nanos: Long): Boolean {
    if (!ready) return false
    try {
      if (lastFrame == 0L) lastFrame = nanos
      val dt = min(0.05f, (nanos - lastFrame) / 1e9f)
      seconds += dt
      lastFrame = nanos
      beginTour(nanos, city)
      timelapse()

      // The camera swings over the whole morph; the city folds block by block, landing before it.
      val swing = morph.swing(nanos)
      val fold = morph.fold(nanos)
      val current = city
      upload(current)
      if (billboard != boardFor) drawBillboard()
      // Glide toward the person's orbit and zoom: quick enough to feel direct, smooth on release.
      val target = view
      val k = 1f - exp(-dt * 16f)
      shown =
        View(
          shown.yaw + (target.yaw - shown.yaw) * k,
          shown.elevation + (target.elevation - shown.elevation) * k,
          shown.zoom + (target.zoom - shown.zoom) * k,
        )
      val air = atmosphere(nanos)
      weatherSky(dt)
      val impact = impactSeconds()
      if (impact >= 0f && !landed) {
        landed = true
        onEvent(SkylineEvent.METEOR_IMPACT)
      }
      if (!calm) {
        val strike = strikeOf(seconds, air.storm)
        if (strike >= 0 && strike != lastStrike) {
          lastStrike = strike
          aim(current, strike, fold)
          onEvent(SkylineEvent.LIGHTNING)
        }
      }
      if (damageAt >= 0f && seconds - damageAt > DAMAGE_AFTER) damageAt = -1f
      // The ground shakes for half a second after the impact.
      val shake = if (impact in 0f..0.6f) exp(-impact * 6f) else 0f
      val shaken = View(shown.yaw + 0.02f * shake * sin(impact * 63f), shown.elevation + 0.015f * shake * cos(impact * 47f), shown.zoom)
      val pose = tourPose(current, Camera.pose(current, width.toFloat() / height, swing, seconds, shaken))
      lastPose = pose
      festivities(current, fold)
      writeFrame(pose, Camera.light(current, air.sunDir), fold, current, air, impact)
      val moving = writeTraffic(current, fold, impact)
      val drops = (MAX_DROPS * air.rain * (if (calm) 0.4f else 1f) * (1f - fold)).toInt()
      val gusts = if (calm) 0 else (MAX_GUSTS * wind * (1f - fold)).toInt()

      val sky = air.skyHorizon
      val strength = BLOOM * (1f - fold)
      Gpu.draw(
        cityCount = if (current != null && uploaded === current) current.count else 0,
        moving = moving,
        drops = drops,
        gusts = gusts,
        fold = fold,
        clear = floatArrayOf(sky[0] * 0.85f, sky[1] * 0.85f, sky[2] * 0.85f),
        post = floatArrayOf(BLOOM_THRESHOLD, BLOOM_KNEE, strength, fold),
        gustVertices = Wgsl.GUST_VERTICES,
        gustBase = Wgsl.GUST_BASE,
        indices = FrameData.CUBE_INDICES.size,
      )
      // The flat code is still: once the morph settles, draw again only when something changes.
      return !(morph.heading == 1f && morph.settled(nanos))
    } catch (e: Throwable) {
      failNow(e.message ?: e.toString())
      return false
    }
  }

  /**
   * Starts a tour that is waiting, once the city stands still: the clock back to 0, anything
   * falling or burning cleared, the sky clear by day or night as the person's sky is now, and the
   * weather's shapes in place at once.
   */
  private fun beginTour(nanos: Long, city: City?) {
    if (!tourPending || city == null || morph.heading != 0f || !morph.settled(nanos)) return
    tourPending = false
    val night = targetAtmosphere(nanos).night > 0.5f
    seconds = 0f
    nextShell = 0f
    meteorAt = -1f
    landed = false
    damageAt = -1f
    lastStrike = -1
    boltAimed = false
    fireworks.clear()
    tourSky = (if (night) TimeOfDay.NIGHT else TimeOfDay.DAY) to Weather.CLEAR
    shownFor = null
    skyStarted = false
    hazeFront = 1f
    shown = View()
    leftTour = null
    enteredFrom = lastPose
    tour = Tour(city, width.toFloat() / height)
  }

  private fun endTour(completed: Boolean) {
    val flying = tour != null
    tour = null
    tourPending = false
    tourSky = null
    if (completed || !flying) leftTour = null else leftAt = seconds
    val done = tourEnd
    tourEnd = null
    done?.invoke(completed)
  }

  /** The camera: the tour's while one flies, easing back from where a stopped one left off, else the city's own ([own]). */
  private fun tourPose(city: City?, own: Pose): Pose {
    val flying = tour
    if (flying != null) {
      if (seconds >= flying.length) {
        endTour(completed = true)
        return own
      }
      val path = flying.pose(seconds)
      val from = enteredFrom
      val blended = if (from == null || seconds >= ENTER) path else between(from, path, smooth(seconds / ENTER), city)
      if (seconds >= ENTER) enteredFrom = null
      return blended.also { leftTour = it }
    }
    val left = leftTour ?: return own
    val k = (seconds - leftAt) / LEAVE
    if (k >= 1f || city == null) {
      leftTour = null
      return own
    }
    return between(left, own, smooth(k), city)
  }

  /** A camera [e] of the way from [a] to [b]: where each stands, and where each looks, half a plate ahead. */
  private fun between(a: Pose, b: Pose, e: Float, city: City?): Pose {
    fun mix(p: FloatArray, q: FloatArray) = FloatArray(3) { p[it] + (q[it] - p[it]) * e }
    val reach = if (city == null) 12f else city.plateHalf
    val from = FloatArray(3) { a.eye[it] + a.fwd[it] * reach }
    val to = FloatArray(3) { b.eye[it] + b.fwd[it] * reach }
    return Camera.look(mix(a.eye, b.eye), mix(from, to), b.aspect, a.shiftY + (b.shiftY - a.shiftY) * e)
  }

  /**
   * Kuala Lumpur's big nights in Now ([Festivals]): shells go up from the landmarks' tops and the
   * square, one every three quarters of a second, five times as often in the midnight finale.
   */
  private fun festivities(city: City?, fold: Float) {
    if (calm || city?.landmarks == null || tour != null || time != TimeOfDay.AUTO || fold > 0.5f) return
    val nowNanos = Browser.nanos()
    if (festivalAt == 0L || nowNanos - festivalAt > 1_000_000_000L) {
      festival = Festivals.at(Browser.millis())
      festivalAt = nowNanos
    }
    if (festival == Festivals.NONE || seconds < nextShell) return
    if (sitesFor !== city) {
      sites = launchSites(city)
      sitesFor = city
    }
    if (sites.isEmpty()) return
    val finale = festival == Festivals.FINALE
    val k = shells++
    val site = k % (sites.size / 3)
    val from = floatArrayOf(sites[site * 3], sites[site * 3 + 1], sites[site * 3 + 2])
    val burst = floatArrayOf(
      from[0] + (unit(k * 7 + 1) - 0.5f) * 5f,
      max(from[1], city.maxHeight * 0.55f) + 3f + 4f * unit(k * 7 + 2),
      from[2] + (unit(k * 7 + 3) - 0.5f) * 5f,
    )
    fireworks.launch(seconds, from, burst, k + 1000, if (finale) 1.3f else 1f)
    nextShell = seconds + if (finale) 0.16f else 0.75f
  }

  private fun upload(city: City?) {
    if (city == null || city === uploaded) return
    Gpu.uploadCity(city.instances, city.count * CityPlanner.STRIDE)
    uploaded = city
  }

  /**
   * Picks the tower [strike]'s bolt comes down on, one of the tallest on the camera's side of the
   * city, so the landmarks do not hide it, and whether it destroys it: about half of them do,
   * while the city stands in 3D and no other is still rebuilding.
   */
  private fun aim(city: City?, strike: Int, fold: Float) {
    boltAimed = false
    if (city == null) return
    if (rodsFor !== city) {
      rods = lightningRods(city)
      rodsFor = city
    }
    val eye = lastPose?.eye
    val near = (0 until rods.size / 3).filter { eye == null || rods[it * 3] * eye[0] + rods[it * 3 + 1] * eye[2] > 0f }
    val pool = near.ifEmpty { (0 until rods.size / 3).toList() }
    if (pool.isEmpty()) return
    // Lightning comes out of a cloud (2026-11-07, at the user's request), so while the deck is over
    // the city it goes for a roof with a cloud close over it, then leaves from under that cloud.
    // With the clouds off, or the deck still blowing in, it comes from high above.
    val decked = abs(shapes.deck) < DECKED
    val cloud = FloatArray(4)
    fun nearest(rod: Int) = SkyParts.nearestStormCloud(rods[rod * 3], rods[rod * 3 + 1], city, seconds, calm, shapes)
    val covered = if (!decked) emptyList() else pool.filter { rod ->
      SkyParts.stormCloud(nearest(rod), city, SkyParts.drift(seconds, calm), shapes, cloud)
      hypot(cloud[0] - rods[rod * 3], cloud[2] - rods[rod * 3 + 1]) < cloud[3] * UNDER_CLOUD
    }
    val choice = covered.ifEmpty { pool }
    val k = choice[min(choice.size - 1, (unit(strike * 7 + 3) * choice.size).toInt())]
    boltX = rods[k * 3]
    boltZ = rods[k * 3 + 1]
    boltTop = rods[k * 3 + 2]
    boltCloud = if (decked) nearest(k) else -1
    boltAimed = true
    if (unit(strike * 5 + 1) < 0.55f && damageAt < 0f && fold < 0.01f && morph.heading == 0f && meteorAt < 0f) {
      damageAt = seconds
      damageX = boltX
      damageZ = boltZ
      damageTop = boltTop
      damageStrike = strike
    }
  }

  /** Seconds since the bolt that destroyed, or -1 while the city is whole. */
  private fun damageSeconds() = if (damageAt < 0f) -1f else seconds - damageAt

  /**
   * Seconds since the current meteor hit: negative while it is still falling, and [NO_IMPACT]
   * once the city has rebuilt or when none is falling.
   */
  private fun impactSeconds(): Float {
    if (meteorAt < 0f) return NO_IMPACT
    val since = seconds - meteorAt - METEOR_FALL
    if (since > METEOR_AFTER) {
      meteorAt = -1f
      return NO_IMPACT
    }
    return since
  }

  /** Packs the Frame uniform, in the order of the WGSL struct. */
  private fun writeFrame(
    pose: Pose,
    light: FloatArray,
    fold: Float,
    city: City?,
    air: Atmosphere,
    impact: Float,
  ) {
    val flash = if (calm) 0f else lightning(seconds, air.storm)
    FrameData.pack(
      frame, pose, light, fold, city, air, seconds, flash, calm, morph.raising, hazeFront, wind,
      meteor = floatArrayOf(meteorX, meteorZ, if (impact >= 0f) impact else -1f, if (impact >= 0f) exp(-impact * 7f) else 0f),
      damage = floatArrayOf(damageX, damageZ, damageSeconds(), 0f),
      bolt = floatArrayOf(boltX, boltZ, boltTop, if (boltAimed && flash > 0f) 1f else 0f),
      screen = screen.also { board.state(seconds, calm, it) },
      stars = FrameData.stars(city, air, height),
      colours = colours,
    )
    Gpu.writeFrame(frame)
  }

  /** Draws the billboard's messages into its texture, and times them. */
  private fun drawBillboard() {
    val messages = billboard
    boardFor = messages
    val widths = BillboardText.draw(messages)
    board = if (widths.isEmpty()) Billboard.NONE else Billboard(widths, BillboardText.VIEW)
  }

  /**
   * Everything that moves, rebuilt as boxes every frame. It shrinks away as the city folds, so the
   * code itself stays modules only. Returns how many boxes to draw.
   */
  private fun writeTraffic(city: City?, fold: Float, impact: Float): Int {
    val shrink = 1f - fold
    if (city == null || shrink < 0.05f || city.movers.isEmpty()) return 0

    var count = 0
    val box =
      Vehicles.Sink { x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper ->
        if (count < MAX_PARTS) {
          val o = count * CityPlanner.STRIDE
          count++
          traffic[o] = x
          traffic[o + 1] = y
          traffic[o + 2] = z
          traffic[o + 3] = sx * shrink
          traffic[o + 4] = sy * shrink
          traffic[o + 5] = sz * shrink
          traffic[o + 6] = role
          traffic[o + 7] = glow
          traffic[o + 8] = 0f
          traffic[o + 9] = rnd
          traffic[o + 10] = yaw
          traffic[o + 11] = taper
        }
      }
    // While a meteor strikes and the city rebuilds, nothing drives through the blast.
    val clearing = impact > -0.3f && impact != NO_IMPACT
    for (m in city.movers) {
      if (clearing) {
        m.at(seconds, pa)
        if (pa[1] < 6f && hypot(pa[0] - meteorX, pa[2] - meteorZ) < BLAST + 1f) continue
      }
      Vehicles.emit(m, seconds, box, pa)
    }
    Vehicles.signals(city.signals, seconds, box)
    val spot = city.screen
    if (spot != null && board !== Billboard.NONE) {
      box.box(spot[0], spot[1], spot[2], spot[3], spot[4], 0.08f, CityPlanner.ROLE_SCREEN, 0f, 0f, 0f, 1f)
    }
    if (meteorAt >= 0f) meteorParts({ x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper -> box.box(x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper) }, impact)
    val flash = if (calm) 0f else lightning(seconds, atmosphere?.storm ?: 0f)
    val bolting = boltAimed && flash > 0.12f
    if (bolting) SkyParts.bolt(box, city, seconds, calm, shapes, lastStrike, boltCloud, boltX, boltZ, boltTop, flash)
    val damage = damageSeconds()
    if (damage >= 0f) fragmentParts(box, damage)
    val air = atmosphere
    if (air != null) SkyParts.emit(box, city, seconds, calm, shapes, air, lit = if (boltAimed) boltCloud else -1, flash = flash)
    city.fountain?.let { Fountain.emit(box, it, if (calm) 0f else seconds) }
    fireworks.emit(box, seconds)
    if (count == 0) return 0
    // Only the boxes in use, not the whole 98 KB buffer.
    Gpu.writeTraffic(traffic, count * CityPlanner.STRIDE)
    return count
  }

  /**
   * The meteor while it falls, a white-hot head with a trail cooling to orange, and the fireball
   * that bursts and shrinks away at the impact.
   */
  private fun meteorParts(
    box: (Float, Float, Float, Float, Float, Float, Float, Float, Float, Float, Float) -> Unit,
    impact: Float,
  ) {
    val fall = impact + METEOR_FALL
    if (fall < METEOR_FALL) {
      for (k in 0 until 20) {
        val t = max(0f, fall - k * 0.016f) / METEOR_FALL
        val u = t.toDouble().pow(1.7).toFloat()
        // From high up and over to the left of the view, so it streaks across the screen.
        val x = meteorX - 15f * (1f - u)
        val y = 0.3f + 32f * (1f - u)
        val z = meteorZ + 3f * (1f - u)
        val size = 1.1f * 0.92.pow(k.toDouble()).toFloat()
        box(x, y, z, size, size, size, CityPlanner.ROLE_METEOR, 1f - k / 20f, 0.5f, k * 0.7f, 1f)
      }
    } else if (impact < 0.45f) {
      val grow = 1.5f + 11f * (impact / 0.45f).toDouble().pow(0.6).toFloat()
      val fade = 1f - smooth((impact - 0.22f) / 0.23f)
      for (turn in floatArrayOf(0f, 0.785f)) {
        box(meteorX, grow * 0.3f * fade, meteorZ, grow * fade, grow * 0.6f * fade, grow * fade, CityPlanner.ROLE_METEOR, 1f - impact * 1.6f, 0.5f, turn, 0.3f)
      }
    }
  }

  /**
   * What the bolt does to the roof it destroys: a fireball bursts off it, burning fragments fly on
   * parabolas, cooling from white-hot to orange and shrinking away in the air, never landing on the
   * code, and dark smoke rises from the stub until the tower grows back.
   */
  private fun fragmentParts(box: Vehicles.Sink, t: Float) {
    if (t < 0.4f) {
      val grow = 0.4f + 2.6f * (t / 0.4f).toDouble().pow(0.5).toFloat()
      val fade = 1f - smooth((t - 0.15f) / 0.25f)
      for (turn in floatArrayOf(0f, 0.785f)) {
        box.box(damageX, damageTop, damageZ, grow * fade, grow * 0.8f * fade, grow * fade, CityPlanner.ROLE_METEOR, 1f - t * 1.5f, 0.5f, turn, 0.5f)
      }
    }
    val seed = damageStrike * 97
    for (k in 0 until 22) {
      val a = unit(seed + k * 5 + 1) * 6.2832f
      val out = 2f + 4f * unit(seed + k * 5 + 2)
      val up = 4f + 7f * unit(seed + k * 5 + 3)
      val life = 1.2f + 0.8f * unit(seed + k * 5 + 4)
      if (t > life) continue
      val x = damageX + cos(a) * out * t
      val z = damageZ + sin(a) * out * t
      val y = damageTop + up * t - 0.5f * 22f * t * t
      if (y < 0.2f) continue
      val size = (0.3f + 0.3f * unit(seed + k * 5 + 5)) * (1f - t / life)
      box.box(x, y, z, size, size, size, CityPlanner.ROLE_METEOR, 0.55f * (1f - t / life), 0.5f, a + t * 9f, 1f)
    }
    // Smoke, a puff at a time, each rising, drifting downwind and swelling as it thins away.
    val stub = damageTop * 0.18f
    for (k in 0 until 10) {
      val born = 0.3f + k * 0.28f
      val age = t - born
      if (age < 0f || age > 1.6f || born > DAMAGE_AFTER - 1.4f) continue
      val swell = 0.25f + 0.55f * age
      val thin = 1f - smooth((age - 0.7f) / 0.9f)
      val drift = 0.9f * age
      box.box(
        damageX + drift + (unit(seed + k + 60) - 0.5f) * 0.5f, stub + 0.3f + 2.4f * age, damageZ + drift * 0.4f + (unit(seed + k + 80) - 0.5f) * 0.5f,
        swell * thin, swell * 0.8f * thin, swell * thin, CityPlanner.ROLE_DETAIL, 0.2f, SMOKE, k * 0.9f, 0.8f,
      )
    }
  }
  companion object {
    /** Boxes for everything that moves: vehicles and their parts, the weather, the meteor, fireworks and the fountain's water. */
    private const val MAX_PARTS = 2048

    /** How long the camera takes to ease back from a stopped tour, and into one from where it was. */
    private const val LEAVE = 0.7f
    private const val ENTER = 0.5f

    /** How long a change of hour, and of weather, takes to ease in. */
    private const val TIME_EASE = 1.5f
    private const val WEATHER_EASE = 3f

    /** How quickly the weather's own sky comes in and goes, in seconds to two thirds of the way. */
    private const val SKY_IN = 0.9f
    private const val SKY_OUT = 0.6f

    /** How long haze's front takes to roll across the plate as it comes. */
    private const val HAZE_IN = 4.5f

    /** How near its place the deck must be for lightning to come out of it: most of the way in. */
    private const val DECKED = 0.3f

    /** How close under a storm cloud a roof must be for lightning to prefer it, in cloud sizes. */
    private const val UNDER_CLOUD = 1.1f

    /** The most wind streaks haze blows through the city at once. */
    private const val MAX_GUSTS = 64
    /** How long a bolt's damage lasts, the crumble and the rebuild, and how long its fragments fly. */
    private const val DAMAGE_AFTER = 4.4f
    /** The grey of the smoke after a bolt, in a detail part's random slot. */
    private const val SMOKE = 0.45f
    /** Seconds the meteor takes to fall, and how long the strike and the rebuild last after. */
    private const val METEOR_FALL = 0.9f
    private const val METEOR_AFTER = 3.6f
    /** Reach of the blast in modules. Keep in step with BLAST in Wgsl. */
    private const val BLAST = 6.5f
    private const val NO_IMPACT = -1000f
    private const val MAX_DROPS = 2600
    private const val ROTOR_SPIN = 26f
    /** What counts as brighter than white, how softly it starts, and how strong the glow is. */
    private const val BLOOM_THRESHOLD = 1.05f
    private const val BLOOM_KNEE = 0.2f
    private const val BLOOM = 0.45f

    /**
     * Lightning: in each 3.4 s spell, nearly always, one strike at a random moment that flickers
     * three times and fades. Pure function of time, so it needs no state.
     */
    internal fun lightning(t: Float, storm: Float): Float {
      if (strikeOf(t, storm) < 0) return 0f
      val since = t - strikeTime(floor(t / SPELL).toInt())
      val second = if (since > 0.10f) 0.9f * exp(-(since - 0.10f) * 12f) else 0f
      val third = if (since > 0.24f) 0.6f * exp(-(since - 0.24f) * 10f) else 0f
      return min(1f, exp(-since * 9f) + second + third) * storm
    }

    /** The spell whose strike is flashing at [t], or -1 when the sky is dark. */
    internal fun strikeOf(t: Float, storm: Float): Int {
      if (storm <= 0f) return -1
      val spell = floor(t / SPELL).toInt()
      if (unit(spell * 2 + 1) > 0.88f) return -1
      val since = t - strikeTime(spell)
      return if (since in 0f..0.7f) spell else -1
    }

    private const val SPELL = 3.4f

    private fun strikeTime(spell: Int) = spell * SPELL + 0.2f + unit(spell * 2 + 2) * (SPELL - 1.0f)

    /** Where the big nights' fireworks go up from, x, top and z each: the landmarks' tops and the square. */
    internal fun launchSites(city: City): FloatArray {
      val plan = city.landmarks ?: return FloatArray(0)
      val n = city.modules
      val half = n / 2f
      val out = ArrayList<Float>()
      fun site(x: Float, top: Float, z: Float) = out.addAll(listOf(x, top, z))
      for (f in plan.all) site(f.centerX - half, plan.height(f, n), f.centerZ - half)
      site(3.5f - half, 0.2f, n - 3.5f - half)
      return out.toFloatArray()
    }

    /** The roofs of the tallest towers, x, z and height each: where lightning comes down. */
    internal fun lightningRods(city: City): FloatArray {
      val d = city.instances
      val found = ArrayList<FloatArray>()
      for (i in 0 until city.count) {
        val o = i * CityPlanner.STRIDE
        if (d[o + 6] == CityPlanner.ROLE_TOWER && d[o + 7] >= 0.5f && !CityPlanner.isPattern(d[o + 8])) {
          found += floatArrayOf(d[o], d[o + 2], d[o + 1] + d[o + 4] / 2f)
        }
      }
      return FloatArray(found.size * 3) { found[it / 3][it % 3] }
    }

    /** 0..1 from an integer: lowbias32 (Chris Wellons, public domain), as in the shaders. */
    private fun unit(n: Int): Float {
      var h = n * -0x61c88647 // spread consecutive inputs before mixing them
      h = h xor (h ushr 16)
      h *= 0x7feb352d
      h = h xor (h ushr 15)
      h *= -0x7b935975 // 0x846ca68b
      h = h xor (h ushr 16)
      return (h ushr 8) / 16777216f
    }

    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
  }
}
