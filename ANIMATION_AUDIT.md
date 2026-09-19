# Animation smoothness and accuracy audit

Refinement pass over the Rasengan / Rasen Shuriken rendering and animation systems. No new
features, no gameplay value changes, no new rendering approaches.

Reproduce with:

```bash
./gradlew build --no-daemon -Porg.gradle.java.installations.paths="$JP"
# maths evidence (drives the real shipped methods, private ones by reflection)
javac -cp "$CP" -d build/verify tools/verify/AnimationAudit.java && java -cp "build/verify:$CP" AnimationAudit
# physics evidence (headless dedicated server + per-tick trace datapack)
bash tools/verify/run_server_trace.sh && python3 tools/verify/analyse_trace.py
```

Captured output is committed under `tools/verify/evidence/`:
[`animation-audit.txt`](tools/verify/evidence/animation-audit.txt) and
[`physics-evidence.txt`](tools/verify/evidence/physics-evidence.txt). The raw 25 000-line
per-tick server log is not committed; regenerate it with the script above.

---

## 1. Interpolation audit

Every animated property, classified. "Analytic" means a closed-form function of a continuous time
that already carries the frame's partial tick.

| Property | Where | Status |
|---|---|---|
| Held sphere core/mid/outer shell spin | `RasenganRenderer.emitShells` | analytic, constant rate — OK |
| Shell surface displacement (noise) | `OrbitMath.noise` | analytic, C∞ sum of sines — OK |
| Held sphere radius + formation overshoot | `ClientCast.radius` | analytic in `progress` — OK |
| Shell opacity / sphere intensity | `ClientCast.sphereIntensity` | analytic smoothstep — OK |
| Orbital ring position, flow head, breathing | `OrbitMath.Layer.point` | analytic, constant rate — OK |
| Spherical helices | `OrbitMath.helixPoint` | analytic, constant rate — OK |
| Electric streak gating | `RasenganRenderer.emitStreaks` | analytic noise gate — OK |
| Aura streamers, currents, sparks | `RasenganRenderer.emitBodyAura` | analytic, constant climb rate — OK |
| Projectile trail braid | `RasenganRenderer.trailPoint` | analytic — OK |
| Projectile world position | `Mth.lerp(partialTick, xOld, getX())` | partial-tick interpolated — OK |
| Palm anchor position / body yaw / pitch | `PalmAnchor` | partial-tick interpolated, short-way yaw — OK |
| Impact rings, fragments, shockwave | `emitImpact`, `emitImpactSequence` | analytic in impact age — OK |
| Shuriken blade angle (wind-up) | `ShurikenRenderer.spinAngle` | **analytic integral** — OK, reference case |
| HUD bar fill | `ClientPowerState.fraction(partialTick)` | partial-tick interpolated + fractional pixel — OK |
| **In-flight shuriken spin clock** | `RasenganProjectile.spinTicks()` | **BUG 1 — fixed** |
| **HUD glow breathe + shimmer phase** | `PowerBarHud.renderGlow` | **BUG 2 — fixed** |
| **Screech pitch ramp clock** | `ClientEffects.tickCast` | **BUG 3 — fixed** |
| **Held shuriken disc plane** | `RasenganRenderer` spin axis | **BUG 4 — fixed** |
| **Trailing wisp traced path** | `ShurikenRenderer.tipAt` | **BUG 5 — fixed** |
| Particle alpha / quad size | `EnergyParticle.tick` | 20 Hz steps; vanilla-equivalent — see §5 |
| Mesh LOD level | `ClientTuning.meshQuality` | stepped by distance — see §5 |

### Bug 1 — in-flight shuriken animation was quantised to 20 Hz (severe)

`spinTicks()` returned `heldSpin + lifeTicks` with `lifeTicks` an `int`, and `renderProjectiles`
passed it straight to the renderer while the Rasengan branch on the next line *did* add
`partialTick`. Because that one clock also drives blade flex, tip vibration, the trailing wisps,
the mist and the shell's breathing brightness, the entire in-flight assembly advanced only on tick
boundaries.

Measured at 60 fps (`tools/verify/evidence/animation-audit.txt` §C):

```
OLD per-frame angle delta: 0.000, 0.000, 0.850   (two frozen frames, then a 48.70 deg snap)
NEW per-frame angle delta: 0.283, 0.283, 0.283   (uniform)
OLD max/min step ratio   : infinite (min step exactly 0.000000)
NEW max/min step ratio   : 1.00002
```

Fixed by adding `spinTicks(float partialTick)` and using it from the renderer; the no-arg form is
retained only for the impact packet, which genuinely wants a whole-tick value.

### Bug 2 — HUD glow phase was `rate(t) · t`, not `∫rate dt` (severe)

This is exactly the bug class the pass was asked to hunt for, and it was hiding in the glow:

```java
float breatheRate = 0.18F + 0.10F * ramp;               // rate ramps with the fill
float breathe = 0.5F + 0.5F * sin(time * breatheRate);  // ... times ABSOLUTE game time
float head    = positiveFraction(time * sweepRate);     // same defect
```

`d/dt [rate(t)·t] = rate + t·(drate/dt)`. The second term is proportional to the **age of the
world**, so the glow strobes faster the older the save. Measured apparent phase rate at 50% charge
(`tools/verify/evidence/animation-audit.txt` §F), against a correct ~0.205 rad/tick:

| world age (ticks) | old apparent rate | correct rate | error |
|---|---|---|---|
| 0 | 0.205033 | 0.205033 | 1.0× |
| 24 000 (1 day) | 1.005047 | 0.205033 | 4.9× |
| 1 000 000 | 33.538931 | 0.205033 | 163.6× |
| 10 000 000 | 333.544009 | 0.205033 | 1626.8× |

At 1 000 000 ticks that is roughly ten full strobe cycles per tick, aliased into noise. A fresh
world looks correct, which is why it survived. Fixed by accumulating both phases one tick at a time
at the rate then in force (which *is* the integral) in `ClientPowerState`, adding the sub-tick
remainder at read time so the drawn value stays continuous in real time, and wrapping at exact
periods (τ for the sine, 1.0 for the sweep) so wrapping is invisible. Amplitudes still come from
the fill fraction and stay in the HUD. The phases advance in every state, so a full bar keeps
breathing instead of freezing.

### Bug 3 — screech pitch ramp ran 15 ticks ahead of the blades

`ClientEffects` derived the spin clock as `castDuration * 0.55`, which at the default 100-tick
shuriken cast is tick **55**, while the renderer and the sustained-loop trigger both use
`BLADE_SNAP_TICK` = **70**. With `SPINUP_TICKS` = 11 the pitch ramp therefore finished climbing at
tick 66 — four ticks *before* the blades began to move.

### Bug 4 — held shuriken disc plane snapped once per tick

The held spin axis was `player.getLookAngle()`, which reads the current tick's rotation only. While
the player turned, the plane of a rigid spinning object stepped 20 times a second. Now
`player.getViewVector(partialTick)`.

### Bug 5 — trailing wisps traced an idealised tip, not the real one

`tipAt` used the rigid base angle plus the `-0.30` sweep, omitting the flex and vibration that
`bladePoint` applies at `f = 1` — so the wisp attached to where the tip *would* be if it were
perfectly rigid. Measured worst wisp-root to true-tip distance (§E):

```
OLD: 0.04486042 blocks  (1.73x the wisp ribbon's own half-width of 0.026)
NEW: 0.00000000 blocks
```

Fixed by factoring the whole angular offset into `bladeAngularOffset(spinTime, f, blade, seed)`,
now shared by the blade geometry and the wisp trace so they cannot drift apart again.

### Also consolidated

`BLADE_SNAP_TICK` existed in three places with two different values (`ShurikenRenderer` 70,
`RasenganProjectile` a hardcoded `70.0F`, `ClientEffects` `0.55 * castDuration`). It is now one
constant in common code, `AbilityType.BLADE_SNAP_TICK`. The Rasengan projectile clock also read the
no-arg `castDurationTicks()`; it is now ability-aware.

---

## 2. Phase transitions

| Transition | Required | Measured / status |
|---|---|---|
| Cast start → core formation | smooth | smoothstep from 0 at tick 0 — continuous |
| Core → shell expansion | smooth | shell ramp starts at `0.8 × core end`, overlapping — continuous |
| Shell → blade extension | **intentionally sharp** | double ease-out over 8 ticks from tick 70; a deliberate snap |
| Charged/held loop seam | smooth | all held motion is analytic in `age`; no cycle to restart |
| Held sphere → projectile | smooth, same object | **angular discontinuity 0.000e+00 rad; size discontinuity 0.000e+00 blocks** (§G) |
| In-flight → impact | **intentionally sharp** | spin is frozen, not decelerated — by design; residual noted below |
| Impact → cleanup | smooth | both impact paths fade alpha to exactly 0 at their own `IMPACT_TICKS` |

The held → flight handoff was re-verified given the earlier duplicate-sphere bug. Held radius
reaches exactly `FULL_RADIUS` because the formation overshoot term resolves to exactly 1.0 at
`progress = 1` — `smoothstep(0.75, 0.85, 1)` is 1, which zeroes it. The projectile draws at that
same constant, and the shuriken's spin clock is continuous by construction
(`castDuration − BLADE_SNAP_TICK` = flight clock at `lifeTicks = 0`). Both measured at exactly zero
discontinuity.

**Residual, not fixed:** the impact freezes the blade angle at the server's whole-tick
`spinTicks()`, while the last in-flight frame drawn may have been up to one tick further on, so the
snap-stop can carry up to 0.85 rad (48.7°) of rotational offset. Eliminating it entirely would
require the client to freeze its own clock at the frame the projectile vanished, which is not
knowable when the impact packet arrives. The cut is intentionally sharp and covered by a 2-tick
flash, so this is left as-is rather than adding machinery.

---

## 3. Physical accuracy — measured

### Straight-line flight

Per-tick trace of a full flight, both abilities (`tools/verify/evidence/physics-evidence.txt`):

| | Rasen Shuriken | Rasengan |
|---|---|---|
| ticks sampled | 78 | 77 |
| lateral (x) deviation | **0.000e+00** | **0.000e+00** |
| vertical (y) deviation | **0.000e+00** | **0.000e+00** |
| z step spread | 7.105e-15 | 7.105e-15 |
| worst residual vs exact line | 9.948e-14 | 7.105e-14 |

`x` and `y` are bit-identical across every tick of the flight. The z residual is at the resolution
of a `double` at that magnitude (~1.8e-14 blocks at z = 83), so there is no accumulating drift —
only representation error.

### Rigid spin

Blade base angles are `angle + blade · τ/4`, so they are exactly 90° apart by construction. Worst
deviation across the wind-up, constant-speed and long-flight regimes was **2.55e-04 degrees**
(4.45e-06 rad), which is **3.5e-06 blocks of arc** at the 0.79-block tip radius — below the float32
ulp at an angle of ~100 rad. It is rounding of the angle magnitude, not drift: `spinAngle` is a pure
function of `t`, recomputed from scratch every frame, so nothing accumulates.

Rendered tips deviate up to 3.85°, which is the *intentional* flex + vibration detail, bounded by
0.030 + 0.055 rad = 4.87°.

`spinAngle` was also confirmed to be the true analytic integral of the ramped rate by comparing it
against a 2 000 000-step trapezoid integration of the real `spinRate`: worst error **2.0 ulp**.
Slope discontinuity at the ramp boundary: **0.000e+00** rad/tick.

### Knockback arc — target missed, and the previously "verified" figure was a harness artifact

Four independent trials, real gravity, target standing on the ground:

| trial | landing distance |
|---|---|
| 1 | 142.0305 blocks |
| 2 | 143.2426 blocks |
| 3 | 143.2426 blocks |
| 4 | 143.2426 blocks |
| mean | **142.9396** |
| spread | 1.2121 |

The vertical path is a clean ballistic parabola: peak rise **20.020 blocks** at tick 21, airtime
**47 ticks / 2.35 s**, and the measured height matches Minecraft's own living-entity air physics
(`y += vy; vy = (vy − 0.08) × 0.98`) to **6.95e-06 blocks** over 46 airborne ticks. So `launch_lift`
behaves exactly as documented.

`launch_speed` does not. The 200–250 block target is **not met — 143 blocks, consistently.** The
root cause is visible in the per-tick horizontal velocity:

```
vz: 20.5000, 11.1936, 10.1835, 9.2642, 8.4277, 7.6665, 6.9738, 6.3434
first-tick ratio : 0.5460   <- block friction (0.6 x 0.91 = 0.546)
every tick after : 0.9100   <- air friction
```

A grounded target loses 45% of the launch on the first tick, because that tick uses block friction,
not air friction. The ~225 figure — in the config comment and recorded as verified in the handoff —
is the *air-friction convergence limit* `speed/(1 − 0.91)` = 11.1 × speed, which only holds for a
target that never touches the ground. Reproducing the old measurement with `NoGravity:1b` gives
**227.7457 blocks against a theoretical 227.7778 (0.014%)** — but that entity never lands at all
(it was still floating at y = 329), so it was measuring convergence, not knockback.

Per the "no gameplay value changes" constraint the default is left at 20.5. The config comment now
records the measured numbers and notes that ~32.2 would be needed for a grounded target. **Changing
it is the owner's call.**

---

## 4. Concurrency and load

**Camera-local aliasing under 3+ simultaneous casts — verified by construction, statically.** The
deferred-submit hazard is that `submitCustomGeometry` stores the callback and runs them later,
batched per `RenderType`, so a callback reading shared mutable state sees whatever the *last* submit
wrote. Every one of the five submit sites now begins its callback by writing all shared state it
depends on before emitting anything: the three in `RasenganRenderer` set `CAMERA_LOCAL` first, and
both in `ShurikenRenderer` set `CAM` and call `setBasis` first. Because each callback re-establishes
its own state at entry, execution order cannot matter, for any number of concurrent casts.
`EnergyGeometry` additionally takes camera-local as an explicit parameter, so the class that owns the
shared primitives cannot regress. This is an ordering-independence property, which a static argument
establishes for all N; it does not need a 3-cast screenshot.

**Particle staggering — genuine, not accidentally synchronised.** Each per-tick batch draws from a
single `RandomSource` sequentially, so every particle in a batch gets different draws; the seed
varies per tick (`cast.seed * 31 + gameTime`). On top of that each particle independently jitters
its size (×0.7–1.3), alpha (×0.8–1.2) and lifetime from the particle engine's own random. There is
no shared per-batch offset.

**HUD independence from tick-perfect updates.** `ClientPowerState` predicts locally every client
tick and re-snaps on each authoritative packet, and the HUD adds the partial tick. After the Bug 2
fix the glow phases no longer reference the world clock at all, so they depend only on client ticks
elapsed, not on server sync cadence.

---

## 5. Known limitations, deliberately not changed

- **Particle alpha and size step at 20 Hz.** `EnergyParticle.tick` writes `alpha` and `quadSize`
  once per tick and vanilla's particle renderer does not interpolate them. Particle *position* is
  engine-interpolated. This is identical to vanilla particle behaviour; changing it means overriding
  the render path, which the "no new rendering approaches" constraint rules out for a cosmetic gain.
- **Mesh LOD is stepped** (1.0 / 0.6 / 0.35 / 0.18 at 8 / 20 / 40 blocks), so triangle and streak
  counts change discretely as the camera moves. `distanceFactor` is continuous, so brightness fades
  smoothly across those boundaries and masks them. Genuinely smooth LOD needs mesh blending.
- `distanceFactor` previously stepped from 0.08 straight to 0 at `max_effect_distance`; it now fades
  the floor out over the final 8 blocks so effects leave the world continuously.
- `ShurikenSoundInstance` is still used for both abilities and for one-shots as well as loops; the
  name remains misleading. Out of scope here.

---

## 6. Not verified — no display or audio device

Everything in this document is either a static/analytic result or a logged server measurement.
**Nothing visual or audible was observed.** Specifically still unverified by eye or ear: that the
in-flight shuriken now *looks* smooth, that the four-blade silhouette reads correctly, that the HUD
glow now *looks* like a steady breath, that the held → thrown handoff has no perceptible pop, that
the screech now peaks with the blade snap, and whether any sound is audible at all. The fixes are
backed by measured numbers showing the underlying quantities are now continuous and correctly
phased — not by looking or listening.
