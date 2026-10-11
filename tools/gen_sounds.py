#!/usr/bin/env python3
"""Synthesizes the mod's sounds with the standard library, then encodes them with oggenc.

Run from the project root:  python3 tools/gen_sounds.py
Needs `oggenc` (brew install vorbis-tools). Minecraft only plays mono Ogg Vorbis as positional sound.
"""
import math
import shutil
import struct
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOUNDS = ROOT / "src/main/resources/assets/nocreeperexplodes/sounds"
RATE = 44100


def envelope(t, length, attack=0.003, release=0.04):
    if t < attack:
        return t / attack
    if t > length - release:
        return max(0.0, (length - t) / release)
    return 1.0


def bomb_beep():
    """A short, hard-edged electronic beep: a softened square wave (odd harmonics) at ~1.9 kHz."""
    length, freq = 0.14, 1900.0
    samples = []
    for i in range(int(length * RATE)):
        t = i / RATE
        wave_value = sum(math.sin(2 * math.pi * freq * k * t) / k for k in (1, 3, 5, 7))
        samples.append(wave_value * envelope(t, length))
    return samples


def write_ogg(name, samples):
    peak = max(abs(s) for s in samples) or 1.0
    pcm = b"".join(struct.pack("<h", int(s / peak * 0.7 * 32767)) for s in samples)
    SOUNDS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / f"{name}.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm)
        out = SOUNDS / f"{name}.ogg"
        subprocess.run(["oggenc", "--quiet", "-q", "6", "-o", str(out), str(wav)], check=True)
    print("wrote", out)


def main():
    if not shutil.which("oggenc"):
        sys.exit("oggenc not found: brew install vorbis-tools")
    write_ogg("defuse_beep", bomb_beep())


if __name__ == "__main__":
    main()
