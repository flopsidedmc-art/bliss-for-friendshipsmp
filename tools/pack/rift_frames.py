"""Procedural rift texture frames (40x96 px = 2.5 x 6 blocks at 16 px/block) and preview sheets."""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
W, H = 40, 96
RIM = (0xB0, 0x70, 0x3A)
DARK = (0x2C, 0x18, 0x0C)
SLIVER = (0xF0, 0xC4, 0x8C)
SWIRL_A = (0x55, 0x30, 0x17)
SWIRL_B = (0x6E, 0x40, 0x1F)
HALO = (0xD4, 0x96, 0x58)

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))

def frame(seed, phase, openness=1.0, jolt=1.0):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = img.load()
    # centre line wobble and jagged half-width profile, regenerated per frame
    centre, half = [], []
    drift = rnd.uniform(-0.6, 0.6)
    for y in range(H):
        t = y / (H - 1)
        c = W / 2 - 0.5 + 1.2 * math.sin(t * 5.1 + seed) + drift * math.sin(t * 9.0)
        base = (W / 2 - 3) * (math.sin(math.pi * t) ** 0.75) * openness * jolt
        jag = rnd.choice([-2, -1, -1, 0, 0, 0, 1, 1, 2]) if base > 2 else 0
        centre.append(c)
        half.append(max(0.0, base + jag))
    # light smoothing so the jaggedness reads as cracks, not noise
    half = [half[0]] + [(half[i - 1] + 2 * half[i] + half[i + 1]) / 4 if i % 3 else half[i] for i in range(1, H - 1)] + [half[-1]]
    cy = H / 2
    for y in range(H):
        for x in range(W):
            d = abs(x - centre[y]) - half[y]
            if d <= 0:
                inner = -d
                if inner < 1.6:
                    px[x, y] = RIM + (255,)
                    continue
                # opposite-turning swirls inside the tear
                dx, dy = (x - centre[y]) / max(1.0, half[y]), (y - cy) / (H / 2)
                ang, r = math.atan2(dy * 2.4, dx), math.hypot(dx, dy * 2.4)
                s1 = math.sin(3 * ang + 6 * r - phase)
                s2 = math.sin(-3 * ang + 5 * r + phase * 0.8)
                col = DARK
                if s1 > 0.55:
                    col = lerp(DARK, SWIRL_A, (s1 - 0.55) / 0.45)
                if s2 > 0.7:
                    col = lerp(col, SWIRL_B, (s2 - 0.7) / 0.3 * 0.8)
                # bright sliver down the centre, strongest in the middle of the height
                t = y / (H - 1)
                core = abs(x - centre[y])
                strength = math.sin(math.pi * t) ** 1.5 * openness
                if core < 0.9 and strength > 0.25:
                    col = lerp(col, SLIVER, min(1, strength * 1.2))
                elif core < 1.9 and strength > 0.35:
                    col = lerp(col, HALO, strength * 0.55)
                px[x, y] = col + (255,)
            elif d < 2.6 and half[y] > 0.5:
                a = int(150 * (1 - d / 2.6))  # soft glow fringe outside the rim
                px[x, y] = RIM + (a,)
    return img

frames = []
for i in range(6):
    frames.append(frame(seed=101 + i * 7, phase=i * math.tau / 6, jolt=(0.93 if i == 3 else 1.0)))
opening = [frame(seed=500 + i, phase=0.0, openness=o) for i, o in enumerate((0.08, 0.35, 0.7, 1.0))]
for i, f in enumerate(frames):
    f.save(f"{OUT}/rift_{i}.png")

def scaled(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)

def on_bg(img, bg, glow=True, k=4):
    big = scaled(img, k)
    canvas = Image.new("RGBA", big.size, bg + (255,))
    if glow:
        halo = Image.new("RGBA", big.size, (0, 0, 0, 0))
        mask = big.split()[3].point(lambda a: 255 if a > 40 else 0)
        halo.paste(RIM + (170,), (0, 0), mask)
        canvas = Image.alpha_composite(canvas, halo.filter(ImageFilter.GaussianBlur(k * 2.2)))
    return Image.alpha_composite(canvas, big)

# Sheet 1: the six loop frames at night, plus the four opening stages
k = 4
night = (0x14, 0x16, 0x1E)
pad = 12
sheet = Image.new("RGBA", (pad + 10 * (W * k + pad), H * k + 70), (40, 40, 48, 255))
d = ImageDraw.Draw(sheet)
for i, f in enumerate(frames + opening):
    x = pad + i * (W * k + pad)
    sheet.alpha_composite(on_bg(f, night), (x, 30))
    d.text((x, 8), f"frame {i}" if i < 6 else f"opening {i - 5}/4", fill=(230, 230, 230, 255))
d.text((pad, H * k + 40), "Loop frames 0-5 (frame 3 is the size jolt). Opening 1-4: crack to full tear during the channel.", fill=(230, 230, 230, 255))
sheet.convert("RGB").save(f"{OUT}/rift_frames_sheet.png")

# Sheet 2: in-world mock-up at night and at midday, with a 2-block player silhouette for scale
def scene(sky, ground, frame_img, light=True):
    ppb = 24  # pixels per block in the mock-up
    w, h = 12 * ppb, 9 * ppb
    img = Image.new("RGBA", (w, h), sky + (255,))
    dd = ImageDraw.Draw(img)
    gy = int(7.5 * ppb)
    dd.rectangle([0, gy, w, h], fill=ground + (255,))
    rift = frame_img.resize((int(2.5 * ppb), 6 * ppb), Image.NEAREST)
    rx = int(6.5 * ppb)
    if light:  # brown light spilled on the ground in front of the rift
        spill = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(spill).ellipse([rx - ppb * 1.5, gy - ppb * 0.15, rx + rift.width + ppb * 1.5, gy + ppb * 0.9], fill=RIM + (120,))
        img = Image.alpha_composite(img, spill.filter(ImageFilter.GaussianBlur(10)))
    halo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mask = rift.split()[3].point(lambda a: 255 if a > 40 else 0)
    halo.paste(RIM + (190,), (rx, gy - rift.height), mask)
    img = Image.alpha_composite(img, halo.filter(ImageFilter.GaussianBlur(7)))
    img.alpha_composite(rift, (rx, gy - rift.height))
    dd = ImageDraw.Draw(img)
    px0 = int(2.5 * ppb)  # 2-block-tall player silhouette
    dd.rectangle([px0, gy - 2 * ppb, px0 + int(0.6 * ppb), gy], fill=(20, 20, 20, 255))
    rnd = random.Random(7)  # brown dust drifting inwards
    for _ in range(26):
        ang = rnd.uniform(0, math.tau)
        dist = rnd.uniform(1.4, 3.0) * ppb
        cx, cy = rx + rift.width / 2 + math.cos(ang) * dist, gy - rift.height / 2 + math.sin(ang) * dist * 1.4
        r = rnd.choice((2, 3))
        dd.rectangle([cx, cy, cx + r, cy + r], fill=rnd.choice((RIM, (0x8C, 0x55, 0x30), HALO)) + (255,))
    return img

a = scene((0x0E, 0x10, 0x1C), (0x1C, 0x24, 0x14), frames[1])
b = scene((0x86, 0xB4, 0xF0), (0x5E, 0x8E, 0x3A), frames[1], light=False)
mock = Image.new("RGBA", (a.width * 2 + 30, a.height + 40), (40, 40, 48, 255))
mock.alpha_composite(a, (10, 30)); mock.alpha_composite(b, (a.width + 20, 30))
dm = ImageDraw.Draw(mock)
dm.text((10, 8), "Night: full-bright display + brown glow outline + light spill", fill=(230, 230, 230, 255))
dm.text((a.width + 20, 8), "Midday (no spill needed). Black bar = 2-block player for scale", fill=(230, 230, 230, 255))
mock.convert("RGB").save(f"{OUT}/rift_in_world.png")

# Animated preview: open, loop with stutter, snap shut
seq = [on_bg(f, night, k=3) for f in opening] + [on_bg(frames[i % 6], night, k=3) for i in range(14)]
seq += [on_bg(frame(900, 0.0, openness=o), night, k=3) for o in (0.5, 0.12)]
durations = [120] * 4 + [160] * 14 + [60, 60]
durations[4 + 3] = 70; durations[4 + 9] = 70  # stutter frames are shorter
seq[0].save(f"{OUT}/rift_preview.gif", save_all=True, append_images=seq[1:], duration=durations, loop=0, disposal=2)
print("ok")
