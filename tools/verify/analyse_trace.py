#!/usr/bin/env python3
"""Analyses the headless server trace into the physical-accuracy evidence.

Reads /projects/sandbox/logs/server-trace.log, which contains one TRACE_PROJ / TRACE_GOLEM
line per server tick per entity, emitted by the trace datapack.
"""
import re
import sys
import math

LOG = "/projects/sandbox/logs/server-trace.log"

VEC = re.compile(r"\[([-0-9.eE]+)d,([-0-9.eE]+)d,([-0-9.eE]+)d\]")
PROJ = re.compile(r"TRACE_PROJ life=(\d+) travelled=([-0-9.eE]+) pos=(\[[^\]]*\]) motion=(\[[^\]]*\])")
GOLEM = re.compile(r"TRACE_GOLEM pos=(\[[^\]]*\]) motion=(\[[^\]]*\])")
MARK = re.compile(r"MARK_([A-Z_0-9]+)")


def vec(s):
    m = VEC.search(s)
    return tuple(float(m.group(i)) for i in (1, 2, 3)) if m else None


def load():
    """Splits the log into (marker, events) sections in order."""
    sections = []
    current = ("PREAMBLE", [])
    with open(LOG, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            mk = MARK.search(line)
            if mk and "TRACE_" not in line:
                sections.append(current)
                current = (mk.group(1), [])
                continue
            pm = PROJ.search(line)
            if pm:
                current[1].append(("P", int(pm.group(1)), float(pm.group(2)),
                                   vec(pm.group(3)), vec(pm.group(4))))
                continue
            gm = GOLEM.search(line)
            if gm:
                current[1].append(("G", None, None, vec(gm.group(1)), vec(gm.group(2))))
    sections.append(current)
    return sections


def head(t):
    print()
    print("=" * 74)
    print(t)
    print("=" * 74)


def analyse_trajectory(name, events):
    rows = [e for e in events if e[0] == "P"]
    # Keep only the first contiguous flight (life increasing from its minimum).
    if not rows:
        print(f"  {name}: no projectile samples")
        return
    head(f"Per-tick trajectory: {name}")
    print(f"{'life':>5} {'x':>22} {'y':>22} {'z':>22} {'travelled':>20}")
    xs, ys, zs = [], [], []
    for kind, life, travelled, pos, mot in rows:
        xs.append(pos[0]); ys.append(pos[1]); zs.append(pos[2])
        if life < 6 or life % 20 == 0 or life >= rows[-1][1] - 1:
            print(f"{life:>5} {pos[0]:>22.17g} {pos[1]:>22.17g} {pos[2]:>22.17g} {travelled:>20.17g}")

    print()
    print(f"  samples                    : {len(rows)} ticks")
    print(f"  x: min={min(xs):.17g} max={max(xs):.17g} spread={max(xs)-min(xs):.3e} blocks")
    print(f"  y: min={min(ys):.17g} max={max(ys):.17g} spread={max(ys)-min(ys):.3e} blocks")
    print(f"  LATERAL/VERTICAL DEVIATION : x {max(xs)-min(xs):.3e}, y {max(ys)-min(ys):.3e} blocks")

    # Per-tick z step: constant velocity means a constant step with no accumulating error.
    steps = [zs[i + 1] - zs[i] for i in range(len(zs) - 1)]
    if steps:
        print(f"  z step: min={min(steps):.17g} max={max(steps):.17g}")
        print(f"  z step spread              : {max(steps)-min(steps):.3e} blocks/tick")
        # Residual against a perfect straight line fitted from the first sample + nominal speed.
        v = steps[0]
        worst = 0.0
        for i, z in enumerate(zs):
            predicted = zs[0] + v * i
            worst = max(worst, abs(z - predicted))
        print(f"  worst residual vs exact line z0 + {v:.17g}*t : {worst:.3e} blocks")
        print(f"  (double has ~{2.2e-16*abs(zs[-1]):.1e} blocks of resolution at z={zs[-1]:.1f})")
    print(f"  VERDICT: "
          + ("PASS - dead straight, no gravity/drag/drift"
             if max(xs) - min(xs) < 1e-9 and max(ys) - min(ys) < 1e-9 else "FAIL - path deviates"))


def analyse_ballistic(name, events):
    rows = [e for e in events if e[0] == "G"]
    if not rows:
        print(f"  {name}: no golem samples")
        return None
    # The launch is the first tick where |vz| becomes large.
    launch = None
    for i, (_, _, _, pos, mot) in enumerate(rows):
        if mot and abs(mot[2]) > 1.0:
            launch = i
            break
    if launch is None:
        print(f"  {name}: no launch detected")
        return None

    flight = rows[launch:]
    z0 = flight[0][3][2]
    y0 = flight[0][3][1]

    head(f"Ballistic arc: {name}")
    print(f"{'t':>4} {'y (height)':>14} {'dy':>12} {'z':>16} {'vy':>14} {'vz':>12}")
    prev_y = None
    peak = -1e9
    peak_t = 0
    for t, (_, _, _, pos, mot) in enumerate(flight[:70]):
        dy = "" if prev_y is None else f"{pos[1]-prev_y:12.6f}"
        if pos[1] > peak:
            peak = pos[1]
            peak_t = t
        if t < 8 or t % 6 == 0 or t > 44:
            print(f"{t:>4} {pos[1]:>14.6f} {dy:>12} {pos[2]:>16.6f} {mot[1]:>14.6f} {mot[2]:>12.6f}")
        prev_y = pos[1]

    # Landing: first tick after the peak where vertical motion stops changing the height.
    land_t = None
    for t in range(peak_t + 1, len(flight)):
        if abs(flight[t][3][1] - flight[t - 1][3][1]) < 1e-6:
            land_t = t
            break
    final = flight[min(land_t if land_t else len(flight) - 1, len(flight) - 1)][3]

    print()
    print(f"  launch velocity            : vy={flight[0][4][1]:.6f} vz={flight[0][4][2]:.6f} blocks/tick")
    print(f"  start                      : y={y0:.6f} z={z0:.6f}")
    print(f"  peak height                : {peak:.6f} (rise of {peak-y0:.3f} blocks) at t={peak_t} ticks")
    print(f"  landed at t                : {land_t} ticks ({(land_t or 0)/20.0:.2f} s airtime)")
    print(f"  final position             : y={final[1]:.6f} z={final[2]:.6f}")
    dist = final[2] - z0
    print(f"  HORIZONTAL LANDING DISTANCE: {dist:.4f} blocks")
    print(f"  within 200-250 target      : {'YES' if 200 <= dist <= 250 else 'NO'}")

    # Compare the measured vertical path against Minecraft's own air physics model.
    # Order matters and is visible in the data: the entity moves by the CURRENT vy first, and the
    # damping is applied afterwards (t=1 moves the full 2.0, and only then vy becomes 1.8816).
    vy = flight[0][4][1]
    y = y0
    worst = 0.0
    # Compare only while genuinely airborne. The tick of ground contact clamps y to the surface,
    # so including it would measure the collision, not the arc.
    n = max(2, (land_t or len(flight)) - 1)
    for t in range(1, n):
        y = y + vy
        vy = (vy - 0.08) * 0.98
        worst = max(worst, abs(y - flight[t][3][1]))
    print(f"  max |measured y - projectile-motion model y| over {n} airborne ticks: {worst:.3e} blocks")
    print(f"  model: y <- y + vy ; vy <- (vy - 0.08) * 0.98   (Minecraft living-entity air physics)")
    print(f"  VERDICT: " + ("PASS - vertical arc is a clean ballistic parabola"
                            if worst < 1e-4 else f"CHECK - deviation {worst:.6f}"))

    # Horizontal decay per tick: this is where the distance shortfall comes from.
    vzs = [f[4][2] for f in flight[:12]]
    ratios = [vzs[i + 1] / vzs[i] for i in range(len(vzs) - 1) if abs(vzs[i]) > 1e-9]
    print()
    print(f"  horizontal vz per tick     : "
          + ", ".join(f"{v:.4f}" for v in vzs[:8]))
    print(f"  tick-to-tick vz ratio      : "
          + ", ".join(f"{r:.4f}" for r in ratios[:7]))
    print(f"  FIRST tick ratio           : {ratios[0]:.4f}  <-- ground friction (0.6 block * 0.91 = 0.546)")
    print(f"  subsequent ratio           : {ratios[1]:.4f}  <-- pure air friction (0.91)")
    return dist


def analyse_nogravity(name, events):
    rows = [e for e in events if e[0] == "G"]
    launch = None
    for i, (_, _, _, pos, mot) in enumerate(rows):
        if mot and abs(mot[2]) > 1.0:
            launch = i
            break
    if launch is None:
        return
    flight = rows[launch:]
    z0 = flight[0][3][2]
    zf = flight[-1][3][2]
    head(f"Horizontal convergence (gravity disabled): {name}")
    print(f"  launch vz                  : {flight[0][4][2]:.6f} blocks/tick")
    print(f"  z start / z final          : {z0:.6f} / {zf:.6f}")
    print(f"  horizontal travel          : {zf - z0:.4f} blocks")
    theory = 20.5 / (1.0 - 0.91)
    print(f"  theoretical limit vx/(1-0.91) = 20.5/0.09 = {theory:.4f} blocks")
    print(f"  difference from theory     : {abs((zf - z0) - theory):.4f} blocks "
          f"({100*abs((zf-z0)-theory)/theory:.3f}%)")
    print(f"  final y                    : {flight[-1][3][1]:.4f} "
          f"(never lands - gravity is off, so NO parabola here by construction)")


def main():
    sections = load()
    dists = []
    for name, events in sections:
        if name in ("TRAJECTORY_SHURIKEN", "TRAJECTORY_RASENGAN"):
            analyse_trajectory(name, events)
        elif name == "KNOCKBACK_NOGRAVITY":
            analyse_nogravity(name, events)
        elif name.startswith("BALLISTIC_TRIAL_") and not name.endswith("_END"):
            d = analyse_ballistic(name, events)
            if d is not None:
                dists.append((name, d))
    if dists:
        head("Knockback landing distance across independent trials")
        for n, d in dists:
            print(f"  {n:<22} {d:>10.4f} blocks   {'in range' if 200 <= d <= 250 else 'OUT OF RANGE'}")
        vals = [d for _, d in dists]
        mean = sum(vals) / len(vals)
        sd = math.sqrt(sum((v - mean) ** 2 for v in vals) / len(vals)) if len(vals) > 1 else 0.0
        print(f"  {'trials':<22} {len(vals):>10}")
        print(f"  {'mean':<22} {mean:>10.4f} blocks")
        print(f"  {'spread (max-min)':<22} {max(vals)-min(vals):>10.4f} blocks")
        print(f"  {'std dev':<22} {sd:>10.4f} blocks")
        print(f"  ALL WITHIN 200-250: {'YES' if all(200 <= v <= 250 for v in vals) else 'NO'}")


if __name__ == "__main__":
    main()
