#!/usr/bin/env python3
"""Draws every texture the mod ships, as pixel art, using only the standard library.

Run from the project root:  python3 tools/gen_textures.py
Writes the PNGs into src/main/resources/assets/nocreeperexplodes/ and 8x previews
into build/texture-preview/.
"""
import math
import random
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/nocreeperexplodes"
PREVIEW = ROOT / "build/texture-preview"

CLEAR = (0, 0, 0, 0)


def hex_color(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


class Canvas:
    def __init__(self, w, h, fill=CLEAR):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def get(self, x, y):
        return self.px[y][x]

    def rect(self, x, y, w, h, c):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, c)

    def bevel(self, x, y, w, h, light, dark):
        for xx in range(x, x + w):
            self.set(xx, y, light)
            self.set(xx, y + h - 1, dark)
        for yy in range(y, y + h):
            self.set(x, yy, light)
            self.set(x + w - 1, yy, dark)

    def paste(self, other, ox, oy):
        for y in range(other.h):
            for x in range(other.w):
                c = other.px[y][x]
                if c[3]:
                    self.set(ox + x, oy + y, c)

    def scaled(self, k):
        out = Canvas(self.w * k, self.h * k)
        for y in range(out.h):
            for x in range(out.w):
                out.px[y][x] = self.px[y // k][x // k]
        return out

    def save(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = b"".join(b"\x00" + bytes(v for c in row for v in c) for row in self.px)

        def chunk(tag, data):
            body = tag + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(raw, 9))
        png += chunk(b"IEND", b"")
        path.write_bytes(png)


# ---------------------------------------------------------------- palettes

CREEPER_GREENS = [hex_color(c) for c in ("#5DB34B", "#4FA03E", "#3F8B31", "#6CC458", "#357A2A", "#86D374")]
FACE_BLACK = [hex_color(c) for c in ("#0C0C0C", "#151515", "#1F1F1F")]
TEAR = [hex_color("#8FE6FF"), hex_color("#30B4FF"), hex_color("#1565E0")]  # top highlight -> bottom

WIRES = {  # light, base, dark
    "red": (hex_color("#FF7A70"), hex_color("#D8372F"), hex_color("#8C1B16")),
    "blue": (hex_color("#7FA8FF"), hex_color("#3466D6"), hex_color("#1C3A8A")),
    "yellow": (hex_color("#FFF19A"), hex_color("#E8C536"), hex_color("#977A10")),
}
WIRE_ORDER = ["red", "blue", "yellow"]
OUTLINE = hex_color("#0A0A0A")
GLOW = hex_color("#FFF7B0")
COPPER = (hex_color("#F2A35A"), hex_color("#C46D2C"))


def camo(canvas, x, y, w, h, rng, cell=2):
    for cy in range(y, y + h, cell):
        for cx in range(x, x + w, cell):
            canvas.rect(cx, cy, min(cell, x + w - cx), min(cell, y + h - cy), rng.choice(CREEPER_GREENS))


def hazard(canvas, x, y, w, h):
    yellow, black = hex_color("#E9B91F"), hex_color("#1B1B1B")
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            canvas.set(xx, yy, yellow if ((xx + yy) // 4) % 2 == 0 else black)
    canvas.bevel(x, y, w, h, hex_color("#141414"), hex_color("#141414"))


def screw(canvas, cx, cy):
    """5x5 brass screw head centred on (cx, cy)."""
    shadow, brass, hi, slot = hex_color("#7A5A18"), hex_color("#D6A93A"), hex_color("#F8E08A"), hex_color("#5A4210")
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            if abs(dx) == 2 and abs(dy) == 2:
                continue
            canvas.set(cx + dx, cy + dy, shadow if dx + dy >= 2 else brass)
    canvas.set(cx - 1, cy - 1, hi)
    for d in range(-1, 2):
        canvas.set(cx + d, cy, slot)


# ---------------------------------------------------------------- tear overlay

def tear_overlay():
    """64x32 overlay for the vanilla creeper UV layout.

    The head's front face lives at u=8..15, v=8..15. The creeper's own right eye is the
    left one as you face it: u=9..10, v=10..11. The tear drips straight down from it.
    """
    c = Canvas(64, 32)
    for i, color in enumerate(TEAR):
        c.set(9, 12 + i, color)
    return c


# ---------------------------------------------------------------- GUI sheet

PANEL_W, PANEL_H = 176, 112
WIRE_W, WIRE_H = 126, 10
WIRE_SHEET_Y = 116
# wire row centres inside the panel; the screen code mirrors these numbers
WIRE_X = 20  # from the left screw (x 15..19) to the TNT screw (x 147..151)
WIRE_ROWS = [32, 54, 76]
GAP = (57, 68)  # where the cut is, in wire-local x


def wire_path(x, phase):
    """Sag of the wire; tapers to zero at both ends so it meets the screws."""
    envelope = math.sin(math.pi * x / (WIRE_W - 1))
    return round(envelope * (2.2 * math.sin(x / 9.0 + phase) + 0.7 * math.sin(x / 3.7 + phase * 2)))


def wire_sprite(name, variant, phase):
    light, base, dark = WIRES[name]
    if variant == "hover":
        light, base, dark = tuple(min(255, v + 40) for v in light[:3]) + (255,), light, base
    c = Canvas(WIRE_W, WIRE_H)
    mid = WIRE_H // 2
    cut = variant == "cut"
    for x in range(WIRE_W):
        if cut and GAP[0] <= x <= GAP[1]:
            continue
        y = mid + wire_path(x, phase)
        for yy, col in ((y - 1, light), (y, base), (y + 1, dark)):
            c.set(x, yy, col)
    # outline (or glow) around every wire pixel
    edge = GLOW if variant == "hover" else OUTLINE
    filled = {(x, y) for y in range(WIRE_H) for x in range(WIRE_W) if c.get(x, y)[3]}
    for x, y in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in filled:
                c.set(x + dx, y + dy, edge)
    if cut:
        # frayed copper strands poking out of both cut ends
        for end, direction in ((GAP[0] - 1, 1), (GAP[1] + 1, -1)):
            y = mid + wire_path(end, phase)
            strands = [(1, -2), (2, -1), (3, 0), (2, 1), (1, 2)]
            for i, (dx, dy) in enumerate(strands):
                for step in range(1, dx + 1):
                    c.set(end + direction * step, y + round(dy * step / dx), COPPER[i % 2])
    return c


def tnt_letters(c, x, y, color):
    glyphs = {
        "T": ["###", ".#.", ".#.", ".#.", ".#."],
        "N": ["#.#", "###", "###", "#.#", "#.#"],
    }
    for i, ch in enumerate("TNT"):
        for gy, row in enumerate(glyphs[ch]):
            for gx, bit in enumerate(row):
                if bit == "#":
                    c.set(x + i * 4 + gx, y + gy, color)


def panel(rng):
    c = Canvas(PANEL_W, PANEL_H)
    # steel frame
    c.rect(0, 0, PANEL_W, PANEL_H, hex_color("#8A9096"))
    c.bevel(0, 0, PANEL_W, PANEL_H, hex_color("#101010"), hex_color("#101010"))
    c.bevel(1, 1, PANEL_W - 2, PANEL_H - 2, hex_color("#C4CAD0"), hex_color("#4E5358"))
    c.bevel(3, 3, PANEL_W - 6, PANEL_H - 6, hex_color("#4E5358"), hex_color("#C4CAD0"))
    for rx, ry in ((2, 2), (PANEL_W - 4, 2), (2, PANEL_H - 4), (PANEL_W - 4, PANEL_H - 4)):
        c.set(rx, ry, hex_color("#E6EBEF"))
        c.set(rx + 1, ry + 1, hex_color("#3A3E42"))
        c.set(rx + 1, ry, hex_color("#B0B6BC"))
        c.set(rx, ry + 1, hex_color("#B0B6BC"))
    # creeper-skin casing
    camo(c, 4, 4, PANEL_W - 8, PANEL_H - 8, rng)
    # hazard bands top and bottom
    hazard(c, 6, 6, PANEL_W - 12, 8)
    hazard(c, 6, PANEL_H - 14, PANEL_W - 12, 8)
    # LCD for the countdown (the screen draws the digits)
    c.rect(PANEL_W // 2 - 18, 4, 36, 12, hex_color("#101010"))
    c.rect(PANEL_W // 2 - 17, 5, 34, 10, hex_color("#2A0A08"))
    c.bevel(PANEL_W // 2 - 18, 4, 36, 12, hex_color("#3A3A3A"), hex_color("#000000"))
    # recessed wire bay
    bay_x, bay_y, bay_w, bay_h = 8, 18, PANEL_W - 16, PANEL_H - 36
    c.rect(bay_x, bay_y, bay_w, bay_h, hex_color("#1C211C"))
    for trace_y in ((WIRE_ROWS[0] + WIRE_ROWS[1]) // 2, (WIRE_ROWS[1] + WIRE_ROWS[2]) // 2):  # faint circuit traces
        for i in range(4, bay_w - 4, 3):
            c.set(bay_x + i, trace_y, hex_color("#273327"))
    c.bevel(bay_x, bay_y, bay_w, bay_h, hex_color("#0E110E"), hex_color("#3A443A"))
    # left terminal block
    tx, ty, tw, th = 11, 21, 13, bay_h - 6
    c.rect(tx, ty, tw, th, hex_color("#3B3F43"))
    c.bevel(tx, ty, tw, th, hex_color("#62686E"), hex_color("#202326"))
    # right: TNT charge
    kx, ky, kw, kh = PANEL_W - 30, 21, 19, bay_h - 6
    c.rect(kx, ky, kw, kh, hex_color("#C6342B"))
    for yy in range(ky, ky + kh, 4):
        c.rect(kx + 1, yy, kw - 2, 1, hex_color("#A82820"))
    band_y = ky + kh // 2 - 5
    c.rect(kx, band_y, kw, 10, hex_color("#E9E4DA"))
    tnt_letters(c, kx + 7, band_y + 3, hex_color("#1A1A1A"))
    c.bevel(kx, ky, kw, kh, hex_color("#E8665C"), hex_color("#6E1510"))
    # brass screws where the wires attach
    for cy in WIRE_ROWS:
        screw(c, tx + tw // 2, cy)
        screw(c, kx + 3, cy)
    return c


def gui_sheet():
    rng = random.Random(1337)
    sheet = Canvas(256, 256)
    sheet.paste(panel(rng), 0, 0)
    for i, name in enumerate(WIRE_ORDER):
        phase = i * 2.1
        for j, variant in enumerate(("intact", "hover", "cut")):
            sheet.paste(wire_sprite(name, variant, phase), 0, WIRE_SHEET_Y + (i * 3 + j) * WIRE_H)
    return sheet


# ---------------------------------------------------------------- icon

def icon():
    rng = random.Random(42)
    c = Canvas(16, 16)
    for y in range(16):
        for x in range(16):
            c.set(x, y, rng.choice(CREEPER_GREENS))
    face = [
        "........",
        "........",
        ".XX..XX.",
        ".XX..XX.",
        "...XX...",
        "..XXXX..",
        "..XXXX..",
        "..X..X..",
    ]
    for fy, row in enumerate(face):
        for fx, bit in enumerate(row):
            if bit == "X":
                c.rect(fx * 2, fy * 2, 2, 2, rng.choice(FACE_BLACK))
    # tear under its own right eye (the left one as you look at it)
    c.set(2, 8, TEAR[0])
    c.set(2, 9, TEAR[1])
    c.set(3, 9, TEAR[1])
    c.set(2, 10, TEAR[2])
    c.set(3, 10, TEAR[2])
    return c.scaled(8)


def main():
    outputs = {
        "textures/entity/creeper/creeper_tear.png": tear_overlay(),
        "textures/gui/wire_cut.png": gui_sheet(),
        "icon.png": icon(),
    }
    for rel, canvas in outputs.items():
        canvas.save(ASSETS / rel)
        canvas.scaled(8 if canvas.w <= 64 else 4).save(PREVIEW / Path(rel).name)
        print("wrote", ASSETS / rel)


if __name__ == "__main__":
    main()
