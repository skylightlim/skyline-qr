#!/usr/bin/env bash
# Makes the Kuala Lumpur map poster that shows while the city loads, with maptoposter
# (https://github.com/originalankur/maptoposter, MIT) from OpenStreetMap data (ODbL; the poster
# carries "© OpenStreetMap contributors"). Needs Python 3.11+ and network access to OpenStreetMap's
# Overpass API. The Poster workflow (.github/workflows/poster.yml) runs it on GitHub.
#
#   tools/poster/make_poster.sh
#
# Writes the website's copy and the app's copy; commit both.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${POSTER_WORK:-$(mktemp -d)}"
# maptoposter at a fixed commit, so the same command makes the same poster.
MAPTOPOSTER_COMMIT=6d03069f34558494ccc7fc2a7de87df7a7543fd5

git clone --quiet https://github.com/originalankur/maptoposter.git "$WORK/maptoposter"
git -C "$WORK/maptoposter" checkout --quiet "$MAPTOPOSTER_COMMIT"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install --quiet -r "$WORK/maptoposter/requirements.txt"

# The city centre between Merdeka Square and KLCC, 7 km across the short side, Monochrome Blue
# (the app's blue sky and blue accent). 6 x 10.67 inches at 300 dpi is a 9:16 phone screen.
(
  cd "$WORK/maptoposter"
  "$WORK/venv/bin/python" create_map_poster.py \
    --city "Kuala Lumpur" --country "Malaysia" \
    --latitude 3.1505 --longitude 101.7000 \
    --distance 7000 --theme monochrome_blue \
    --width 6 --height 10.6667 --format png
)
PNG="$(ls "$WORK"/maptoposter/posters/kuala_lumpur_monochrome_blue_*.png | head -n 1)"
test -s "$PNG"

"$WORK/venv/bin/python" - "$PNG" "$ROOT" <<'PY'
import sys
from PIL import Image

png, root = sys.argv[1], sys.argv[2]
img = Image.open(png).convert("RGB")
# 900 wide at quality 70: sharp enough on a phone held upright, and about 250 KB as WebP.
w = 900
img = img.resize((w, round(img.height * w / img.width)), Image.LANCZOS)
for out in (
    f"{root}/skylineqr-web/src/wasmJsMain/resources/kl-poster.webp",
    f"{root}/app/src/main/res/drawable-nodpi/kl_poster.webp",
):
    import os
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "WEBP", quality=70, method=6)
    print(out, img.size, os.path.getsize(out), "bytes")
PY
