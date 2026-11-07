package com.skylightlim.skylineqr.render

import com.skylightlim.skylineqr.city.FlatColours

/**
 * The WGSL for every pipeline, in one module so they share the Frame uniform layout. Everything the
 * light, the weather or the camera changes lives in that one uniform buffer; everything the link
 * changes lives in the instance buffer.
 */
internal object Wgsl {

  /** Floats in the Frame uniform. Keep in step with the struct below and CityRenderer.writeFrame. */
  const val FRAME_FLOATS = 2 * 16 + 4 * (53 + FlatColours.MAX_TONES + FlatColours.INKS) + Billboard.FLOATS

  /** Side of the square sun shadow map, in texels. */
  const val SHADOW_MAP_SIZE = 2048

  /**
   * Haze's wind streaks are drawn by the rain pipeline from this instance on, each a ribbon of
   * [GUST_SEGMENTS] quads, [GUST_VERTICES] vertices.
   */
  const val GUST_BASE = 65536
  const val GUST_SEGMENTS = 10
  const val GUST_VERTICES = 6 * GUST_SEGMENTS

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
  paper: vec4f,            // the flat code's light modules; w: 1 while raising the city from the code
  ink: vec4f,              // the flat code's colour at this hour; w: how far haze's front has rolled across, 0..1
  vehicle: vec4f,          // w: how strongly haze's wind streaks show, 0..1
  roofs: array<vec4f, 4>,
  landmarks: array<vec4f, 32>, // steel, glass, concrete, hill, copper, red, smoked glass, blue glass,
                               // leaf, lawn, water, bronze, brick, flag blue, flag yellow, granite,
                               // champagne, lattice, flag red, flag white, crystal, the mall's warm
                               // and cool neon; the rest unused
  blocks: array<vec4f, 16>,    // a tower's colour by its tone: its district's, or paler
  inks: array<vec4f, ${FlatColours.INKS}>,      // the flat code's inks, from FlatColours
  scene: vec4f,            // x: plate half size, y: tallest tower, z: rain speed
  impact: vec4f,           // a meteor strike: x, z, seconds since impact (negative: none), flash
  damage: vec4f,           // a bolt that destroys: x, z, seconds since it hit (negative: none)
  bolt: vec4f,             // where this flash's bolt comes down: x, z, roof height; w 1 while it shows
  screen: array<vec4f, 3>, // the billboard: row, offset, width of the message showing and the next; flip, view, shown
}

@group(0) @binding(0) var<uniform> frame: Frame;
@group(0) @binding(1) var shadowMap: texture_depth_2d;
@group(0) @binding(2) var shadowSampler: sampler_comparison;
@group(0) @binding(3) var screenAtlas: texture_2d<f32>;   // the billboard's messages, a row each
@group(0) @binding(4) var screenSampler: sampler;

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
  let fold = frame.eye.w;
  let tower = select(0.0, 1.0, role > 1.5 && role < 2.5);
  var scale = v.scale;
  // How far this vertex has come down to its plate.
  var flatness: f32;
  if (raising()) {
    // Back to the city (2026-11-06 revision): the code's colours go and the towers narrow to their
    // footprints first, while the landmarks' footprints spread out of the code, so what rises is
    // the city and not the code's slabs. Then everything grows up from the ground (see rise).
    // Beacons, traffic and the lights come back last, on what has risen.
    let r = 1.0 - fold;
    let widen = (1.0 - smoothstep(0.0, RAISE_NARROW, r)) * tower;
    scale.x = mix(scale.x, 1.0, widen);
    scale.z = mix(scale.z, 1.0, widen);
    let start = riseStart(v.offset.xz);
    flatness = 1.0 - rise(r, start, max(v.offset.y + v.position.y * v.scale.y, 0.0));
    if (role > 4.5 && role < 6.5) {
      let spread = smoothstep(start, start + RAISE_NARROW, r);
      scale.x = scale.x * spread;
      scale.z = scale.z * spread;
    } else if (role > 2.5) {
      scale = scale * smoothstep(RAISE_LAST, 1.0, r);
    }
  } else {
    // Only the shapes move once the colours have washed (2026-11-01 revision, after the ICQR
    // tree). What has no part in the code shrinks away first: traffic and beacons, then the
    // landmarks and the monorail. The towers widen until they touch, so the code can be read
    // while they still stand, and sink together, a little scattered so it is not mechanical. All
    // ease in and settle softly (smootherstep), and all have landed when the fold ends.
    var beat = fold;
    if (tower > 0.5) {
      let start = 0.18 + 0.06 * fract(v.info.w * 37.0);
      beat = (fold - start) / (1.0 - start);
    } else if (role > 4.5 && role < 6.5) {
      beat = fold / 0.45;
    } else if (role > 2.5) {
      beat = fold / 0.3;
    }
    beat = clamp(beat, 0.0, 1.0);
    flatness = beat * beat * beat * (beat * (beat * 6.0 - 15.0) + 10.0);
    let widen = smoothstep(0.0, 0.35, fold) * tower;
    scale.x = mix(scale.x, 1.0, widen);
    scale.z = mix(scale.z, 1.0, widen);
    // Beacons, traffic, landmarks and the monorail have no part in the flat code.
    scale = scale * (1.0 - flatness * step(2.5, role));
  }
  let taper = mix(1.0, v.shape.y, v.position.y + 0.5);
  var local = turn(vec3f(v.position.x * taper, v.position.y, v.position.z * taper) * scale, v.shape.x);
  // A flag's cloth waves in the wind, more toward the fly. Each of its eight pieces carries in its
  // colour slot how far from the pole it hangs (see KualaLumpur.flagColour), and the wave runs
  // along the flag, so neighbouring pieces meet.
  let share = fract(v.info.w * 32.0);
  if (role > 4.5 && role < 5.5 && share > 0.69) {
    let span = v.scale.x * 8.0;
    let reach = max((share - 0.7) / 0.28 + v.position.x * 0.125, 0.0);
    let along = (v.offset.x + local.x) / span;
    local.z = local.z + sin(along * 6.5 - frame.forward.w * 5.0) * 0.12 * span * reach;
    local.y = local.y + sin(along * 4.0 - frame.forward.w * 3.0) * 0.03 * span * reach;
  }
  var placed = meteor(local, blockNormal(v.normal, v.scale, v.shape), v.offset, role, index);
  placed = boltDamage(placed, v.offset, v.scale.y, role);
  // Flat, the city lies on thin plates in separate tiers by role, so nothing can z-fight.
  placed.p.y = mix(placed.p.y, 0.004 * role + (v.position.y + 0.5) * 0.02, flatness);
  // What stays washes to the code's colours; what shrinks away keeps its own.
  placed.fold = select(wash(), 0.0, role > 2.5);
  return placed;
}

// Whether the morph is on its way from the code back to the city, which has its own choreography
// (Morph.raising). Both ways give the same city at 0 and the same code at 1, and the morph only
// changes way at one of those ends, so nothing jumps.
fn raising() -> bool {
  return frame.paper.w > 0.5;
}

// The raise: the code's colours have gone and the towers have their own footprints by this far.
const RAISE_NARROW = 0.3;
// The raise: beacons, traffic and the traffic lights come back from here on.
const RAISE_LAST = 0.75;

// When the ground at this point starts to rise: the middle of the city first and the edges a
// moment later, in one smooth wave, so a landmark's parts and a beacon and its roof rise as one.
fn riseStart(at: vec2f) -> f32 {
  return 0.04 + 0.08 * min(length(at) / frame.scene.x, 1.0);
}

// How far up a point that stands [standing] high has come, [r] of the way into the raise. The
// higher it stands, the longer it takes, so the low city has settled while the tallest towers
// still climb, at a steady pace and not in a rush, and they settle softly as the raise ends. The
// time grows more slowly than the height, so a point higher up is always above one lower down.
fn rise(r: f32, start: f32, standing: f32) -> f32 {
  let share = 0.5 + 0.5 * sqrt(min(standing / frame.scene.y, 1.0));
  let b = clamp((r - start) / ((1.0 - start) * share), 0.0, 1.0);
  return b * b * (3.0 - 2.0 * b);
}

// How far the colours have gone from the city's to the code's. Toward the code, all at once and
// early, while the camera is moving fastest, so the blocks are their final colours for most of
// their way down. Back to the city, at once, so the city's colours are what rises.
fn wash() -> f32 {
  let fold = frame.eye.w;
  if (raising()) {
    return 1.0 - smoothstep(0.0, RAISE_NARROW, 1.0 - fold);
  }
  return smoothstep(0.02, 0.4, fold);
}

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
  @location(4) info: vec4f,      // role, height 0..1 (a landmark's glow), the flat code's module number, per-instance random
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
  @location(6) @interpolate(flat) size: vec3f,   // the box's own size, for trims of a set width
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
  out.size = v.scale;
  return out;
}

// The same blocks seen from the sun, depth only, for the shadow map.
@vertex
fn casterVertex(v: BlockIn, @builtin(instance_index) index: u32) -> @builtin(position) vec4f {
  // The weather's shapes cast no shadow: they are put out of the sun's view.
  if (v.info.x > 11.5) {
    return vec4f(0.0, 0.0, -2.0, 1.0);
  }
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
  let building = (role > 1.5 && role < 2.5) || (landmark && fract(rnd * 32.0) < 0.45);

  let family = u32(floor(rnd * 8.0)) % 4u;
  let grain = 0.90 + 0.20 * cellHash(f.world.xz, rnd);
  let roof = frame.roofs[family].rgb * grain;
  // A tower's tone is its district's colour; a tile's, its shade of paper.
  let tone = (u32(f.info.z + 0.5) >> 1u) & 15u;

  var albedo: vec3f;
  var gloss = 0.06;
  if (role < 0.5) {
    albedo = frame.plaza.rgb * 0.86;                     // the plate reads as asphalt next to the tiles
  } else if (ground) {
    albedo = frame.plaza.rgb * (0.99 + 0.05 * rnd);      // plaza tiles
  } else if (role < 2.5) {
    let block = frame.blocks[tone].rgb * grain;
    albedo = select(block * 0.58, block, top);           // sunlit roofs, darker facades
    gloss = select(0.24, 0.10, top);
  } else if (role < 3.5) {
    albedo = frame.window.rgb;                           // beacon
    gloss = 0.0;
  } else if (role < 4.5) {
    // Traffic: the city's paint over its roof families.
    albedo = mix(roof, frame.vehicle.rgb, 0.85) * (1.0 + 0.20 * rnd);
    gloss = 0.30;
  } else if (role > 11.5) {
    // The weather: a cloud in its own grey, a touch cool; the sun and the moon glow (see below).
    albedo = vec3f(rnd) * vec3f(0.96, 0.98, 1.0);
    gloss = 0.0;
  } else if (role > 10.5) {
    albedo = vec3f(0.07);                                // the billboard's frame; its face shines
    gloss = 0.3;
  } else if (role > 9.5) {
    albedo = signalColour(rnd);                          // a traffic light's lamp
    gloss = 0.0;
  } else if (role > 8.5) {
    albedo = vec3f(1.0);                                 // a lightning bolt glows on its own; see below
  } else if (role > 7.5) {
    albedo = vec3f(rnd) * vec3f(0.92, 0.96, 1.0);        // a vehicle's glass, metal or rubber, or smoke
    gloss = select(0.55, 0.0, rnd > 0.4 && rnd < 0.5);   // smoke, the renderer's 0.45, does not shine
  } else if (role > 6.5) {
    albedo = vec3f(1.0);                                 // the meteor glows on its own; see below
  } else if (landmark) {
    let slot = min(u32(rnd * 32.0), 31u);
    albedo = frame.landmarks[slot].rgb;
    // Steel and glass shine; the hill, trees, lawns and granite do not; water shines most.
    gloss = select(0.40, 0.04, slot == 3u || slot == 8u || slot == 9u || slot == 15u);
    gloss = select(gloss, 0.75, slot == 10u);
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
  // The mall's fins, five to a module, and its lattice of diamonds, two to a module: their lines'
  // widths in screen space, taken here for the same reason.
  let finUv = uv.x * 5.0;
  let finAa = fwidth(finUv);
  let latticeUv = vec2f(uv.x + uv.y, uv.x - uv.y) * 2.0;
  let latticeAa = fwidth(latticeUv);

  // The mall's skin: champagne fins up its walls and white lattice in diamonds. After dark the
  // skin falls dark and each fin's edge is a line of warm neon, each line of the lattice cool
  // neon (2026-10-22).
  var neon = vec3f(0.0);
  if (landmark && abs(n.y) <= 0.5) {
    let skin = min(u32(rnd * 32.0), 31u);
    let unlit = mix(1.0, 0.35, smoothstep(0.3, 0.9, night));
    if (skin == 16u) {
      albedo = albedo * (0.8 + 0.32 * smoothstep(0.08, 0.32, abs(fract(finUv) - 0.5))) * unlit;
      neon = frame.landmarks[21].rgb * neonLine(0.5 - abs(fract(finUv) - 0.5), 0.05, finAa);
    } else if (skin == 17u) {
      let d = vec2f(abs(fract(latticeUv.x) - 0.5), abs(fract(latticeUv.y) - 0.5));
      albedo = albedo * (0.84 + 0.16 * smoothstep(0.04, 0.1, min(d.x, d.y))) * unlit;
      let lines = max(neonLine(d.x, 0.06, latticeAa.x), neonLine(d.y, 0.06, latticeAa.y));
      neon = frame.landmarks[22].rgb * lines;
    }
  }

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
  // What shines by its own light, added after the tone curve so it can be brighter than white and
  // glow softly (2026-10-22: every light alike, gently): lit panes, beacons (vehicles' lights
  // too), traffic lights, the billboard, the meteor and bolts.
  var emit = vec3f(0.0);
  let dark = smoothstep(0.3, 0.9, night);
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
  emit = emit + frame.window.rgb * lit * dark * 0.3;
  if (landmark && min(u32(rnd * 32.0), 31u) == 20u) {
    emit = emit + albedo * (0.05 + 0.15 * dark);                // the crystal fountain, lit from within
  }
  // The mall's neon: off by day, soft after dark. Its name shines by day too, as a sign does.
  color = color + neon * 0.7 * dark;
  emit = emit + neon * 0.55 * dark;
  if (landmark && min(u32(rnd * 32.0), 31u) == 22u) {
    color = color + albedo * 0.3;
    emit = emit + albedo * (0.15 + 0.45 * dark);
  }

  let view = normalize(frame.eye.xyz - f.world);
  if (role > 2.5 && role < 3.5) {
    let pulse = 1.7 + 0.5 * sin(time * 2.0 + rnd * 12.0);
    color = frame.window.rgb * pulse;                                        // beacon pulse
    emit = emit + frame.window.rgb * pulse * (0.1 + 0.4 * dark);
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
  // The banks ride the wind downwind (+x), as the wind streaks do, only slower.
  let drift = vec2f(f.world.x * 0.09 - time * 0.11, f.world.z * 0.09 - time * 0.02) + vec2f(f.world.y * 0.06);
  let bank = smoothstep(0.30, 0.78, valueNoise(drift));
  let aloft = smoothstep(0.5, max(frame.scene.y * 0.55, 1.0), f.world.y);
  // It rolls in from upwind (-x) when it comes (2026-11-07, at the user's request): ahead of its
  // front the air is still clear. The front hugs the ground, running ahead along the streets while
  // the air above fills in behind it, and billows as it comes; it crosses the plate as frame.ink.w
  // goes from 0 to 1.
  let billow = 7.0 * (valueNoise(vec2f(f.world.z * 0.11 + time * 0.3, f.world.y * 0.12 + 5.0)) - 0.5);
  let edge = billow - 0.5 * f.world.y;
  let crossing = frame.scene.x + 0.5 * frame.scene.y + 4.0;
  let frontX = mix(-1.0, 1.0, frame.ink.w) * crossing * 1.3 + edge;
  let blown = select(1.0 - smoothstep(frontX - 4.0, frontX + 4.0, f.world.x), 1.0, frame.ink.w >= 1.0);
  // Its leading edge is a thicker wall, so it is seen to come.
  let wall = select(smoothstep(frontX - 12.0, frontX - 3.0, f.world.x) * (1.0 - smoothstep(frontX - 3.0, frontX + 3.0, f.world.x)), 0.0, frame.ink.w >= 1.0);
  let fog = haze * clamp(blown * (0.22 + 0.45 * depth + 0.62 * bank * (0.35 + 0.65 * aloft)) + 0.45 * wall, 0.0, 0.94);
  toned = mix(toned, mix(frame.skyHorizon.rgb, backdrop(), 0.4), fog);

  if (role > 6.5 && role < 7.5) {
    toned = mix(vec3f(1.0, 0.55, 0.18), vec3f(1.0, 0.97, 0.88), heightNorm);   // white-hot head, orange tail
    emit = emit + toned * 1.0;
  }
  if (role > 8.5 && role < 9.5) {
    toned = mix(vec3f(0.55, 0.66, 1.0), vec3f(0.96, 0.98, 1.0), heightNorm);   // a bolt, white at its brightest
    emit = emit + toned * 1.2;
  }
  if (role > 9.5 && role < 10.5) {
    toned = signalColour(rnd);                                                  // a lamp shines by day too
    emit = emit + toned * (0.3 + 0.9 * dark);
  }
  if (role > 11.5 && heightNorm > 0.9) {
    // The sun: deep orange on the horizon, warm white high up (rnd is how high), a dim orange
    // through haze.
    let high = smoothstep(0.0, 0.45, rnd);
    toned = mix(vec3f(1.0, 0.42, 0.16), vec3f(1.0, 0.88, 0.50), high);
    toned = mix(toned, vec3f(1.0, 0.50, 0.22), 0.65 * haze);
    toned = mix(toned, mix(frame.skyHorizon.rgb, backdrop(), 0.4), 0.35 * haze);
    emit = emit + toned * (0.6 + 0.35 * high) * (1.0 - 0.75 * haze);
  } else if (role > 11.5 && heightNorm > 0.5) {
    // The moon: pale, glowing after dark and faint by day, brighter the more of it is lit (rnd).
    toned = vec3f(0.90, 0.93, 1.0);
    emit = emit + toned * (0.15 + 0.75 * night) * (0.45 + 0.55 * rnd);
  } else if (role > 11.5 && heightNorm > 0.0) {
    // A storm cloud lit from inside by the bolt leaving it.
    let lit = heightNorm * 2.0;
    toned = mix(toned, vec3f(0.86, 0.90, 1.0), 0.7 * lit);
    emit = emit + vec3f(0.55, 0.62, 0.95) * lit;
  }
  if (role > 10.5 && role < 11.5) {
    let screen = billboard(f.local);
    toned = mix(toned, screen.rgb, screen.a);
    emit = emit + screen.rgb * screen.a * (0.4 + 0.8 * dark);
  }
  // Haze swallows light as well as colour.
  emit = emit * (1.0 - 0.75 * fog) * (1.0 - flatness);

  // Flat, every module is a square of its colour: a dark one its ink, a light one its shade of the
  // hour's paper; the plate's quiet zone is the hour's paper itself. Sides that still stand are
  // darker, so a block in its final colour still reads as a block.
  var flatColor = frame.paper.rgb;
  if (role > 1.5 && role < 2.5) {
    flatColor = frame.inks[(u32(f.info.z + 0.5) >> 5u) & ${FlatColours.INKS - 1}u].rgb;
  } else if (role > 0.5 && role < 1.5) {
    flatColor = paperAt(tone);
  }
  flatColor = flatColor * select(0.62, 1.0, top);
  return vec4f(mix(toned + emit, flatColor, flatness), 1.0);
}

// A lit line of half-width w (in its pattern's cells) at distance d from its middle, smoothed over
// aa, the cell's width in pixels' terms. Once the line is thinner than a pixel it fades to its
// average over the cell, so far away it glows evenly instead of shimmering.
fn neonLine(d: f32, w: f32, aa: f32) -> f32 {
  let sharp = 1.0 - smoothstep(w - aa, w + aa, d);
  return mix(sharp, 2.0 * w, smoothstep(w, 4.0 * w, aa));
}

// The billboard's letter colours, a message each: pink, cyan, violet, amber and green.
fn neonColour(k: f32) -> vec3f {
  var palette = array<vec3f, 5>(
    vec3f(1.0, 0.22, 0.62),
    vec3f(0.15, 0.85, 1.0),
    vec3f(0.62, 0.35, 1.0),
    vec3f(1.0, 0.55, 0.15),
    vec3f(0.35, 1.0, 0.55),
  );
  return palette[min(u32(k * 5.0), 4u)];
}

// The billboard's face (the box's +z side), as CityRenderer and Billboard describe it: the message
// showing, its letters in a neon colour of its own on a dark screen, and while it turns to the
// next, twelve slats flipping in a wave like a tri-vision board, each squeezed by its turn and
// darker edge-on. a is 1 on the face.
fn billboard(local: vec3f) -> vec4f {
  if (local.z < 0.499 || frame.screen[2].z < 0.5) {
    return vec4f(0.0);
  }
  let uv = vec2f(local.x + 0.5, 0.5 - local.y);
  let inset = 0.035;
  if (any(uv < vec2f(inset)) || any(uv > vec2f(1.0 - inset))) {
    return vec4f(vec3f(0.05), 1.0);
  }
  let p = (uv - vec2f(inset)) / (1.0 - 2.0 * inset);
  let slats = 12.0;
  let k = floor(p.x * slats);
  let turn = clamp(frame.screen[2].x * 1.6 - k / slats * 0.6, 0.0, 1.0);
  let edge = cos(turn * 3.14159265);
  let across = (fract(p.x * slats) - 0.5) / max(abs(edge), 0.02);
  if (abs(across) > 0.5) {
    return vec4f(vec3f(0.01), 1.0);
  }
  let shown = select(frame.screen[0], frame.screen[1], turn > 0.5);
  let u = shown.y + (k + 0.5 + across) / slats * frame.screen[2].y;
  var ink = 0.0;
  if (u >= 0.0 && u <= shown.z) {
    ink = textureSampleLevel(screenAtlas, screenSampler, vec2f(u, (shown.x + p.y) / 8.0), 0.0).r;
  }
  let letters = neonColour(fract(shown.x * 0.2 + 0.05));
  return vec4f(mix(vec3f(0.015, 0.02, 0.045), letters, ink) * (0.35 + 0.65 * abs(edge)), 1.0);
}

// A traffic light's lamp: red, amber or green, as Vehicles.signals puts them in the random slot.
fn signalColour(rnd: f32) -> vec3f {
  if (rnd < 0.3) {
    return vec3f(1.0, 0.12, 0.08);
  }
  if (rnd < 0.7) {
    return vec3f(1.0, 0.62, 0.05);
  }
  return vec3f(0.10, 1.0, 0.45);
}

// The paper in shade s of four.
fn paperAt(s: u32) -> vec3f {
  var shades = array<f32, 4>(0.968, 0.982, 0.994, 1.006);
  return clamp(frame.paper.rgb * shades[min(s, 3u)], vec3f(0.0), vec3f(1.0));
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

  // Flat, the plate itself is the code's paper, the same size as the city's platform, so nothing
  // grows as the city folds; the screen around it takes a deeper colour of the same sky.
  let surround = mix(frame.skyTop.rgb, backdrop(), 0.35);
  return vec4f(mix(sky, surround, wash()), 1.0);
}

// ---------------------------------------------------------------------------------------------
// Rain: streaks that fall through the city, one instance each, positions from the drop's index.
// ---------------------------------------------------------------------------------------------

struct RainOut {
  @builtin(position) clip: vec4f,
  @location(0) along: f32,
  @location(1) gust: f32,   // a wind streak's strength there; 0 for a raindrop
}

const GUST_BASE = ${GUST_BASE}u;
const GUST_SEGMENTS = ${GUST_SEGMENTS}u;

// Where a wind streak is a share s of the way along itself: a line along the wind that ripples
// as it goes and curls up a little at its head.
fn gustPoint(g: u32, s: f32, head: f32) -> vec3f {
  let span = 3.5 + 3.5 * unit(g * 7u + 5u);
  let x = head - span * (1.0 - s);
  let z = (unit(g * 7u + 3u) * 2.0 - 1.0) * frame.scene.x * 1.05;
  let y = 0.5 + unit(g * 7u + 4u) * max(frame.scene.y * 0.55, 2.0);
  let t = frame.forward.w;
  let ripple = 0.28 * sin(s * 7.0 - t * 4.0 + f32(g)) * s;
  let curl = 0.6 * pow(s, 6.0);
  return vec3f(x, y + ripple + curl, z + 0.35 * sin(s * 3.0 + t * 1.3 + f32(g) * 1.7));
}

// Haze's wind (2026-11-07, at the user's request: "some visual wind"): pale streaks blowing
// through the streets downwind (+x), each a thin ribbon that grows in, crosses the city and thins
// away, then comes again elsewhere.
fn gustVertex(vi: u32, g: u32) -> RainOut {
  var corners = array<vec2f, 6>(
    vec2f(-1.0, 0.0), vec2f(1.0, 0.0), vec2f(1.0, 1.0),
    vec2f(-1.0, 0.0), vec2f(1.0, 1.0), vec2f(-1.0, 1.0),
  );
  let c = corners[vi % 6u];
  let s = (f32(vi / 6u) + c.y) / f32(GUST_SEGMENTS);
  let period = 4.5 + 3.0 * unit(g * 7u + 1u);
  let life = fract(frame.forward.w / period + unit(g * 7u + 2u));
  let reach = frame.scene.x * 1.5;
  let head = mix(-reach, reach + 7.0, life);
  let p = gustPoint(g, s, head);
  let ahead = gustPoint(g, min(s + 0.02, 1.0), head) - gustPoint(g, max(s - 0.02, 0.0), head);
  let side = normalize(cross(normalize(ahead), frame.eye.xyz - p));
  let width = 0.045 * sin(3.14159 * s);
  var out: RainOut;
  out.clip = frame.viewProj * vec4f(p + side * (width * c.x), 1.0);
  out.along = 0.0;
  out.gust = sin(3.14159 * life) * smoothstep(0.0, 0.35, s) * (1.0 - smoothstep(0.85, 1.0, s));
  return out;
}

@vertex
fn rainVertex(@builtin(vertex_index) vi: u32, @builtin(instance_index) drop: u32) -> RainOut {
  if (drop >= GUST_BASE) {
    return gustVertex(vi, drop - GUST_BASE);
  }
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
  out.gust = 0.0;
  return out;
}

@fragment
fn rainFragment(r: RainOut) -> @location(0) vec4f {
  if (r.gust > 0.0) {
    // A wind streak: paler than the haze around it, never quite white.
    let pale = mix(frame.skyHorizon.rgb, vec3f(1.0, 0.98, 0.94), 0.55);
    return vec4f(pale, 0.42 * r.gust * frame.vehicle.w * (1.0 - frame.eye.w));
  }
  let amount = frame.skyTop.w * (1.0 - frame.eye.w);
  // Against a bright sky rain reads as darker streaks, against a dark one as lighter ones.
  let tint = mix(frame.skyHorizon.rgb * 0.72, vec3f(0.86, 0.90, 1.0), 0.25 + 0.55 * frame.sunDir.w) + vec3f(0.4) * frame.window.w;
  // The head of the streak is strongest; it fades out up the tail.
  return vec4f(tint, 0.34 * amount * (1.0 - r.along));
}
"""

  /**
   * The glow, after the city is drawn into a float target: bloom from whatever shines brighter
   * than white (a bright pass, then a chain of halving blurs and growing ones added back, after
   * Jimenez's "Next generation post processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014,
   * written from the talk), and the composite onto the screen. In the flat code the composite
   * passes the city's colours through untouched, so the code is exactly as the city drew it.
   */
  const val POST =
    """
struct Post {
  params: vec4f,   // x: bright threshold, y: its soft knee, z: bloom strength, w: flatness
}

@group(0) @binding(0) var<uniform> post: Post;
@group(0) @binding(1) var source: texture_2d<f32>;
@group(0) @binding(2) var linearSampler: sampler;
@group(0) @binding(3) var glow: texture_2d<f32>;

struct Screen {
  @builtin(position) clip: vec4f,
  @location(0) uv: vec2f,
}

@vertex
fn screenVertex(@builtin(vertex_index) i: u32) -> Screen {
  let p = vec2f(f32((i << 1u) & 2u), f32(i & 2u)) * 2.0 - 1.0;
  var out: Screen;
  out.clip = vec4f(p, 0.0, 1.0);
  out.uv = vec2f(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  return out;
}

fn tap(uv: vec2f, offset: vec2f, texel: vec2f) -> vec3f {
  return textureSampleLevel(source, linearSampler, uv + offset * texel, 0.0).rgb;
}

// Halves the source: thirteen taps in five overlapping boxes, the middle one weighted most, so
// the blur is wide and steady without shimmering as things move.
fn down(uv: vec2f) -> vec3f {
  let t = 1.0 / vec2f(textureDimensions(source));
  let a = tap(uv, vec2f(-2.0, -2.0), t);
  let b = tap(uv, vec2f(0.0, -2.0), t);
  let c = tap(uv, vec2f(2.0, -2.0), t);
  let d = tap(uv, vec2f(-1.0, -1.0), t);
  let e = tap(uv, vec2f(1.0, -1.0), t);
  let f = tap(uv, vec2f(-2.0, 0.0), t);
  let g = tap(uv, vec2f(0.0, 0.0), t);
  let h = tap(uv, vec2f(2.0, 0.0), t);
  let i = tap(uv, vec2f(-1.0, 1.0), t);
  let j = tap(uv, vec2f(1.0, 1.0), t);
  let k = tap(uv, vec2f(-2.0, 2.0), t);
  let l = tap(uv, vec2f(0.0, 2.0), t);
  let m = tap(uv, vec2f(2.0, 2.0), t);
  return (d + e + i + j) * 0.125 + (a + b + f + g + b + c + g + h + f + g + k + l + g + h + l + m) * 0.03125;
}

// Only what is brighter than white, with a soft knee so it does not switch on at a hard edge.
fn bright(c: vec3f) -> vec3f {
  let peak = max(c.r, max(c.g, c.b));
  let knee = post.params.y;
  var soft = clamp(peak - post.params.x + knee, 0.0, 2.0 * knee);
  soft = soft * soft / (4.0 * knee + 1e-4);
  return c * (max(soft, peak - post.params.x) / max(peak, 1e-4));
}

@fragment
fn bloomPrefilter(s: Screen) -> @location(0) vec4f {
  return vec4f(bright(down(s.uv)), 1.0);
}

@fragment
fn bloomDown(s: Screen) -> @location(0) vec4f {
  return vec4f(down(s.uv), 1.0);
}

// Doubles the smaller level back up with a 3 x 3 tent, added onto the level above.
@fragment
fn bloomUp(s: Screen) -> @location(0) vec4f {
  let t = 1.0 / vec2f(textureDimensions(source));
  var sum = tap(s.uv, vec2f(0.0, 0.0), t) * 4.0;
  sum = sum + (tap(s.uv, vec2f(-1.0, 0.0), t) + tap(s.uv, vec2f(1.0, 0.0), t) + tap(s.uv, vec2f(0.0, -1.0), t) + tap(s.uv, vec2f(0.0, 1.0), t)) * 2.0;
  sum = sum + tap(s.uv, vec2f(-1.0, -1.0), t) + tap(s.uv, vec2f(1.0, -1.0), t) + tap(s.uv, vec2f(-1.0, 1.0), t) + tap(s.uv, vec2f(1.0, 1.0), t);
  return vec4f(sum / 16.0, 1.0);
}

// Light far brighter than white burns toward white at its core, like a real lamp seen by a
// camera, then everything over 0.9 rolls smoothly into the screen's white.
fn shoulder(c: vec3f) -> vec3f {
  let peak = max(c.r, max(c.g, c.b));
  let hot = clamp((peak - 1.6) * 0.4, 0.0, 1.0);
  let burnt = mix(c, vec3f(peak), hot * 0.5);
  let over = max(burnt - vec3f(0.9), vec3f(0.0));
  return min(burnt, vec3f(0.9)) + 0.1 * (vec3f(1.0) - exp(-over * 10.0));
}

@fragment
fn composite(s: Screen) -> @location(0) vec4f {
  let base = textureLoad(source, vec2i(s.clip.xy), 0).rgb;
  let lit = base + textureSampleLevel(glow, linearSampler, s.uv, 0.0).rgb * post.params.z;
  return vec4f(mix(shoulder(lit), clamp(base, vec3f(0.0), vec3f(1.0)), post.params.w), 1.0);
}
"""
}
