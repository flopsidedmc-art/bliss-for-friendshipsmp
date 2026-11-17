"""Builds the Rift and Time Dilate restyle assets into the Relay overlay.

    python3 tools/pack/rift_frames.py <frames_dir>             # the six approved rift frames + previews
    python3 tools/pack/relay_pack_assets.py <overlay_dir> <frames_dir>

Re-runnable: the thick clock models are always rebuilt from the thin originals in tools/pack/source.
"""
import json, math, os, shutil, sys
from collections import Counter
from PIL import Image

overlay, frames_dir = sys.argv[1], sys.argv[2]
HERE = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(overlay, "assets")
RIM, DEEP, PALE = (0xB0, 0x70, 0x3A), (0x8C, 0x55, 0x30), (0xF0, 0xC4, 0x8C)

def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

# ---- Rift: six frames, a vertical plane model per frame, a light-spill plane
tex = os.path.join(A, "blissgems/textures/item")
for i in range(6):
    shutil.copyfile(os.path.join(frames_dir, f"rift_{i}.png"), os.path.join(tex, f"rift_{i}.png"))
    write_json(os.path.join(A, f"blissgems/models/item/rift_{i}.json"), {
        "textures": {"0": f"blissgems:item/rift_{i}", "particle": f"blissgems:item/rift_{i}"},
        "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "shade": False, "faces": {
            "north": {"uv": [16, 0, 0, 16], "texture": "#0"},
            "south": {"uv": [0, 0, 16, 16], "texture": "#0"}}}]})
    write_json(os.path.join(A, f"blissgems/items/rift_{i}.json"),
               {"model": {"type": "minecraft:model", "model": f"blissgems:item/rift_{i}"}})

light = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
for y in range(32):
    for x in range(32):
        r = math.hypot((x - 15.5) / 15.5, (y - 15.5) / 15.5)
        if r < 1:
            light.putpixel((x, y), RIM + (int(115 * (1 - r) ** 1.6),))
light.save(os.path.join(tex, "rift_light.png"))
write_json(os.path.join(A, "blissgems/models/item/rift_light.json"), {
    "textures": {"0": "blissgems:item/rift_light", "particle": "blissgems:item/rift_light"},
    "elements": [{"from": [0, 8, 0], "to": [16, 8, 16], "shade": False, "faces": {
        "up": {"uv": [0, 0, 16, 16], "texture": "#0"},
        "down": {"uv": [0, 0, 16, 16], "texture": "#0"}}}]})
write_json(os.path.join(A, "blissgems/items/rift_light.json"),
           {"model": {"type": "minecraft:model", "model": "blissgems:item/rift_light"}})

# ---- HUD icon U+E043: the Dilation icon's frame with a small jagged tear inside (33x20, like the others)
icons = os.path.join(A, "blissgems/textures/icons")
icon = Image.open(os.path.join(icons, "relay_dilation.png")).convert("RGBA")
P = {".": (0, 0, 0), "d": (92, 61, 31), "m": (122, 85, 44), "l": (184, 147, 88), "L": (211, 179, 122), "P": (234, 213, 164)}
TEAR = ["................", ".......lL.......", "......lPPl......", "......LdPl......",
        ".....lddPdl.....", ".....LmdPdL.....", "....lddPddml....", "....LdmPPddL....",
        "....LddPPmdL....", "....lmddPddl....", ".....LdPddL.....", ".....ldPmdl.....",
        "......LPdl......", "......lPPl......", ".......Ll.......", "................"]
for y, row in enumerate(TEAR):
    assert len(row) == 16
    for x, ch in enumerate(row):
        icon.putpixel((7 + x, 2 + y), P[ch] + (255,))
icon.save(os.path.join(icons, "relay_rift.png"))
font_path = os.path.join(A, "minecraft/font/default.json")
font = json.load(open(font_path, encoding="utf-8"))
if not any(p.get("chars") == [""] for p in font["providers"]):
    at = next(i for i, p in enumerate(font["providers"]) if p.get("chars") == [""]) + 1
    font["providers"].insert(at, {"type": "bitmap", "file": "blissgems:icons/relay_rift.png", "ascent": 17, "height": 20, "chars": [""]})
    with open(font_path, "w", encoding="utf-8") as f:
        json.dump(font, f, indent=2, ensure_ascii=True)
        f.write("\n")

# ---- Time Dilate: glowing-brown palette
for name, col in (("temporal_brown", RIM), ("temporal_bronze", DEEP), ("temporal_gold", PALE)):
    Image.new("RGBA", (16, 16), col + (255,)).save(os.path.join(tex, name + ".png"))

# ---- Time Dilate: slightly thicker lines (every line grows 1/8 unit on each side, 0.5 -> 0.75)
G = 8  # grid cells per model unit
def thicken(src, dst):
    model = json.load(open(src, encoding="utf-8"))
    cells = {}
    for e in model["elements"]:
        t = (e["faces"].get("up") or next(iter(e["faces"].values())))["texture"]
        x0, x1 = sorted((round(e["from"][0] * G), round(e["to"][0] * G)))
        z0, z1 = sorted((round(e["from"][2] * G), round(e["to"][2] * G)))
        for x in range(x0, x1):
            for z in range(z0, z1):
                cells[(x, z)] = t
    grown = dict(cells)
    order = {"#brown": 0, "#bronze": 1, "#gold": 2}
    for (x, z) in list(cells):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                c = (x + dx, z + dz)
                if c in cells or c in grown and grown[c] != "?":
                    continue
                grown[c] = "?"
    for c, t in list(grown.items()):
        if t != "?":
            continue
        votes = Counter(cells[(c[0] + dx, c[1] + dz)] for dx in (-1, 0, 1) for dz in (-1, 0, 1) if (c[0] + dx, c[1] + dz) in cells)
        grown[c] = sorted(votes.items(), key=lambda kv: (-kv[1], order.get(kv[0], 9)))[0][0]
    elements = []
    for colour in sorted(set(grown.values()), key=lambda t: order.get(t, 9)):
        left = {c for c, t in grown.items() if t == colour}
        while left:  # greedy rectangles: as wide as possible, then as deep as possible
            x, z = min(left)
            w = 1
            while (x + w, z) in left:
                w += 1
            d = 1
            while all((x + i, z + d) in left for i in range(w)):
                d += 1
            for i in range(w):
                for j in range(d):
                    left.discard((x + i, z + j))
            elements.append({"from": [x / G, 8, z / G], "to": [(x + w) / G, 8.025, (z + d) / G], "faces": {
                "up": {"texture": colour, "uv": [0, 0, 16, 16]}, "down": {"texture": colour, "uv": [0, 0, 16, 16]}}, "shade": False})
    model["elements"] = elements
    write_json(dst, model)
    return len(cells), len(grown), len(elements)

for n in ("temporal_clock", "temporal_hand"):
    print(n, thicken(os.path.join(HERE, "source", n + ".json"), os.path.join(A, f"blissgems/models/item/{n}.json")))
print("done")
