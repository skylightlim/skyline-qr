package com.skylightlim.skylineqr.render

/**
 * The WGSL for every pipeline, in one module so they share the Frame uniform layout. Everything the
 * light, the weather or the camera changes lives in that one uniform buffer; everything the link
 * changes lives in the instance buffer.
 */
internal object Wgsl {

  /** Floats in the Frame uniform. Keep in step with the struct below and CityRenderer.writeFrame. */
  const val FRAME_FLOATS = 2 * 16 + 4 * 29

  /** Side of the square sun shadow map, in texels. */
  const val SHADOW_MAP_SIZE = 2048

  const val SOURCE =
    """
struct Frame {
  viewProj: mat4x4f,
  lightViewProj: mat4x4f,  // the sun's view, for the shadow map
  eye: vec4f,              // xyz camera position, w: flatness, 0 is the city and 1 the code
  right: vec4f,            // w: tan(fov / 2)
  up: vec4f,               // w: aspect
  forward: vec4f,          // w: seconds since start
  sunDir: vec4f,           // toward the sun or moon; w: night, 0..1
  sunColor: vec4f,         // w: how dark the sun's shadows are
  skyTop: vec4f,           // w: rain, 0..1
  skyHorizon: vec4f,       // w: haze, 0..1
  plaza: vec4f,            // w: wet streets, 0..1
  window: vec4f,           // w: lightning flash, 0..1
  paper: vec4f,            // the flat code's light modules
  inkLight: vec4f,         // the hour's light on the flat code's ink
  vehicle: vec4f,
  roofs: array<vec4f, 4>,     // w of the first: the family the flat code's function patterns take
  landmarks: array<vec4f, 8>,  // steel, glass, concrete, hill, copper, red, smoked glass, blue glass
  scene: vec4f,            // x: plate half size, y: tallest tower, z: rain speed
  impact: vec4f,           // a meteor strike: x, z, seconds since impact (negative: none), flash
  damage: vec4f,           // a bolt that destroys: x, z, seconds since it hit (negative: none)
  bolt: vec4f,             // where this flash's bolt comes down: x, z, roof height; w 1 while it shows
}

@group(0) @binding(0) var<uniform> frame: Frame;
@group(0) @binding(1) var shadowMap: texture_depth_2d;
@group(0) @binding(2) var shadowSampler: sampler_comparison;

// lowbias32 (Chris Wellons, public domain): an integer mix, so every GPU gives the same pattern.
fn mix32(v: u32) -> u32 {
  var h = v;
  h = h ^ (h >> 16u);
  h = h * 0x7feb352du;
  h = h ^ (h >> 15u);
  h = h * 0x846ca68bu;
  h = h ^ (h >> 16u);
  return h;
}

// 0..1, the same for every point of one grid cell and one salt.
fn cellHash(cell: vec2f, salt: f32) -> f32 {
  let c = bitcast<vec2u>(vec2i(floor(cell)));
  let s = u32(max(salt, 0.0) * 65536.0);
  return f32(mix32(c.x ^ mix32(c.y ^ mix32(s))) >> 8u) / 16777216.0;
}

fn unit(v: u32) -> f32 {
  return f32(mix32(v) >> 8u) / 16777216.0;
}

// Smooth value noise, 0..1, between cell hashes on a unit grid.
fn valueNoise(p: vec2f) -> f32 {
  let i = floor(p);
  let f = fract(p);
  let u = f * f * (3.0 - 2.0 * f);
  let a = cellHash(i, 11.0);
  let b = cellHash(i + vec2f(1.0, 0.0), 11.0);
  let c = cellHash(i + vec2f(0.0, 1.0), 11.0);
  let d = cellHash(i + vec2f(1.0, 1.0), 11.0);
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// ACES filmic curve (Narkowicz fit): highlights roll off instead of clipping.
fn aces(x: vec3f) -> vec3f {
  return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), vec3f(0.0), vec3f(1.0));
}

fn luma(c: vec3f) -> f32 {
  return dot(c, vec3f(0.2126, 0.7152, 0.0722));
}

// What lies behind the city from above: the air below the horizon, a deeper blend of horizon and
// zenith. Looking down, most of the screen is this, so it must not be the raw horizon colour,
// which is the most saturated part of a dawn or dusk sky. Keep in step with the app's status bar.
fn backdrop() -> vec3f {
  return mix(frame.skyHorizon.rgb, frame.skyTop.rgb, 0.35) * 0.92;
}

fn turn(v: vec3f, yaw: f32) -> vec3f {
  let c = cos(yaw);
  let s = sin(yaw);
  return vec3f(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
}

// A box's side leans in when it tapers, so its normal tilts up by the slope. Then it turns.
fn blockNormal(normal: vec3f, size: vec3f, shape: vec2f) -> vec3f {
  var n = normal;
  if (abs(normal.y) < 0.5) {
    let half = select(size.z, size.x, abs(normal.x) > 0.5) * 0.5;
    n = normalize(vec3f(normal.x * size.y, half * (1.0 - shape.y), normal.z * size.y));
  }
  return turn(n, shape.x);
}

struct Placed {
  p: vec3f,
  n: vec3f,
  heat: f32,
  fold: f32,
}

// Where one vertex of a block instance ends up: shaped, struck by a meteor if one fell nearby,
// and collapsing toward the flat code as flatness rises. The shape's x turns the box about its
// axis and its y narrows (or widens) the top: landmarks are made of these.
fn place(v: BlockIn, index: u32) -> Placed {
  let role = v.info.x;
  // Each block folds on its own beat, easing through just under half of the fold: vehicles and
  // beacons first, then the paving, then the towers, the tallest and a scattered few of the rest
  // late, and the landmarks last of all. So blocks keep dropping into their modules after the camera
  // is overhead, and everything is flat by the time the morph is.
  let scatter = fract(v.info.w * 37.0);
  var lag = 0.1 + 0.15 * scatter;
  if (role > 1.5 && role < 2.5) {
    lag = 0.15 + 0.55 * v.info.y + 0.3 * scatter;
  } else if (role > 4.5 && role < 5.5) {
    lag = 1.0;
  } else if (role > 5.5 && role < 6.5) {
    lag = 0.8;
  } else if (role > 2.5) {
    lag = 0.0;
  }
  let beat = clamp((frame.eye.w - LAG_SPAN * lag) / (1.0 - LAG_SPAN), 0.0, 1.0);
  let flatness = beat * beat * (3.0 - 2.0 * beat);
  let tower = select(0.0, 1.0, role > 1.5 && role < 2.5);
  var scale = v.scale;
  // Towers close their street gaps as the city flattens: every dark module becomes one solid block.
  scale.x = mix(scale.x, 1.0, flatness * tower);
  scale.z = mix(scale.z, 1.0, flatness * tower);
  // Beacons, traffic, landmarks and the monorail have no part in the flat code.
  scale = scale * (1.0 - flatness * step(2.5, role));
  let taper = mix(1.0, v.shape.y, v.position.y + 0.5);
  let local = turn(vec3f(v.position.x * taper, v.position.y, v.position.z * taper) * scale, v.shape.x);
  var placed = meteor(local, blockNormal(v.normal, v.scale, v.shape), v.offset, role, index);
  placed = boltDamage(placed, v.offset, v.scale.y, role);
  // Flat, the city lies on thin plates in separate tiers by role, so nothing can z-fight.
  placed.p.y = mix(placed.p.y, 0.004 * role + (v.position.y + 0.5) * 0.02, flatness);
  placed.fold = flatness;
  return placed;
}

// How much of the fold the blocks' beats are spread across; each block takes the rest.
const LAG_SPAN = 0.55;

// What a bolt that destroys does to the towers within BOLT_REACH of it, one or two: they crumble
// to a charred, glowing stub in a third of a second and grow back after BOLT_BACK seconds. A
// function of the seconds since the strike alone, like the meteor. Beacons on them go out.
const BOLT_REACH = 1.2;
const BOLT_BACK = 3.2;

fn boltDamage(p: Placed, offset: vec3f, height: f32, role: f32) -> Placed {
  var out = p;
  let t = frame.damage.z;
  let hit = role > 1.5 && role < 3.5 && length(offset.xz - frame.damage.xy) < BOLT_REACH;
  if (t < 0.0 || !hit) {
    return out;
  }
  let down = smoothstep(0.0, 0.35, t) * (1.0 - smoothstep(BOLT_BACK, BOLT_BACK + 0.8, t));
  if (role < 2.5) {
    // Toward the tower's base: a point h above the ground comes down to h times what stands.
    let base = offset.y - height * 0.5;
    out.p.y = base + (out.p.y - base) * mix(1.0, 0.18, down);
  } else {
    out.p = offset + (out.p - offset) * (1.0 - down);
  }
  // It smoulders: hot at first, then a flickering glow until it grows back.
  let flicker = 0.85 + 0.15 * sin(frame.forward.w * 17.0 + offset.x * 3.0);
  out.heat = max(out.heat, down * (0.6 + 0.4 * exp(-t * 1.2)) * flicker);
  return out;
}

const BLAST = 6.5;
const GRAVITY = 22.0;

// How deep the crater is at this distance (near: 1 at the impact) and time since impact: it fills
// back in as the city rebuilds, on the same rim-first clock as the blocks.
fn crater(near: f32, t: f32) -> f32 {
  let back = 1.7 + 0.9 * near;
  return smoothstep(0.0, 0.6, near) * (1.0 - smoothstep(back, back + 0.6, t));
}

// The crater's char on the ground at a world point, for the plate as well as the tiles.
fn craterHeat(world: vec3f) -> f32 {
  let t = frame.impact.z;
  let near = 1.0 - length(world.xz - frame.impact.xy) / BLAST;
  if (t < 0.0 || near <= 0.0) {
    return 0.0;
  }
  return crater(near, t) * mix(1.0, exp(-t * 0.5), 0.5);
}

// Rodrigues' rotation of v about a unit axis.
fn spin(v: vec3f, axis: vec3f, angle: f32) -> vec3f {
  let c = cos(angle);
  return v * c + cross(axis, v) * sin(angle) + axis * dot(axis, v) * (1.0 - c);
}

// What a meteor strike does to one block, as a function of the seconds since impact alone, so a
// dropped frame can never desync the debris. Blocks near the impact fly out on parabolas, spinning,
// and shrink away in the air, so nothing ever piles up on the code. Paving sinks into a glowing
// crater. Then every block grows back up from the ground where it stood, the rim first.
fn meteor(local: vec3f, normal: vec3f, offset: vec3f, role: f32, index: u32) -> Placed {
  var out = Placed(local + offset, normal, 0.0, 0.0);
  let t = frame.impact.z;
  // Never the plate, the traffic (the renderer hides it), or the meteor itself.
  let struck = role > 0.5 && !(role > 3.5 && role < 4.5) && role < 6.5;
  let d = length(offset.xz - frame.impact.xy);
  if (t < 0.0 || !struck || d > BLAST) {
    return out;
  }
  let near = 1.0 - d / BLAST;          // 1 at the impact, 0 at the rim
  let back = 1.7 + 0.9 * near;         // when this block starts to come back
  if (role < 1.5) {
    // Paving dips into the crater (within its own thickness, or the plate would hide it); the
    // fragment shader chars it, plate and all, by distance.
    out.p.y = out.p.y - 0.05 * crater(near, t);
    return out;
  }
  if (t < back) {
    let r = vec3f(unit(index * 7u + 1u), unit(index * 7u + 2u), unit(index * 7u + 3u));
    let away = normalize(vec3f(offset.x - frame.impact.x, 0.0, offset.z - frame.impact.y) + (r - 0.5) * 0.8 + vec3f(1e-4, 0.0, 0.0));
    let speed = (4.0 + 10.0 * near) * (0.6 + 0.8 * r.x);
    let velocity = away * speed + vec3f(0.0, 5.0 + 11.0 * near * r.y, 0.0);
    let axis = normalize(r * 2.0 - 1.0 + vec3f(0.0, 0.0, 1e-3));
    let angle = t * (2.0 + 7.0 * r.z);
    let fade = 1.0 - smoothstep(0.5 + 0.4 * r.z, 1.1 + 0.4 * r.z, t);
    out.p = offset + velocity * t + vec3f(0.0, -0.5 * GRAVITY * t * t, 0.0) + spin(local, axis, angle) * fade;
    out.n = spin(normal, axis, angle);
    out.heat = near * exp(-t * 2.0);
    return out;
  }
  let grow = clamp((t - back) / 0.6, 0.0, 1.0);
  let eased = 1.0 - (1.0 - grow) * (1.0 - grow) * (1.0 - grow);
  out.p = vec3f(offset.x, offset.y * eased, offset.z) + local * eased;
  return out;
}

// 1 in sunlight, toward 0 in a tower's shadow. Nine taps of the hardware-filtered compare.
fn sunShadow(world: vec3f, n: vec3f) -> f32 {
  let p = frame.lightViewProj * vec4f(world + n * 0.05, 1.0);
  let uv = vec2f(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  let texel = 1.0 / f32(textureDimensions(shadowMap).x);
  var sum = 0.0;
  for (var y = -1; y <= 1; y = y + 1) {
    for (var x = -1; x <= 1; x = x + 1) {
      sum = sum + textureSampleCompareLevel(shadowMap, shadowSampler, uv + vec2f(f32(x), f32(y)) * texel * 1.5, p.z - 0.0015);
    }
  }
  let inside = all(uv > vec2f(0.0)) && all(uv < vec2f(1.0)) && p.z < 1.0;
  return select(1.0, sum / 9.0, inside);
}

// ---------------------------------------------------------------------------------------------
// City blocks: plate, plaza tiles, towers, beacons and traffic, all one instanced cube.
// ---------------------------------------------------------------------------------------------

struct BlockIn {
  @location(0) position: vec3f,
  @location(1) normal: vec3f,
  @location(2) offset: vec3f,
  @location(3) scale: vec3f,
  @location(4) info: vec4f,      // role, height 0..1 (a landmark's glow), 1 on a function pattern, per-instance random
  @location(5) shape: vec2f,     // yaw, taper
}

struct BlockOut {
  @builtin(position) clip: vec4f,
  @location(0) normal: vec3f,
  @location(1) world: vec3f,
  @location(2) local: vec3f,
  @location(3) @interpolate(flat) info: vec4f,
  @location(4) heat: f32,          // a meteor's scorch and embers
  @location(5) @interpolate(flat) fold: f32,
}

@vertex
fn blockVertex(v: BlockIn, @builtin(instance_index) index: u32) -> BlockOut {
  let placed = place(v, index);
  var out: BlockOut;
  out.clip = frame.viewProj * vec4f(placed.p, 1.0);
  out.normal = placed.n;
  out.world = placed.p;
  out.local = v.position;
  out.info = v.info;
  out.heat = placed.heat;
  out.fold = placed.fold;
  return out;
}

// The same blocks seen from the sun, depth only, for the shadow map.
@vertex
fn casterVertex(v: BlockIn, @builtin(instance_index) index: u32) -> @builtin(position) vec4f {
  return frame.lightViewProj * vec4f(place(v, index).p, 1.0);
}

@fragment
fn blockFragment(f: BlockOut) -> @location(0) vec4f {
  let n = normalize(f.normal);
  let role = f.info.x;
  let heightNorm = f.info.y;
  let rnd = f.info.w;
  let flatness = f.fold;
  let time = frame.forward.w;
  let night = frame.sunDir.w;
  let haze = frame.skyHorizon.w;
  let wet = frame.plaza.w;
  let flash = frame.window.w;
  let top = n.y > 0.5;
  let ground = role < 1.5;
  let landmark = role > 4.5 && role < 5.5;
  let rail = role > 5.5 && role < 6.5;
  // Facades that carry windows: ordinary towers, and landmark parts placed early in their colour
  // slot (late in it, they are plain: the telecom tower, the spires, the hill).
  let building = (role > 1.5 && role < 2.5) || (landmark && fract(rnd * 8.0) < 0.45);

  let family = u32(floor(rnd * 8.0)) % 4u;
  let roof = frame.roofs[family].rgb * (0.90 + 0.20 * cellHash(f.world.xz, rnd));

  var albedo: vec3f;
  var gloss = 0.06;
  if (role < 0.5) {
    albedo = frame.plaza.rgb * 0.86;                     // the plate reads as asphalt next to the tiles
  } else if (ground) {
    albedo = frame.plaza.rgb * (0.99 + 0.05 * rnd);      // plaza tiles
  } else if (role < 2.5) {
    albedo = select(roof * 0.58, roof, top);             // sunlit roofs, darker facades
    gloss = select(0.24, 0.10, top);
  } else if (role < 3.5) {
    albedo = frame.window.rgb;                           // beacon
    gloss = 0.0;
  } else if (role < 4.5) {
    // Traffic: the city's paint over its roof families.
    albedo = mix(roof, frame.vehicle.rgb, 0.85) * (1.0 + 0.20 * rnd);
    gloss = 0.30;
  } else if (role > 8.5) {
    albedo = vec3f(1.0);                                 // a lightning bolt glows on its own; see below
  } else if (role > 7.5) {
    albedo = vec3f(rnd) * vec3f(0.92, 0.96, 1.0);        // a vehicle's glass, metal or rubber, or smoke
    gloss = select(0.55, 0.0, rnd > 0.4 && rnd < 0.5);   // smoke, the renderer's 0.45, does not shine
  } else if (role > 6.5) {
    albedo = vec3f(1.0);                                 // the meteor glows on its own; see below
  } else if (landmark) {
    let slot = min(u32(rnd * 8.0), 7u);
    albedo = frame.landmarks[slot].rgb;
    gloss = select(0.40, 0.04, slot == 3u);              // steel and glass shine; the hill does not
  } else {
    albedo = frame.landmarks[2].rgb * 0.85;              // the monorail's concrete
    gloss = 0.10;
  }

  // Coordinates in the plane of the face: the ground for tops; for walls, along the wall's own
  // horizontal, so panes line up on faces turned to any angle.
  var uv = f.world.xz;
  if (abs(n.y) <= 0.5) {
    let along = normalize(vec3f(-n.z, 0.0, n.x) + vec3f(1e-5, 0.0, 0.0));
    uv = vec2f(dot(f.world, along), f.world.y);
  }

  // Window panes: two per module across, one row per floor. Their edges are anti-aliased with the
  // screen-space derivative, taken here where control flow is uniform, as WGSL requires.
  let paneUv = uv * vec2f(2.0, 3.2);
  let paneAa = min(fwidth(paneUv), vec2f(0.3));
  let pane = fract(paneUv);
  let across = smoothstep(0.16 - paneAa.x, 0.16 + paneAa.x, pane.x) * (1.0 - smoothstep(0.84 - paneAa.x, 0.84 + paneAa.x, pane.x));
  let down = smoothstep(0.24 - paneAa.y, 0.24 + paneAa.y, pane.y) * (1.0 - smoothstep(0.80 - paneAa.y, 0.80 + paneAa.y, pane.y));
  let inPane = across * down;

  if (ground && top) {
    // Paving seams on the module grid read as streets.
    let g = abs(fract(uv + 0.5) - 0.5);
    albedo = albedo * mix(1.0, 0.86, smoothstep(0.40, 0.5, max(g.x, g.y)));
    // Wet paving darkens and turns glossy, with puddles here and there.
    let puddle = smoothstep(0.55, 0.85, cellHash(floor(uv * 0.5), 7.0));
    albedo = albedo * (1.0 - wet * (0.26 + 0.12 * puddle));
    gloss = mix(gloss, 0.55 + 0.35 * puddle, wet);
  } else if (building) {
    albedo = albedo * (1.0 - 0.12 * wet);
  }
  if (ground) {
    albedo = albedo * (1.0 - 0.5 * night);               // after dark the street lights, not the moon, light the paving
  }

  var lit = 0.0;
  if (building && !top) {
    let cell = floor(paneUv);                            // each pane is lit or not on its own
    let litChance = 0.26 + 0.16 * night;                // a city is never more than about half lit
    lit = step(1.0 - litChance, cellHash(cell, rnd * 53.0));
    lit = lit * (0.45 + 0.55 * cellHash(cell + 4.0, rnd));
    // A few lights switch on and off, so the skyline is never quite still.
    lit = lit * (0.65 + 0.35 * step(0.30, cellHash(cell, floor(time * 0.4) + rnd * 31.0)));
    lit = lit * inPane;
    albedo = albedo * mix(1.0, 0.74, inPane);           // glass reads darker than the wall
  }

  let sun = normalize(frame.sunDir.xyz);
  let ndl = max(dot(n, sun), 0.0);
  let shadow = mix(1.0, sunShadow(f.world, n), frame.sunColor.w * (1.0 - flatness));
  // A flash lights the whole city, and the towers round the bolt most.
  let fromBolt = f.world.xz - frame.bolt.xy;
  let byBolt = frame.bolt.w * exp(-dot(fromBolt, fromBolt) / 20.0);
  let skyLight = frame.skyHorizon.rgb * 0.62 + vec3f(0.78, 0.84, 1.0) * flash * (1.0 + 2.4 * byBolt);
  let groundLight = frame.plaza.rgb * 0.22;
  let ambient = mix(groundLight, skyLight, n.y * 0.5 + 0.5);
  var color = albedo * (ambient * 0.42 + frame.sunColor.rgb * ndl * shadow * 1.15);
  // Lit windows glow after dark; by day they barely show, and the glass mirrors the sky instead.
  color = color + frame.window.rgb * lit * (0.10 + 0.90 * night);
  if (building && !top) {
    color = color + frame.skyHorizon.rgb * inPane * 0.10 * (1.0 - night);
  }
  if (ground && top) {
    color = color + frame.window.rgb * 0.05 * night;              // street light on the paving
  }
  if (landmark) {
    // After dark the landmarks are floodlit, the spires brightest.
    color = color + albedo * heightNorm * 0.32 * night;
  }

  let view = normalize(frame.eye.xyz - f.world);
  if (role > 2.5 && role < 3.5) {
    color = frame.window.rgb * (1.7 + 0.5 * sin(time * 2.0 + rnd * 12.0));   // beacon pulse
  } else {
    let h = normalize(sun + view);
    color = color + frame.sunColor.rgb * pow(max(dot(n, h), 1e-4), 42.0) * gloss * shadow;
    if (ground && top) {
      // Wet streets mirror the sky, most at grazing angles.
      let fresnel = pow(1.0 - max(dot(n, view), 0.0), 3.0);
      color = color + frame.skyHorizon.rgb * wet * (0.10 + 0.45 * fresnel) * gloss;
    }
  }
  if (role > 3.5 && role < 4.5) {
    color = color + frame.vehicle.rgb * 0.30;                    // lifts small boxes off sky and plaza
    color = color + frame.window.rgb * (0.10 + 0.85 * night);    // headlights after dark
  }

  // A meteor strike: the crater chars dark with embers flickering in it; flying fragments glow.
  let heat = select(f.heat, craterHeat(f.world), ground);
  if (heat > 0.0) {
    if (ground) {
      let cell = floor(f.world.xz * 2.0);
      let embers = step(0.7, cellHash(cell, 3.0)) * (0.6 + 0.4 * sin(time * 9.0 + cellHash(cell, 5.0) * 20.0));
      color = color * (1.0 - 0.82 * heat) + vec3f(1.0, 0.33, 0.07) * embers * heat * 1.6;
    } else {
      color = color * (1.0 - 0.5 * heat) + vec3f(1.0, 0.42, 0.10) * heat * heat * 1.8;
    }
  }
  color = color + albedo * vec3f(1.0, 0.78, 0.55) * frame.impact.w * 0.9;

  // Darker toward the base, and a soft edge so each box reads as a rounded block.
  let baseShade = mix(0.62, 1.0, clamp(f.local.y + 0.5, 0.0, 1.0));
  var face = abs(f.local.xz);
  if (abs(n.x) > 0.5) {
    face = abs(f.local.zy);
  } else if (abs(n.z) > 0.5) {
    face = abs(f.local.xy);
  }
  let rim = smoothstep(0.40, 0.5, max(face.x, face.y));
  color = color * baseShade * mix(1.0, 0.86, rim * (1.0 - flatness));

  var toned = pow(aces(color), vec3f(1.0 / 2.2));
  // Slightly richer chroma, so the materials and vehicle paint read as colour, not pastel. Not on
  // steel, glass and concrete: there it only inflates the sun's warm cast into pink.
  let chroma = select(1.45, 1.05, landmark || rail);
  toned = clamp(mix(vec3f(luma(toned)), toned, chroma), vec3f(0.0), vec3f(1.0));
  // Haze thickens from the plate's near edge, and drifts across the city in banks that grow
  // denser with height, so they swallow whole stretches of the skyline, the landmarks' upper
  // floors first, and let them go again.
  let near = length(frame.eye.xyz) - frame.scene.x;
  let depth = 1.0 - exp(-max(length(frame.eye.xyz - f.world) - near, 0.0) * 0.06);
  let drift = vec2f(f.world.x * 0.09 + time * 0.06, f.world.z * 0.09 - time * 0.04) + vec2f(f.world.y * 0.06);
  let bank = smoothstep(0.30, 0.78, valueNoise(drift));
  let aloft = smoothstep(0.5, max(frame.scene.y * 0.55, 1.0), f.world.y);
  let fog = haze * clamp(0.22 + 0.45 * depth + 0.62 * bank * (0.35 + 0.65 * aloft), 0.0, 0.94);
  toned = mix(toned, mix(frame.skyHorizon.rgb, backdrop(), 0.4), fog);

  if (role > 6.5 && role < 7.5) {
    toned = mix(vec3f(1.0, 0.55, 0.18), vec3f(1.0, 0.97, 0.88), heightNorm);   // white-hot head, orange tail
  }
  if (role > 8.5) {
    toned = mix(vec3f(0.55, 0.66, 1.0), vec3f(0.96, 0.98, 1.0), heightNorm);   // a bolt, white at its brightest
  }

  // Flat, every dark module keeps its tower's colour in the hour's light, and every light module
  // is the hour's paper, a shade lighter or darker. The finders, timing, alignment and format
  // modules all take one ink, so a scanner always finds them. The quiet zone's plate is plain paper.
  var flatColor = frame.paper.rgb;
  if (role > 1.5 && f.info.z > 0.5) {
    flatColor = ink(frame.roofs[u32(frame.roofs[0].w)].rgb);
  } else if (role > 1.5) {
    flatColor = ink(frame.roofs[family].rgb);
  } else if (role > 0.5) {
    flatColor = paperShade(rnd);
  }
  return vec4f(mix(toned, flatColor, flatness), 1.0);
}

// The flat code's colours: the same arithmetic as FlatColours in skylineqr-core, which explains it
// and whose tests scan it; keep the two in step. In short, every ink has exactly INK_LUMA under
// ZXing's (R + 2G + B) / 4, so to a local threshold the code is one ink on paper, and differs only
// in hue, within BAND_601 of it under the camera's BT.601 and BAND_709 under Rec. 709.
const INK_LUMA = 0.29;
const BAND_601 = 0.02;
const BAND_709 = 0.07;
const INK_CHROMA = 2.0;
const PAPER_JITTER = 0.012;

fn luma601(c: vec3f) -> f32 {
  return dot(c, vec3f(0.299, 0.587, 0.114));
}

fn lumaZxing(c: vec3f) -> f32 {
  return dot(c, vec3f(0.25, 0.5, 0.25));
}

fn ink(roof: vec3f) -> vec3f {
  let lit = roof * frame.inkLight.rgb;
  let grey = lumaZxing(lit);
  var c = clamp(vec3f(grey) + (lit - vec3f(grey)) * INK_CHROMA, vec3f(0.0), vec3f(1.0));
  c = c * (INK_LUMA / max(lumaZxing(c), 0.001));
  // Moving toward grey keeps every luma formula's value: first for a hue too saturated to reach
  // the brightness without clipping, then for the two bands.
  let peak = max(c.r, max(c.g, c.b));
  if (peak > 1.0) {
    c = vec3f(INK_LUMA) + (c - vec3f(INK_LUMA)) * ((1.0 - INK_LUMA) / (peak - INK_LUMA));
  }
  let d601 = abs(luma601(c) - INK_LUMA);
  let d709 = abs(luma(c) - INK_LUMA);
  var k = 1.0;
  if (d601 > 1e-5) {
    k = min(k, BAND_601 / d601);
  }
  if (d709 > 1e-5) {
    k = min(k, BAND_709 / d709);
  }
  return vec3f(INK_LUMA) + (c - vec3f(INK_LUMA)) * k;
}

fn paperShade(rnd: f32) -> vec3f {
  return clamp(frame.paper.rgb * (1.0 + PAPER_JITTER * (fract(rnd * 7.0) * 2.0 - 1.0)), vec3f(0.0), vec3f(1.0));
}

// ---------------------------------------------------------------------------------------------
// Sky: one full-screen triangle, a ray per pixel.
// ---------------------------------------------------------------------------------------------

struct SkyOut {
  @builtin(position) clip: vec4f,
  @location(0) ndc: vec2f,
}

@vertex
fn skyVertex(@builtin(vertex_index) i: u32) -> SkyOut {
  let p = vec2f(f32((i << 1u) & 2u), f32(i & 2u)) * 2.0 - 1.0;
  var out: SkyOut;
  out.clip = vec4f(p, 1.0, 1.0);
  out.ndc = p;
  return out;
}

@fragment
fn skyFragment(s: SkyOut) -> @location(0) vec4f {
  let tanHalf = frame.right.w;
  let aspect = frame.up.w;
  let shift = frame.scene.w;   // the camera's lens shift, so these rays line up with the city
  let dir = normalize(
    frame.forward.xyz + frame.right.xyz * (s.ndc.x * tanHalf * aspect) + frame.up.xyz * ((s.ndc.y - shift) * tanHalf)
  );
  let h = clamp(dir.y, -0.25, 1.0);
  var sky = mix(frame.skyHorizon.rgb, frame.skyTop.rgb, smoothstep(0.0, 0.55, h));

  // Clouds and haze hide the sun's disc before they hide its glow.
  let clear = (1.0 - frame.skyTop.w) * (1.0 - 0.8 * frame.skyHorizon.w);
  let sunAmount = max(dot(dir, normalize(frame.sunDir.xyz)), 1e-4);
  sky = sky + frame.sunColor.rgb * (0.60 * clear * pow(sunAmount, 350.0) + 0.14 * pow(sunAmount, 10.0)) * (1.0 - 0.55 * frame.sunDir.w);
  sky = sky + vec3f(1.0, 0.75, 0.5) * frame.impact.w * 0.6;
  // A lightning flash lights the whole sky, brightest overhead.
  sky = sky + vec3f(0.95, 1.0, 1.2) * frame.window.w * (0.5 + 0.5 * max(dir.y, 0.0));
  // Below the horizon the city floats in its own atmosphere, not against a grey wall; a flash
  // lights that too.
  sky = mix(backdrop() + vec3f(0.45, 0.48, 0.6) * frame.window.w, sky, smoothstep(-0.30, 0.04, dir.y));

  // Flat, the code sits on a pale card of the hour's own sky, a module wider than the plate all
  // round, and the screen around it takes a deeper colour of the same sky.
  let surround = mix(frame.skyTop.rgb, backdrop(), 0.35);
  let t = -frame.eye.y / min(dir.y, -1e-4);
  let hit = frame.eye.xz + dir.xz * t;
  let corner = 1.0;
  let q = abs(hit) - vec2f(frame.scene.x + 1.0 - corner);
  let d = length(max(q, vec2f(0.0))) + min(max(q.x, q.y), 0.0) - corner;
  let edge = max(fwidth(d), 1e-3);
  let card = select(0.0, 1.0 - smoothstep(-edge, edge, d), dir.y < 0.0);
  return vec4f(mix(sky, mix(surround, frame.paper.rgb, card), frame.eye.w), 1.0);
}

// ---------------------------------------------------------------------------------------------
// Rain: streaks that fall through the city, one instance each, positions from the drop's index.
// ---------------------------------------------------------------------------------------------

struct RainOut {
  @builtin(position) clip: vec4f,
  @location(0) along: f32,
}

@vertex
fn rainVertex(@builtin(vertex_index) vi: u32, @builtin(instance_index) drop: u32) -> RainOut {
  var corners = array<vec2f, 6>(
    vec2f(-1.0, 0.0), vec2f(1.0, 0.0), vec2f(1.0, 1.0),
    vec2f(-1.0, 0.0), vec2f(1.0, 1.0), vec2f(-1.0, 1.0),
  );
  let c = corners[vi];
  let spread = frame.scene.x * 1.15;
  let bottom = -1.5;
  let height = frame.scene.y + 9.0 - bottom;
  let speed = (24.0 + 10.0 * unit(drop * 4u + 3u)) * frame.scene.z;   // gentler with animations off
  let fall = normalize(vec3f(0.16, -1.0, 0.07));
  let x = (unit(drop * 4u) * 2.0 - 1.0) * spread;
  let z = (unit(drop * 4u + 1u) * 2.0 - 1.0) * spread;
  let y = bottom + height * (1.0 - fract(unit(drop * 4u + 2u) + frame.forward.w * speed / height));
  let head = vec3f(x, y, z);
  // A thin quad along the fall, turned to face the camera.
  let side = normalize(cross(fall, frame.eye.xyz - head));
  let p = head - fall * (0.75 * c.y) + side * (0.022 * c.x);
  var out: RainOut;
  out.clip = frame.viewProj * vec4f(p, 1.0);
  out.along = c.y;
  return out;
}

@fragment
fn rainFragment(r: RainOut) -> @location(0) vec4f {
  let amount = frame.skyTop.w * (1.0 - frame.eye.w);
  // Against a bright sky rain reads as darker streaks, against a dark one as lighter ones.
  let tint = mix(frame.skyHorizon.rgb * 0.72, vec3f(0.86, 0.90, 1.0), 0.25 + 0.55 * frame.sunDir.w) + vec3f(0.4) * frame.window.w;
  // The head of the streak is strongest; it fades out up the tail.
  return vec4f(tint, 0.34 * amount * (1.0 - r.along));
}
"""
}
