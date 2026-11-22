"""
Draws the scenes FrameDumpTest wrote with the app's own WGSL on a desktop GPU, the same passes as
CityRenderer.encode (sun shadow map, city into a 4x MSAA float target, bloom chain, composite),
for checking a visual change when no phone is at hand. Not a replacement for the phone: the
device's GPU and Tint compiler can differ.

  SKYLINEQR_FRAMES=/tmp/frames ./gradlew :skylineqr-compose:testDebugUnitTest --tests '*FrameDumpTest'
  python tools/preview.py /tmp/frames /tmp/frames/out      (needs: pip install wgpu numpy pillow)
"""
import sys
from pathlib import Path

import numpy as np
import wgpu
from PIL import Image

frames, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
width, height, count, moving = map(int, (frames / "meta.txt").read_text().split())

STRIDE = 48
SAMPLES = 4
HDR = wgpu.TextureFormat.rgba16float
BLOOM_LEVELS = 6
BLOOM_THRESHOLD, BLOOM_KNEE, BLOOM = 1.05, 0.2, 0.45
INDICES = np.array([0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 8, 9, 10, 8, 10, 11, 12, 13, 14, 12, 14, 15,
                    16, 17, 18, 16, 18, 19, 20, 21, 22, 20, 22, 23], dtype=np.uint16)

device = wgpu.gpu.request_adapter_sync(power_preference="high-performance").request_device_sync()
U = wgpu.BufferUsage
T = wgpu.TextureUsage


def buffer(data: bytes, usage):
    data = data + b"\0" * (-len(data) % 4)
    b = device.create_buffer(size=max(16, len(data)), usage=usage | U.COPY_DST)
    if data:
        device.queue.write_buffer(b, 0, data)
    return b


skyline = device.create_shader_module(code=(frames / "skyline.wgsl").read_text())
glow = device.create_shader_module(code=(frames / "glow.wgsl").read_text())
frame_size = (frames / "frame-night.bin").stat().st_size
frame_buf = device.create_buffer(size=frame_size, usage=U.UNIFORM | U.COPY_DST)
post_buf = device.create_buffer(size=16, usage=U.UNIFORM | U.COPY_DST)
cube = buffer((frames / "cube.bin").read_bytes(), U.VERTEX)
index = buffer(INDICES.tobytes(), U.INDEX)
instances = buffer((frames / "instances.bin").read_bytes(), U.VERTEX)
traffic = buffer((frames / "traffic.bin").read_bytes(), U.VERTEX)

shadow = device.create_texture(size=(2048, 2048, 1), format=wgpu.TextureFormat.depth32float,
                               usage=T.RENDER_ATTACHMENT | T.TEXTURE_BINDING)
shadow_view = shadow.create_view()
compare = device.create_sampler(mag_filter="linear", min_filter="linear", compare="less-equal")
linear = device.create_sampler(mag_filter="linear", min_filter="linear",
                               address_mode_u="clamp-to-edge", address_mode_v="clamp-to-edge")

VS_FS = wgpu.ShaderStage.VERTEX | wgpu.ShaderStage.FRAGMENT
FS = wgpu.ShaderStage.FRAGMENT
scene_layout = device.create_bind_group_layout(entries=[
    {"binding": 0, "visibility": VS_FS, "buffer": {"type": "uniform"}},
    {"binding": 1, "visibility": FS, "texture": {"sample_type": "depth", "view_dimension": "2d"}},
    {"binding": 2, "visibility": FS, "sampler": {"type": "comparison"}},
    {"binding": 3, "visibility": FS, "texture": {"sample_type": "float", "view_dimension": "2d"}},
    {"binding": 4, "visibility": FS, "sampler": {"type": "filtering"}},
])
ATLAS_W, ATLAS_H = 2048, 128 * 8
atlas = device.create_texture(size=(ATLAS_W, ATLAS_H, 1), format=wgpu.TextureFormat.r8unorm,
                              usage=T.TEXTURE_BINDING | T.COPY_DST)
device.queue.write_texture({"texture": atlas}, (frames / "atlas.bin").read_bytes(),
                           {"bytes_per_row": ATLAS_W, "rows_per_image": ATLAS_H}, (ATLAS_W, ATLAS_H, 1))
caster_layout = device.create_bind_group_layout(entries=[{"binding": 0, "visibility": VS_FS, "buffer": {"type": "uniform"}}])
post_layout = device.create_bind_group_layout(entries=[
    {"binding": 0, "visibility": FS, "buffer": {"type": "uniform"}},
    {"binding": 1, "visibility": FS, "texture": {"sample_type": "float", "view_dimension": "2d"}},
    {"binding": 2, "visibility": FS, "sampler": {"type": "filtering"}},
    {"binding": 3, "visibility": FS, "texture": {"sample_type": "float", "view_dimension": "2d"}},
])
scene_group = device.create_bind_group(layout=scene_layout, entries=[
    {"binding": 0, "resource": {"buffer": frame_buf}},
    {"binding": 1, "resource": shadow_view},
    {"binding": 2, "resource": compare},
    {"binding": 3, "resource": atlas.create_view()},
    {"binding": 4, "resource": linear},
])
caster_group = device.create_bind_group(layout=caster_layout, entries=[{"binding": 0, "resource": {"buffer": frame_buf}}])

block_buffers = [
    {"array_stride": 24, "step_mode": "vertex", "attributes": [
        {"format": "float32x3", "offset": 0, "shader_location": 0},
        {"format": "float32x3", "offset": 12, "shader_location": 1}]},
    {"array_stride": STRIDE, "step_mode": "instance", "attributes": [
        {"format": "float32x3", "offset": 0, "shader_location": 2},
        {"format": "float32x3", "offset": 12, "shader_location": 3},
        {"format": "float32x4", "offset": 24, "shader_location": 4},
        {"format": "float32x2", "offset": 40, "shader_location": 5}]},
]
scene_pl = device.create_pipeline_layout(bind_group_layouts=[scene_layout])
caster_pl = device.create_pipeline_layout(bind_group_layouts=[caster_layout])
post_pl = device.create_pipeline_layout(bind_group_layouts=[post_layout])


def depth(write, compare_fn):
    return {"format": wgpu.TextureFormat.depth24plus, "depth_write_enabled": write, "depth_compare": compare_fn}


blocks = device.create_render_pipeline(
    layout=scene_pl, vertex={"module": skyline, "entry_point": "blockVertex", "buffers": block_buffers},
    primitive={"topology": "triangle-list"}, depth_stencil=depth(True, "less-equal"), multisample={"count": SAMPLES},
    fragment={"module": skyline, "entry_point": "blockFragment", "targets": [{"format": HDR}]})
sky = device.create_render_pipeline(
    layout=scene_pl, vertex={"module": skyline, "entry_point": "skyVertex"}, primitive={"topology": "triangle-list"},
    depth_stencil=depth(False, "less-equal"), multisample={"count": SAMPLES},
    fragment={"module": skyline, "entry_point": "skyFragment", "targets": [{"format": HDR}]})
alpha = {"color": {"operation": "add", "src_factor": "src-alpha", "dst_factor": "one-minus-src-alpha"},
         "alpha": {"operation": "add", "src_factor": "one", "dst_factor": "one-minus-src-alpha"}}
rain = device.create_render_pipeline(
    layout=scene_pl, vertex={"module": skyline, "entry_point": "rainVertex"}, primitive={"topology": "triangle-list"},
    depth_stencil=depth(False, "less-equal"), multisample={"count": SAMPLES},
    fragment={"module": skyline, "entry_point": "rainFragment", "targets": [{"format": HDR, "blend": alpha}]})
GUST_BASE, GUST_VERTICES = 65536, 60  # Wgsl.GUST_BASE, Wgsl.GUST_VERTICES
clouds = device.create_render_pipeline(
    layout=scene_pl, vertex={"module": skyline, "entry_point": "cloudVertex", "buffers": block_buffers},
    primitive={"topology": "triangle-list"},
    depth_stencil=depth(False, "less-equal"), multisample={"count": SAMPLES},
    fragment={"module": skyline, "entry_point": "cloudFragment", "targets": [{"format": HDR, "blend": alpha}]})
caster = device.create_render_pipeline(
    layout=caster_pl, vertex={"module": skyline, "entry_point": "casterVertex", "buffers": block_buffers},
    primitive={"topology": "triangle-list"},
    depth_stencil={"format": wgpu.TextureFormat.depth32float, "depth_write_enabled": True, "depth_compare": "less-equal",
                   "depth_bias": 2, "depth_bias_slope_scale": 2.0},
    fragment=None)


def screen(entry, fmt, blend=None):
    target = {"format": fmt}
    if blend:
        target["blend"] = blend
    return device.create_render_pipeline(
        layout=post_pl, vertex={"module": glow, "entry_point": "screenVertex"}, primitive={"topology": "triangle-list"},
        fragment={"module": glow, "entry_point": entry, "targets": [target]})


add = {"color": {"operation": "add", "src_factor": "one", "dst_factor": "one"},
       "alpha": {"operation": "add", "src_factor": "one", "dst_factor": "one"}}
prefilter, down, up = screen("bloomPrefilter", HDR), screen("bloomDown", HDR), screen("bloomUp", HDR, add)
composite = screen("composite", wgpu.TextureFormat.rgba8unorm)


def texture(w, h, fmt, usage, samples=1):
    return device.create_texture(size=(w, h, 1), format=fmt, usage=usage, sample_count=samples)


color = texture(width, height, HDR, T.RENDER_ATTACHMENT, SAMPLES)
depth_tex = texture(width, height, wgpu.TextureFormat.depth24plus, T.RENDER_ATTACHMENT, SAMPLES)
hdr = texture(width, height, HDR, T.RENDER_ATTACHMENT | T.TEXTURE_BINDING)
bloom = [texture(max(1, width >> (i + 1)), max(1, height >> (i + 1)), HDR, T.RENDER_ATTACHMENT | T.TEXTURE_BINDING)
         for i in range(BLOOM_LEVELS)]
final = texture(width, height, wgpu.TextureFormat.rgba8unorm, T.RENDER_ATTACHMENT | T.COPY_SRC)
color_view, depth_view, hdr_view, final_view = color.create_view(), depth_tex.create_view(), hdr.create_view(), final.create_view()
bloom_views = [b.create_view() for b in bloom]


def post_group(source, second=None):
    return device.create_bind_group(layout=post_layout, entries=[
        {"binding": 0, "resource": {"buffer": post_buf}},
        {"binding": 1, "resource": source},
        {"binding": 2, "resource": linear},
        {"binding": 3, "resource": second or source},
    ])


down_groups = [post_group(hdr_view if i == 0 else bloom_views[i - 1]) for i in range(BLOOM_LEVELS)]
up_groups = [post_group(bloom_views[i + 1]) for i in range(BLOOM_LEVELS - 1)]
composite_group = post_group(hdr_view, bloom_views[0])


def draw_blocks(p, moving, traffic):
    p.set_vertex_buffer(0, cube)
    p.set_index_buffer(index, "uint16")
    p.set_vertex_buffer(1, instances)
    p.draw_indexed(36, count)
    if moving:
        p.set_vertex_buffer(1, traffic)
        p.draw_indexed(36, moving)


def screen_pass(enc, target, pipeline, group, load):
    p = enc.begin_render_pass(color_attachments=[{
        "view": target, "load_op": "load" if load else "clear", "store_op": "store", "clear_value": (0, 0, 0, 1)}])
    p.set_pipeline(pipeline)
    p.set_bind_group(0, group)
    p.draw(3)
    p.end()


for line in (frames / "scenes.txt").read_text().splitlines():
    # A scene with its own weather has its own traffic, and rain drops and wind streaks to draw.
    name, fold, r, g, b, *extra = line.split()
    fold = float(fold)
    scene_moving, drops, gusts, scene_traffic = moving, 0, 0, traffic
    if extra:
        scene_moving, drops, gusts = map(int, extra)
        scene_traffic = buffer((frames / f"traffic-{name}.bin").read_bytes(), U.VERTEX)
    device.queue.write_buffer(frame_buf, 0, (frames / f"frame-{name}.bin").read_bytes())
    strength = BLOOM * (1 - fold)
    device.queue.write_buffer(post_buf, 0, np.array([BLOOM_THRESHOLD, BLOOM_KNEE, strength, fold], np.float32).tobytes())
    enc = device.create_command_encoder()
    if fold < 0.999:
        p = enc.begin_render_pass(color_attachments=[], depth_stencil_attachment={
            "view": shadow_view, "depth_load_op": "clear", "depth_store_op": "store", "depth_clear_value": 1.0})
        p.set_pipeline(caster)
        p.set_bind_group(0, caster_group)
        draw_blocks(p, scene_moving, scene_traffic)
        p.end()
    p = enc.begin_render_pass(
        color_attachments=[{"view": color_view, "resolve_target": hdr_view, "load_op": "clear", "store_op": "discard",
                            "clear_value": (float(r), float(g), float(b), 1)}],
        depth_stencil_attachment={"view": depth_view, "depth_load_op": "clear", "depth_store_op": "discard",
                                  "depth_clear_value": 1.0})
    p.set_bind_group(0, scene_group)
    p.set_pipeline(blocks)
    draw_blocks(p, scene_moving, scene_traffic)
    # The sky after the city, as CityRenderer draws it: at the far plane, only where the city isn't.
    p.set_pipeline(sky)
    p.draw(3)
    if scene_moving and fold < 0.999:
        p.set_pipeline(clouds)
        p.draw_indexed(36, scene_moving)
    if drops:
        p.set_pipeline(rain)
        p.draw(6, drops)
    if gusts:
        p.set_pipeline(rain)
        p.draw(GUST_VERTICES, gusts, 0, GUST_BASE)
    p.end()
    if strength > 0:
        screen_pass(enc, bloom_views[0], prefilter, down_groups[0], False)
        for i in range(1, BLOOM_LEVELS):
            screen_pass(enc, bloom_views[i], down, down_groups[i], False)
        for i in range(BLOOM_LEVELS - 2, -1, -1):
            screen_pass(enc, bloom_views[i], up, up_groups[i], True)
    screen_pass(enc, final_view, composite, composite_group, False)
    device.queue.submit([enc.finish()])
    pixels = device.queue.read_texture({"texture": final}, {"bytes_per_row": width * 4}, (width, height, 1))
    Image.frombuffer("RGBA", (width, height), bytes(pixels)).convert("RGB").save(out / f"{name}.png")
    print("wrote", out / f"{name}.png")
