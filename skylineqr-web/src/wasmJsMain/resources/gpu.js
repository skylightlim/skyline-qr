// The website's WebGPU plumbing: the passes skylineqr-compose's CityRenderer builds with
// androidx.webgpu, built here with the browser's WebGPU from the same WGSL (Wgsl.SOURCE and
// Wgsl.POST, handed over by the Kotlin side). Everything else, what to draw and when, is Kotlin
// (WebRenderer.kt). Keep the two in step with CityRenderer.
'use strict';

const SkylineGpu = (() => {
  const SAMPLES = 4;
  /** The city is drawn in floats, so lights can be brighter than white and bloom. */
  const HDR = 'rgba16float';
  const DEPTH = 'depth24plus';
  const SHADOW = 'depth32float';
  /** Levels in the bloom chain, from half the screen down to about a thirtieth of it. */
  const BLOOM_LEVELS = 6;

  let device = null;
  let context = null;
  let format = null;
  let res = null;
  let pipes = null;
  let targets = null;
  let width = 0;
  let height = 0;
  let instances = null;
  let failed = false;
  let fail = () => {};
  /** Set once the GPU has finished a whole frame: until then the page can't trust WebGPU. */
  let proven = false;
  let proving = false;
  /** Waiting for the next frame's pixels, for the postcard: [resolve, reject]. */
  let snapshotWanted = null;
  /** Drawing the postcard's photo: the city on the clear colour, with no sky behind it. */
  let photo = false;

  function photoMode(on) {
    photo = on;
  }

  function failNow(message) {
    if (failed) return;
    failed = true;
    fail(String(message));
  }

  /** Rejects if [promise] hasn't settled in [ms]: a hung WebGPU call must not leave the page blank. */
  function within(ms, promise, what) {
    return Promise.race([promise, new Promise((_, reject) => setTimeout(() => reject(new Error(what + ' timed out')), ms))]);
  }

  /**
   * Firefox and the browsers built on it (Zen, LibreWolf, Floorp…) ship WebGPU that can take the
   * whole tab down with this renderer, which no error handler can catch, so they get the flat code
   * unless the page is opened with ?webgpu=1.
   */
  function geckoWithoutOptIn() {
    const ua = navigator.userAgent;
    const gecko = /Gecko\/\d/.test(ua) && !/like Gecko/.test(ua);
    return gecko && new URLSearchParams(location.search).get('webgpu') !== '1';
  }

  async function start(source, post, frameFloats, shadowSize, trafficFloats, mesh, indices, atlasWidth, atlasHeight, ready, onFail) {
    fail = onFail;
    try {
      if (!navigator.gpu) throw new Error('WebGPU is not available in this browser');
      if (geckoWithoutOptIn()) throw new Error('WebGPU is not stable enough in Firefox-based browsers yet');
      const adapter = await within(5000, navigator.gpu.requestAdapter({ powerPreference: 'high-performance' }), 'requestAdapter');
      if (!adapter) throw new Error('no WebGPU adapter');
      device = await within(5000, adapter.requestDevice({ label: 'SkylineQR' }), 'requestDevice');
      device.lost.then((info) => { if (info.reason !== 'destroyed') failNow('WebGPU device lost: ' + info.message); });
      device.addEventListener('uncapturederror', (e) => failNow('WebGPU error: ' + e.error.message));
      const canvas = document.getElementById('city');
      context = canvas.getContext('webgpu');
      if (!context) throw new Error('no WebGPU canvas context');
      // Shaders write display-ready colour themselves, so the canvas must not be an sRGB format.
      format = navigator.gpu.getPreferredCanvasFormat();
      res = resources(frameFloats, shadowSize, trafficFloats, mesh, indices, atlasWidth, atlasHeight);
      pipes = await within(15000, pipelines(source, post), 'building the pipelines');
      ready();
    } catch (e) {
      failNow(e && e.message ? e.message : e);
    }
  }

  function resize(w, h) {
    if (!device || failed) return;
    width = Math.max(1, w);
    height = Math.max(1, h);
    const canvas = context.canvas;
    canvas.width = width;
    canvas.height = height;
    // COPY_SRC lets the postcard read a frame back (snapshot).
    context.configure({ device, format, alphaMode: 'opaque', usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.COPY_SRC });
    if (targets) targets.destroy();
    targets = makeTargets(width, height);
  }

  function staticBuffer(usage, data, label) {
    const buffer = device.createBuffer({ size: (data.byteLength + 3) & ~3, usage: usage | GPUBufferUsage.COPY_DST, label, mappedAtCreation: true });
    new data.constructor(buffer.getMappedRange()).set(data);
    buffer.unmap();
    return buffer;
  }

  /** Buffers, bindings and the shadow map: everything that lives as long as the device. */
  function resources(frameFloats, shadowSize, trafficFloats, mesh, indices, atlasWidth, atlasHeight) {
    const frame = device.createBuffer({ size: frameFloats * 4, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, label: 'frame' });
    const frameEntry = { binding: 0, visibility: GPUShaderStage.VERTEX | GPUShaderStage.FRAGMENT, buffer: { type: 'uniform' } };
    const sceneLayout = device.createBindGroupLayout({
      entries: [
        frameEntry,
        { binding: 1, visibility: GPUShaderStage.FRAGMENT, texture: { sampleType: 'depth', viewDimension: '2d' } },
        { binding: 2, visibility: GPUShaderStage.FRAGMENT, sampler: { type: 'comparison' } },
        { binding: 3, visibility: GPUShaderStage.FRAGMENT, texture: { sampleType: 'float', viewDimension: '2d' } },
        { binding: 4, visibility: GPUShaderStage.FRAGMENT, sampler: { type: 'filtering' } },
      ],
    });
    // The shadow pass renders into the shadow map, so it must not also bind it.
    const casterLayout = device.createBindGroupLayout({ entries: [frameEntry] });
    const shadowMap = device.createTexture({
      size: [shadowSize, shadowSize],
      format: SHADOW,
      usage: GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING,
      label: 'sun shadow map',
    });
    const shadowView = shadowMap.createView();
    const shadowSampler = device.createSampler({ magFilter: 'linear', minFilter: 'linear', compare: 'less-equal' });
    // The billboard's messages, one row each. Empty until it has some.
    const atlas = device.createTexture({
      size: [atlasWidth, atlasHeight],
      format: 'r8unorm',
      usage: GPUTextureUsage.TEXTURE_BINDING | GPUTextureUsage.COPY_DST,
      label: 'billboard',
    });
    const linear = device.createSampler({ magFilter: 'linear', minFilter: 'linear', addressModeU: 'clamp-to-edge', addressModeV: 'clamp-to-edge' });
    const post = device.createBuffer({ size: 16, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, label: 'post' });
    const postLayout = device.createBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'uniform' } },
        { binding: 1, visibility: GPUShaderStage.FRAGMENT, texture: { sampleType: 'float', viewDimension: '2d' } },
        { binding: 2, visibility: GPUShaderStage.FRAGMENT, sampler: { type: 'filtering' } },
        { binding: 3, visibility: GPUShaderStage.FRAGMENT, texture: { sampleType: 'float', viewDimension: '2d' } },
      ],
    });
    return {
      frame,
      atlas,
      atlasWidth,
      atlasHeight,
      shadowView,
      post,
      postLayout,
      linear,
      layout: device.createPipelineLayout({ bindGroupLayouts: [sceneLayout] }),
      casterPipelineLayout: device.createPipelineLayout({ bindGroupLayouts: [casterLayout] }),
      postPipelineLayout: device.createPipelineLayout({ bindGroupLayouts: [postLayout] }),
      sceneGroup: device.createBindGroup({
        layout: sceneLayout,
        entries: [
          { binding: 0, resource: { buffer: frame } },
          { binding: 1, resource: shadowView },
          { binding: 2, resource: shadowSampler },
          { binding: 3, resource: atlas.createView() },
          { binding: 4, resource: linear },
        ],
      }),
      casterGroup: device.createBindGroup({ layout: casterLayout, entries: [{ binding: 0, resource: { buffer: frame } }] }),
      cube: staticBuffer(GPUBufferUsage.VERTEX, mesh, 'cube'),
      index: staticBuffer(GPUBufferUsage.INDEX, indices, 'cube indices'),
      traffic: device.createBuffer({ size: trafficFloats * 4, usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST, label: 'traffic' }),
    };
  }

  function blockVertexState(module, entryPoint) {
    return {
      module,
      entryPoint,
      buffers: [
        {
          arrayStride: 24,
          stepMode: 'vertex',
          attributes: [
            { format: 'float32x3', offset: 0, shaderLocation: 0 },
            { format: 'float32x3', offset: 12, shaderLocation: 1 },
          ],
        },
        {
          arrayStride: 12 * 4,
          stepMode: 'instance',
          attributes: [
            { format: 'float32x3', offset: 0, shaderLocation: 2 },
            { format: 'float32x3', offset: 12, shaderLocation: 3 },
            { format: 'float32x4', offset: 24, shaderLocation: 4 },
            { format: 'float32x2', offset: 40, shaderLocation: 5 },
          ],
        },
      ],
    };
  }

  async function pipelines(source, postSource) {
    device.pushErrorScope('validation');
    const module = device.createShaderModule({ label: 'skyline', code: source });
    const postModule = device.createShaderModule({ label: 'glow', code: postSource });
    for (const m of [module, postModule]) {
      const info = await m.getCompilationInfo();
      const errors = info.messages.filter((msg) => msg.type === 'error');
      if (errors.length) throw new Error('WGSL: ' + errors.map((e) => `${e.lineNum}:${e.linePos} ${e.message}`).join('; '));
    }
    const multisample = { count: SAMPLES };
    const depth = (write, compare) => ({ format: DEPTH, depthWriteEnabled: write, depthCompare: compare });
    const pipeline = (label, vertex, fragment, depthStencil, blend) =>
      device.createRenderPipelineAsync({
        label,
        layout: res.layout,
        vertex,
        depthStencil,
        multisample,
        fragment: { module, entryPoint: fragment, targets: [blend ? { format: HDR, blend } : { format: HDR }] },
      });
    const alpha = {
      color: { operation: 'add', srcFactor: 'src-alpha', dstFactor: 'one-minus-src-alpha' },
      alpha: { operation: 'add', srcFactor: 'one', dstFactor: 'one-minus-src-alpha' },
    };
    const additive = {
      color: { operation: 'add', srcFactor: 'one', dstFactor: 'one' },
      alpha: { operation: 'add', srcFactor: 'one', dstFactor: 'one' },
    };
    const screen = (label, fragment, target, blend) =>
      device.createRenderPipelineAsync({
        label,
        layout: res.postPipelineLayout,
        vertex: { module: postModule, entryPoint: 'screenVertex' },
        fragment: { module: postModule, entryPoint: fragment, targets: [blend ? { format: target, blend } : { format: target }] },
      });
    const built = await Promise.all([
      pipeline('blocks', blockVertexState(module, 'blockVertex'), 'blockFragment', depth(true, 'less-equal')),
      device.createRenderPipelineAsync({
        label: 'shadow caster',
        layout: res.casterPipelineLayout,
        vertex: blockVertexState(module, 'casterVertex'),
        // A little bias keeps faces from shadowing themselves.
        depthStencil: { format: SHADOW, depthWriteEnabled: true, depthCompare: 'less-equal', depthBias: 2, depthBiasSlopeScale: 2 },
      }),
      // The sky lies at the far plane and is drawn after the city, so it shades only what the city leaves.
      pipeline('sky', { module, entryPoint: 'skyVertex' }, 'skyFragment', depth(false, 'less-equal')),
      pipeline('clouds', blockVertexState(module, 'cloudVertex'), 'cloudFragment', depth(false, 'less-equal'), alpha),
      pipeline('rain', { module, entryPoint: 'rainVertex' }, 'rainFragment', depth(false, 'less-equal'), alpha),
      screen('bloom prefilter', 'bloomPrefilter', HDR),
      screen('bloom down', 'bloomDown', HDR),
      screen('bloom up', 'bloomUp', HDR, additive),
      screen('composite', 'composite', format),
    ]);
    const error = await device.popErrorScope();
    if (error) throw new Error(error.message);
    const [blocks, caster, sky, clouds, rain, prefilter, down, up, composite] = built;
    return { blocks, caster, sky, clouds, rain, prefilter, down, up, composite };
  }

  /**
   * Sized to the canvas: the multisampled float colour and depth the city is drawn into, the float
   * texture it resolves to, and the bloom chain, each level half the one before, with the bind
   * groups the glow passes read them through.
   */
  function makeTargets(w, h) {
    const attachment = (tw, th, fmt, samples, usage) => device.createTexture({ size: [tw, th], format: fmt, sampleCount: samples, usage });
    const color = attachment(w, h, HDR, SAMPLES, GPUTextureUsage.RENDER_ATTACHMENT);
    const depth = attachment(w, h, DEPTH, SAMPLES, GPUTextureUsage.RENDER_ATTACHMENT);
    const hdr = attachment(w, h, HDR, 1, GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING);
    const bloom = [];
    for (let i = 0; i < BLOOM_LEVELS; i++) {
      bloom.push(attachment(Math.max(1, w >> (i + 1)), Math.max(1, h >> (i + 1)), HDR, 1, GPUTextureUsage.RENDER_ATTACHMENT | GPUTextureUsage.TEXTURE_BINDING));
    }
    const hdrView = hdr.createView();
    const bloomViews = bloom.map((t) => t.createView());
    const group = (source, second = source) =>
      device.createBindGroup({
        layout: res.postLayout,
        entries: [
          { binding: 0, resource: { buffer: res.post } },
          { binding: 1, resource: source },
          { binding: 2, resource: res.linear },
          { binding: 3, resource: second },
        ],
      });
    return {
      colorView: color.createView(),
      depthView: depth.createView(),
      hdrView,
      bloomViews,
      // Level i of the chain reads the level above it (the resolved city for the first).
      downGroups: bloomViews.map((_, i) => group(i === 0 ? hdrView : bloomViews[i - 1])),
      // Adding back onto level i reads the level below it.
      upGroups: bloomViews.slice(0, BLOOM_LEVELS - 1).map((_, i) => group(bloomViews[i + 1])),
      compositeGroup: group(hdrView, bloomViews[0]),
      destroy() {
        for (const t of [color, depth, hdr, ...bloom]) t.destroy();
      },
    };
  }

  function uploadCity(data, floats) {
    if (!device || failed) return;
    if (instances) instances.destroy();
    instances = device.createBuffer({ size: Math.max(4, floats * 4), usage: GPUBufferUsage.VERTEX | GPUBufferUsage.COPY_DST, label: 'city' });
    if (floats > 0) device.queue.writeBuffer(instances, 0, data, 0, floats);
  }

  function writeFrame(data) {
    if (!device || failed) return;
    device.queue.writeBuffer(res.frame, 0, data);
  }

  function writeTraffic(data, floats) {
    if (!device || failed || floats <= 0) return;
    device.queue.writeBuffer(res.traffic, 0, data, 0, floats);
  }

  const postValues = new Float32Array(4);

  function drawBlocks(pass, count, moving, indexCount) {
    pass.setVertexBuffer(0, res.cube);
    pass.setIndexBuffer(res.index, 'uint16');
    pass.setVertexBuffer(1, instances);
    pass.drawIndexed(indexCount, count);
    if (moving > 0) {
      // Traffic is the same cube with a per-frame instance buffer.
      pass.setVertexBuffer(1, res.traffic);
      pass.drawIndexed(indexCount, moving);
    }
  }

  function screenPass(encoder, target, pipeline, group, load) {
    const pass = encoder.beginRenderPass({
      colorAttachments: [{ view: target, loadOp: load ? 'load' : 'clear', storeOp: 'store', clearValue: { r: 0, g: 0, b: 0, a: 1 } }],
    });
    pass.setPipeline(pipeline);
    pass.setBindGroup(0, group);
    pass.draw(3);
    pass.end();
  }

  function draw(count, moving, drops, gusts, fold, r, g, b, threshold, knee, strength, flatness, gustVertices, gustBase, indexCount) {
    if (!device || failed || !targets) return;
    const city = count > 0 && instances;
    const encoder = device.createCommandEncoder();
    if (fold < 0.999 && city) {
      // The city from the sun, depth only: what the main pass compares against for shadows.
      const pass = encoder.beginRenderPass({
        colorAttachments: [],
        depthStencilAttachment: { view: res.shadowView, depthLoadOp: 'clear', depthStoreOp: 'store', depthClearValue: 1 },
      });
      pass.setPipeline(pipes.caster);
      pass.setBindGroup(0, res.casterGroup);
      drawBlocks(pass, count, moving, indexCount);
      pass.end();
    }
    const pass = encoder.beginRenderPass({
      colorAttachments: [
        { view: targets.colorView, resolveTarget: targets.hdrView, loadOp: 'clear', storeOp: 'discard', clearValue: { r, g, b, a: 1 } },
      ],
      depthStencilAttachment: { view: targets.depthView, depthLoadOp: 'clear', depthStoreOp: 'discard', depthClearValue: 1 },
    });
    pass.setBindGroup(0, res.sceneGroup);
    if (city) {
      pass.setPipeline(pipes.blocks);
      drawBlocks(pass, count, moving, indexCount);
    }
    // The sky after the city, behind it by depth: the same picture, but the GPU skips the sky's
    // work wherever a building or the plate already stands. None behind the postcard's photo.
    if (!photo) {
      pass.setPipeline(pipes.sky);
      pass.draw(3);
    }
    if (city && moving > 0 && fold < 0.999) {
      // The clouds, soft and blended over all that: their puffs are among the traffic's boxes,
      // whose buffers drawBlocks left bound.
      pass.setPipeline(pipes.clouds);
      pass.drawIndexed(indexCount, moving);
    }
    if (drops > 0) {
      pass.setPipeline(pipes.rain);
      pass.draw(6, drops);
    }
    // Haze's wind streaks: the same pipeline, from instance gustBase on, a ribbon each.
    if (gusts > 0) {
      pass.setPipeline(pipes.rain);
      pass.draw(gustVertices, gusts, 0, gustBase);
    }
    pass.end();
    // The glow: halve down the bloom chain from what is brighter than white, then add each level
    // back onto the one above. None in the flat code, which the composite passes through as is.
    postValues[0] = threshold;
    postValues[1] = knee;
    postValues[2] = strength;
    postValues[3] = flatness;
    device.queue.writeBuffer(res.post, 0, postValues);
    if (strength > 0) {
      screenPass(encoder, targets.bloomViews[0], pipes.prefilter, targets.downGroups[0], false);
      for (let i = 1; i < BLOOM_LEVELS; i++) screenPass(encoder, targets.bloomViews[i], pipes.down, targets.downGroups[i], false);
      for (let i = BLOOM_LEVELS - 2; i >= 0; i--) screenPass(encoder, targets.bloomViews[i], pipes.up, targets.upGroups[i], true);
    }
    const screen = context.getCurrentTexture();
    screenPass(encoder, screen.createView(), pipes.composite, targets.compositeGroup, false);
    const readback = snapshotWanted ? copyOut(encoder, screen) : null;
    device.queue.submit([encoder.finish()]);
    if (readback) finishSnapshot(readback, grab(screen.width, screen.height));
    if (!proven && !proving) {
      // The first frame proves WebGPU works here; one that never finishes means it doesn't.
      proving = true;
      // Only a frame that hangs counts: errors and lost devices are heard by the handlers in start.
      within(8000, device.queue.onSubmittedWorkDone().catch(() => {}), 'the first frame')
        .then(() => {
          proven = true;
          // The city is on screen: the loading poster can go.
          globalThis.SkylinePage?.loaded();
        })
        .catch((e) => failNow(e.message));
    }
  }

  /**
   * The next frame as it shows on screen, for the postcard: resolves to a 2D canvas of it. It is
   * copied from the WebGPU canvas in the same task as the frame is drawn, while the canvas still
   * holds it; where that comes out empty, the frame is read back with copyTextureToBuffer instead.
   * (The read back alone failed in Chrome, 2026-11-22: its mapAsync could reject with "A valid
   * external Instance reference no longer exists".)
   */
  function snapshot() {
    if (!device || failed || !proven) return Promise.reject(new Error('no city to photograph'));
    if (snapshotWanted) snapshotWanted[1](new Error('superseded'));
    const photo = new Promise((resolve, reject) => { snapshotWanted = [resolve, reject]; });
    const mine = snapshotWanted;
    return within(5000, photo, 'the postcard photo').finally(() => { if (snapshotWanted === mine) snapshotWanted = null; });
  }

  function copyOut(encoder, texture) {
    const w = texture.width;
    const h = texture.height;
    const bytesPerRow = Math.ceil((w * 4) / 256) * 256;
    const buffer = device.createBuffer({ size: bytesPerRow * h, usage: GPUBufferUsage.COPY_DST | GPUBufferUsage.MAP_READ, label: 'snapshot' });
    encoder.copyTextureToBuffer({ texture }, { buffer, bytesPerRow }, [w, h]);
    const wanted = snapshotWanted;
    snapshotWanted = null;
    return { buffer, w, h, bytesPerRow, bgra: texture.format.startsWith('bgra'), wanted };
  }

  /** The canvas as it stands now, on a 2D canvas; null if it comes out empty. */
  function grab(w, h) {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(context.canvas, 0, 0);
      // An opaque canvas always has alpha; an empty copy has none.
      const probe = ctx.getImageData(w >> 1, h >> 1, 1, 1).data;
      return probe[3] === 0 ? null : canvas;
    } catch (e) {
      return null;
    }
  }

  async function finishSnapshot({ buffer, w, h, bytesPerRow, bgra, wanted }, grabbed) {
    const [resolve, reject] = wanted;
    if (grabbed) {
      buffer.destroy();
      resolve(grabbed);
      return;
    }
    try {
      await buffer.mapAsync(GPUMapMode.READ);
      const src = new Uint8Array(buffer.getMappedRange());
      const image = new ImageData(w, h);
      const out = image.data;
      for (let y = 0; y < h; y++) {
        let s = y * bytesPerRow;
        let d = y * w * 4;
        for (let x = 0; x < w; x++, s += 4, d += 4) {
          out[d] = bgra ? src[s + 2] : src[s];
          out[d + 1] = src[s + 1];
          out[d + 2] = bgra ? src[s] : src[s + 2];
          out[d + 3] = 255;
        }
      }
      buffer.unmap();
      const canvas = document.createElement('canvas');
      canvas.width = w;
      canvas.height = h;
      canvas.getContext('2d').putImageData(image, 0, 0);
      resolve(canvas);
    } catch (e) {
      reject(e);
    } finally {
      buffer.destroy();
    }
  }

  /**
   * The billboard's messages, white on nothing, one per row of the atlas: as large as fits the
   * screen, down to half size; longer than that, full size, and the billboard scrolls it. Returns
   * each one's width as a share of a row, comma-separated.
   */
  function drawAtlas(text, w, row, rows, view) {
    if (!device || failed) return '';
    const canvas = new OffscreenCanvas(w, row);
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const margin = 0.06 * row;
    const pixels = new Uint8Array(w * row * rows);
    const widths = [];
    text.split('\n').slice(0, rows).forEach((message, i) => {
      const full = row * 0.74;
      const font = (size) => `bold ${size}px system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Noto Sans CJK SC', sans-serif`;
      ctx.font = font(full);
      let size = full;
      const fit = (full * (view * w - 2 * margin)) / ctx.measureText(message).width;
      if (fit < full && fit >= full * 0.5) size = fit;
      ctx.font = font(size);
      const m = ctx.measureText(message);
      const width = Math.min(m.width + 2 * margin, w);
      ctx.clearRect(0, 0, w, row);
      ctx.fillStyle = '#fff';
      ctx.textBaseline = 'alphabetic';
      const ascent = m.fontBoundingBoxAscent ?? size * 0.93;
      const descent = m.fontBoundingBoxDescent ?? size * 0.24;
      ctx.fillText(message, margin, row / 2 - (descent - ascent) / 2);
      const rgba = ctx.getImageData(0, 0, w, row).data;
      for (let p = 0; p < w * row; p++) pixels[i * w * row + p] = rgba[p * 4 + 3];
      widths.push(width / w);
    });
    device.queue.writeTexture({ texture: res.atlas }, pixels, { offset: 0, bytesPerRow: w, rowsPerImage: row * rows }, [w, row * rows]);
    return widths.join(',');
  }

  return { start, resize, photoMode, uploadCity, writeFrame, writeTraffic, draw, drawAtlas, snapshot };
})();
globalThis.SkylineGpu = SkylineGpu;
