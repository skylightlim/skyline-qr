package com.skylightlim.skylineqr.render

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
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
import com.skylightlim.skylineqr.city.FlatColours
import com.skylightlim.skylineqr.city.Materials
import com.skylightlim.skylineqr.city.Vehicles
import com.skylightlim.skylineqr.city.TimeOfDay
import com.skylightlim.skylineqr.city.Weather
import com.skylightlim.skylineqr.compose.SkylineEvent
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

  /** Android's "Remove animations" is on: no lightning, gentler rain, an instant morph. */
  @Volatile var calm = false

  /** Frames presented so far. The on-device test reads it to check the frame rate. */
  @Volatile
  var framesDrawn = 0
    private set

  private val thread = HandlerThread("SkylineQR-WebGPU").apply { start() }
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
  private var atmosphere: Atmosphere? = null
  private var atmosphereFor: Pair<TimeOfDay, Weather>? = null
  private var atmosphereAt = 0L
  private val morph = Morph()
  private val frame = FloatArray(Wgsl.FRAME_FLOATS)
  private val frameBytes = direct(Wgsl.FRAME_FLOATS * 4)
  private val traffic = FloatArray(MAX_PARTS * CityPlanner.STRIDE)
  private val trafficBytes = direct(traffic.size * 4)
  private val pa = FloatArray(4)
  private val choreographer by lazy { Choreographer.getInstance() }
  private val frameCallback =
    Choreographer.FrameCallback { nanos ->
      looping = false
      if (drawFrame(nanos)) wake()
    }

  fun setFlat(flat: Boolean) {
    post {
      if (calm) morph.finish(flat) else morph.aim(flat, System.nanoTime())
      // A meteor or a bolt only ever strikes the city, never the code.
      if (flat) {
        meteorAt = -1f
        damageAt = -1f
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
    targets = Targets(device, width, height, format)
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

  /** The light and the air right now. Auto follows the clock, so it is refreshed every second. */
  private fun atmosphere(nanos: Long): Atmosphere {
    val key = time to weather
    val cached = atmosphere
    if (cached != null && key == atmosphereFor && (key.first != TimeOfDay.AUTO || nanos - atmosphereAt < 1_000_000_000L)) {
      return cached
    }
    return Atmospheres.at(key.first, key.second, Atmospheres.clockMinute()).also {
      atmosphere = it
      atmosphereFor = key
      atmosphereAt = nanos
    }
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

      val shot = snapshotRequest
      if (shot != null && snapshotFlat) morph.finish(flat = true)
      // The camera swings overhead in the first half; the city folds block by block until the end.
      val progress = morph.at(nanos)
      val swing = Morph.swing(progress)
      val fold = Morph.fold(progress)
      val current = city
      upload(queue, current)
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
      val pose = Camera.pose(current, width.toFloat() / height, swing, seconds, shaken)
      lastPose = pose
      writeFrame(queue, resources, pose, Camera.light(current, air.sunDir), fold, current, air, impact)
      val moving = writeTraffic(queue, resources, current, fold, impact)
      val drops = (MAX_DROPS * air.rain * (if (calm) 0.4f else 1f) * (1f - fold)).toInt()

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
          encode(queue, pipelines, resources, targets, view, current, moving, drops, air, shadows = fold < 0.999f)
        }
        surface.present()
      }
      framesDrawn++

      if (shot != null) {
        snapshotRequest = null
        capture(queue, pipelines, resources, targets, current, moving, drops, air, shot)
      }
      // The flat code is still: once the morph settles, draw again only when something changes.
      return !(progress >= 1f && morph.settled(nanos))
    } catch (e: Throwable) {
      failNow(e)
      return false
    }
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
    air: Atmosphere,
    shadows: Boolean,
  ) {
    val sky = air.skyHorizon
    val clear = GPUColor(sky[0] * 0.85, sky[1] * 0.85, sky[2] * 0.85, 1.0)
    val instances = instances
    val device = gpu!!.device
    device.createCommandEncoder().use { encoder ->
      if (shadows && city != null && instances != null) {
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
                  resolveTarget = view,
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
          }
          if (drops > 0) {
            pass.setPipeline(pipelines.rain)
            pass.draw(6, drops)
          }
          pass.end()
        }
      encoder.finish().use { commands -> queue.submit(arrayOf(commands)) }
    }
  }

  private fun drawBlocks(pass: GPURenderPassEncoder, resources: Resources, instances: GPUBuffer, count: Int, moving: Int) {
    pass.setVertexBuffer(0, resources.cube)
    pass.setIndexBuffer(resources.index, IndexFormat.Uint16)
    pass.setVertexBuffer(1, instances)
    pass.drawIndexed(CUBE_INDICES.size, count)
    if (moving > 0) {
      // Traffic is the same cube with a per-frame instance buffer.
      pass.setVertexBuffer(1, resources.traffic)
      pass.drawIndexed(CUBE_INDICES.size, moving)
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
    air: Atmosphere,
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
    // The shadow map from this frame is still current, so the copy needs no shadow pass of its own.
    target.createView().use { encode(queue, pipelines, resources, targets, it, city, moving, drops, air, shadows = false) }
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
    val k = pool[min(pool.size - 1, (unit(strike * 7 + 3) * pool.size).toInt())]
    boltX = rods[k * 3]
    boltZ = rods[k * 3 + 1]
    boltTop = rods[k * 3 + 2]
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
    val f = frame
    System.arraycopy(pose.viewProjection, 0, f, 0, 16)
    System.arraycopy(light, 0, f, 16, 16)
    var o = 32
    fun put4(x: Float, y: Float, z: Float, w: Float) {
      f[o] = x
      f[o + 1] = y
      f[o + 2] = z
      f[o + 3] = w
      o += 4
    }
    fun put(v: FloatArray, w: Float = 0f, scale: Float = 1f) = put4(v[0] * scale, v[1] * scale, v[2] * scale, w)

    val materials = city?.materials ?: Materials.recipes.first()
    val sunLuma = 0.2126f * air.sunColor[0] + 0.7152f * air.sunColor[1] + 0.0722f * air.sunColor[2]
    // Strong sun throws dark shadows; overcast, haze and moonlight only soft ones.
    val shadowStrength = (0.35f + 0.6f * min(1f, sunLuma / 0.9f)) * (1f - 0.5f * air.night)
    val flash = if (calm) 0f else lightning(seconds, air.storm)

    put(pose.eye, fold)
    put(pose.right, pose.tanHalfFov)
    put(pose.up, pose.aspect)
    put(pose.fwd, seconds)
    put(air.sunDir, air.night)
    put(air.sunColor, shadowStrength)
    put(air.skyTop, air.rain)
    put(air.skyHorizon, air.haze)
    put(PLAZA, air.wet)
    put(air.window, flash)
    put(air.paper)
    put(air.inkLight)
    put(materials.vehicle)
    // The first roof's w names the family the flat code's function patterns are inked in.
    val anchor = FlatColours.anchorFamily(materials).toFloat()
    for (i in 0 until 4) put4(materials.families[i * 3], materials.families[i * 3 + 1], materials.families[i * 3 + 2], if (i == 0) anchor else 0f)
    for (i in 0 until 8) put4(materials.landmarks[i * 3], materials.landmarks[i * 3 + 1], materials.landmarks[i * 3 + 2], 0f)
    put4(city?.plateHalf ?: 20f, city?.maxHeight ?: 4f, if (calm) 0.45f else 1f, pose.shiftY)
    put4(meteorX, meteorZ, if (impact >= 0f) impact else -1f, if (impact >= 0f) exp(-impact * 7f) else 0f)
    put4(damageX, damageZ, damageSeconds(), 0f)
    put4(boltX, boltZ, boltTop, if (boltAimed && flash > 0f) 1f else 0f)
    check(o == f.size) { "frame uniform packed $o of ${f.size} floats" }

    frameBytes.asFloatBuffer().put(f)
    queue.writeBuffer(resources.frame, 0, frameBytes)
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
    if (meteorAt >= 0f) meteorParts({ x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper -> box.box(x, y, z, sx, sy, sz, role, glow, rnd, yaw, taper) }, impact)
    val flash = if (calm) 0f else lightning(seconds, atmosphere?.storm ?: 0f)
    if (boltAimed && flash > 0.12f) boltParts(box, city, flash)
    val damage = damageSeconds()
    if (damage >= 0f) fragmentParts(box, damage)
    if (count == 0) return 0
    trafficBytes.asFloatBuffer().put(traffic)
    queue.writeBuffer(resources.traffic, 0, trafficBytes)
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
   * The bolt: from high above the city down onto the struck roof, in steps that jog sideways less
   * and less, each a glowing column and a rung to the next. Boxes only turn about their axis, so
   * a bolt is drawn as a staircase. Its jitter is seeded by the strike, so it holds still while it
   * flickers.
   */
  private fun boltParts(box: Vehicles.Sink, city: City, flash: Float) {
    val sky = city.maxHeight + 14f
    var px = boltX + (unit(lastStrike * 13 + 1) - 0.5f) * 7f
    var pz = boltZ + (unit(lastStrike * 13 + 2) - 0.5f) * 7f
    val startX = px
    val startZ = pz
    var py = sky
    val steps = 9
    for (i in 1..steps) {
      val t = i / steps.toFloat()
      val wobble = 1.6f * (1f - t)
      val nx = startX + (boltX - startX) * t + (unit(lastStrike * 13 + i * 2 + 3) - 0.5f) * wobble
      val nz = startZ + (boltZ - startZ) * t + (unit(lastStrike * 13 + i * 2 + 4) - 0.5f) * wobble
      val ny = sky + (boltTop - sky) * t
      val w = 0.22f - 0.08f * t
      box.box(px, (py + ny) / 2f, pz, w, py - ny, w, CityPlanner.ROLE_BOLT, flash, 0.5f, 0f, 1f)
      val dx = nx - px
      val dz = nz - pz
      val run = hypot(dx, dz)
      if (run > 0.01f) {
        val yaw = kotlin.math.atan2(dz, dx)
        box.box((px + nx) / 2f, ny, (pz + nz) / 2f, run + w, w, w, CityPlanner.ROLE_BOLT, flash, 0.5f, yaw, 1f)
      }
      px = nx
      py = ny
      pz = nz
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
    val sceneGroup =
      device.createBindGroup(
        GPUBindGroupDescriptor(
          layout = sceneLayout,
          entries =
            arrayOf(
              GPUBindGroupEntry(binding = 0, buffer = frame),
              GPUBindGroupEntry(binding = 1, textureView = shadowView),
              GPUBindGroupEntry(binding = 2, sampler = shadowSampler),
            ),
        )
      )
    val casterGroup =
      device.createBindGroup(GPUBindGroupDescriptor(layout = casterLayout, entries = arrayOf(GPUBindGroupEntry(binding = 0, buffer = frame))))
    val cube = staticBuffer(device, queue, BufferUsage.Vertex, direct(CUBE_MESH.size * 4).also { it.asFloatBuffer().put(CUBE_MESH) }, "cube")
    val index = staticBuffer(device, queue, BufferUsage.Index, direct(CUBE_INDICES.size * 2).also { it.asShortBuffer().put(CUBE_INDICES) }, "cube indices")
    val traffic = device.createBuffer(GPUBufferDescriptor(BufferUsage.Vertex or BufferUsage.CopyDst, MAX_PARTS * CityPlanner.STRIDE * 4L, "traffic"))

    override fun close() {
      for (buffer in listOf(frame, cube, index, traffic)) {
        buffer.destroy()
        buffer.close()
      }
      sceneGroup.close()
      casterGroup.close()
      shadowSampler.close()
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
    val rain: GPURenderPipeline,
  ) : AutoCloseable {
    override fun close() {
      for (pipeline in listOf(blocks, caster, sky, rain)) pipeline.close()
    }
  }

  /** The multisampled colour and depth attachments, sized to the surface. */
  private class Targets(device: GPUDevice, width: Int, height: Int, format: Int) : AutoCloseable {
    private val color = attachment(device, width, height, format)
    private val depth = attachment(device, width, height, DEPTH_FORMAT)
    val colorView: GPUTextureView = color.createView()
    val depthView: GPUTextureView = depth.createView()

    override fun close() {
      colorView.close()
      depthView.close()
      for (texture in listOf(color, depth)) {
        texture.destroy()
        texture.close()
      }
    }

    private fun attachment(device: GPUDevice, width: Int, height: Int, format: Int): GPUTexture =
      device.createTexture(
        GPUTextureDescriptor(
          usage = TextureUsage.RenderAttachment,
          size = GPUExtent3D(width, height),
          format = format,
          sampleCount = SAMPLES,
        )
      )
  }

  companion object {
    private const val TAG = "SkylineQR"
    /** Boxes for everything that moves: vehicles and their parts, the meteor and its fireball. */
    private const val MAX_PARTS = 240
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
    private const val DEPTH_FORMAT = TextureFormat.Depth24Plus
    private const val SHADOW_FORMAT = TextureFormat.Depth32Float

    /** Paving: one warm stone for every city, lit by the hour. */
    private val PLAZA = floatArrayOf(0xEE / 255f, 0xE8 / 255f, 0xDC / 255f)

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

    /** The roofs of the tallest towers, x, z and height each: where lightning comes down. */
    private fun lightningRods(city: City): FloatArray {
      val d = city.instances
      val found = ArrayList<FloatArray>()
      for (i in 0 until city.count) {
        val o = i * CityPlanner.STRIDE
        if (d[o + 6] == CityPlanner.ROLE_TOWER && d[o + 7] >= 0.5f && d[o + 8] < 0.5f) {
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
              fragment = GPUFragmentState(module, fragment, targets = arrayOf(GPUColorTargetState(format = format, blend = blend))),
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
        val rain = pipeline("rain", GPUVertexState(module, "rainVertex"), "rainFragment", depth(write = false, compare = CompareFunction.LessEqual), alpha)
        // Throws with the WGSL compiler's message if anything above was invalid.
        device.popErrorScope()
        return Pipelines(blocks, caster, sky, rain)
      } finally {
        module.close()
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

    private val CUBE_MESH =
      floatArrayOf(
        0.5f, -0.5f, 0.5f, 1f, 0f, 0f,
        0.5f, -0.5f, -0.5f, 1f, 0f, 0f,
        0.5f, 0.5f, -0.5f, 1f, 0f, 0f,
        0.5f, 0.5f, 0.5f, 1f, 0f, 0f,
        -0.5f, -0.5f, -0.5f, -1f, 0f, 0f,
        -0.5f, -0.5f, 0.5f, -1f, 0f, 0f,
        -0.5f, 0.5f, 0.5f, -1f, 0f, 0f,
        -0.5f, 0.5f, -0.5f, -1f, 0f, 0f,
        -0.5f, 0.5f, 0.5f, 0f, 1f, 0f,
        0.5f, 0.5f, 0.5f, 0f, 1f, 0f,
        0.5f, 0.5f, -0.5f, 0f, 1f, 0f,
        -0.5f, 0.5f, -0.5f, 0f, 1f, 0f,
        -0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
        0.5f, -0.5f, -0.5f, 0f, -1f, 0f,
        0.5f, -0.5f, 0.5f, 0f, -1f, 0f,
        -0.5f, -0.5f, 0.5f, 0f, -1f, 0f,
        -0.5f, -0.5f, 0.5f, 0f, 0f, 1f,
        0.5f, -0.5f, 0.5f, 0f, 0f, 1f,
        0.5f, 0.5f, 0.5f, 0f, 0f, 1f,
        -0.5f, 0.5f, 0.5f, 0f, 0f, 1f,
        0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
        -0.5f, -0.5f, -0.5f, 0f, 0f, -1f,
        -0.5f, 0.5f, -0.5f, 0f, 0f, -1f,
        0.5f, 0.5f, -0.5f, 0f, 0f, -1f,
      )

    private val CUBE_INDICES =
      shortArrayOf(
        0, 1, 2, 0, 2, 3,
        4, 5, 6, 4, 6, 7,
        8, 9, 10, 8, 10, 11,
        12, 13, 14, 12, 14, 15,
        16, 17, 18, 16, 18, 19,
        20, 21, 22, 20, 22, 23,
      )
  }
}
