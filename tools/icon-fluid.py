"""Icon A redrawn in the 'Fluid' icon-pack language (user's reference): a squircle full of soft liquid shapes with
depth, and a bold puffy 3D symbol in the middle — here the TV with the two AK eyes, in the launcher's colours.
Writes fluid_1.png, fluid_2.png (1024) and fluid_sheet.png."""
import math
import random
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

EYE, OUT, FONT = sys.argv[1], sys.argv[2], sys.argv[3]
S = 2048


def arr(img):
    return np.asarray(img, dtype=np.float32) / 255.0


def to_l(a):
    return Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8), 'L')


def squircle(size, n=5.0, pad=0):
    """Superellipse mask (the pack's rounded-square)."""
    h = np.linspace(-1, 1, size)
    x, y = np.meshgrid(h, h)
    r = (np.abs(x) ** n + np.abs(y) ** n) ** (1 / n)
    m = np.clip((1 - r) * size / 3, 0, 1)
    return to_l(m)


def blob_mask(seed, count, area, rmin, rmax, thresh=0.5, blur=120):
    """Organic liquid shape: soft circles merged (metaballs) and cut at a threshold."""
    rnd = random.Random(seed)
    m = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(m)
    x0, y0, x1, y1 = area
    for _ in range(count):
        r = rnd.uniform(rmin, rmax)
        cx, cy = rnd.uniform(x0, x1), rnd.uniform(y0, y1)
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(blur))
    a = arr(m)
    a = np.clip((a - thresh) * 12 + 0.5, 0, 1)
    return to_l(a)


def shade(mask, base_top, base_bottom, light=1.0, depth=26, gloss=True):
    """Puffy 3D fill for a mask: vertical colour, lit top-left rim, dark bottom-right rim, soft gloss."""
    m = arr(mask)
    h = np.linspace(0, 1, S)[:, None]
    col = np.zeros((S, S, 3), np.float32)
    for i in range(3):
        col[..., i] = (base_top[i] + (base_bottom[i] - base_top[i]) * h) / 255
    b = arr(mask.filter(ImageFilter.GaussianBlur(depth)))
    lit = np.roll(np.roll(b, depth // 2, 0), depth // 2, 1) - np.roll(np.roll(b, -depth // 2, 0), -depth // 2, 1)
    # lit > 0 where the edge faces the light (top-left)
    hi = np.clip(-lit, 0, 1) * 1.6 * light
    lo = np.clip(lit, 0, 1) * 1.3
    col = col + hi[..., None] * (1 - col) * 0.85 - lo[..., None] * col * 0.7
    if gloss:
        g = arr(mask.filter(ImageFilter.GaussianBlur(depth * 3)))
        top = np.clip(1 - h * 2.2, 0, 1)
        col = col + (g * top)[..., None] * 0.16
    out = np.dstack([np.clip(col, 0, 1), m])
    return Image.fromarray((out * 255).astype(np.uint8), 'RGBA')


def drop(mask, dy, blur, alpha):
    s = mask.filter(ImageFilter.GaussianBlur(blur))
    s = ImageChops.offset(s, 0, dy)
    img = Image.new('RGBA', (S, S), (0, 0, 0, 255))
    img.putalpha(to_l(arr(s) * alpha))
    return img


def rr(box, r):
    m = Image.new('L', (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle(box, r, fill=255)
    return m


def eyes_img(height):
    e = Image.open(EYE).convert('RGBA')
    disc = e.crop((0, 0, e.width, e.width)).resize((height, height), Image.LANCZOS)
    gap = int(height * 0.1)
    out = Image.new('RGBA', (height * 2 + gap, height), (0, 0, 0, 0))
    out.alpha_composite(disc, (0, 0)); out.alpha_composite(disc, (height + gap, 0))
    return out


def background(palette, seed):
    """Squircle with a gradient and three layers of liquid shapes, each with depth and a shadow on the one below."""
    # plain gradient ground (no rim shading: the squircle's own edge must stay clean)
    t, b = palette['base']
    h = np.linspace(0, 1, S)[:, None, None]
    g = (np.array(t) + (np.array(b) - np.array(t)) * h) / 255
    bg = Image.fromarray((np.dstack([np.broadcast_to(g, (S, S, 3)), np.ones((S, S))]) * 255).astype(np.uint8), 'RGBA')
    # big smooth waves flowing in from the corners, back to front
    layers = [
        (seed, 9, (-500, -500, S * 0.75, S * 0.3), 300, 520, palette['l1']),
        (seed + 1, 9, (S * 0.35, S * 0.7, S + 500, S + 500), 300, 540, palette['l2']),
        (seed + 2, 7, (-500, S * 0.62, S * 0.45, S + 500), 280, 460, palette['l3']),
        (seed + 3, 5, (S * 0.8, -400, S + 500, S * 0.45), 260, 420, palette['l4']),
    ]
    for sd, n, area, r0, r1, cols in layers:
        m = blob_mask(sd, n, area, r0, r1, thresh=0.42, blur=200)
        bg.alpha_composite(drop(m, 40, 50, 0.6))
        bg.alpha_composite(shade(m, cols[0], cols[1], light=1.0, depth=56))
    return bg


def tv(white_body):
    """The puffy TV: a thick rounded body, a deep screen with a glow, the two eyes, a little stand."""
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    w, h = S * 0.66, S * 0.46
    x0, y0 = (S - w) / 2, S * 0.24
    body = rr((x0, y0, x0 + w, y0 + h), S * 0.09)
    stand_m = rr((S / 2 - S * 0.17, y0 + h + S * 0.045, S / 2 + S * 0.17, y0 + h + S * 0.1), S * 0.03)
    neck = rr((S / 2 - S * 0.05, y0 + h - 20, S / 2 + S * 0.05, y0 + h + S * 0.07), S * 0.02)
    both = ImageChops.lighter(ImageChops.lighter(body, stand_m), neck)
    layer.alpha_composite(drop(both, 60, 60, 0.7))
    if white_body:
        top, bot = (255, 246, 249), (226, 196, 208)
    else:
        top, bot = (70, 34, 50), (16, 6, 12)
    layer.alpha_composite(shade(ImageChops.lighter(stand_m, neck), top, bot, depth=22))
    layer.alpha_composite(shade(body, top, bot, depth=34))
    # screen: inset, darker at the top edge (it sits inside the body)
    b = S * 0.05
    scr = rr((x0 + b, y0 + b, x0 + w - b, y0 + h - b), S * 0.05)
    s = shade(scr, (154, 20, 80), (40, 2, 22), light=0.0, depth=30, gloss=False)
    sa = arr(scr)
    inner = arr(scr.filter(ImageFilter.GaussianBlur(26)))
    rim = np.clip(sa - np.roll(inner, 24, 0), 0, 1)                   # shadow from the bezel along the top
    sp = np.asarray(s, dtype=np.float32) / 255
    sp[..., :3] *= (1 - rim[..., None] * 0.8)
    # a glow in the screen's upper-left
    yy, xx = np.mgrid[0:S, 0:S]
    gl = np.exp(-(((xx - (x0 + w * 0.28)) / (w * 0.35)) ** 2 + ((yy - (y0 + h * 0.3)) / (h * 0.45)) ** 2))
    sp[..., 0] += gl * 0.25 * sa; sp[..., 1] += gl * 0.05 * sa; sp[..., 2] += gl * 0.12 * sa
    s = Image.fromarray((np.clip(sp, 0, 1) * 255).astype(np.uint8), 'RGBA')
    layer.alpha_composite(s)
    # the eyes, raised off the screen with their own shadow
    eh = int(h * 0.48)
    e = eyes_img(eh)
    ex, ey = int(S / 2 - e.width / 2), int(y0 + h / 2 - eh / 2)
    em = Image.new('L', (S, S), 0); em.paste(e.split()[3], (ex, ey))
    layer.alpha_composite(drop(em, 26, 22, 0.75))
    layer.alpha_composite(e, (ex, ey))
    # glass shine over the screen
    shine = Image.new('L', (S, S), 0)
    ImageDraw.Draw(shine).polygon([(x0 + b, y0 + b), (x0 + w * 0.6, y0 + b), (x0 + w * 0.36, y0 + h - b), (x0 + b, y0 + h - b)], fill=40)
    shine = ImageChops.multiply(shine.filter(ImageFilter.GaussianBlur(30)), scr)
    wl = Image.new('RGBA', (S, S), (255, 255, 255, 0)); wl.putalpha(shine)
    layer.alpha_composite(wl)
    return layer


PALETTES = {
    # 1: the launcher's night with maroon liquid (no pink), creamy puffy TV
    1: dict(base=((28, 3, 16), (8, 1, 6)), l1=((142, 18, 74), (72, 5, 38)), l2=((122, 14, 62), (52, 3, 28)),
            l3=((44, 10, 28), (14, 3, 9)), l4=((96, 10, 50), (40, 3, 22))),
    # 2: black with maroon liquid and one warm gold wave (the launcher's gold dot)
    2: dict(base=((16, 8, 12), (4, 2, 3)), l1=((122, 14, 62), (56, 4, 30)), l2=((255, 201, 92), (196, 120, 30)),
            l3=((40, 12, 26), (12, 3, 8)), l4=((154, 20, 80), (70, 6, 36))),
}


def frost(img, blur):
    """Frosted, shiny glass over the waves (user: «ضبابية أكثر وزجاجية لماعة»): blur, a soft white veil at the top,
    a wide diagonal shine and a narrow bright streak."""
    g = img.filter(ImageFilter.GaussianBlur(blur))
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    # dark, smoky glass instead of white light (user: «الضوء الأبيض مو حلو، ضوء أسود»): a black veil heavier at
    # the top, a wide dark diagonal band and a thin dark streak beside it
    veil = 0.18 + np.clip(0.55 - yy, 0, 1) * 0.35
    band = np.exp(-((xx + yy * 0.9 - 0.62) / 0.16) ** 2) * 0.28
    streak = np.exp(-((xx + yy * 0.9 - 0.86) / 0.03) ** 2) * 0.35
    smoke = Image.new('RGBA', (S, S), (0, 0, 0, 0)); smoke.putalpha(to_l(np.clip(veil + band + streak, 0, 0.85)))
    g.alpha_composite(smoke)
    return g


def make(k, white, glass=0):
    img = background(PALETTES[k], seed=7 + k * 10)
    if glass:
        img = frost(img, glass)
    img.alpha_composite(tv(white))
    # drop the outer 5 % (blurs go grey at the canvas edge), then the squircle cut
    c = int(S * 0.05)
    img = img.crop((c, c, S - c, S - c)).resize((S, S), Image.LANCZOS)
    mask = squircle(S)
    # the pack's glossy rim: a thin light line along the top edge, fading down the sides
    rim = arr(mask) - arr(ImageChops.offset(mask, 0, 10))
    rim = np.clip(rim, 0, 1) * np.clip(1 - np.linspace(0, 1, S)[:, None] * 1.6, 0, 1)
    lite = Image.new('RGBA', (S, S), (255, 255, 255, 0)); lite.putalpha(to_l(rim * (0.0 if glass else 0.55)))
    img.alpha_composite(lite)
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.resize((1024, 1024), Image.LANCZOS)


if len(sys.argv) > 4:
    # export the chosen icon (A2 - 2) for Android: adaptive layers (Android 8+) and legacy PNGs
    RES = sys.argv[4]
    import os
    c = int(S * 0.05)
    bg = frost(background(PALETTES[2], seed=27), 90).crop((c, c, S - c, S - c)).resize((S, S), Image.LANCZOS)
    fg = tv(True)
    # the system cuts adaptive icons to its own shape: keep the TV inside the safe middle (66 of 108 dp)
    k = 0.64
    fg_s = fg.resize((int(S * k), int(S * k)), Image.LANCZOS)
    fg_full = Image.new('RGBA', (S, S), (0, 0, 0, 0)); fg_full.alpha_composite(fg_s, ((S - fg_s.width) // 2, (S - fg_s.height) // 2 + int(S * 0.02)))
    legacy = make(2, True, glass=90)
    for dens, adaptive, flat in (('mdpi', 108, 48), ('hdpi', 162, 72), ('xhdpi', 216, 96), ('xxhdpi', 324, 144), ('xxxhdpi', 432, 192)):
        d = os.path.join(RES, f'mipmap-{dens}'); os.makedirs(d, exist_ok=True)
        bg.convert('RGB').resize((adaptive, adaptive), Image.LANCZOS).save(os.path.join(d, 'ic_launcher_background.png'))
        fg_full.resize((adaptive, adaptive), Image.LANCZOS).save(os.path.join(d, 'ic_launcher_foreground.png'))
        legacy.resize((flat, flat), Image.LANCZOS).save(os.path.join(d, 'ic_launcher.png'))
    legacy.save(os.path.join(OUT, 'final_icon_1024.png'))
    print('exported')
    sys.exit(0)

icons = {1: make(2, True, glass=40), 2: make(2, True, glass=90)}
for k, v in icons.items():
    v.save(f'{OUT}/glass_{k}.png')

# sheet in the reference's mood: black stage, soft reflection under each icon, the name under it
W, H = 1300, 900
sheet = Image.new('RGBA', (W, H), (0, 0, 0, 255))
d = ImageDraw.Draw(sheet)
fn = ImageFont.truetype(FONT, 48); ft = ImageFont.truetype(FONT, 56)
for i, k in enumerate((1, 2)):
    cx = 340 + i * 620
    d.text((cx, 70), ('A2 · ضباب خفيف', 'A2 · ضباب أقوى')[k - 1][::-1] if False else ('A2 - 1', 'A2 - 2')[k - 1], font=ft, fill=(246, 196, 208, 255), anchor='mm')
    ic = icons[k].resize((420, 420), Image.LANCZOS)
    sheet.alpha_composite(ic, (cx - 210, 130))
    refl = ic.transpose(Image.FLIP_TOP_BOTTOM).crop((0, 0, 420, 120))
    fade = Image.linear_gradient('L').resize((420, 120)).point(lambda v: int((255 - v) * 0.3))
    refl.putalpha(ImageChops.multiply(refl.split()[3], fade.transpose(Image.FLIP_TOP_BOTTOM).transpose(Image.FLIP_TOP_BOTTOM)))
    sheet.alpha_composite(refl, (cx - 210, 556))
    d.text((cx, 720), 'AK Home', font=fn, fill=(255, 255, 255, 255), anchor='mm')
    sheet.alpha_composite(icons[k].resize((130, 130), Image.LANCZOS), (cx - 65, 760))
sheet.convert('RGB').save(f'{OUT}/glass_sheet.png')
print('ok')
