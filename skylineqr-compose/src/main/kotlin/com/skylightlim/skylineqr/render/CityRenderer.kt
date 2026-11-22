package com.skylightlim.skylineqr.render

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import androidx.webgpu.AddressMode
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.CompareFunction
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostReason
import androidx.webgpu.ErrorFilter
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUDepthStencilState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUMultisampleState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDepthStencilAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPassEncoder
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUSamplerBindingLayout
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.GPUTexelCopyBufferInfo
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureBindingLayout
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexAttribute
import androidx.webgpu.GPUVertexBufferLayout
import androidx.webgpu.GPUVertexState
import androidx.webgpu.IndexFormat
import androidx.webgpu.LoadOp
import androidx.webgpu.MapMode
import androidx.webgpu.OptionalBool
import androidx.webgpu.PresentMode
import androidx.webgpu.SamplerBindingType
import androidx.webgpu.ShaderStage
import androidx.webgpu.StoreOp
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureSampleType
import androidx.webgpu.TextureUsage
import androidx.webgpu.TextureViewDimension
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.VertexFormat
import androidx.webgpu.VertexStepMode
import androidx.webgpu.helper.Util
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createWebGpu
import com.skylightlim.skylineqr.city.Atmosphere
import com.skylightlim.skylineqr.city.Atmospheres
import com.skylightlim.skylineqr.city.City
import com.skylightlim.skylineqr.city.CityPlanner
import com.skylightlim.skylineqr.city.Festivals
import com.skylightlim.skylineqr.city.Vehicles
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.SkylineEvent
import com.skylightlim.skylineqr.render.tour.Tour
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Draws the city with WebGPU. One cube mesh and one instance buffer draw the whole city in a single
 * instanced call, once from the sun for the shadow map and once from the camera. The camera, the
 * light and the weather are one uniform buffer, so changing them costs nothing.
 *
 * Dawn is not thread-safe, so every GPU call happens on one render thread. The UI thread only sets
 * the volatile fields below and hands surfaces over and back.
 */
internal class CityRenderer(
  /** Runs on the main thread if WebGPU cannot start or stops working. */
  private val onFailure: (Throwable) -> Unit,
  /** Runs on the main thread when lightning strikes or a meteor falls or lands. */
  private val onEvent: (SkylineEvent) -> Unit = {},
  /** Runs on the main thread once, when the first frame with a city in it is on screen. */
  private val onFirstCity: () -> Unit = {},
) {

  @Volatile
  var city: City? = null
    set(value) {
      if (field === value) return
      field = value
      post { wake() }
    }

  @Volatile
  var time: TimeOfDay = TimeOfDay.AUTO
    set(value) {
      if (field == value) return
      field = value
      post { wake() }
    }

  @Volatile
  var weather: Weather = Weather.CLEAR
    set(value) {
      if (field == value) return
      field = value
      post { wake() }
    }

  /** Where the person has turned and zoomed the camera. The renderer glides to it. */
  @Volatile
  var view: View = View()
    set(value) {
      field = value
      post { wake() }
    }

  /**
   * Whether the weather's clouds show (2026-11-07, at the user's request; a setting in the app).
   * Off, the clear sky's clouds and a storm's deck blow away downwind, and lightning comes from
   * high above; on, they blow back in from upwind.
   */
  @Volatile
  var clouds = true
    set(value) {
      if (field == value) return
      field = value
      post { wake() }
    }

  /** Android's "Remove animations" is on: no lightning, gentler rain, an instant morph. */
  @Volatile var calm = false

  /** The billboard's messages, shown on the city's screen if it has one; none, no screen. */
  @Volatile
  var billboard: List<String> = emptyList()
    set(value) {
      if (field == value) return
      field = value
      post { wake() }
    }

  /** Frames presented so far. The on-device test reads it to check the frame rate. */
  @Volatile
  var framesDrawn = 0
    private set

  /** Whether a frame with a city in it has been presented, for [onFirstCity]. */
  private var cityShown = false

  private val thread = HandlerThread("SkylineQR-WebGPU", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
  private val handler = Handler(thread.looper)
  private val dispatcher = handler.asCoroutineDispatcher("SkylineQR-WebGPU")
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val main = Handler(Looper.getMainLooper())

  // Everything below belongs to the render thread.
  private var gpuJob: Deferred<WebGpu>? = null
  private var gpu: WebGpu? = null
  private var queue: GPUQueue? = null
  private var resources: Resources? = null
  private var pipelinesJob: Deferred<Pipelines>? = null
  private var pipelines: Pipelines? = null
  private var wanted: Surface? = null
  private var surface: GPUSurface? = null
  private var format = TextureFormat.Undefined
  private var alphaMode = CompositeAlphaMode.Auto
  private var width = 0
  private var height = 0
  private var targets: Targets? = null
  private var instances: GPUBuffer? = null
  private var uploaded: City? = null
  private var snapshotRequest: ((Bitmap) -> Unit)? = null
  private var snapshotFlat = true
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
  private val frameBytes = direct(Wgsl.FRAME_FLOATS * 4)
  private val post = FloatArray(4)
  private val postBytes = direct(16)
  private var board = Billboard.NONE
  private var boardFor: List<String> = emptyList()
  private val screen = FloatArray(Billboard.FLOATS)
  private val traffic = FloatArray(MAX_PARTS * CityPlanner.STRIDE)
  private val trafficBytes = direct(traffic.size * 4)
  private val pa = FloatArray(4)
  private val choreographer by lazy { Choreographer.getInstance() }
  private val frameCallback =
    Choreographer.FrameCallback { nanos ->
      looping = false
      // On a 120 Hz screen draw every other frame: a steady 60 instead of a 120 the phone cannot
      // hold (90 Hz and 60 Hz draw every frame).
      if (nanos - lastFrame in 1 until 10_000_000L) wake() else if (drawFrame(nanos)) wake()
    }

  fun setFlat(flat: Boolean) {
    post {
      if (calm) morph.finish(flat) else morph.aim(flat, System.nanoTime())
      // A meteor, a bolt or fireworks only ever happen over the city, never the code.
      if (flat) {
        meteorAt = -1f
        damageAt = -1f
        fireworks.clear()
      }
      wake()
    }
  }

  /**
   * A long-press at ([u], [v]) on screen, 0..1 from the top left: a meteor falls on the city there.
   * Ignored when flat, with animations off, or while one is already falling.
   */
  fun strike(u: Float, v: Float) {
    post {
      val city = city ?: return@post
      val pose = lastPose ?: return@post
      if (calm || meteorAt >= 0f || morph.heading > 0f) return@post
      val hit = pose.ground(u, v) ?: return@post
      val reach = city.modules / 2f - 0.5f
      meteorX = hit[0].coerceIn(-reach, reach)
      meteorZ = hit[2].coerceIn(-reach, reach)
      meteorAt = seconds
      landed = false
      main.post { onEvent(SkylineEvent.METEOR_FALLING) }
      wake()
    }
  }

  /**
   * A long press at ([u], [v]) on screen, 0..1 from the top left, with fireworks chosen: three
   * shells go up from the ground there and burst above the skyline; a press on the sky bursts
   * them right there. Ignored when flat or with animations off.
   */
  fun fireworks(u: Float, v: Float) {
    post {
      val city = city ?: return@post
      val pose = lastPose ?: return@post
      if (calm || morph.heading > 0f) return@post
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
    post {
      if (lapseAt >= 0f) endTimelapse(completed = false)
      tourEnd?.let { old -> main.post { old(false) } }
      tourEnd = onEnd
      tour = null
      tourPending = true
      wake()
    }
  }

  /** Stops the tour, if one is on its way or flying: the camera eases back to the city's own view. */
  fun stopTour() {
    post {
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
    post {
      if (tour != null || tourPending) endTour(completed = false)
      lapseEnd?.let { old -> main.post { old(false) } }
      lapseEnd = onEnd
      lapseAt = seconds
      wake()
    }
  }

  /** Stops the timelapse, if one runs: the sky eases back to the hour the person chose. */
  fun stopTimelapse() {
    post {
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
    main.post { done?.invoke(completed) }
  }

  /** Starts drawing into [androidSurface]. */
  fun attach(androidSurface: Surface, width: Int, height: Int) {
    post {
      wanted = androidSurface
      this.width = width
      this.height = height
      scope.launch { connect(androidSurface) }
    }
  }

  fun resize(width: Int, height: Int) {
    post {
      this.width = width
      this.height = height
      if (surface != null) {
        configure()
        wake()
      }
    }
  }

  /** Stops using the surface, and returns only once the GPU has let go of it, as SurfaceView needs. */
  fun detach() {
    val released = CountDownLatch(1)
    val posted = post {
      wanted = null
      releaseSurface()
      released.countDown()
    }
    // After close() the thread is gone, and so is everything it held.
    if (posted) released.await(2, TimeUnit.SECONDS)
  }

  /**
   * The next frame, handed to [onBitmap] on the main thread: in the flat, scannable pose, or with
   * [flat] false exactly as the city looks right now.
   */
  fun requestSnapshot(flat: Boolean = true, onBitmap: (Bitmap) -> Unit) {
    post {
      snapshotRequest = onBitmap
      snapshotFlat = flat
      wake()
    }
  }

  fun close() {
    post { shutdown() }
  }

  private fun shutdown() {
    releaseSurface()
    scope.cancel()
    instances?.let { it.destroy(); it.close() }
    pipelines?.close()
    resources?.close()
    queue?.close()
    gpu?.let {
      it.device.destroy()
      it.close()
    }
    gpu = null
    thread.quitSafely()
  }

  private fun post(block: () -> Unit): Boolean = handler.post(block)

  private suspend fun connect(androidSurface: Surface) {
    try {
      val gpu = (gpuJob ?: scope.async { startGpu() }.also { gpuJob = it }).await()
      if (wanted !== androidSurface) return // detached while the device was starting
      releaseSurface()
      val created =
        gpu.instance.createSurface(
          GPUSurfaceDescriptor(
            surfaceSourceAndroidNativeWindow =
              GPUSurfaceSourceAndroidNativeWindow(Util.windowFromSurface(androidSurface))
          )
        )
      surface = created
      val capabilities = created.getCapabilities(gpu.adapter)
      format = PREFERRED_FORMATS.firstOrNull { it in capabilities.formats } ?: capabilities.formats.first()
      alphaMode =
        if (CompositeAlphaMode.Opaque in capabilities.alphaModes) CompositeAlphaMode.Opaque
        else CompositeAlphaMode.Auto
      configure()
      // The surface format is only known now, and the pipelines are built for it, once.
      val resources = resources!!
      val job = pipelinesJob ?: scope.async { buildPipelines(gpu.device, resources, format) }.also { pipelinesJob = it }
      pipelines = job.await()
      if (wanted === androidSurface) wake()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      failNow(e)
    }
  }

  private suspend fun startGpu(): WebGpu {
    val here = Executor(Runnable::run)
    val descriptor =
      GPUDeviceDescriptor(
        deviceLostCallbackExecutor = here,
        uncapturedErrorCallbackExecutor = here,
        label = "SkylineQR",
        deviceLostCallback =
          DeviceLostCallback { _, reason, message ->
            if (reason != DeviceLostReason.Destroyed) fail(IllegalStateException("WebGPU device lost: $message"))
          },
        uncapturedErrorCallback =
          UncapturedErrorCallback { _, _, message -> fail(IllegalStateException("WebGPU error: $message")) },
      )
    val created = createWebGpu(dispatcher = dispatcher, deviceDescriptor = descriptor)
    val queue = created.device.queue
    this.queue = queue
    resources = Resources(created.device, queue)
    // Buffer maps and error scopes resolve when Dawn processes events.
    scope.launch { created.processEventsLoop() }
    gpu = created
    return created
  }

  private fun configure() {
    val surface = surface ?: return
    val device = gpu?.device ?: return
    if (width <= 0 || height <= 0) return
    surface.configure(
      GPUSurfaceConfiguration(
        device = device,
        width = width,
        height = height,
        format = format,
        usage = TextureUsage.RenderAttachment,
        alphaMode = alphaMode,
        presentMode = PresentMode.Fifo,
      )
    )
    targets?.close()
    targets = Targets(device, width, height, resources!!)
  }

  private fun releaseSurface() {
    if (looping) {
      choreographer.removeFrameCallback(frameCallback)
      looping = false
    }
    targets?.close()
    targets = null
    surface?.let {
      it.unconfigure()
      it.close()
    }
    surface = null
    lastFrame = 0L
  }

  private fun wake() {
    if (looping || failed || surface == null || pipelines == null || targets == null) return
    looping = true
    choreographer.postFrameCallback(frameCallback)
  }

  /** Reports a failure from any thread. */
  private fun fail(error: Throwable) {
    if (Looper.myLooper() == thread.looper) failNow(error) else post { failNow(error) }
  }

  private fun failNow(error: Throwable) {
    if (failed) return
    failed = true
    Log.e(TAG, "WebGPU renderer stopped", error)
    if (looping) {
      choreographer.removeFrameCallback(frameCallback)
      looping = false
    }
    main.post { onFailure(error) }
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
    val now = System.currentTimeMillis()
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
    val surface = surface ?: return false
    val pipelines = pipelines ?: return false
    val resources = resources ?: return false
    val targets = targets ?: return false
    val queue = queue ?: return false
    try {
      if (lastFrame == 0L) lastFrame = nanos
      val dt = min(0.05f, (nanos - lastFrame) / 1e9f)
      seconds += dt
      lastFrame = nanos
      beginTour(nanos, city)
      timelapse()

      val shot = snapshotRequest
      // A snapshot shows the finished pose it asked for, never a frame mid-morph.
      if (shot != null) morph.finish(flat = snapshotFlat)
      // The camera swings over the whole morph; the city folds block by block, landing before it.
      val swing = morph.swing(nanos)
      val fold = morph.fold(nanos)
      val current = city
      upload(queue, current)
      if (billboard != boardFor) drawBillboard(queue, resources)
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
        main.post { onEvent(SkylineEvent.METEOR_IMPACT) }
      }
      if (!calm) {
        val strike = strikeOf(seconds, air.storm)
        if (strike >= 0 && strike != lastStrike) {
          lastStrike = strike
          aim(current, strike, fold)
          main.post { onEvent(SkylineEvent.LIGHTNING) }
        }
      }
      if (damageAt >= 0f && seconds - damageAt > DAMAGE_AFTER) damageAt = -1f
      // The ground shakes for half a second after the impact.
      val shake = if (impact in 0f..0.6f) exp(-impact * 6f) else 0f
      val shaken = View(shown.yaw + 0.02f * shake * sin(impact * 63f), shown.elevation + 0.015f * shake * cos(impact * 47f), shown.zoom)
      val pose = tourPose(current, Camera.pose(current, width.toFloat() / height, swing, seconds, shaken))
      lastPose = pose
      festivities(current, fold)
      writeFrame(queue, resources, pose, Camera.light(current, air.sunDir), fold, current, air, impact)
      val moving = writeTraffic(queue, resources, current, fold, impact)
      val drops = (MAX_DROPS * air.rain * (if (calm) 0.4f else 1f) * (1f - fold)).toInt()
      val gusts = if (calm) 0 else (MAX_GUSTS * wind * (1f - fold)).toInt()

      val frameTexture = surface.getCurrentTexture()
      when (frameTexture.status) {
        SurfaceGetCurrentTextureStatus.SuccessOptimal,
        SurfaceGetCurrentTextureStatus.SuccessSuboptimal -> {}
        SurfaceGetCurrentTextureStatus.Outdated,
        SurfaceGetCurrentTextureStatus.Lost -> {
          configure()
          return true
        }
        SurfaceGetCurrentTextureStatus.Timeout -> return true
        else -> throw IllegalStateException("surface texture status ${frameTexture.status}")
      }
      frameTexture.texture.use { texture ->
        texture.createView().use { view ->
          encode(queue, pipelines, resources, targets, view, current, moving, drops, gusts, air, fold)
        }
        surface.present()
      }
      framesDrawn++
      if (!cityShown && current != null && uploaded === current) {
        cityShown = true
        main.post { onFirstCity() }
      }

      if (shot != null) {
        snapshotRequest = null
        capture(queue, pipelines, resources, targets, current, moving, drops, gusts, air, fold, shot)
      }
      // The flat code is still: once the morph settles, draw again only when something changes.
      return !(morph.heading == 1f && morph.settled(nanos))
    } catch (e: Throwable) {
      failNow(e)
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
    main.post { done?.invoke(completed) }
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
    val nowNanos = System.nanoTime()
    if (festivalAt == 0L || nowNanos - festivalAt > 1_000_000_000L) {
      festival = Festivals.at(System.currentTimeMillis())
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

  private fun encode(
    queue: GPUQueue,
    pipelines: Pipelines,
    resources: Resources,
    targets: Targets,
    view: GPUTextureView,
    city: City?,
    moving: Int,
    drops: Int,
    gusts: Int,
    air: Atmosphere,
    fold: Float,
  ) {
    val sky = air.skyHorizon
    val clear = GPUColor(sky[0] * 0.85, sky[1] * 0.85, sky[2] * 0.85, 1.0)
    val instances = instances
    val device = gpu!!.device
    device.createCommandEncoder().use { encoder ->
      if (fold < 0.999f && city != null && instances != null) {
        // The city from the sun, depth only: what the main pass compares against for shadows.
        encoder
          .beginRenderPass(
            GPURenderPassDescriptor(
              depthStencilAttachment =
                GPURenderPassDepthStencilAttachment(
                  view = resources.shadowView,
                  depthLoadOp = LoadOp.Clear,
                  depthStoreOp = StoreOp.Store,
                  depthClearValue = 1f,
                )
            )
          )
          .use { pass ->
            pass.setPipeline(pipelines.caster)
            pass.setBindGroup(0, resources.casterGroup)
            drawBlocks(pass, resources, instances, city.count, moving)
            pass.end()
          }
      }
      encoder
        .beginRenderPass(
          GPURenderPassDescriptor(
            colorAttachments =
              arrayOf(
                GPURenderPassColorAttachment(
                  clearValue = clear,
                  view = targets.colorView,
                  resolveTarget = targets.hdrView,
                  loadOp = LoadOp.Clear,
                  storeOp = StoreOp.Discard,
                )
              ),
            depthStencilAttachment =
              GPURenderPassDepthStencilAttachment(
                view = targets.depthView,
                depthLoadOp = LoadOp.Clear,
                depthStoreOp = StoreOp.Discard,
                depthClearValue = 1f,
              ),
          )
        )
        .use { pass ->
          pass.setBindGroup(0, resources.sceneGroup)
          pass.setPipeline(pipelines.sky)
          pass.draw(3)
          if (city != null && instances != null) {
            pass.setPipeline(pipelines.blocks)
            drawBlocks(pass, resources, instances, city.count, moving)
            if (moving > 0 && fold < 0.999f) {
              // The clouds, soft and blended over all that: their puffs are among the traffic's boxes.
              pass.setPipeline(pipelines.clouds)
              pass.drawIndexed(FrameData.CUBE_INDICES.size, moving)
            }
          }
          if (drops > 0) {
            pass.setPipeline(pipelines.rain)
            pass.draw(6, drops)
          }
          // Haze's wind streaks: the same pipeline, from instance GUST_BASE on, a ribbon each.
          if (gusts > 0) {
            pass.setPipeline(pipelines.rain)
            pass.draw(Wgsl.GUST_VERTICES, gusts, 0, Wgsl.GUST_BASE)
          }
          pass.end()
        }
      // The glow: halve down the bloom chain from what is brighter than white, then add each level
      // back onto the one above. None in the flat code, which the composite passes through as is.
      val strength = BLOOM * (1f - fold)
      post[0] = BLOOM_THRESHOLD
      post[1] = BLOOM_KNEE
      post[2] = strength
      post[3] = fold
      postBytes.asFloatBuffer().put(post)
      queue.writeBuffer(resources.post, 0, postBytes)
      if (strength > 0f) {
        screenPass(encoder, targets.bloomViews[0], pipelines.prefilter, targets.downGroups[0], load = false)
        for (i in 1 until BLOOM_LEVELS) screenPass(encoder, targets.bloomViews[i], pipelines.down, targets.downGroups[i], load = false)
        for (i in BLOOM_LEVELS - 2 downTo 0) screenPass(encoder, targets.bloomViews[i], pipelines.up, targets.upGroups[i], load = true)
      }
      screenPass(encoder, view, pipelines.composite, targets.compositeGroup, load = false)
      encoder.finish().use { commands -> queue.submit(arrayOf(commands)) }
    }
  }

  /** One full-screen triangle into [target], reading what [group] binds. */
  private fun screenPass(encoder: GPUCommandEncoder, target: GPUTextureView, pipeline: GPURenderPipeline, group: GPUBindGroup, load: Boolean) {
    encoder
      .beginRenderPass(
        GPURenderPassDescriptor(
          colorAttachments =
            arrayOf(
              GPURenderPassColorAttachment(
                view = target,
                loadOp = if (load) LoadOp.Load else LoadOp.Clear,
                storeOp = StoreOp.Store,
                clearValue = GPUColor(0.0, 0.0, 0.0, 1.0),
              )
            )
        )
      )
      .use { pass ->
        pass.setPipeline(pipeline)
        pass.setBindGroup(0, group)
        pass.draw(3)
        pass.end()
      }
  }

  private fun drawBlocks(pass: GPURenderPassEncoder, resources: Resources, instances: GPUBuffer, count: Int, moving: Int) {
    pass.setVertexBuffer(0, resources.cube)
    pass.setIndexBuffer(resources.index, IndexFormat.Uint16)
    pass.setVertexBuffer(1, instances)
    pass.drawIndexed(FrameData.CUBE_INDICES.size, count)
    if (moving > 0) {
      // Traffic is the same cube with a per-frame instance buffer.
      pass.setVertexBuffer(1, resources.traffic)
      pass.drawIndexed(FrameData.CUBE_INDICES.size, moving)
    }
  }

  /** Renders the frame again into a texture and reads it back as a bitmap. */
  private fun capture(
    queue: GPUQueue,
    pipelines: Pipelines,
    resources: Resources,
    targets: Targets,
    city: City?,
    moving: Int,
    drops: Int,
    gusts: Int,
    air: Atmosphere,
    fold: Float,
    onBitmap: (Bitmap) -> Unit,
  ) {
    val device = gpu!!.device
    val w = width
    val h = height
    val target =
      device.createTexture(
        GPUTextureDescriptor(
          usage = TextureUsage.RenderAttachment or TextureUsage.CopySrc,
          size = GPUExtent3D(w, h),
          format = format,
        )
      )
    target.createView().use { encode(queue, pipelines, resources, targets, it, city, moving, drops, gusts, air, fold) }
    // Copies out of a texture need rows padded to 256 bytes.
    val rowBytes = (w * 4 + 255) / 256 * 256
    val size = rowBytes.toLong() * h
    val readback = device.createBuffer(GPUBufferDescriptor(BufferUsage.MapRead or BufferUsage.CopyDst, size, "snapshot"))
    device.createCommandEncoder().use { encoder ->
      encoder.copyTextureToBuffer(
        GPUTexelCopyTextureInfo(target),
        GPUTexelCopyBufferInfo(readback, GPUTexelCopyBufferLayout(0, rowBytes, h)),
        GPUExtent3D(w, h),
      )
      encoder.finish().use { queue.submit(arrayOf(it)) }
    }
    val bgra = format == TextureFormat.BGRA8Unorm
    scope.launch {
      try {
        readback.mapAndAwait(MapMode.Read, 0, size)
        val bitmap = toBitmap(readback.getConstMappedRange(0, size), w, h, rowBytes, bgra)
        readback.unmap()
        main.post { onBitmap(bitmap) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        Log.e(TAG, "snapshot failed", e)
      } finally {
        readback.destroy()
        readback.close()
        target.destroy()
        target.close()
      }
    }
  }

  private fun upload(queue: GPUQueue, city: City?) {
    if (city == null || city === uploaded) return
    val floats = city.count * CityPlanner.STRIDE
    val bytes = direct(floats * 4)
    bytes.asFloatBuffer().put(city.instances, 0, floats)
    instances?.let { it.destroy(); it.close() }
    val buffer =
      gpu!!.device.createBuffer(GPUBufferDescriptor(BufferUsage.Vertex or BufferUsage.CopyDst, floats * 4L, "city"))
    queue.writeBuffer(buffer, 0, bytes)
    instances = buffer
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
    queue: GPUQueue,
    resources: Resources,
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
    )
    val f = frame
    frameBytes.asFloatBuffer().put(f)
    queue.writeBuffer(resources.frame, 0, frameBytes)
  }

  /** Draws the billboard's messages into its texture, and times them. */
  private fun drawBillboard(queue: GPUQueue, resources: Resources) {
    val messages = billboard
    boardFor = messages
    val drawn = BillboardAtlas.draw(messages)
    if (drawn.widths.isEmpty()) {
      board = Billboard.NONE
      return
    }
    queue.writeTexture(
      GPUTexelCopyTextureInfo(resources.atlas),
      drawn.pixels,
      GPUExtent3D(BillboardAtlas.WIDTH, BillboardAtlas.ROW * BillboardAtlas.ROWS),
      GPUTexelCopyBufferLayout(0, BillboardAtlas.WIDTH, BillboardAtlas.ROW * BillboardAtlas.ROWS),
    )
    board = Billboard(drawn.widths, BillboardAtlas.VIEW)
  }

  /**
   * Everything that moves, rebuilt as boxes every frame. It shrinks away as the city folds, so the
   * code itself stays modules only. Returns how many boxes to draw.
   */
  private fun writeTraffic(queue: GPUQueue, resources: Resources, city: City?, fold: Float, impact: Float): Int {
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
    val floats = count * CityPlanner.STRIDE
    trafficBytes.asFloatBuffer().put(traffic, 0, floats)
    val used = trafficBytes.duplicate()
    used.limit(floats * 4)
    queue.writeBuffer(resources.traffic, 0, used.slice())
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
        val u = Math.pow(t.toDouble(), 1.7).toFloat()
        // From high up and over to the left of the view, so it streaks across the screen.
        val x = meteorX - 15f * (1f - u)
        val y = 0.3f + 32f * (1f - u)
        val z = meteorZ + 3f * (1f - u)
        val size = 1.1f * Math.pow(0.92, k.toDouble()).toFloat()
        box(x, y, z, size, size, size, CityPlanner.ROLE_METEOR, 1f - k / 20f, 0.5f, k * 0.7f, 1f)
      }
    } else if (impact < 0.45f) {
      val grow = 1.5f + 11f * Math.pow((impact / 0.45f).toDouble(), 0.6).toFloat()
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
      val grow = 0.4f + 2.6f * Math.pow((t / 0.4f).toDouble(), 0.5).toFloat()
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

  /** Buffers, bindings and the shadow map: everything that lives as long as the device. */
  private class Resources(device: GPUDevice, queue: GPUQueue) : AutoCloseable {
    val frame = device.createBuffer(GPUBufferDescriptor(BufferUsage.Uniform or BufferUsage.CopyDst, Wgsl.FRAME_FLOATS * 4L, "frame"))
    private val frameEntry = GPUBindGroupLayoutEntry(
      binding = 0,
      visibility = ShaderStage.Vertex or ShaderStage.Fragment,
      buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
    )
    private val sceneLayout: GPUBindGroupLayout =
      device.createBindGroupLayout(
        GPUBindGroupLayoutDescriptor(
          entries =
            arrayOf(
              frameEntry,
              GPUBindGroupLayoutEntry(
                binding = 1,
                visibility = ShaderStage.Fragment,
                texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Depth, viewDimension = TextureViewDimension._2D),
              ),
              GPUBindGroupLayoutEntry(
                binding = 2,
                visibility = ShaderStage.Fragment,
                sampler = GPUSamplerBindingLayout(type = SamplerBindingType.Comparison),
              ),
              GPUBindGroupLayoutEntry(
                binding = 3,
                visibility = ShaderStage.Fragment,
                texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Float, viewDimension = TextureViewDimension._2D),
              ),
              GPUBindGroupLayoutEntry(binding = 4, visibility = ShaderStage.Fragment, sampler = GPUSamplerBindingLayout(type = SamplerBindingType.Filtering)),
            )
        )
      )
    // The shadow pass renders into the shadow map, so it must not also bind it.
    private val casterLayout: GPUBindGroupLayout =
      device.createBindGroupLayout(GPUBindGroupLayoutDescriptor(entries = arrayOf(frameEntry)))
    val layout: GPUPipelineLayout = device.createPipelineLayout(GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(sceneLayout)))
    val casterPipelineLayout: GPUPipelineLayout =
      device.createPipelineLayout(GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(casterLayout)))
    private val shadowMap =
      device.createTexture(
        GPUTextureDescriptor(
          usage = TextureUsage.RenderAttachment or TextureUsage.TextureBinding,
          size = GPUExtent3D(Wgsl.SHADOW_MAP_SIZE, Wgsl.SHADOW_MAP_SIZE),
          format = SHADOW_FORMAT,
          label = "sun shadow map",
        )
      )
    val shadowView: GPUTextureView = shadowMap.createView()
    private val shadowSampler =
      device.createSampler(
        GPUSamplerDescriptor(magFilter = FilterMode.Linear, minFilter = FilterMode.Linear, compare = CompareFunction.LessEqual)
      )
    /** The billboard's messages, one row each; see [BillboardAtlas]. Empty until it has some. */
    val atlas =
      device.createTexture(
        GPUTextureDescriptor(
          usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
          size = GPUExtent3D(BillboardAtlas.WIDTH, BillboardAtlas.ROW * BillboardAtlas.ROWS),
          format = TextureFormat.R8Unorm,
          label = "billboard",
        )
      )
    private val atlasView: GPUTextureView = atlas.createView()
    private val atlasSampler =
      device.createSampler(
        GPUSamplerDescriptor(
          magFilter = FilterMode.Linear,
          minFilter = FilterMode.Linear,
          addressModeU = AddressMode.ClampToEdge,
          addressModeV = AddressMode.ClampToEdge,
        )
      )
    val sceneGroup =
      device.createBindGroup(
        GPUBindGroupDescriptor(
          layout = sceneLayout,
          entries =
            arrayOf(
              GPUBindGroupEntry(binding = 0, buffer = frame),
              GPUBindGroupEntry(binding = 1, textureView = shadowView),
              GPUBindGroupEntry(binding = 2, sampler = shadowSampler),
              GPUBindGroupEntry(binding = 3, textureView = atlasView),
              GPUBindGroupEntry(binding = 4, sampler = atlasSampler),
            ),
        )
      )
    val casterGroup =
      device.createBindGroup(GPUBindGroupDescriptor(layout = casterLayout, entries = arrayOf(GPUBindGroupEntry(binding = 0, buffer = frame))))
    val cube = staticBuffer(device, queue, BufferUsage.Vertex, direct(FrameData.CUBE_MESH.size * 4).also { it.asFloatBuffer().put(FrameData.CUBE_MESH) }, "cube")
    val index = staticBuffer(device, queue, BufferUsage.Index, direct(FrameData.CUBE_INDICES.size * 2).also { it.asShortBuffer().put(FrameData.CUBE_INDICES) }, "cube indices")
    val traffic = device.createBuffer(GPUBufferDescriptor(BufferUsage.Vertex or BufferUsage.CopyDst, MAX_PARTS * CityPlanner.STRIDE * 4L, "traffic"))

    /** The glow passes' settings, and what they read: a source, a sampler, and a second texture. */
    val post = device.createBuffer(GPUBufferDescriptor(BufferUsage.Uniform or BufferUsage.CopyDst, 16L, "post"))
    val postLayout: GPUBindGroupLayout =
      device.createBindGroupLayout(
        GPUBindGroupLayoutDescriptor(
          entries =
            arrayOf(
              GPUBindGroupLayoutEntry(binding = 0, visibility = ShaderStage.Fragment, buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform)),
              GPUBindGroupLayoutEntry(
                binding = 1,
                visibility = ShaderStage.Fragment,
                texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Float, viewDimension = TextureViewDimension._2D),
              ),
              GPUBindGroupLayoutEntry(binding = 2, visibility = ShaderStage.Fragment, sampler = GPUSamplerBindingLayout(type = SamplerBindingType.Filtering)),
              GPUBindGroupLayoutEntry(
                binding = 3,
                visibility = ShaderStage.Fragment,
                texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Float, viewDimension = TextureViewDimension._2D),
              ),
            )
        )
      )
    val postPipelineLayout: GPUPipelineLayout = device.createPipelineLayout(GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(postLayout)))
    val linear =
      device.createSampler(
        GPUSamplerDescriptor(
          magFilter = FilterMode.Linear,
          minFilter = FilterMode.Linear,
          addressModeU = AddressMode.ClampToEdge,
          addressModeV = AddressMode.ClampToEdge,
        )
      )

    override fun close() {
      for (buffer in listOf(frame, cube, index, traffic, post)) {
        buffer.destroy()
        buffer.close()
      }
      linear.close()
      postPipelineLayout.close()
      postLayout.close()
      sceneGroup.close()
      casterGroup.close()
      shadowSampler.close()
      atlasSampler.close()
      atlasView.close()
      atlas.destroy()
      atlas.close()
      shadowView.close()
      shadowMap.destroy()
      shadowMap.close()
      layout.close()
      casterPipelineLayout.close()
      sceneLayout.close()
      casterLayout.close()
    }

    private fun staticBuffer(device: GPUDevice, queue: GPUQueue, usage: Int, data: ByteBuffer, label: String) =
      device.createBuffer(GPUBufferDescriptor(usage or BufferUsage.CopyDst, data.capacity().toLong(), label)).also {
        queue.writeBuffer(it, 0, data)
      }
  }

  private class Pipelines(
    val blocks: GPURenderPipeline,
    val caster: GPURenderPipeline,
    val sky: GPURenderPipeline,
    val clouds: GPURenderPipeline,
    val rain: GPURenderPipeline,
    val prefilter: GPURenderPipeline,
    val down: GPURenderPipeline,
    val up: GPURenderPipeline,
    val composite: GPURenderPipeline,
  ) : AutoCloseable {
    override fun close() {
      for (pipeline in listOf(blocks, caster, sky, clouds, rain, prefilter, down, up, composite)) pipeline.close()
    }
  }

  /**
   * Sized to the surface: the multisampled float colour and depth the city is drawn into, the
   * float texture it resolves to, and the bloom chain, each level half the one before, with the
   * bind groups the glow passes read them through.
   */
  private class Targets(private val device: GPUDevice, width: Int, height: Int, private val resources: Resources) : AutoCloseable {
    private val color = attachment(device, width, height, HDR_FORMAT, SAMPLES, TextureUsage.RenderAttachment)
    private val depth = attachment(device, width, height, DEPTH_FORMAT, SAMPLES, TextureUsage.RenderAttachment)
    private val hdr = attachment(device, width, height, HDR_FORMAT, 1, TextureUsage.RenderAttachment or TextureUsage.TextureBinding)
    private val bloom =
      List(BLOOM_LEVELS) { i ->
        attachment(device, max(1, width shr (i + 1)), max(1, height shr (i + 1)), HDR_FORMAT, 1, TextureUsage.RenderAttachment or TextureUsage.TextureBinding)
      }
    val colorView: GPUTextureView = color.createView()
    val depthView: GPUTextureView = depth.createView()
    val hdrView: GPUTextureView = hdr.createView()
    val bloomViews: List<GPUTextureView> = bloom.map { it.createView() }

    private fun group(source: GPUTextureView, second: GPUTextureView = source) =
      device.createBindGroup(
        GPUBindGroupDescriptor(
          layout = resources.postLayout,
          entries =
            arrayOf(
              GPUBindGroupEntry(binding = 0, buffer = resources.post),
              GPUBindGroupEntry(binding = 1, textureView = source),
              GPUBindGroupEntry(binding = 2, sampler = resources.linear),
              GPUBindGroupEntry(binding = 3, textureView = second),
            ),
        )
      )

    /** Level i of the chain reads the level above it (the resolved city for the first). */
    val downGroups: List<GPUBindGroup> = List(BLOOM_LEVELS) { i -> group(if (i == 0) hdrView else bloomViews[i - 1]) }

    /** Adding back onto level i reads the level below it. */
    val upGroups: List<GPUBindGroup> = List(BLOOM_LEVELS - 1) { i -> group(bloomViews[i + 1]) }

    val compositeGroup: GPUBindGroup = group(hdrView, bloomViews[0])

    override fun close() {
      (downGroups + upGroups + compositeGroup).forEach { it.close() }
      (listOf(colorView, depthView, hdrView) + bloomViews).forEach { it.close() }
      for (texture in listOf(color, depth, hdr) + bloom) {
        texture.destroy()
        texture.close()
      }
    }

    private fun attachment(device: GPUDevice, width: Int, height: Int, format: Int, samples: Int, usage: Int): GPUTexture =
      device.createTexture(
        GPUTextureDescriptor(
          usage = usage,
          size = GPUExtent3D(width, height),
          format = format,
          sampleCount = samples,
        )
      )
  }

  companion object {
    private const val TAG = "SkylineQR"
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
    private const val SAMPLES = 4
    /** The city is drawn in floats, so lights can be brighter than white and bloom. */
    private const val HDR_FORMAT = TextureFormat.RGBA16Float
    /** Levels in the bloom chain, from half the screen down to about a thirtieth of it. */
    private const val BLOOM_LEVELS = 6
    /** What counts as brighter than white, how softly it starts, and how strong the glow is. */
    private const val BLOOM_THRESHOLD = 1.05f
    private const val BLOOM_KNEE = 0.2f
    private const val BLOOM = 0.45f
    private const val DEPTH_FORMAT = TextureFormat.Depth24Plus
    private const val SHADOW_FORMAT = TextureFormat.Depth32Float

    /** Shaders write display-ready colour themselves, so the surface must not be an sRGB format. */
    private val PREFERRED_FORMATS = intArrayOf(TextureFormat.RGBA8Unorm, TextureFormat.BGRA8Unorm)

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

    private suspend fun buildPipelines(device: GPUDevice, resources: Resources, format: Int): Pipelines {
      device.pushErrorScope(ErrorFilter.Validation)
      val module =
        device.createShaderModule(GPUShaderModuleDescriptor(label = "skyline", shaderSourceWGSL = GPUShaderSourceWGSL(Wgsl.SOURCE)))
      val postModule =
        device.createShaderModule(GPUShaderModuleDescriptor(label = "glow", shaderSourceWGSL = GPUShaderSourceWGSL(Wgsl.POST)))
      try {
        val multisample = GPUMultisampleState(count = SAMPLES)
        fun depth(write: Boolean, compare: Int) =
          GPUDepthStencilState(
            format = DEPTH_FORMAT,
            depthWriteEnabled = if (write) OptionalBool.True else OptionalBool.False,
            depthCompare = compare,
          )
        fun pipeline(label: String, vertex: GPUVertexState, fragment: String, depth: GPUDepthStencilState, blend: GPUBlendState? = null) =
          device.createRenderPipeline(
            GPURenderPipelineDescriptor(
              vertex = vertex,
              label = label,
              layout = resources.layout,
              depthStencil = depth,
              multisample = multisample,
              fragment = GPUFragmentState(module, fragment, targets = arrayOf(GPUColorTargetState(format = HDR_FORMAT, blend = blend))),
            )
          )
        val alpha = GPUBlendState(
          color = GPUBlendComponent(BlendOperation.Add, BlendFactor.SrcAlpha, BlendFactor.OneMinusSrcAlpha),
          alpha = GPUBlendComponent(BlendOperation.Add, BlendFactor.One, BlendFactor.OneMinusSrcAlpha),
        )

        val blocks = pipeline("blocks", blockVertexState(module, "blockVertex"), "blockFragment", depth(write = true, compare = CompareFunction.LessEqual))
        val caster =
          device.createRenderPipeline(
            GPURenderPipelineDescriptor(
              vertex = blockVertexState(module, "casterVertex"),
              label = "shadow caster",
              layout = resources.casterPipelineLayout,
              // A little bias keeps faces from shadowing themselves.
              depthStencil =
                GPUDepthStencilState(
                  format = SHADOW_FORMAT,
                  depthWriteEnabled = OptionalBool.True,
                  depthCompare = CompareFunction.LessEqual,
                  depthBias = 2,
                  depthBiasSlopeScale = 2f,
                ),
            )
          )
        val sky = pipeline("sky", GPUVertexState(module, "skyVertex"), "skyFragment", depth(write = false, compare = CompareFunction.Always))
        val clouds = pipeline("clouds", blockVertexState(module, "cloudVertex"), "cloudFragment", depth(write = false, compare = CompareFunction.LessEqual), alpha)
        val rain = pipeline("rain", GPUVertexState(module, "rainVertex"), "rainFragment", depth(write = false, compare = CompareFunction.LessEqual), alpha)
        val additive = GPUBlendState(
          color = GPUBlendComponent(BlendOperation.Add, BlendFactor.One, BlendFactor.One),
          alpha = GPUBlendComponent(BlendOperation.Add, BlendFactor.One, BlendFactor.One),
        )
        fun screen(label: String, fragment: String, target: Int, blend: GPUBlendState? = null) =
          device.createRenderPipeline(
            GPURenderPipelineDescriptor(
              vertex = GPUVertexState(postModule, "screenVertex"),
              label = label,
              layout = resources.postPipelineLayout,
              fragment = GPUFragmentState(postModule, fragment, targets = arrayOf(GPUColorTargetState(format = target, blend = blend))),
            )
          )
        val prefilter = screen("bloom prefilter", "bloomPrefilter", HDR_FORMAT)
        val down = screen("bloom down", "bloomDown", HDR_FORMAT)
        val up = screen("bloom up", "bloomUp", HDR_FORMAT, additive)
        val composite = screen("composite", "composite", format)
        // Throws with the WGSL compiler's message if anything above was invalid.
        device.popErrorScope()
        return Pipelines(blocks, caster, sky, clouds, rain, prefilter, down, up, composite)
      } finally {
        module.close()
        postModule.close()
      }
    }

    private fun blockVertexState(module: GPUShaderModule, entryPoint: String) =
      GPUVertexState(
        module,
        entryPoint,
        buffers =
          arrayOf(
            GPUVertexBufferLayout(
              arrayStride = 24,
              stepMode = VertexStepMode.Vertex,
              attributes = arrayOf(GPUVertexAttribute(VertexFormat.Float32x3, 0, 0), GPUVertexAttribute(VertexFormat.Float32x3, 12, 1)),
            ),
            GPUVertexBufferLayout(
              arrayStride = CityPlanner.STRIDE * 4L,
              stepMode = VertexStepMode.Instance,
              attributes =
                arrayOf(
                  GPUVertexAttribute(VertexFormat.Float32x3, 0, 2),
                  GPUVertexAttribute(VertexFormat.Float32x3, 12, 3),
                  GPUVertexAttribute(VertexFormat.Float32x4, 24, 4),
                  GPUVertexAttribute(VertexFormat.Float32x2, 40, 5),
                ),
            ),
          ),
      )

    private fun toBitmap(data: ByteBuffer, width: Int, height: Int, rowBytes: Int, bgra: Boolean): Bitmap {
      val tight = ByteBuffer.allocateDirect(width * height * 4)
      val row = ByteArray(width * 4)
      for (y in 0 until height) {
        data.position(y * rowBytes)
        data.get(row)
        if (bgra) {
          for (i in row.indices step 4) {
            val b = row[i]
            row[i] = row[i + 2]
            row[i + 2] = b
          }
        }
        tight.put(row)
      }
      tight.rewind()
      // ARGB_8888 stores its pixels as R, G, B, A bytes, which is what the texture held.
      return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(tight) }
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    private fun frac(v: Float) = v - floor(v)

    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
  }
}
