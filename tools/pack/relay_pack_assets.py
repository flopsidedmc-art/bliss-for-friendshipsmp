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
# U+E044 Temporal Divinity: an eye inside a triangle, same frame
EYE = ["................", ".......LL.......", "......L..L......", "......L..L......",
       ".....L....L.....", ".....L....L.....", "....L.llll.L....", "....LlmPPmlL....",
       "...LldPddPdlL...", "...L.lmPPml.L...", "..L...llll...L..", "..L..........L..",
       ".L............L.", ".L............L.", "LLLLLLLLLLLLLLLL", "................"]
eye = Image.open(os.path.join(icons, "relay_dilation.png")).convert("RGBA")
for y, row in enumerate(EYE):
    assert len(row) == 16
    for x, ch in enumerate(row):
        eye.putpixel((7 + x, 2 + y), P[ch] + (255,))
eye.save(os.path.join(icons, "relay_divinity.png"))

# Tuned In meter (U+E042 empty, U+E058-E05C 20%..full): a target reticle around a figure, matching the rings
# drawn round tagged players. Each fifth of charge lights one quarter of the ring (clockwise from the top
# right); full makes the whole icon bright. Only the 16x16 inside is redrawn, so each frame's border and
# side bar stay as they were (re-runnable).
RET_CX, RET_CY, RET_R = 14.5, 9.5, 6.4
# A small standing player: head, shoulders, body
RET_FIGURE = {(14, 6), (15, 6), (14, 7), (15, 7), (13, 8), (14, 8), (15, 8), (16, 8), (13, 9), (14, 9), (15, 9), (16, 9),
              (14, 10), (15, 10), (14, 11), (15, 11), (14, 12), (15, 12)}
def reticle(x, y, step):
    full, lit = step == 5, min(step, 4)
    dx, dy = x - RET_CX, y - RET_CY
    if (x, y) in RET_FIGURE:
        return "P" if full else ("L" if lit else "m")
    if (abs(dx) < 0.6 and 4.8 <= abs(dy) <= 7.6) or (abs(dy) < 0.6 and 4.8 <= abs(dx) <= 7.6):
        return "P" if full else "l"  # crosshair ticks at north, east, south and west
    if abs(math.hypot(dx, dy) - RET_R) <= 0.6:
        ang = (math.degrees(math.atan2(dx, -dy)) + 360) % 360
        if min(ang % 90, 90 - ang % 90) < 9:
            return "."  # gaps where the ticks cross the ring
        return ("P" if full else "L") if int(ang // 90) < lit else "d"
    return "."
for step, name in enumerate(["relay_tuned", "relay_tuned_c1", "relay_tuned_c2", "relay_tuned_c3", "relay_tuned_c4", "relay_tuned_c5"]):
    path = os.path.join(icons, name + ".png")
    meter = Image.open(path).convert("RGBA")
    for y in range(2, 18):
        for x in range(7, 23):
            meter.putpixel((x, y), P[reticle(x, y, step)] + (255,))
    meter.save(path)

font_path = os.path.join(A, "minecraft/font/default.json")
font = json.load(open(font_path, encoding="utf-8"))
if not any(p.get("chars") == ["\ue043"] for p in font["providers"]):
    at = next(i for i, p in enumerate(font["providers"]) if p.get("chars") == ["\ue042"]) + 1
    font["providers"].insert(at, {"type": "bitmap", "file": "blissgems:icons/relay_rift.png", "ascent": 17, "height": 20, "chars": ["\ue043"]})
if not any(p.get("chars") == ["\ue044"] for p in font["providers"]):
    at = next(i for i, p in enumerate(font["providers"]) if p.get("chars") == ["\ue043"]) + 1
    font["providers"].insert(at, {"type": "bitmap", "file": "blissgems:icons/relay_divinity.png", "ascent": 17, "height": 20, "chars": ["\ue044"]})
with open(font_path, "w", encoding="utf-8") as f:
    json.dump(font, f, indent=2, ensure_ascii=True)
    f.write("\n")

# ---- U+E045: an eye for Temporal Divinity's lore line. White and greys, so the lore colour tints it like
# the other symbols; 11x7 on an 8px cell with ascent 7, so it lines up with capital letters.
EYE_GLYPH = ["....WWW....", "..WW...WW..", ".W..GGG..W.", "W..GHDDG..W", ".W..GGG..W.", "..WW...WW..", "....WWW....", "..........."]
SHADE = {".": (0, 0, 0, 0), "W": (255, 255, 255, 255), "G": (176, 176, 176, 255), "D": (72, 72, 72, 255), "H": (255, 255, 255, 255)}
eye_glyph = Image.new("RGBA", (11, 8), (0, 0, 0, 0))
for y, row in enumerate(EYE_GLYPH):
    assert len(row) == 11
    for x, ch in enumerate(row):
        eye_glyph.putpixel((x, y), SHADE[ch])
os.makedirs(os.path.join(A, "blissgems/textures/font"), exist_ok=True)
eye_glyph.save(os.path.join(A, "blissgems/textures/font/lore_eye.png"))
font = json.load(open(font_path, encoding="utf-8"))
if not any(p.get("chars") == ["\ue045"] for p in font["providers"]):
    at = next(i for i, p in enumerate(font["providers"]) if p.get("chars") == ["\ue044"]) + 1
    font["providers"].insert(at, {"type": "bitmap", "file": "blissgems:font/lore_eye.png", "ascent": 7, "height": 8, "chars": ["\ue045"]})
    with open(font_path, "w", encoding="utf-8") as f:
        json.dump(font, f, indent=2, ensure_ascii=True)
        f.write("\n")

# ---- blissgems:hud_low: the Rift slot drawn beside the hotbar instead of on the action-bar line.
# The action bar's text top is 72 GUI px above the screen bottom; a glyph's top is (7 - ascent) below
# that. Ascent -44 centres the 20px icon on the 22px hotbar; -50 centres 8px text on it.
# U+E400+i / U+E410+i move the pen by +2^i / -2^i so the plugin can place the slot exactly.
NUL = "\u0000" * 16
ascii_rows = [NUL, NUL] + ["".join(chr(c) for c in range(r * 16, r * 16 + 16)) for r in range(2, 8)] + [NUL] * 8
ascii_rows[7] = ascii_rows[7][:-1] + "\u0000"  # U+007F is not a glyph
spaces = {" ": 4}
for i in range(9):
    spaces[chr(0xE400 + i)] = 1 << i
    spaces[chr(0xE410 + i)] = -(1 << i)
# The Tuned In meter frames (U+E042, U+E058-E05C) sit next to the Rift slot, so copy their providers lowered.
tuned_chars = ["\ue042"] + [chr(c) for c in range(0xE058, 0xE05D)]
tuned = [dict(p, ascent=-44) for p in font["providers"] if p.get("type") == "bitmap" and p.get("chars") and p["chars"][0] in tuned_chars]
assert len(tuned) == 6, tuned
hud_low = {"providers": [
    {"type": "space", "advances": spaces},
    {"type": "bitmap", "file": "blissgems:icons/relay_rift.png", "height": 20, "ascent": -44, "chars": ["\ue043"]},
    *tuned,
    {"type": "bitmap", "file": "minecraft:font/ascii.png", "height": 8, "ascent": -50, "chars": ascii_rows},
]}
os.makedirs(os.path.join(A, "blissgems/font"), exist_ok=True)
with open(os.path.join(A, "blissgems/font/hud_low.json"), "w", encoding="utf-8") as f:
    json.dump(hud_low, f, indent=2, ensure_ascii=True)
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
