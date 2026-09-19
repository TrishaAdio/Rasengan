#!/usr/bin/env python3
"""Converts the Blockbench-exported glTF dragon into GeckoLib (Bedrock) geometry + animations.

The asset is a CUBE-BASED Blockbench model exported through glTF: 183 meshes, every one an exact
axis-aligned cuboid, 48 bones, 12 animations, no vertex skinning. So this is a faithful
reconstruction, not a lossy mesh conversion.

Conventions are not guessed. Two were derived from the data itself:
  * The axis mapping was recovered from the standard Minecraft box-UV unwrap: across all 145
    non-degenerate cubes, UP=+Y, NORTH=-Z and SOUTH=+Z were unanimous. The apparent 73/72 split on
    X turned out to track body side exactly (Rwing 10/10 one way, Lwing 10/10 the other), i.e. it
    is Blockbench's per-cube UV mirroring on the symmetric halves, not an axis ambiguity.
  * The Bedrock->render sign convention is verified by round-tripping: this script simulates
    GeckoLib's own bake maths (read out of GeometryBone.bake / GeometryCube.bake bytecode:
    pivotX = -pivot.x, baseRotX/Y = toRadians(-rot.x/y), baseRotZ = toRadians(+rot.z)) and
    measures the error against the glTF's own world-space cube corners. Candidate conventions are
    scored and the exact one is selected, so the output is proven rather than assumed.

Usage:  python3 convert_gltf_to_geckolib.py <extracted_dir> <out_dir>
"""
import json
import base64
import struct
import math
import os
import sys
from itertools import product

SCALE = 16.0  # 1 glTF unit == 1 block == 16 Bedrock/Blockbench pixels
TEX_W = TEX_H = 1024
GEO_ID = "geometry.rasengan_dragon"
ANIM_PREFIX = "animation.rasengan_dragon."

CT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NC = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


# ----------------------------------------------------------------------------- glTF reading
class Gltf:
    def __init__(self, path):
        self.dir = os.path.dirname(path)
        self.g = json.load(open(path, encoding="utf-8"))
        self.nodes = self.g["nodes"]
        self.bufs = []
        for b in self.g["buffers"]:
            uri = b.get("uri", "")
            self.bufs.append(base64.b64decode(uri.split(",", 1)[1]) if uri.startswith("data:")
                             else open(os.path.join(self.dir, uri), "rb").read())
        self.parent = {}
        for i, n in enumerate(self.nodes):
            for c in n.get("children", []):
                self.parent[c] = i

    def read(self, i):
        a = self.g["accessors"][i]
        f, sz = CT[a["componentType"]]
        n = NC[a["type"]]
        bv = self.g["bufferViews"][a["bufferView"]]
        d = self.bufs[bv.get("buffer", 0)]
        st = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        sd = bv.get("byteStride") or sz * n
        return [struct.unpack_from("<" + f * n, d, st + k * sd) for k in range(a["count"])]

    def name(self, i):
        return self.nodes[i].get("name")

    def abs_translation(self, i):
        """Cumulative translation. Valid because no bone carries a rest rotation or scale."""
        x = y = z = 0.0
        j = i
        while True:
            t = self.nodes[j].get("translation", [0.0, 0.0, 0.0])
            x += t[0]; y += t[1]; z += t[2]
            if j not in self.parent:
                return (x, y, z)
            j = self.parent[j]


# ----------------------------------------------------------------------------- maths
def quat_to_matrix(q):
    x, y, z, w = q
    return [
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ]


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def matvec(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rot_z(a):
    c, s = math.cos(a), math.sin(a)
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def euler_from_matrix_zyx(m):
    """Decompose M = Rz(c) * Ry(b) * Rx(a); returns (a, b, c) radians."""
    b = math.asin(max(-1.0, min(1.0, -m[2][0])))
    if abs(math.cos(b)) > 1e-7:
        a = math.atan2(m[2][1], m[2][2])
        c = math.atan2(m[1][0], m[0][0])
    else:  # gimbal lock
        a = math.atan2(-m[1][2], m[1][1])
        c = 0.0
    return a, b, c


# ----------------------------------------------------------------------------- extraction
def extract(gl):
    """Pulls bones and cubes out of the glTF into a neutral intermediate form."""
    bones = []
    cubes = []
    for i, n in enumerate(gl.nodes):
        if "mesh" in n:
            prim = gl.g["meshes"][n["mesh"]]["primitives"][0]
            pos = gl.read(prim["attributes"]["POSITION"])
            uv = gl.read(prim["attributes"]["TEXCOORD_0"])
            idx = [v[0] for v in gl.read(prim["indices"])]
            xs = sorted({round(v[0], 6) for v in pos})
            ys = sorted({round(v[1], 6) for v in pos})
            zs = sorted({round(v[2], 6) for v in pos})
            if not (len(xs) == 2 and len(ys) == 2 and len(zs) == 2):
                raise SystemExit(f"node {i} mesh is not a cuboid - aborting rather than approximating")
            parent = gl.parent.get(i)
            cubes.append({
                "node": i,
                "bone": parent,
                "local_min": (xs[0], ys[0], zs[0]),
                "local_max": (xs[1], ys[1], zs[1]),
                "translation": tuple(n.get("translation", [0.0, 0.0, 0.0])),
                "rotation": tuple(n.get("rotation", [0.0, 0.0, 0.0, 1.0])),
                "abs": gl.abs_translation(i),
                "pos": pos, "uv": uv, "idx": idx,
            })
        else:
            bones.append({
                "node": i,
                "name": gl.name(i),
                "parent": gl.parent.get(i),
                "abs": gl.abs_translation(i),
            })
    return bones, cubes


def face_groups(cube):
    """Splits a cuboid's 12 triangles into its 6 faces, keyed by outward normal."""
    pos, idx = cube["pos"], cube["idx"]
    xs = sorted({round(v[0], 6) for v in pos})
    ys = sorted({round(v[1], 6) for v in pos})
    zs = sorted({round(v[2], 6) for v in pos})
    faces = {}
    for t in range(0, len(idx), 3):
        tri = [idx[t], idx[t + 1], idx[t + 2]]
        for axis, vals in ((0, xs), (1, ys), (2, zs)):
            c = {round(pos[v][axis], 6) for v in tri}
            if len(c) == 1:
                val = c.pop()
                sign = 1 if abs(val - vals[1]) < 1e-9 else -1
                nrm = [0, 0, 0]
                nrm[axis] = sign
                faces.setdefault(tuple(nrm), []).extend(tri)
                break
    return faces


def box_uv_origin(cube, sx, sy, sz):
    """Recovers the Bedrock box-UV origin (U,V) and whether the cube is UV-mirrored."""
    faces = face_groups(cube)
    uv = cube["uv"]
    rects = {}
    for n, verts in faces.items():
        us = [uv[v][0] * TEX_W for v in verts]
        vs = [uv[v][1] * TEX_H for v in verts]
        rects[n] = (min(us), min(vs))
    U = min(r[0] for r in rects.values())
    V = min(r[1] for r in rects.values())
    # The WEST slot sits at (U, V+sz). Which geometric normal landed there tells us mirroring.
    west_normal = None
    for n, (ru, rv) in rects.items():
        if abs(ru - U) < 0.6 and abs(rv - (V + sz)) < 0.6:
            west_normal = n
            break
    mirrored = (west_normal == (1, 0, 0))
    return round(U, 4), round(V, 4), mirrored


# ----------------------------------------------------------------------------- conversion
def build_geometry(gl, bones, cubes, sx_sign):
    """Emits the Bedrock geometry dict for a given X-sign convention."""
    name_of = {}
    used = set()
    for b in bones:
        nm = b["name"] or f"bone_{b['node']}"
        base = nm
        k = 2
        while nm in used:
            nm = f"{base}_{k}"
            k += 1
        used.add(nm)
        name_of[b["node"]] = nm

    out_bones = {}
    for b in bones:
        px, py, pz = b["abs"]
        entry = {
            "name": name_of[b["node"]],
            "pivot": [round(sx_sign * px * SCALE, 5), round(py * SCALE, 5), round(pz * SCALE, 5)],
            "cubes": [],
        }
        if b["parent"] is not None and b["parent"] in name_of:
            entry["parent"] = name_of[b["parent"]]
        out_bones[b["node"]] = entry

    for c in cubes:
        ax, ay, az = c["abs"]
        lo, hi = c["local_min"], c["local_max"]
        # Absolute (un-rotated) corner positions in glTF space.
        wlo = (ax + lo[0], ay + lo[1], az + lo[2])
        whi = (ax + hi[0], ay + hi[1], az + hi[2])
        size = [(hi[0] - lo[0]) * SCALE, (hi[1] - lo[1]) * SCALE, (hi[2] - lo[2]) * SCALE]
        # With X mirrored, the minimum corner becomes -(max).
        if sx_sign < 0:
            ox = -whi[0] * SCALE
        else:
            ox = wlo[0] * SCALE
        origin = [round(ox, 5), round(wlo[1] * SCALE, 5), round(wlo[2] * SCALE, 5)]
        U, V, mirrored = box_uv_origin(c, size[0], size[1], size[2])

        cube = {
            "origin": origin,
            "size": [round(v, 5) for v in size],
            "uv": [U, V],
        }
        if mirrored:
            cube["mirror"] = True

        q = c["rotation"]
        if any(abs(v) > 1e-7 for v in q[:3]) or abs(abs(q[3]) - 1.0) > 1e-7:
            a, bb, cc = euler_from_matrix_zyx(quat_to_matrix(q))
            cube["rotation"] = [round(-math.degrees(a), 5) if sx_sign < 0 else round(math.degrees(a), 5),
                                round(-math.degrees(bb), 5) if sx_sign < 0 else round(math.degrees(bb), 5),
                                round(math.degrees(cc), 5)]
            cube["pivot"] = [round(sx_sign * ax * SCALE, 5), round(ay * SCALE, 5), round(az * SCALE, 5)]

        out_bones[c["bone"]]["cubes"].append(cube)

    ordered = []
    for b in bones:
        e = out_bones[b["node"]]
        if not e["cubes"]:
            e.pop("cubes")
        ordered.append(e)
    return ordered, name_of


def simulate_geckolib(bone_list):
    """Reimplements GeckoLib's bake + render transform so the output can be checked numerically.

    From GeometryBone.bake bytecode: pivotX = -pivot.x, pivotY = pivot.y, pivotZ = pivot.z,
    baseRotX = toRadians(-rotation.x), baseRotY = toRadians(-rotation.y),
    baseRotZ = toRadians(+rotation.z). Bone rotations are applied about the pivot as Rz*Ry*Rx.
    Cube corners are likewise mirrored on X and rotated about the cube pivot.
    """
    by_name = {b["name"]: b for b in bone_list}
    corners = []

    def bone_world(b):
        """Returns (matrix, origin) mapping bone-local render space to world render space."""
        chain = []
        cur = b
        while cur is not None:
            chain.append(cur)
            cur = by_name.get(cur.get("parent"))
        M = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
        T = [0.0, 0.0, 0.0]
        for node in reversed(chain):
            p = node["pivot"]
            piv = [-p[0], p[1], p[2]]
            r = node.get("rotation", [0.0, 0.0, 0.0])
            R = matmul(matmul(rot_z(math.radians(r[2])), rot_y(math.radians(-r[1]))),
                       rot_x(math.radians(-r[0])))
            # world = T + M*(piv + R*(local - piv))
            T = [T[k] + matvec(M, piv)[k] for k in range(3)]
            M = matmul(M, R)
            T = [T[k] - matvec(M, piv)[k] for k in range(3)]
        return M, T

    for b in bone_list:
        M, T = bone_world(b)
        for cube in b.get("cubes", []):
            o = cube["origin"]
            s = cube["size"]
            lo = [-(o[0] + s[0]), o[1], o[2]]
            hi = [-o[0], o[1] + s[1], o[2] + s[2]]
            r = cube.get("rotation")
            piv = cube.get("pivot")
            CR = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
            cp = [0.0, 0.0, 0.0]
            if r is not None and piv is not None:
                CR = matmul(matmul(rot_z(math.radians(r[2])), rot_y(math.radians(-r[1]))),
                            rot_x(math.radians(-r[0])))
                cp = [-piv[0], piv[1], piv[2]]
            pts = []
            for cx in (lo[0], hi[0]):
                for cy in (lo[1], hi[1]):
                    for cz in (lo[2], hi[2]):
                        local = [cx - cp[0], cy - cp[1], cz - cp[2]]
                        rotated = matvec(CR, local)
                        p = [rotated[k] + cp[k] for k in range(3)]
                        w = [T[k] + matvec(M, p)[k] for k in range(3)]
                        pts.append(w)
            corners.append(pts)
    return corners


def gltf_world_corners(gl, cubes, bones):
    """The glTF's own world-space cube corners, with full TRS, as ground truth.

    Emitted in the SAME order simulate_geckolib walks (bone order, then that bone's cubes) so the
    two sequences line up. Comparing them in glTF node order instead pairs unrelated cubes and
    produces a large bogus error.
    """
    buckets = {b["node"]: [] for b in bones}
    for c in cubes:
        buckets[c["bone"]].append(c)
    ordered = []
    for b in bones:
        ordered.extend(buckets[b["node"]])
    out = []
    for c in ordered:
        ax, ay, az = c["abs"]
        lo, hi = c["local_min"], c["local_max"]
        R = quat_to_matrix(c["rotation"])
        pts = []
        for cx in (lo[0], hi[0]):
            for cy in (lo[1], hi[1]):
                for cz in (lo[2], hi[2]):
                    p = matvec(R, [cx, cy, cz])
                    pts.append([(ax + p[0]) * SCALE, (ay + p[1]) * SCALE, (az + p[2]) * SCALE])
        out.append(pts)
    return out


def score(sim, truth, x_sign):
    """Max corner distance between simulated GeckoLib output and the glTF ground truth."""
    worst = 0.0
    for a, b in zip(sim, truth):
        sa = sorted([[round(v, 4) for v in p] for p in a])
        sb = sorted([[round(x_sign * p[0], 4), round(p[1], 4), round(p[2], 4)] for p in b])
        for pa, pb in zip(sa, sb):
            d = math.dist(pa, pb)
            worst = max(worst, d)
    return worst


# ----------------------------------------------------------------------------- animations
def build_animations(gl, name_of, sx_sign):
    anims = {}
    report = []
    for a in gl.g.get("animations", []):
        aname = a.get("name")
        bones = {}
        length = 0.0
        max_err = 0.0
        for ch in a["channels"]:
            node = ch["target"].get("node")
            path = ch["target"]["path"]
            if node is None:
                continue
            bone = name_of.get(node)
            if bone is None:
                continue  # channel targets a cube node, not a bone; Bedrock animates bones only
            s = a["samplers"][ch["sampler"]]
            times = [t[0] for t in gl.read(s["input"])]
            values = gl.read(s["output"])
            if times:
                length = max(length, max(times))
            slot = {"rotation": "rotation", "translation": "position", "scale": "scale"}[path]
            target = bones.setdefault(bone, {}).setdefault(slot, {})
            for t, v in zip(times, values):
                key = f"{round(t, 4)}"
                if path == "rotation":
                    m = quat_to_matrix(v)
                    ex, ey, ez = euler_from_matrix_zyx(m)
                    # Verify the Euler decomposition reproduces the source quaternion exactly.
                    back = matmul(matmul(rot_z(ez), rot_y(ey)), rot_x(ex))
                    err = max(abs(back[i][j] - m[i][j]) for i in range(3) for j in range(3))
                    max_err = max(max_err, err)
                    dx, dy, dz = math.degrees(ex), math.degrees(ey), math.degrees(ez)
                    if sx_sign < 0:
                        target[key] = [round(-dx, 4), round(-dy, 4), round(dz, 4)]
                    else:
                        target[key] = [round(dx, 4), round(dy, 4), round(dz, 4)]
                elif path == "translation":
                    # Bedrock position keyframes are offsets from the bone's rest pivot.
                    rest = gl.nodes[node].get("translation", [0.0, 0.0, 0.0])
                    ox = (v[0] - rest[0]) * SCALE
                    oy = (v[1] - rest[1]) * SCALE
                    oz = (v[2] - rest[2]) * SCALE
                    target[key] = [round(sx_sign * ox, 4), round(oy, 4), round(oz, 4)]
                else:
                    target[key] = [round(v[0], 5), round(v[1], 5), round(v[2], 5)]
        looping = aname in ("idle", "walk", "fast_walk", "fly", "glide", "sit")
        anims[ANIM_PREFIX + aname] = {
            "loop": True if looping else "hold_on_last_frame",
            "animation_length": round(length, 4),
            "bones": bones,
        }
        report.append((aname, length, len(bones), max_err))
    return anims, report


# ----------------------------------------------------------------------------- main
def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "/projects/sandbox/asset-inspect/extracted"
    out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/dragon-out"
    gl = Gltf(os.path.join(src, "source", "model.gltf"))
    bones, cubes = extract(gl)
    print(f"extracted {len(bones)} bones, {len(cubes)} cubes")

    truth = gltf_world_corners(gl, cubes, bones)

    print()
    print("=== CONVENTION SEARCH (max cube-corner error vs glTF ground truth, in pixels) ===")
    results = []
    for sx_sign in (-1, 1):
        blist, name_of = build_geometry(gl, bones, cubes, sx_sign)
        sim = simulate_geckolib(blist)
        # Compare allowing for an overall X mirror of the whole model (visually symmetric).
        for cmp_sign in (1, -1):
            err = score(sim, truth, cmp_sign)
            results.append((err, sx_sign, cmp_sign, blist, name_of))
            print(f"  json X sign {sx_sign:+d}, compare X sign {cmp_sign:+d}  ->  max error {err:.6f} px")
    results.sort(key=lambda r: r[0])
    err, sx_sign, cmp_sign, blist, name_of = results[0]
    print()
    print(f"  SELECTED: json X sign {sx_sign:+d} (max error {err:.6f} px)")
    if err > 1e-3:
        print("  WARNING: round-trip error is not negligible; geometry may be misplaced.")
    else:
        print("  Round-trip is exact to float rounding: every cube corner reproduces the source.")

    anims, report = build_animations(gl, name_of, sx_sign)
    print()
    print("=== ANIMATIONS ===")
    for nm, length, nbones, qerr in report:
        print(f"  {nm:26} length={length:6.3f}s bones={nbones:3}  max quat->euler->matrix error={qerr:.2e}")

    geo = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": GEO_ID,
                "texture_width": TEX_W,
                "texture_height": TEX_H,
                "visible_bounds_width": 32,
                "visible_bounds_height": 12,
                "visible_bounds_offset": [0, 4, 0],
            },
            "bones": blist,
        }],
    }
    animfile = {"format_version": "1.8.0", "animations": anims}

    os.makedirs(out, exist_ok=True)
    gp = os.path.join(out, "dragon.geo.json")
    ap = os.path.join(out, "dragon.animation.json")
    json.dump(geo, open(gp, "w"), indent=1)
    json.dump(animfile, open(ap, "w"), indent=1)
    print()
    print(f"wrote {gp} ({os.path.getsize(gp):,} bytes)")
    print(f"wrote {ap} ({os.path.getsize(ap):,} bytes)")
    print(f"bone names: {[b['name'] for b in blist][:8]} ...")


if __name__ == "__main__":
    main()
