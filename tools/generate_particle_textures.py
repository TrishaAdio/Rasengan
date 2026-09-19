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


def value_noise_2d(seed, size, cells):
    """
    Periodic value noise on a `cells` x `cells` lattice, bilinearly interpolated with a smoothstep
    fade, sampled at `size` x `size`.

    Periodic on both axes so the lattice wraps: without that, the puff texture would show a seam
    where the noise restarts, and the seam is visible as a straight edge across a soft cloud.

    Deterministic from `seed` via a plain integer hash, so regenerating the textures reproduces them
    byte for byte. No PRNG state is carried between calls.
    """
    lattice = {}
    for gy in range(cells):
        for gx in range(cells):
            h = (gx * 374761393 + gy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
            h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
            h = h ^ (h >> 16)
            lattice[(gx, gy)] = (h & 0xFFFF) / 65535.0

    def fade(t):
        return t * t * (3.0 - 2.0 * t)

    out = []
    scale = cells / float(size)
    for y in range(size):
        fy = y * scale
        y0 = int(math.floor(fy)) % cells
        y1 = (y0 + 1) % cells
        ty = fade(fy - math.floor(fy))
        for x in range(size):
            fx = x * scale
            x0 = int(math.floor(fx)) % cells
            x1 = (x0 + 1) % cells
            tx = fade(fx - math.floor(fx))
            n00 = lattice[(x0, y0)]
            n10 = lattice[(x1, y0)]
            n01 = lattice[(x0, y1)]
            n11 = lattice[(x1, y1)]
            top = n00 + (n10 - n00) * tx
            bot = n01 + (n11 - n01) * tx
            out.append(top + (bot - top) * ty)
    return out


def fractal_noise(seed, size, octaves):
    """Sum of periodic value-noise octaves at doubling frequency and halving amplitude."""
    total = [0.0] * (size * size)
    amplitude = 1.0
    norm = 0.0
    cells = 4
    for octave in range(octaves):
        layer = value_noise_2d(seed + octave * 7919, size, cells)
        for i in range(size * size):
            total[i] += layer[i] * amplitude
        norm += amplitude
        amplitude *= 0.5
        cells *= 2
    return [v / norm for v in total]


def smoke_puff(size, seed, power, fray, core):
    """
    One soft-edged smoke billboard.

    The alpha is a radial falloff whose *radius* is perturbed by fractal noise, rather than a clean
    disc multiplied by noise. Perturbing the radius is what makes the edge fray into wisps: the
    boundary itself becomes irregular, so the puff has no detectable circular outline. Multiplying a
    disc by noise instead leaves the circle's edge intact and merely mottles the interior, which is
    what makes cheap smoke read as a cluster of grey balls.

    `core` lifts the centre so overlapping billboards accumulate into something opaque enough to hide
    what is behind them, which the reveal depends on.
    """
    noise = fractal_noise(seed, size, 4)
    pixels = []
    centre = (size - 1) / 2.0
    max_distance = centre + 0.5
    for y in range(size):
        for x in range(size):
            dx = (x - centre) / max_distance
            dy = (y - centre) / max_distance
            distance = math.sqrt(dx * dx + dy * dy)

            # Noise displaces the effective edge inward/outward by up to `fray`.
            n = noise[y * size + x]
            edge = 1.0 + fray * (n - 0.5) * 2.0
            value = (edge - distance) / max(1e-6, edge)

            if value <= 0.0:
                alpha = 0.0
            else:
                alpha = value ** power
                # Denser middle, so a stack of these obscures rather than veils.
                alpha = min(1.0, alpha * (1.0 + core * max(0.0, 1.0 - distance * 1.6)))
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

    # ---- Summoning smoke ----
    # Larger than the energy particles: these are drawn at 2-6 blocks across, where a 16px sprite
    # shows its own pixel grid. Four variants so a cloud of them does not repeat one silhouette;
    # the particle picks one at spawn.
    for index, seed in enumerate((0x5EED1, 0x5EED2, 0x5EED3, 0x5EED4)):
        write_png(
            os.path.join(OUT_DIR, "smoke_puff_{}.png".format(index)),
            smoke_puff(32, seed, power=1.25, fray=0.42, core=0.55),
            32,
            32,
        )

    # Ground fog: wider, flatter falloff and a heavier fray, so the rolling layer has a ragged
    # leading edge instead of a visible line sweeping outward.
    write_png(
        os.path.join(OUT_DIR, "ground_fog.png"),
        smoke_puff(32, 0xF0C1, power=0.85, fray=0.55, core=0.18),
        32,
        32,
    )


if __name__ == "__main__":
    main()
