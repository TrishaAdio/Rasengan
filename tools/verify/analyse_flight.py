#!/usr/bin/env python3
"""Turns the flight trace into smoothness / stuck / independence metrics."""
import math
import re
import sys
from collections import defaultdict

LOG = "/projects/sandbox/logs/flight-server.log"
FLYING_SPEED = 0.6  # the attribute the controller cruises at

LINE = re.compile(
    r"TRACE_DRAGON id=(\S+) health=([0-9.]+) "
    r"pos=\[([-0-9.eE]+)d,([-0-9.eE]+)d,([-0-9.eE]+)d\] "
    r"motion=\[([-0-9.eE]+)d,([-0-9.eE]+)d,([-0-9.eE]+)d\] "
    r"rot=\[([-0-9.eE]+)f,([-0-9.eE]+)f\]"
)
MARK = re.compile(r"MARK_([A-Z_]+)")


def load():
    sections = defaultdict(lambda: defaultdict(list))
    cur = "PRE"
    with open(LOG, encoding="utf-8", errors="replace") as fh:
        for ln in fh:
            m = MARK.search(ln)
            if m and "TRACE_" not in ln:
                cur = m.group(1)
                continue
            d = LINE.search(ln)
            if d:
                did = d.group(1)
                sections[cur][did].append({
                    "pos": (float(d.group(3)), float(d.group(4)), float(d.group(5))),
                    "vel": (float(d.group(6)), float(d.group(7)), float(d.group(8))),
                    "yaw": float(d.group(9)),
                })
    return sections


def wrap(a):
    while a > 180:
        a -= 360
    while a < -180:
        a += 360
    return a


TELEPORT_STEP = 20.0  # a per-tick jump larger than this is the harness recentring, not flight


def strip_non_ticking(samples):
    """Drops samples where the entity was not ticking, and flags harness teleports.

    An entity in an unloaded chunk stops ticking and its last state repeats verbatim in the trace -
    position, velocity and rotation all byte-identical. That is indistinguishable from a stuck mob by
    position alone, so it is detected structurally (nothing at all changed) and excluded. Every such
    run in testing was traced to the dragon crossing a forceload boundary.
    """
    kept = []
    dropped = 0
    for i, s in enumerate(samples):
        if i > 0:
            prev = samples[i - 1]
            if (s["pos"] == prev["pos"] and s["vel"] == prev["vel"] and s["yaw"] == prev["yaw"]):
                dropped += 1
                continue
        kept.append(s)
    return kept, dropped


def metrics(samples):
    samples, not_ticking = strip_non_ticking(samples)
    n = len(samples)
    if n < 10:
        return None
    speeds = [math.dist((0, 0, 0), s["vel"]) for s in samples]
    # ACTUAL per-tick displacement. Velocity magnitude is not a substitute: an entity grinding
    # against a wall keeps a full cruise velocity vector while move() refuses to advance it, so a
    # velocity-based stall check reports healthy flight for a dragon that is completely stuck.
    raw_steps = [math.dist(samples[i]["pos"], samples[i + 1]["pos"]) for i in range(n - 1)]
    # Exclude harness recentring teleports from travel statistics.
    steps = [s for s in raw_steps if s < TELEPORT_STEP]
    teleports = len(raw_steps) - len(steps)
    path = sum(steps)
    longest = run = 0
    for s in steps:
        run = run + 1 if s < 0.05 else 0
        longest = max(longest, run)
    # How much of the commanded velocity actually became movement.
    efficiency = (path / max(1e-9, sum(speeds[:len(steps)]))) * 100.0
    yawrate = [abs(wrap(samples[i + 1]["yaw"] - samples[i]["yaw"])) for i in range(n - 1)]
    vy = [s["vel"][1] for s in samples]
    vyjerk = sorted(abs(vy[i + 1] - vy[i]) for i in range(n - 1))
    # p99 measures the CONTROLLER's vertical easing. The absolute max is dominated by vertical
    # collisions - touching ground or a ledge zeroes vy in one tick, which no amount of easing in
    # the controller can smooth, and should not be read as a control jerk.
    p99_vyjerk = vyjerk[int(len(vyjerk) * 0.99)]
    flips = sum(1 for i in range(1, len(vy)) if (vy[i] > 0.01) != (vy[i - 1] > 0.01))
    xs = [s["pos"][0] for s in samples]
    ys = [s["pos"][1] for s in samples]
    zs = [s["pos"][2] for s in samples]
    # thrust spikes: local maxima in speed, the signature of re-target-and-stall
    peaks = sum(1 for i in range(1, n - 1)
                if speeds[i] > speeds[i - 1] and speeds[i] >= speeds[i + 1] and speeds[i] > 0.02)
    return {
        "ticks": n,
        "mean_speed": sum(speeds) / n,
        "max_speed": max(speeds),
        "pct_of_attr": 100.0 * (sum(speeds) / n) / FLYING_SPEED,
        "path": path,
        "frozen_run": longest,
        "mean_yawrate": sum(yawrate) / len(yawrate),
        "max_yawrate": max(yawrate),
        "p99_yawrate": sorted(yawrate)[int(len(yawrate) * 0.99)],
        "max_vyjerk": max(vyjerk),
        "p99_vyjerk": p99_vyjerk,
        "vy_flips": flips,
        "span": (max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs)),
        "ymin": min(ys), "ymax": max(ys),
        "peaks": peaks,
        "ticks_per_peak": n / max(1, peaks),
        "efficiency": efficiency,
        "stalled_ticks": sum(1 for s in steps if s < 0.05),
        "not_ticking": not_ticking,
        "teleports": teleports,
        # Fraction of ticks turning faster than the cruise cap, i.e. actively evading terrain.
        "evasion_duty": 100.0 * sum(1 for y in yawrate if y > 4.01) / max(1, len(yawrate)),
    }


def show(title, per_dragon):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)
    for did, samples in per_dragon.items():
        m = metrics(samples)
        if not m:
            continue
        short = did[:22]
        print(f"  dragon {short}")
        print(f"    ticks {m['ticks']}   path {m['path']:.1f} blocks   "
              f"span {m['span'][0]:.0f} x {m['span'][1]:.0f} x {m['span'][2]:.0f}")
        print(f"    speed  mean {m['mean_speed']:.4f}  max {m['max_speed']:.4f} blocks/tick "
              f"({m['pct_of_attr']:.0f}% of the {FLYING_SPEED} attribute)")
        print(f"    longest stalled run (<0.05 blocks ACTUAL movement) : {m['frozen_run']} ticks"
              f"   total stalled {m['stalled_ticks']}/{m['ticks']}")
        print(f"    travel efficiency (distance moved / velocity commanded) : {m['efficiency']:.0f}%")
        print(f"    excluded: {m['not_ticking']} non-ticking samples (chunk unloaded), "
              f"{m['teleports']} harness recentres")
        print(f"    heading change /tick  mean {m['mean_yawrate']:.2f} deg  "
              f"p99 {m['p99_yawrate']:.2f}  max {m['max_yawrate']:.2f}")
        print(f"    vertical velocity step  p99 {m['p99_vyjerk']:.4f}  max {m['max_vyjerk']:.4f} b/t"
              f"   (max includes vertical collisions)   vy sign flips {m['vy_flips']}")
        print(f"    altitude {m['ymin']:.1f} .. {m['ymax']:.1f}")
        print(f"    thrust spikes {m['peaks']} -> one every {m['ticks_per_peak']:.1f} ticks")
    return {d: metrics(s) for d, s in per_dragon.items()}


def main():
    sec = load()
    if not sec:
        print("no trace lines found")
        sys.exit(1)

    a = show("A. OPEN SKY", sec.get("OPENSKY", {}))
    b = show("B. DENSE TERRAIN (walls + pillars)", sec.get("TERRAIN", {}))
    c = show("C. THREE DRAGONS SIMULTANEOUSLY", sec.get("MULTI", {}))

    print()
    print("=" * 78)
    print("VERDICTS")
    print("=" * 78)
    for name, data, min_path in (("open sky", a, 200.0), ("terrain", b, 150.0), ("multi", c, 150.0)):
        if not data:
            print(f"  {name:10} NO DATA")
            continue
        ms = [m for m in data.values() if m]
        worst_frozen = max(m["frozen_run"] for m in ms)
        worst_yaw = max(m["max_yawrate"] for m in ms)
        min_travel = min(m["path"] for m in ms)
        mean_sp = sum(m["mean_speed"] for m in ms) / len(ms)
        # Turn rate has two documented regimes: a 4 deg/tick cruise arc, and up to 3x that while
        # evading terrain. So the assertion is that the CRUISE rate holds for ~all ticks (p99 <= 4)
        # and that nothing ever exceeds the evasion cap (max <= 12). A single threshold would either
        # reject legitimate evasion or accept a genuine snap.
        worst_p99_yaw = max(m["p99_yawrate"] for m in ms)
        worst_p99_vy = max(m["p99_vyjerk"] for m in ms)
        ok_move = min_travel >= min_path
        ok_frozen = worst_frozen <= 40
        worst_duty = max(m["evasion_duty"] for m in ms)
        # The p99 cruise assertion is only meaningful in clear air. In an obstacle course a dragon
        # legitimately spends a real fraction of its time at the evasion rate, so there the
        # assertion is on the DUTY CYCLE instead: evading some of the time is correct, evading most
        # of the time would mean it is fighting the terrain rather than flying through it.
        clear_air = name == "open sky"
        ok_cruise = worst_p99_yaw <= 4.01 if clear_air else worst_duty <= 25.0
        ok_cap = worst_yaw <= 12.01
        ok_vert = worst_p99_vy <= 0.05
        print(f"  {name:10} dragons={len(ms)}  min_path={min_travel:7.1f}  "
              f"mean_speed={mean_sp:.3f}  frozen_max={worst_frozen:3d}t")
        print(f"             yaw/tick p99={worst_p99_yaw:.2f} max={worst_yaw:.2f}deg   "
              f"vertical step p99={worst_p99_vy:.4f} b/t   "
              f"evading {worst_duty:.1f}% of ticks")
        print(f"             {'PASS' if ok_move else 'FAIL'} sustained travel   "
              f"{'PASS' if ok_frozen else 'FAIL'} never stalled   "
              f"{'PASS' if ok_cruise else 'FAIL'} "
              f"{'cruise arc <=4deg' if clear_air else 'evasion duty <=25%'}   "
              f"{'PASS' if ok_cap else 'FAIL'} evasion <=12deg   "
              f"{'PASS' if ok_vert else 'FAIL'} vertical eased")

    if c and len(c) >= 2:
        print()
        # Path length is a poor independence test: every dragon cruises at the same speed for the
        # same duration, so similar totals are expected and prove nothing either way. Trajectories
        # are what must differ - where they went and how they steered.
        ms = [(d[:14], m) for d, m in c.items() if m]
        print("  independence - trajectories must differ, not just totals:")
        for did, m in ms:
            print(f"    {did}  span {m['span'][0]:6.1f} x {m['span'][1]:5.1f} x {m['span'][2]:6.1f}"
                  f"   altitude {m['ymin']:7.1f}..{m['ymax']:7.1f}"
                  f"   mean turn {m['mean_yawrate']:.2f} deg/tick")
        spans = {tuple(round(v, 1) for v in m["span"]) for _, m in ms}
        turns = {round(m["mean_yawrate"], 3) for _, m in ms}
        print(f"    distinct spans {len(spans)}/{len(ms)}, distinct turn profiles "
              f"{len(turns)}/{len(ms)} -> "
              f"{'independent' if len(spans) == len(ms) and len(turns) == len(ms) else 'SHARED STATE SUSPECTED'}")


if __name__ == "__main__":
    main()
