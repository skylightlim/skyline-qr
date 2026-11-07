"""SkylineQR's launcher icon (2026-11-07 revision, after a slanted skyline icon the user showed): KL's
landmarks as 3D blocks seen at an angle, each with two faces showing, the left one slit with
windows and the right one solid, white gaps where a nearer block stands in front of a farther one,
on a slanted ground. Built from boxes in a little world, projected, then fitted into the adaptive
icon's 33-unit safe circle round (54, 54).

    python3 tools/launcher_icon.py OUT_DIR

writes ic_launcher_foreground.xml and ic_launcher_monochrome.xml (copy both to
app/src/main/res/drawable/) and preview.svg (render it with rsvg-convert to look at it)."""
import math, sys

INK = "#12151C"
PAPER = "#FFFFFF"
COS, SIN = math.cos(math.radians(30)), math.sin(math.radians(30))

def project(x, y, z):
    """World (x right-back, y up, z left-back) to the page, before fitting."""
    return ((x - z) * COS, (x + z) * SIN * 0.5 - y)

class Box:
    def __init__(self, x0, z0, w, d, y0, h, slits=0, name=""):
        self.x0, self.z0, self.x1, self.z1 = x0, z0, x0 + w, z0 + d
        self.y0, self.y1, self.slits, self.name = y0, y0 + h, slits, name

    def depth(self):
        return self.x1 + self.z1 + self.y0 * 0.01

    def faces(self):
        """The two faces that show: left (z = z1) and right (x = x1), as page polygons."""
        x0, z0, x1, z1, y0, y1 = self.x0, self.z0, self.x1, self.z1, self.y0, self.y1
        left = [project(x0, y0, z1), project(x1, y0, z1), project(x1, y1, z1), project(x0, y1, z1)]
        right = [project(x1, y0, z1), project(x1, y0, z0), project(x1, y1, z0), project(x1, y1, z1)]
        return left, right

    def slit_lines(self):
        """Window slits down the left face, as page polygons."""
        out = []
        if not self.slits:
            return out
        w = self.x1 - self.x0
        gap = (self.y1 - self.y0) * 0.08
        for k in range(self.slits):
            cx = self.x0 + w * (k + 1) / (self.slits + 1)
            half = w * 0.18 / (self.slits + 1)
            out.append([project(cx - half, self.y0 + gap, self.z1), project(cx + half, self.y0 + gap, self.z1),
                        project(cx + half, self.y1 - gap, self.z1), project(cx - half, self.y1 - gap, self.z1)])
        return out

world = []          # boxes, drawn far to near
spires = []         # (x, z, y0, y1, width): thin lines on top
bridges = []        # page-space quads, drawn after the boxes they join

# Menara KL, on the left: a shaft, its pod, a cap and a mast.
world += [Box(0.0, 8.6, 0.8, 0.8, 0, 8.6, name="Menara KL shaft"), Box(-0.55, 8.05, 1.9, 1.9, 8.6, 1.4, name="Menara KL pod"),
          Box(-0.15, 8.45, 1.1, 1.1, 10.0, 0.6, name="Menara KL cap")]
spires.append((0.4, 9.0, 10.6, 13.0, 0.26))

# The Petronas Twin Towers: bodies, three setbacks each, a spire each, the skybridge between.
STEPS = [(0.35, 1.5), (0.7, 1.2), (1.0, 0.9)]
def tower(x, z, name):
    out = [Box(x, z, 2.7, 2.7, 0, 12.0, slits=3, name=name)]
    y = 12.0
    for inset, h in STEPS:
        out.append(Box(x + inset, z + inset, 2.7 - 2 * inset, 2.7 - 2 * inset, y, h, name=name + " setback"))
        y += h
    spires.append((x + 1.35, z + 1.35, y, y + 3.4, 0.22))
    return out
world += tower(3.0, 6.2, "Petronas 1")
world += tower(6.4, 2.8, "Petronas 2")

# Merdeka 118, tallest: a tapering stack ending in a long spire.
for k, (inset, y, h) in enumerate([(0, 0, 9.5), (0.45, 9.5, 5), (0.9, 14.5, 3.5), (1.3, 18, 2)]):
    world.append(Box(9.8 + inset, 3.6 + inset, 3.2 - 2 * inset, 3.2 - 2 * inset, y, h, slits=(3 if k == 0 else 0), name="Merdeka 118"))
spires.append((11.4, 5.2, 20, 24.5, 0.28))

# Low blocks in front, an everyday street before the landmarks.
world += [Box(2.2, 10.0, 3.6, 2.2, 0, 3.6, slits=2, name="block"), Box(7.4, 8.2, 3.2, 2.4, 0, 4.8, slits=2, name="block"),
          Box(13.2, 2.8, 2.2, 3.0, 0, 6.6, slits=2, name="block")]

# The ground: a slanted slab under everything.
ground = Box(-1.2, 1.8, 17.2, 11.4, -0.9, 0.9, name="ground")

def bridge_quad():
    # Between the twins at mid-height: a short bar across the gap, in page space.
    a = project(5.7, 7.0, 8.9)
    b = project(6.4, 7.0, 5.5)
    return [(a[0], a[1] - 0.35), (b[0], b[1] - 0.35), (b[0], b[1] + 0.35), (a[0], a[1] + 0.35)]

# Fit every point into the safe circle round (54, 54).
pts = []
for bx in world + [ground]:
    for f in bx.faces():
        pts += f
for x, z, y0, y1, w in spires:
    pts += [project(x, y1, z), project(x, y0, z)]
minx, maxx = min(p[0] for p in pts), max(p[0] for p in pts)
miny, maxy = min(p[1] for p in pts), max(p[1] for p in pts)
cx, cy = (minx + maxx) / 2, (miny + maxy) / 2
def fit(scale):
    return lambda p: (54 + (p[0] - cx) * scale, 54 + (p[1] - cy) * scale)
scale = 1.0
for _ in range(60):
    f = fit(scale)
    worst = max(math.hypot(f(p)[0] - 54, f(p)[1] - 54) for p in pts)
    scale *= 33.5 / worst
f = fit(scale)
worst = max(math.hypot(f(p)[0] - 54, f(p)[1] - 54) for p in pts)
print(f"farthest point from the centre: {worst:.2f} (safe circle 33)", file=sys.stderr)

def d(poly):
    return "M" + " L".join(f"{f(p)[0]:.2f},{f(p)[1]:.2f}" for p in poly) + " Z"

GAP = 1.6  # the white seam round a nearer block, in icon units (half of it shows)

layers = []  # (path data, fill, stroke): drawn in order
ordered = sorted(world, key=Box.depth)
layers.append((" ".join(d(x) for x in ground.faces()), INK, None))
for bx in ordered:
    left, right = bx.faces()
    outline = d(left) + " " + d(right)
    # The seam first, then the block over it: the seam only cuts what stands behind.
    layers.append((outline, PAPER, PAPER))
    layers.append((outline, INK, None))
    if bx.slits:
        layers.append((" ".join(d(s) for s in bx.slit_lines()), PAPER, None))
    # The corner where the two faces meet: a hairline of paper, so the faces read apart.
    a, b = project(bx.x1, bx.y0, bx.z1), project(bx.x1, bx.y1, bx.z1)
    layers.append((f"M{f(a)[0]:.2f},{f(a)[1]:.2f} L{f(b)[0]:.2f},{f(b)[1]:.2f}", None, PAPER + "@0.5"))
    if bx.name == "Petronas 2":
        layers.append((d(bridge_quad()), PAPER, PAPER))
        layers.append((d(bridge_quad()), INK, None))
for x, z, y0, y1, w in spires:
    a, b = project(x, y0, z), project(x, y1, z)
    layers.append((f"M{f(a)[0]:.2f},{f(a)[1]:.2f} L{f(b)[0]:.2f},{f(b)[1]:.2f}", None, INK + f"@{w * scale:.2f}"))

def vector(mono):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- KL landmarks as slanted blocks: Menara KL, the Petronas Twin Towers, Merdeka 118 and',
           '     three lower blocks on a slanted ground. Drawn for SkylineQR (2026-11-07) by',
           '     tools/launcher_icon.py; edit that, not this. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for path, fill, stroke in layers:
        attrs = [f'android:pathData="{path}"']
        if fill and not (mono and fill == PAPER):
            attrs.append(f'android:fillColor="{fill}"')
        elif fill == PAPER and mono:
            continue
        if stroke and not mono:
            colour, _, width = stroke.partition("@")
            attrs += [f'android:strokeColor="{colour}"', f'android:strokeWidth="{float(width) if width else GAP}"', 'android:strokeLineJoin="round"', 'android:strokeLineCap="round"']
        elif stroke and mono and stroke.startswith(INK):
            colour, _, width = stroke.partition("@")
            attrs += [f'android:strokeColor="{colour}"', f'android:strokeWidth="{float(width)}"', 'android:strokeLineCap="round"']
        if len(attrs) == 1:
            continue
        out.append("    <path " + " ".join(attrs) + " />")
    out.append("</vector>")
    return "\n".join(out) + "\n"

def svg():
    parts = []
    for path, fill, stroke in layers:
        a = [f'd="{path}"', f'fill="{fill or "none"}"']
        if stroke:
            colour, _, width = stroke.partition("@")
            a += [f'stroke="{colour}"', f'stroke-width="{float(width) if width else GAP}"', 'stroke-linejoin="round"', 'stroke-linecap="round"']
        parts.append("<path " + " ".join(a) + "/>")
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
            '<defs><clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
            f'<g clip-path="url(#m)"><rect width="108" height="108" fill="{PAPER}"/>' + "".join(parts) + "</g></svg>")

out = sys.argv[1]
open(f"{out}/ic_launcher_foreground.xml", "w").write(vector(False))
open(f"{out}/ic_launcher_monochrome.xml", "w").write(vector(True))
open(f"{out}/preview.svg", "w").write(svg())
