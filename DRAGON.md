# Dragon mob

A boss-tier flying dragon. Two ways in: `/spawn dragon` for gamemasters, or the player-facing
**Summoning Jutsu** documented in [`SUMMONING.md`](SUMMONING.md). Still **no mount, ride, taming or
ownership** — see [Future work](#future-work) for what is deliberately absent.

The model is third-party work: **"Demonic Wingwalker"** by **CsDani50**
([source](https://sketchfab.com/3d-models/demonic-wingwalker-f807bf53631e403094920b05dbebde17)),
licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). **Changes were made:** converted
from glTF to Bedrock GeckoLib geometry and animations, and the texture was re-extracted from the
glTF to correct its vertical orientation. Full attribution and the documented residual risk are in
[`DRAGON_CREDITS.md`](DRAGON_CREDITS.md) — read it before redistributing.

## Asset and conversion

The supplied bundle was a Blockbench-exported **glTF 2.0**, not a mesh sculpt: all 183 meshes are
exact axis-aligned cuboids (12 triangles each), 48 bones, 12 animations, no vertex skinning. So it
was converted rather than rebuilt — the cube and animation data were all recoverable.

`tools/dragon/convert_gltf_to_geckolib.py` produces Bedrock geometry + animations for GeckoLib. Two
conventions that are normally guesswork were derived from the data instead:

- **Axis mapping**, from the standard Minecraft box-UV unwrap. Across all 145 non-degenerate cubes,
  `UP=+Y`, `NORTH=-Z`, `SOUTH=+Z` were unanimous. The apparent 73/72 split on X turned out to track
  body side exactly (`Rwing` 10/10 one way, `Lwing` 10/10 the other) — Blockbench's per-cube UV
  mirroring on the symmetric halves, not an ambiguity.
- **Sign convention**, by round-trip. The script reimplements GeckoLib's own bake maths (read from
  `GeometryBone.bake` bytecode: `pivotX = -pivot.x`, `baseRotX/Y = toRadians(-rot.x/y)`,
  `baseRotZ = toRadians(+rot.z)`) and scores candidate conventions against the glTF's own
  world-space cube corners:

  | json X sign | compare X sign | max corner error |
  |---|---|---|
  | **-1** | **+1** | **0.000100 px** |
  | -1 | -1 | 452.000000 px |
  | +1 | +1 | 452.000000 px |
  | +1 | -1 | 30.989676 px |

  So the mapping is pinned, not assumed. All 12 animations convert with a quaternion→Euler→matrix
  reconstruction error of ~1e-7.

**Texture trap:** the `textures/` PNG in the bundle is **vertically flipped** relative to the UVs.
Both copies hold the same 89,600 opaque pixels, but the standalone file has its content at rows
357–1023 while the UVs address rows 0–667. The install script therefore extracts the copy embedded
*inside* the glTF. Using the standalone file offsets the whole texture.

**Known source artifact:** 10 wing-membrane cubes have box-UV widths that overflow the 1024 px
texture (`u` up to 1203). This is inherited from the asset; those faces clamp to the texture edge,
which is also what the glTF itself does. Flagged for visual check.

## Size and hitbox

The converted model is **29.5 blocks across** with wings spread, **19.8** nose-to-tail and **4.8**
tall. The collision box is deliberately the *body*: `6.0 × 5.0`. The wings reach outside it, exactly
as the Ender Dragon's do — a 29-block-wide box on a flying mob could not path anywhere and could be
hit from absurd range. No render scale is applied, so hitbox and model share one scale.

## Flight AI — two bugs, both measured

### 1. Pathfinding cannot route this hitbox

`WaterAvoidingRandomFlyingGoal` over `FlyingPathNavigation` (what parrots and allays use) produced a
dragon that **never moved: 0.00 blocks over 500 ticks**. That goal asks the navigator to path to a
chosen point, and the node evaluator cannot fit a 6×5 collision box, so every request failed
silently. `FlyingPathNavigation` is still assigned — a `Mob` needs one — but nothing steers through
it. Flight is steering-based, like the Ender Dragon and the Ghast.

### 2. `FlyingMoveControl` only thrusts for one tick

Switching the wander goal to `Ghast.RandomFloatAroundGoal` fixed the freeze but produced jitter, not
flight. `FlyingMoveControl.tick()` flips `operation` to `WAIT` on entry to its `MOVE_TO` branch, and
on every later tick takes the else branch and calls `setYya(0)`/`setZza(0)`. That is correct only
when a **path navigator re-issues the destination every tick** — which is exactly what bees and
parrots have. Ghast's goal sets a destination *once*, because `GhastMoveControl` keeps its own state.
Pairing the two gave: one tick of thrust → stall → `hasWanted()` false → instant re-target.

Measured, before the fix:

| | before | after |
|---|---|---|
| mean speed | 0.049 blocks/tick (**8.2%** of the 0.6 attribute) | **0.508** (85%) |
| thrust spikes | one every **3.8 ticks** | one every ~50–90 |
| path over 500 ticks | 25.8 blocks | ~460 blocks per 900 |

### The replacement

`DragonFlightMoveControl` holds its destination across ticks, limits heading change to **4°/tick**
(a ~8 block turn radius, so course changes are banked arcs), thrusts along its *current* heading
rather than straight at the target — which is what produces curves instead of sideways drift — and
**lerps** velocity toward the desired vector rather than assigning it. Vertical is eased separately
and clamped.

`DragonWanderFlightGoal` commits to 24–48 block legs within ±55° of the current heading, so legs
chain into long arcs, and changes target altitude by at most 14 blocks per leg inside a
terrain-clearance band.

Obstacle avoidance has three parts, each fixing a distinct failure:

- **Body-width clearance probe.** A single centre raycast reports a 1-block gap as passable for a
  6-block-wide dragon, which then wedges. Clearance is cast as a bundle — centre, both wingtips at
  half-width, above and below.
- **Turn-radius-scaled look-ahead.** A fixed distance is wrong: the turn radius is
  `speed / turnRate`, so a warning shorter than ~2× that arrives too late regardless of how hard it
  turns.
- **Graduated evasion.** Turn rate scales up to 3× (12°/tick) with proximity, and on
  `horizontalCollision` the horizontal velocity is bled off — holding cruise velocity into stone
  keeps the vector saturated so the lerp can never rotate it away. Escape headings are
  clearance-checked in both directions before use, falling back to a climb.

## Animation is driven by replicated movement state

`DATA_FLIGHT_STATE` is derived server-side from real velocity each tick and replicated, rather than
each client guessing from `getDeltaMovement()` — which for a server-driven mob is only intermittently
synced, and could play a flap cycle at a standstill. States: ground idle/walk, hover, flap (climbing
or driving), glide (losing height with forward speed). Sending the decision also guarantees every
viewer sees the same animation for the same dragon.

## Attacks

- **Melee bite** — custom charge-and-bite goal; damage from `ATTACK_DAMAGE`. Reach derived from the
  collision box, not hard-coded.
- **Fire breath** — a channelled forward cone that damages and ignites everything inside it. A cone
  test rather than a spray of fireball entities, so damage is exact, configurable and measurable
  headlessly; flames are server-sent particles. Will not fire through walls.

Fire-immune by design (it breathes fire); vulnerable to everything else.

## Getting one

- **Summoning Jutsu** — press **L**. The player-facing route: its own POWER BAR, a 5.0 s cinematic,
  one dragon per player. Fully documented in [`SUMMONING.md`](SUMMONING.md).
- `/spawn dragon` — tab-completes from this mod's mob list. Gated at `Commands.LEVEL_GAMEMASTERS`,
  matching the existing `/chargeit <player>` gate and vanilla `/summon`.
- `/summon rasengan:dragon` — works independently; the shorthand does not wrap or replace it.

A summoned dragon is the same entity as a `/spawn`ed one, with two differences: it holds position for
a 30-tick arrival flourish before the normal flight AI takes over, and it records its summoner's UUID
so that player cannot summon a second one while it lives. The UUID confers nothing else.

## Config

All under `[dragon]` in the server config: health, armour, knockback resistance, movement/flight
speed, attack damage, follow range, `can_fly`, `boss_bar`, `aggressive`, and the full breath set
(damage, range, cone angle, burn duration, cooldown, duration).

The summon has its own `[summoning]` section — see [`SUMMONING.md`](SUMMONING.md#5-config).

## Verified on a dedicated server

| Check | Result |
|---|---|
| `/spawn dragon` | spawns; health `300.0` (config) |
| Unknown mob rejected | `Unknown mob 'notamob'. Available: dragon` |
| `/summon rasengan:dragon` | spawns |
| Untethered wandering flight | see the flight table below; **zero players connected**, so movement cannot be player-following |
| Takes damage from a mob | 300.0 → 195.5 (104.5 taken from an iron golem) |
| Deals melee damage | golem 100.0 → 12.0 (88.0 dealt) |
| Fire breath connects | golem **on fire 127/324 ticks**, peak 99 fire ticks — only the breath ignites, so this isolates it from the bite |
| Generic damage | 195.53 → 145.53 (exactly 50) |
| Fall damage | 145.53 → 120.53 (exactly 25) |
| **Fire immunity** | 30 fire damage → 120.53 → **120.53, unchanged** |
| Dies normally | entity gone after lethal damage |
| Loot drops | items present after death |
| Dedicated-server boot | clean; GeckoLib 5.5.2 loads; no exceptions |
| Client isolation intact | 31 non-client classes, **0** referencing `net.minecraft.client` |

### Flight quality, measured

`tools/verify/run_flight_test.sh` + `analyse_flight.py`, three scenarios on a dedicated server.
Committed output: [`tools/verify/evidence/flight-evidence.txt`](tools/verify/evidence/flight-evidence.txt).

| scenario | path | mean speed | stalled ticks | yaw/tick p99 / max | vertical step p99 | evading |
|---|---|---|---|---|---|---|
| open sky | 461 blocks | 0.508 b/t (85%) | **0** | 4.00 / 4.00° | 0.0285 b/t | 0.0% |
| walls + pillars | 563 blocks | 0.507 b/t | **0** | 4.00 / 12.00° | 0.0198 b/t | 0.3% |
| 3 dragons at once | 462 blocks min | 0.512 b/t | **0** | 12.00 / 12.00° | 0.0254 b/t | 1.6% |

Travel efficiency (distance actually moved ÷ velocity commanded) is 101–102% in all three, so
essentially none of the commanded velocity is being absorbed by geometry.

Independence across 3 simultaneous dragons: 3/3 distinct spatial spans and 3/3 distinct turn
profiles (mean turn rates 1.07 / 0.25 / 0.33 °/tick). Path *length* is deliberately not used as the
independence test — all three cruise at the same speed for the same duration, so similar totals prove
nothing either way.

#### Two measurement traps worth recording

- **Velocity magnitude is not movement.** An entity grinding against a wall keeps a full cruise
  velocity vector while `move()` refuses to advance it, so a velocity-based stall check reported
  healthy flight for a dragon that was completely stuck. The analyser measures actual per-tick
  displacement.
- **An unloaded entity looks exactly like a stuck one.** Every apparent 400–780 tick "freeze" was the
  dragon crossing a forceload boundary and ceasing to tick — position, velocity *and* rotation frozen
  at byte-identical values. All of them sat at |x| ≈ 96 or |z| ≈ 112, the edges of the forceloaded
  region. Detected structurally (nothing changed at all) and excluded; the harness now recentres the
  dragon and starts from a clean world each run.

### NOT verified — no display, no second client

- **Anything visual**: that the model renders at all, the texture orientation is right, the
  silhouette reads as a dragon, or the 10 overflowing UV faces look acceptable.
- **Animation playback**: that idle/walk/fly/glide/bite/breath actually play rather than a T-pose.
  The files parse and the bone names match the rig, but that is not the same as seeing them run.
- **The boss bar.** It is a `ServerBossEvent` added in `startSeenByPlayer`, and no player ever
  connected, so it was never exercised. `/bossbar list` only reports `/bossbar`-created bars, not
  entity boss events, so the "no custom bossbars" lines in the log prove nothing either way.
- **Multiple players / sync.** Cannot connect clients here. Entity registration, attributes, AI and
  damage are all server-side and confirmed; replication to multiple clients is not.

## Future work — deliberately not built

A summoning method **has since been built** — [`SUMMONING.md`](SUMMONING.md). What remains out of
scope:

- mount and ride controls
- ownership in the behavioural sense, so it obeys and carries its summoner
- dismissal/despawn tied to the summon rather than death only

None of taming, saddling or riding exists in this code. The dragon is a hostile mob that wanders,
fights and dies; it is exactly as hostile to whoever summoned it as to anyone else. The summoner's
UUID is recorded for the one-dragon-per-player limit and is read for nothing else.
