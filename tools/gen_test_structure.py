#!/usr/bin/env python3
"""Writes the structure the game tests run in: a flat grass floor with room above it.

Run from the project root:  python3 tools/gen_test_structure.py
Writes src/gametest/resources/data/nocreeperexplodes/structure/arena.nbt (gzipped NBT, the structure block format).
The arena is wide enough for a 5x creeper blast in its middle to tear up the floor 6 blocks out; the game test
framework walls it in with barriers, which the blast can't break.
"""
import gzip
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/gametest/resources/data/nocreeperexplodes/structure/arena.nbt"

WIDTH, HEIGHT, DEPTH = 17, 5, 17
FLOOR = "minecraft:grass_block"
DATA_VERSION = 3955  # Minecraft 1.21.1

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def string(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def payload(tag_type, value):
    if tag_type == TAG_INT:
        return struct.pack(">i", value)
    if tag_type == TAG_STRING:
        return string(value)
    if tag_type == TAG_LIST:
        item_type, items = value
        return struct.pack(">bi", item_type if items else TAG_END, len(items)) + b"".join(payload(item_type, item) for item in items)
    if tag_type == TAG_COMPOUND:
        return b"".join(struct.pack(">b", t) + string(name) + payload(t, v) for name, (t, v) in value.items()) + struct.pack(">b", TAG_END)
    raise ValueError(tag_type)


def int_list(*values):
    return TAG_LIST, (TAG_INT, list(values))


def structure():
    blocks = [
        {"pos": int_list(x, 0, z), "state": (TAG_INT, 0)}
        for x in range(WIDTH)
        for z in range(DEPTH)
    ]
    return {
        "DataVersion": (TAG_INT, DATA_VERSION),
        "size": int_list(WIDTH, HEIGHT, DEPTH),
        "palette": (TAG_LIST, (TAG_COMPOUND, [{"Name": (TAG_STRING, FLOOR)}])),
        "blocks": (TAG_LIST, (TAG_COMPOUND, blocks)),
        "entities": (TAG_LIST, (TAG_COMPOUND, [])),
    }


def main():
    # The root is a compound with an empty name; mtime=0 keeps the file the same on every run.
    data = struct.pack(">b", TAG_COMPOUND) + string("") + payload(TAG_COMPOUND, structure())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(gzip.compress(data, mtime=0))
    print(f"Wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
