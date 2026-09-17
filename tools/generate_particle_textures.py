#!/usr/bin/env python3
"""
Generates the mod's particle textures from scratch.

Every texture here is produced by evaluating a mathematical falloff function per pixel - there
is no imported, traced or edited source art anywhere in this mod. The images are pure white
with a varying alpha channel, which lets the particle code tint them at runtime using the
locked cyan/blue/white palette instead of baking colour into the file.

Run from the repository root:
    python3 tools/generate_particle_textures.py

Only the standard library is used, so this needs no pip install. PNGs are written by hand
(zlib + struct) rather than via a library for the same reason.
"""

import math
import os
import struct
import zlib

OUT_DIR = os.path.join("src", "main", "resources", "assets", "rasengan", "textures", "particle")

SIZE = 16


def write_png(path, pixels, width, height):
    """Writes an 8-bit RGBA PNG. `pixels` is a flat list of (r, g, b, a) tuples."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) for this scanline
        for x in range(width):
            r, g, b, a = pixels[y * width + x]
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit, RGBA
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", header)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    with open(path, "wb") as handle:
        handle.write(png)
    print("wrote {} ({}x{})".format(path, width, height))


def radial(size, power, inner=0.0):
    """Soft round glow: alpha falls off from the centre with an adjustable curve."""
    pixels = []
    centre = (size - 1) / 2.0
    max_distance = centre + 0.5
    for y in range(size):
        for x in range(size):
            dx = (x - centre) / max_distance
            dy = (y - centre) / max_distance
            distance = math.sqrt(dx * dx + dy * dy)
            value = 1.0 - distance
            if value <= 0.0:
                alpha = 0.0
            else:
                alpha = value ** power
                if inner > 0.0 and distance < inner:
                    alpha = 1.0  # flat hot centre
            pixels.append((255, 255, 255, max(0, min(255, int(round(alpha * 255))))))
    return pixels


def spark(size):
    """A hot core with four faint axis flares, so sparks read as directional glints."""
    pixels = []
    centre = (size - 1) / 2.0
    max_distance = centre + 0.5
    for y in range(size):
        for x in range(size):
            dx = (x - centre) / max_distance
            dy = (y - centre) / max_distance
            distance = math.sqrt(dx * dx + dy * dy)

            core = max(0.0, 1.0 - distance) ** 2.4

            # Cross flares: bright along the axes, tight in the perpendicular direction.
            flare_x = max(0.0, 1.0 - abs(dy) * 7.0) * max(0.0, 1.0 - abs(dx)) ** 1.6
            flare_y = max(0.0, 1.0 - abs(dx) * 7.0) * max(0.0, 1.0 - abs(dy)) ** 1.6
            flare = max(flare_x, flare_y) * 0.55

            alpha = min(1.0, core + flare)
            pixels.append((255, 255, 255, max(0, min(255, int(round(alpha * 255))))))
    return pixels


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    # Tiny dense dot for motes being pulled inward.
    write_png(os.path.join(OUT_DIR, "mote.png"), radial(SIZE, 3.2, inner=0.12), SIZE, SIZE)

    # Wide soft glow for wisps and the body aura.
    write_png(os.path.join(OUT_DIR, "soft_glow.png"), radial(SIZE, 1.7), SIZE, SIZE)

    # Directional glint for sparks and burst fragments.
    write_png(os.path.join(OUT_DIR, "spark.png"), spark(SIZE), SIZE, SIZE)


if __name__ == "__main__":
    main()
