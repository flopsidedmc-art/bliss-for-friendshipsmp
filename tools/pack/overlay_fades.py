"""Builds the finale overlay's fade frames: camera overlays have no alpha setting, so a fade steps through fainter copies.

    python3 tools/pack/overlay_fades.py resource-pack/overlay [steps]

For each style (gold, red) it writes textures/overlay/<style>_fade_1..N.png from <style>_0.png, at 1/(N+1) .. N/(N+1)
of its opacity (N = 4: 20, 40, 60, 80%). The plugin's finale.overlay.fade-steps must match N.
"""
import os
import sys

from PIL import Image

overlay = sys.argv[1]
steps = int(sys.argv[2]) if len(sys.argv) > 2 else 4
folder = os.path.join(overlay, "assets", "blissgems", "textures", "overlay")
for style in ("gold", "red"):
    base = Image.open(os.path.join(folder, style + "_0.png")).convert("RGBA")
    r, g, b, a = base.split()
    for k in range(1, steps + 1):
        f = k / (steps + 1)
        faded = Image.merge("RGBA", (r, g, b, a.point(lambda v, f=f: int(round(v * f)))))
        out = os.path.join(folder, "%s_fade_%d.png" % (style, k))
        faded.save(out, optimize=True)
        print(out, "%.0f%%" % (f * 100))
