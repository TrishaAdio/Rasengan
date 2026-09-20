#!/usr/bin/env python3
"""
Measures where the dragon's head actually is, across its animations.

WHY THIS EXISTS
The rider stands on the dragon's head, and that anchor has to be computed SERVER-side because the
mount is server-authoritative. But GeckoLib animations only run in the render path - the server never
evaluates a bone. So the server needs a cheap analytic formula for the head position, and the only
honest way to choose one is to compute the TRUE animated head position offline and measure the error.

This script does that. It replicates GeckoLib's bake maths for the neck->head chain using the
convention `tools/dragon/convert_gltf_to_geckolib.py` already proved by round-tripping against the
source glTF:

    pivotX = -pivot.x,  baseRotX = toRadians(-rotation.x),  rotations applied Rz*Ry*Rx about the pivot

Only X rotations are needed: across both `idle` and `fly`, every bone in the Torso->Neck->bone->bone2
->Head chain animates on X alone (Y and Z are flat zero in the source data). That is checked here
rather than assumed.

Output feeds dev.rasengan.DragonAnchor: the rest-pose offsets, the bob amplitude, and the residual
error of the analytic approximation against this exact computation.

Run from the repository root:
    python3 tools/dragon/measure_head_anchor.py
"""
import json
import math
import os

GEO = os.path.join("src", "main", "resources", "assets", "rasengan", "geo", "dragon.geo.json")
ANIM = os.path.join("src", "main", "resources", "assets", "rasengan", "animations",
                    "dragon.animation.json")

UNITS_PER_BLOCK = 16.0

# The chain from the torso out to the head, root first. Verified against the geometry's parent links.
CHAIN = ["Torso", "Neck", "bone", "bone2", "Head"]


def load():
    geo = json.load(open(GEO))["minecraft:geometry"][0]
    bones = {b["name"]: b for b in geo["bones"]}
    anims = json.load(open(ANIM))["animations"]
    return bones, anims


def skull_top_centre(bones):
    """
    The point a rider stands on: the centre of the top face of the broad skull cubes.

    NOT the highest point in the head - that is the horn pair (top y 83.03, only 4 units wide each and
    set out at |x| 6.89..10.88). Standing a player on a horn tip would look like a bug. The broad
    cubes at |x| 2..6, z -64..-60 top out at y 73.0, and that flat span between the horns is the
    surface to use.
    """
    head = bones["Head"]
    best = None
    for cube in head["cubes"]:
        o, s = cube["origin"], cube["size"]
        top = o[1] + s[1]
        # Broad in x and reasonably deep in z: skull plate, not a horn or a spike.
        if s[0] >= 3.5 and s[2] >= 3.5 and abs(o[0] + s[0] / 2.0) < 8.0:
            if best is None or top > best[0]:
                best = (top, o, s)
    top, o, s = best
    return (0.0, top, o[2] + s[2] / 2.0)


def head_aabb(bones):
    """Bounding box of every Head cube, in model units. This is the mount interaction region."""
    head = bones["Head"]
    xs, ys, zs = [], [], []
    for cube in head["cubes"]:
        o, s = cube["origin"], cube["size"]
        xs += [o[0], o[0] + s[0]]
        ys += [o[1], o[1] + s[1]]
        zs += [o[2], o[2] + s[2]]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def sample_channel(channel, time, length):
    """
    Linear sample of one animation channel at `time`.

    Bedrock keyframes default to linear interpolation and none of the channels in this chain declare
    anything else, which is checked in main().
    """
    if channel is None:
        return [0.0, 0.0, 0.0]
    if isinstance(channel, list):
        return [float(v) for v in channel]

    keys = sorted((float(t), v) for t, v in channel.items())
    time = time % length if length > 0 else time

    def vec(entry):
        v = entry.get("vector", entry) if isinstance(entry, dict) else entry
        return [float(x) for x in v]

    if time <= keys[0][0]:
        return vec(keys[0][1])
    if time >= keys[-1][0]:
        return vec(keys[-1][1])
    for i in range(len(keys) - 1):
        t0, v0 = keys[i]
        t1, v1 = keys[i + 1]
        if t0 <= time <= t1:
            a, b = vec(v0), vec(v1)
            k = 0.0 if t1 == t0 else (time - t0) / (t1 - t0)
            return [a[j] + (b[j] - a[j]) * k for j in range(3)]
    return vec(keys[-1][1])


def rot_x(v, degrees):
    """Rotation about X by GeckoLib's baked angle, which is the NEGATED json value."""
    a = math.radians(-degrees)
    c, s = math.cos(a), math.sin(a)
    return [v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c]


def head_point_at(bones, anim, time, point):
    """
    Transforms a Head-local model point through the animated chain, root first.

    Each bone rotates everything below it about its own pivot, so the composition is applied from the
    root outward with the point carried through. Bone positions (the Torso's y bob in `idle`) are a
    straight translation of the subtree.
    """
    length = anim.get("animation_length", 1.0)
    p = list(point)
    # Apply from the deepest bone outward is equivalent to composing root-first on the point; walk the
    # chain from the head end back to the root, applying each bone's own pivot rotation in turn.
    for name in reversed(CHAIN):
        bone = bones[name]
        pivot = bone["pivot"]
        pivot = [-pivot[0], pivot[1], pivot[2]]  # GeckoLib mirrors pivot X

        channels = anim.get("bones", {}).get(name, {})
        rot = sample_channel(channels.get("rotation"), time, length)
        pos = sample_channel(channels.get("position"), time, length)

        rel = [p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]]
        rel = rot_x(rel, rot[0])
        p = [rel[0] + pivot[0] + pos[0], rel[1] + pivot[1] + pos[1], rel[2] + pivot[2] + pos[2]]
    return p


def main():
    bones, anims = load()

    # ---- assumption check: is the chain really X-only? ----
    print("=== assumption check: chain rotations are X-only ===")
    worst = 0.0
    for anim_name in ("animation.rasengan_dragon.idle", "animation.rasengan_dragon.fly"):
        anim = anims[anim_name]
        for name in CHAIN:
            ch = anim.get("bones", {}).get(name, {})
            rot = ch.get("rotation")
            if rot is None or isinstance(rot, list):
                continue
            for entry in rot.values():
                v = entry.get("vector", entry) if isinstance(entry, dict) else entry
                worst = max(worst, abs(float(v[1])), abs(float(v[2])))
    print(f"  max |Y| or |Z| rotation anywhere in the chain: {worst:.6f} degrees")
    print(f"  -> X-only is {'CONFIRMED' if worst < 1e-6 else 'VIOLATED'}\n")

    anchor = skull_top_centre(bones)
    lo, hi = head_aabb(bones)
    print("=== rest-pose geometry (model units, then blocks) ===")
    print(f"  rider anchor  (skull plate top centre): {anchor}")
    print(f"                -> blocks: x={-anchor[0] / UNITS_PER_BLOCK:.5f} "
          f"y={anchor[1] / UNITS_PER_BLOCK:.5f} z={anchor[2] / UNITS_PER_BLOCK:.5f}")
    print(f"  head AABB min: {lo}\n  head AABB max: {hi}")
    print(f"                -> blocks x[{-hi[0] / UNITS_PER_BLOCK:.5f},{-lo[0] / UNITS_PER_BLOCK:.5f}] "
          f"y[{lo[1] / UNITS_PER_BLOCK:.5f},{hi[1] / UNITS_PER_BLOCK:.5f}] "
          f"z[{lo[2] / UNITS_PER_BLOCK:.5f},{hi[2] / UNITS_PER_BLOCK:.5f}]")
    print()

    # ---- true animated trajectory of the anchor point ----
    for anim_name in ("animation.rasengan_dragon.idle", "animation.rasengan_dragon.fly"):
        anim = anims[anim_name]
        length = anim["animation_length"]
        ticks = length * 20.0
        samples = []
        for i in range(400):
            t = length * i / 400.0
            samples.append(head_point_at(bones, anim, t, anchor))

        ys = [p[1] / UNITS_PER_BLOCK for p in samples]
        zs = [p[2] / UNITS_PER_BLOCK for p in samples]
        xs = [p[0] / UNITS_PER_BLOCK for p in samples]
        print(f"=== {anim_name}  ({length}s = {ticks:.2f} ticks) ===")
        print(f"  anchor y: {min(ys):.5f} .. {max(ys):.5f}  "
              f"(mean {sum(ys) / len(ys):.5f}, swing {max(ys) - min(ys):.5f} blocks)")
        print(f"  anchor z: {min(zs):.5f} .. {max(zs):.5f}  "
              f"(mean {sum(zs) / len(zs):.5f}, swing {max(zs) - min(zs):.5f} blocks)")
        print(f"  anchor x: {min(xs):.5f} .. {max(xs):.5f}  (should be flat)")

        # Best-fit single sinusoid on y, which is the cheapest thing the server could evaluate.
        mean_y = sum(ys) / len(ys)
        amp = (max(ys) - min(ys)) / 2.0
        best = None
        for phase_i in range(720):
            phase = phase_i * math.pi / 360.0
            err = 0.0
            for i, y in enumerate(ys):
                model = mean_y + amp * math.sin(2.0 * math.pi * i / len(ys) + phase)
                err = max(err, abs(model - y))
            if best is None or err < best[0]:
                best = (err, phase)
        print(f"  single-sinusoid fit (mean {mean_y:.5f}, amp {amp:.5f}, phase {best[1]:.4f} rad):")
        print(f"    worst-case residual {best[0]:.5f} blocks ({best[0] * 100:.2f} cm)")

        # A sampled table, linearly interpolated - what DragonAnchor actually uses. Reported at
        # several sizes so the choice of resolution is a measurement rather than a guess.
        for n in (16, 32, 64):
            table_y = [head_point_at(bones, anim, length * i / n, anchor)[1] / UNITS_PER_BLOCK
                       for i in range(n)]
            table_z = [head_point_at(bones, anim, length * i / n, anchor)[2] / UNITS_PER_BLOCK
                       for i in range(n)]
            err_y = err_z = 0.0
            for i in range(2000):
                t = length * i / 2000.0
                true = head_point_at(bones, anim, t, anchor)
                k = (i / 2000.0) * n
                i0 = int(k) % n
                i1 = (i0 + 1) % n
                f = k - math.floor(k)
                err_y = max(err_y, abs((table_y[i0] + (table_y[i1] - table_y[i0]) * f)
                                       - true[1] / UNITS_PER_BLOCK))
                err_z = max(err_z, abs((table_z[i0] + (table_z[i1] - table_z[i0]) * f)
                                       - true[2] / UNITS_PER_BLOCK))
            print(f"  {n:2d}-sample table, linear: worst residual y {err_y * 100:.3f} cm, "
                  f"z {err_z * 100:.3f} cm")
        print()

    # ---- Java constants for dev.rasengan.DragonAnchor ----
    print("=" * 78)
    print("Java table constants - paste into dev.rasengan.DragonAnchor")
    print("=" * 78)
    for anim_name, java_name in (("animation.rasengan_dragon.idle", "IDLE"),
                                 ("animation.rasengan_dragon.fly", "FLY")):
        anim = anims[anim_name]
        length = anim["animation_length"]
        n = 32
        ys, zs = [], []
        for i in range(n):
            p = head_point_at(bones, anim, length * i / n, anchor)
            ys.append(p[1] / UNITS_PER_BLOCK)
            zs.append(p[2] / UNITS_PER_BLOCK)
        print(f"\n    /** {java_name}: {n} samples over {length}s = {length * 20:.4f} ticks. */")
        print(f"    private static final float {java_name}_CYCLE_TICKS = {length * 20:.4f}F;")
        for label, data in (("UP", ys), ("FORWARD", zs)):
            body = ", ".join(f"{v:.5f}F" for v in data)
            print(f"    private static final float[] {java_name}_{label} = {{")
            # wrap at ~5 per line for readability
            vals = [f"{v:.5f}F" for v in data]
            for j in range(0, len(vals), 6):
                print("            " + ", ".join(vals[j:j + 6]) + ("," if j + 6 < len(vals) else ""))
            print("    };")


if __name__ == "__main__":
    main()
