# Dragon mob

A summon-only, boss-tier flying dragon. **Standalone hostile mob only** — see
[Future work](#future-work) for what is deliberately absent.

The model is third-party work used under **CC BY 4.0** — "Demonic Wingwalker" by **CsDani50**.
Attribution is mandatory and lives in [`DRAGON_CREDITS.md`](DRAGON_CREDITS.md); read it before
redistributing.

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

## Flight AI — why it does not pathfind

The obvious build (`WaterAvoidingRandomFlyingGoal` over `FlyingPathNavigation`, as parrots and
allays use) produced a dragon that **never moved: 0.00 blocks over 500 ticks**. That goal asks the
navigator to path to a chosen point, and the node evaluator cannot fit a 6×5 collision box, so every
request failed silently.

Vanilla's own answer for a large flyer is the Ghast: steer by writing directly to the move control
with `setWantedPosition` and never pathfind. `FlyingMoveControl.tick()` honours that with no
navigator involved. So wandering reuses vanilla's `Ghast.RandomFloatAroundGoal` (it accepts any
`Mob`), and the melee approach uses the same technique rather than `MeleeAttackGoal`, which would
also have depended on navigation.

## Attacks

- **Melee bite** — custom charge-and-bite goal; damage from `ATTACK_DAMAGE`. Reach derived from the
  collision box, not hard-coded.
- **Fire breath** — a channelled forward cone that damages and ignites everything inside it. A cone
  test rather than a spray of fireball entities, so damage is exact, configurable and measurable
  headlessly; flames are server-sent particles. Will not fire through walls.

Fire-immune by design (it breathes fire); vulnerable to everything else.

## Commands

- `/spawn dragon` — tab-completes from this mod's mob list. Gated at `Commands.LEVEL_GAMEMASTERS`,
  matching the existing `/chargeit <player>` gate and vanilla `/summon`.
- `/summon rasengan:dragon` — works independently; the shorthand does not wrap or replace it.

## Config

All under `[dragon]` in the server config: health, armour, knockback resistance, movement/flight
speed, attack damage, follow range, `can_fly`, `boss_bar`, `aggressive`, and the full breath set
(damage, range, cone angle, burn duration, cooldown, duration).

## Verified on a dedicated server

| Check | Result |
|---|---|
| `/spawn dragon` | spawns; health `300.0` (config) |
| Unknown mob rejected | `Unknown mob 'notamob'. Available: dragon` |
| `/summon rasengan:dragon` | spawns |
| Untethered wandering flight | 25.80 blocks travelled over 500 ticks, **499 distinct positions**, y from −59.38 to −51.20 (airborne), **zero players connected** |
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

Phase two, explicitly out of scope for this build:

- a summoning method (scroll item, hand-seal gesture, or player-bound command)
- mount and ride controls
- ownership, so it only obeys and carries its summoner
- dismissal/despawn tied to the summon rather than death only

None of taming, saddling, player ownership or any jutsu/scroll/circle mechanic exists in this code.
The dragon is a plain hostile mob that wanders, fights and dies.
