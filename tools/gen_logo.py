#!/usr/bin/env python3
"""Draws the project logo (CurseForge avatar): a crying creeper behind a red "no" sign.

Run from the project root:  python3 tools/gen_logo.py
Writes branding/logo.png (512x512, a 64x64 pixel-art design scaled 8x).
"""
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_textures import CLEAR, CREEPER_GREENS, FACE_BLACK, TEAR, Canvas, hex_color  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "branding/logo.png"

SIZE = 64
SCALE = 8
CENTER = SIZE / 2  # pixel (x, y) covers x..x+1, so its centre is x + 0.5

BG_TOP = hex_color("#2E3946")
BG_BOTTOM = hex_color("#141A21")
HEAD_OUTLINE = hex_color("#173314")
RED = hex_color("#D9262F")
RED_LIGHT = hex_color("#F25A5F")
RED_DARK = hex_color("#9E141C")
WHITE = hex_color("#FFFFFF")

FACE = [
	"........",
	"........",
	".XX..XX.",
	".XX..XX.",
	"...XX...",
	"..XXXX..",
	"..XXXX..",
	"..X..X..",
]
CELL = 5
# 8 cells of 5 px: a 40x40 head in the middle, its corners tucked under the ring.
HEAD_X = HEAD_Y = 32 - 20
CRYING_EYE_COLUMNS = (1, 5)

RING_INNER = 24.5
RING_OUTER = 29.5
SLASH_HALF_WIDTH = 2.6
TEAR_OUTLINE = hex_color("#0B3566")


def lerp_color(a, b, t):
	return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def background(c):
	"""Dark rounded square with a banded top-to-bottom gradient."""
	radius = 7
	for y in range(SIZE):
		band = (y // 4) * 4 / (SIZE - 4)
		color = lerp_color(BG_TOP, BG_BOTTOM, band)
		for x in range(SIZE):
			dx = max(radius - x - 0.5, x + 0.5 - (SIZE - radius), 0)
			dy = max(radius - y - 0.5, y + 0.5 - (SIZE - radius), 0)
			if dx * dx + dy * dy <= radius * radius:
				c.set(x, y, color)


def creeper(c):
	rng = random.Random(7)
	c.rect(HEAD_X - 1, HEAD_Y - 1, 8 * CELL + 2, 8 * CELL + 2, HEAD_OUTLINE)
	for row, line in enumerate(FACE):
		for col, bit in enumerate(line):
			palette = FACE_BLACK if bit == "X" else CREEPER_GREENS
			c.rect(HEAD_X + col * CELL, HEAD_Y + row * CELL, CELL, CELL, rng.choice(palette))

	# Tears streaming from both eyes, each ending in a fat drop: still readable behind the slash.
	for column in CRYING_EYE_COLUMNS:
		tear_stream(c, HEAD_X + column * CELL + 1, HEAD_Y + 4 * CELL)


def tear_stream(c, eye_x, top):
	light, mid, dark = TEAR
	tear = {}
	for y in range(top, top + 8):
		tear[(eye_x, y)] = light
		tear[(eye_x + 1, y)] = mid
	drop = [
		"..LM..",
		".LMMD.",
		"LMMMMD",
		"LMMMMD",
		".MMDD.",
		"..DD..",
	]
	for dy, row in enumerate(drop):
		for dx, ch in enumerate(row):
			if ch != ".":
				tear[(eye_x - 2 + dx, top + 8 + dy)] = {"L": light, "M": mid, "D": dark}[ch]
	for (x, y) in tear:
		for ox, oy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
			if (x + ox, y + oy) not in tear and y + oy >= top:
				c.set(x + ox, y + oy, TEAR_OUTLINE)
	for (x, y), col in tear.items():
		c.set(x, y, col)


def no_sign(c):
	"""Red prohibition ring with a top-left to bottom-right slash, outlined in white."""
	def shape(x, y):
		px, py = x + 0.5 - CENTER, y + 0.5 - CENTER
		r = math.hypot(px, py)
		on_ring = RING_INNER <= r <= RING_OUTER
		on_slash = r < RING_INNER + 0.5 and abs(py - px) / math.sqrt(2) <= SLASH_HALF_WIDTH
		return on_ring or on_slash

	red = {(x, y) for y in range(SIZE) for x in range(SIZE) if shape(x, y)}
	for x, y in red:
		for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
			if (x + dx, y + dy) not in red:
				c.set(x + dx, y + dy, WHITE)
	for x, y in red:
		px, py = x + 0.5 - CENTER, y + 0.5 - CENTER
		# light from the top-left: brighter on that side, darker bottom-right
		shade = (px + py) / (RING_OUTER * 1.4)
		c.set(x, y, RED_LIGHT if shade < -0.55 else RED_DARK if shade > 0.55 else RED)


def main():
	c = Canvas(SIZE, SIZE, CLEAR)
	background(c)
	creeper(c)
	no_sign(c)
	c.scaled(SCALE).save(OUT)
	print("wrote", OUT)


if __name__ == "__main__":
	main()
