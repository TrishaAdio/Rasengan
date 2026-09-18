# Rasengan — server-authoritative charged energy sphere

A Minecraft **Java Edition 26.1.2** mod that adds a chargeable energy-sphere ability with a
persistent **POWER BAR** HUD, a global cast announcement, a single 20-heart hit, and a fully
procedural 3D animation.

Everything that affects gameplay is decided by the server. Clients render, predict and
interpolate, but they never decide whether the ability may be used.

---

## 1. Loader, dependencies and exact versions

`26.1.2` is a real Minecraft version, released **9 April 2026**. Mojang moved to year-based
versioning in 2026, so `26.1.2` is the third release of the `26.1` line — it is *newer* than the
old `1.21.x` scheme, not older. Nothing here targets a downlevel version.

| Component | Version | Why this one |
|---|---|---|
| Minecraft Java Edition | **26.1.2** | Target. `version.json` in the artifact reports `"id": "26.1.2"`, `"build_time": "2026-04-09"`, `"java_version": 25`, `"protocol_version": 775`. |
| Mod loader | **NeoForge 26.1.2.109** | Latest NeoForge build for 26.1.2 (106 builds exist for this MC version). NeoForge's version scheme is now `<mcMajor>.<mcMinor>.<mcPatch>.<neoforgeBuild>`. |
| Java | **25** | Mandatory. Minecraft 26.1 moved from Java 21 to Java 25. |
| Gradle | **9.7.1** (wrapper included) | NeoForge 26.1 requires Gradle **9.1.0 or newer**. |
| ModDevGradle | **2.0.147** | Build plugin. |
| Extra APIs | **none** | No Fabric API / architectury / library mod needed. |

### Why NeoForge rather than Fabric

Fabric does list `26.1.2` as a supported game version, so Fabric is a viable choice. NeoForge was
picked because this specific feature set maps onto things NeoForge ships in the loader itself,
with no additional dependency for a server owner to install:

- a TOML server config system that is **auto-synced to connecting clients**,
- a typed payload/networking registrar,
- a HUD layer registration API with explicit ordering against vanilla layers,
- `SubmitCustomGeometryEvent` for custom world geometry,
- `Dist`-based client/server separation.

### Notable 26.1 API facts this mod is built against

These are the things that changed most recently and that the code depends on:

- `ResourceLocation` is now **`net.minecraft.resources.Identifier`**.
- `GuiGraphics` is now **`GuiGraphicsExtractor`**; GUI drawing moved to a render-state
  *extraction* model (`render` → `extractRenderState`, `drawString` → `text`, and so on).
- `RenderType` moved to `net.minecraft.client.renderer.rendertype`, with the static instances on
  `RenderTypes`.
- `TextureSheetParticle` is gone; particles extend **`SingleQuadParticle`**.
- `ItemRenderer` and `BlockRenderDispatcher` were removed in favour of the submit pipeline.
- Obfuscation was **removed** in 26.1, so Parchment mappings are no longer needed.

---

## 2. Installation

### Client

1. Install **Java 25**.
2. Install the **NeoForge 26.1.2.109** client profile from <https://neoforged.net>.
3. Drop `rasengan-1.4.0.jar` into `.minecraft/mods/`.
4. Launch the NeoForge 26.1.2 profile.
5. Optionally rebind the ability key: **Options → Controls → Gameplay → "Cast Rasengan"**
   (default **`R`**).

### Dedicated server

1. Install **Java 25** on the host.
2. Install the NeoForge **26.1.2.109** server.
3. Drop `rasengan-1.4.0.jar` into `mods/`.
4. Start the server once. It writes `config/rasengan-server.toml`.
5. Edit that file, then restart (or use `/reload` for the values read per-cast).

The mod is required on both sides. The server refuses nothing on join, but a vanilla client will
not render the effect or the HUD.

**Dedicated servers never load rendering code.** All client classes live in `dev.rasengan.client`
and are reached only through a `Dist.CLIENT` branch. This is verified, not asserted — see
§8.

### Single-player and LAN

No extra steps. Single-player runs an integrated server, and all the gameplay logic runs there
under the same `ServerPlayer` code path — the same is true for **Open to LAN**. The logic guards on
logical side, not on physical side, so a LAN host and a dedicated server behave identically.

---

## 3. Gameplay

**Release behaviour: thrown projectile — one single continuous object.**

The sphere forms in the caster's hand, and on release **that same sphere becomes the projectile**.
There is never more than one Rasengan in existence per cast.

The handoff is a transfer of ownership, not a spawn:

| | Before release | After release |
|---|---|---|
| Who draws the sphere | the cast record, at the palm | the projectile entity |
| `ClientCast.sphereIntensity()` | ramps to 1 | returns a hard **`0.0`** |

So the held sphere and the projectile cannot coexist for even one frame — the held source switches
off in the same tick the entity takes over. Continuity is exact:

- **Position** — the server launches from `PalmAnchor.palmPosition(...)`, the identical shared
  function the client draws the held sphere with, so there is no jump.
- **Size** — the held sphere resolves to exactly `FULL_RADIUS` before release and the projectile is
  drawn at that same constant. No spawn-in ramp, so no pop.
- **Animation phase** — the projectile's clock starts at `castDuration + lifeTicks`, continuing the
  held sphere's count instead of restarting at zero, so the rings and helices don't jump phase.
- **Layers and colours** — same seed, same generated layer set, same locked palette.

> **Fixed in 1.2.0.** Previously the release left a *second* sphere behind: a melee-era code path
> glided the held sphere forward toward the impact point after release and kept the cast record alive
> for ~88 ticks, so it hung in the air while the real projectile flew off separately. That glide is
> gone, the post-release window is now 6 ticks (aura fade only), and impacts were moved out of the
> cast record into `ClientImpactTracker` so a blast renders at the projectile's actual contact point
> rather than being anchored to the caster's hand.

### Flight is dead straight

Constant velocity along the launch vector until it hits something or reaches max range.

- `setNoGravity(true)` on the entity, **and** the tick logic never touches velocity
- No gravity, no drag, no air resistance, no velocity decay, no wobble, no homing
- There are deliberately **no `gravity` or `drag` config options** — the arc was removed entirely
  rather than defaulted to zero, so nothing can reintroduce it
- Default speed `0.85` blocks/tick (~17 blocks/second): reads as a launched attack, slow enough to
  watch the sphere spin

1. Wait for the POWER BAR to reach 100% (150 seconds by default), or use `/chargeit` in Creative.
2. Aim and press **`R`**.
3. The caster braces and the sphere forms in-hand over ~1.5 seconds.
4. The sphere launches. On first contact it detonates.
5. A direct entity hit deals **40 health points = 20 full hearts**, once.
6. The bar resets to 0% and immediately begins the next 150-second cycle.

On contact the projectile stops being a projectile immediately: it applies the single hit, fires the
impact packet, optionally damages terrain, and discards itself in the same tick.

- **Entity contact** → full 40-point hit, once, plus the impact blast.
- **Terrain contact** (if no entity was hit first) → impact blast and optional block damage per
  `block_damage_enabled`, but **no** entity damage.
- **Lifetime or range exceeded** → fizzles with a whiff effect, no damage, entity removed.

It is not built on vanilla projectile code. It extends `Entity` directly — not `Arrow`, not
`AbstractArrow`, not `Projectile` — and reuses no arrow item, model or texture.

### Why it cannot pass through a target

Moving the entity and then asking what it overlaps misses anything smaller than one tick of travel,
which at 1.2 blocks/tick is most hitboxes. Instead, each tick sweeps the **segment** from the current
position to the intended next position:

1. Clip the segment against terrain to find how far it can actually reach.
2. Test that segment against every nearby entity's hitbox, inflated by `hitbox_size`, using a
   slab-method ray/box intersection written for this mod.
3. Take the nearest entity hit along the segment; if there is none, take the block hit.

Because the test is continuous along the path rather than a point sample, the first entity in the
way always registers regardless of speed. Raising `speed` cannot cause tunnelling.

### `/chargeit`

Fills your POWER BAR to 100% instantly, skipping the timer.

| Form | Requires |
|---|---|
| `/chargeit` | Caller must be in **Creative mode**. No permission level needed. |
| `/chargeit <player>` | Gamemaster permission (vanilla's `/gamemode` tier), **and** the *target* must be in Creative. |

This is server-authoritative by construction: Brigadier parses and executes commands on the server,
and the game mode is read from the server's own copy of the player. There is no packet and no client
involvement, so a modified client cannot claim to be in Creative — it is never asked. In any other
game mode the command does nothing and reports `"/chargeit is Creative mode only."`

The Creative requirement follows the player *being charged*, not the operator running the command,
so it cannot be used to hand a survival player a free cast.

On a successful cast — and only then — every online player receives **`<player name> casted Rasengan`**,
sent once, server-side, to everyone online including the caster.

The message is **bold** and coloured from the same locked palette as the sphere: the player name in
pale white-blue `#BFE9FF`, the connecting text in near-white `#E8F6FF`, and *Rasengan* in the palette
cyan `#4FD6FF`. That keeps name and ability visually distinct and both legible on light and dark chat
backgrounds.

It is built from three styled `Component`s using `Style.withColor(int)` for exact RGB — **not** legacy
`§` codes in a raw string. Section codes are a rendering-layer hack: they can't be translated,
inspected or restyled by other mods, and some chat plugins strip or escape them. Real components
carry their style as data all the way to the client.

Charging produces no message. A failed or rejected activation produces no message.

---

## 4. POWER BAR state flow

The server owns the bar. `PowerData` is a data attachment on `ServerPlayer`, so it is written into
that player's save data and survives restarts and dimension changes.

```
              ┌──────────────────────────────────────────────┐
              │                                              │
              v                                              │
        ┌───────────┐  chargeTicks == chargeDuration   ┌──────────┐
        │ CHARGING  │ ───────────────────────────────> │  READY   │
        │ +1 / tick │                                  │  at 100% │
        └───────────┘                                  └──────────┘
              ^                                              │
              │ cooldownTicks hits 0                         │ player presses key
              │                                              │ AND server validates
        ┌───────────┐                                  ┌──────────┐
        │ COOLDOWN  │ <─────────────────────────────── │ CASTING  │
        │ -1 / tick │  cast resolves (hit/miss/cancel) │ charge=0 │
        └───────────┘  (skipped when cooldown == 0)    └──────────┘
```

- **CHARGING** — `chargeTicks` increments once per server tick, capped at `chargeDuration`
  (150 s × 20 = 3000 ticks). Reaching the cap transitions to READY and pushes a packet at once.
- **READY** — holds at 100%. This is the only state in which an activation is accepted.
- **CASTING** — entered only after validation. The charge is zeroed **in the same tick** the cast
  is accepted, so the bar visibly resets the instant the ability fires. Further activations are
  rejected while in this state.
- **COOLDOWN** — entered after the cast resolves, only if `cooldown_ticks > 0`. With the default
  of `0` the bar goes straight back to CHARGING and the next full cycle starts immediately.

Every player has an independent `PowerData` and therefore an independent bar and timer.

### Validation gate

An activation is accepted only if **all** of these hold. Each failure is a silent rejection —
no chat message, no charge reset, no animation:

- state is exactly `READY`,
- `chargeTicks >= chargeDuration` (defensive double-check),
- the player is alive and not in spectator mode,
- no cast is already in flight for that player.

### Lifecycle safety

Charge state and cast state are torn down explicitly on every exit path, so nothing can be left
stuck:

| Event | Handling |
|---|---|
| Death | Cast cancelled. Charge preserved by default, or wiped if `reset_charge_on_death = true`. |
| Respawn | Cast cancelled, bar resynced. |
| Dimension change | Cast cancelled (the nearby-player set differs per dimension), bar resynced. |
| Disconnect | Server cast state dropped without trying to write to a closing connection. |
| Server restart | `chargeTicks` persists in player data; transient cast state does not exist on disk. |
| Client disconnect / world unload | Client clears all effect and HUD state. |
| Cast packet never arrives | Client-side casts self-expire after `castDuration + 18 + 40` ticks. |

---

## 5. Server / client synchronization

### What crosses the network

Five payloads, all small. **No particle position is ever sent.**

| Payload | Direction | Contents | When |
|---|---|---|---|
| `Activate` | **C → S** | *nothing at all* | On keypress |
| `PowerSync` | S → C | chargeTicks, chargeDuration, cooldownTicks, state | On every state change, plus a 1/second heartbeat, to the owning player only |
| `CastStart` | S → C | casterId, seed, castDuration, hand, aim direction, packed cosmetic caps | Once per cast, to players within `max_effect_distance` |
| `CastImpact` | S → C | casterId, impact x/y/z, hit kind | Once, at the server-validated impact moment |
| `CastEnd` | S → C | casterId, reason (completed / cancelled) | Once, on any cast exit |

The client → server packet is a **zero-field record**. It cannot assert a charge level, a target,
a position or a damage value, because it carries none. That is the core of the anti-cheat posture:
the server reads charge from its own attachment, aim from its own copy of the player's rotation,
and damage from its own config.

### How the HUD stays smooth without spamming packets

The bar advances by exactly one tick per tick, which is perfectly predictable, so per-tick packets
would be pure waste. Instead:

1. The server pushes `PowerSync` on every **state transition**, and otherwise once per second.
2. Between packets the client advances its own copy one tick per client tick — the same arithmetic
   the server is doing.
3. The HUD then adds the current frame's **partial tick** on top, so the value it draws is a
   continuous function of real time rather than a stair-step.
4. Each arriving packet snaps the local value back to the authoritative one. Because prediction
   and truth agree, the snap is invisible; and because it re-anchors every second, error cannot
   accumulate.

Cost: roughly **one packet per second per player**, plus a handful per cast.

### How every nearby player sees the same animation

`CastStart` carries a server-generated `seed`. Each client feeds that seed into
`RandomSource.create(seed)` to derive all per-layer motion parameters, and into
`RandomSource.create(seed * 31 + gameTime)` for each tick's particle emission. Two clients given
the same seed and the same start tick therefore generate **identical** geometry and identical
particles, with no synchronization traffic. Nobody sees a different or disconnected animation.

Timing is anchored to `level.getGameTime()` at packet arrival, and all animation is expressed as a
function of elapsed ticks, so clients stay in step.

---

## 6. How the 3D animation is rendered

Two complementary systems. The structural, must-be-smooth parts are **real geometry**; the
irregular, must-feel-alive parts are **particles**.

### Geometry — `RasenganRenderer`

Submitted through NeoForge's `SubmitCustomGeometryEvent`, which supplies the level `PoseStack` and
a `SubmitNodeCollector`. Geometry is submitted with `RenderTypes.dragonRays()`, whose pipeline is:

- vertex format `POSITION_COLOR`, mode `TRIANGLES`,
- `ColorTargetState(BlendFunction.LIGHTNING)` — **additive** blending,
- `DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)` — depth **tested** but not **written**.

That combination is what makes the sphere read as light rather than as plastic: overlapping layers
accumulate brightness instead of occluding each other, while still being correctly hidden behind
solid blocks. Coordinates are camera-relative, so the pose is translated by
`worldPos − cameraPos`.

**Why it looks volumetric instead of flat:**

| Element | Detail |
|---|---|
| Nested shells | Three concentric procedurally generated icospheres (`SphereMesh`, built from an icosahedron by edge-midpoint subdivision — 80 or 320 triangles) at different radii, alphas and **counter-rotations**. |
| Rim shading | Each shell vertex's alpha is scaled by `1 − |dot(normal, viewDir)|`, so the silhouette glows brighter than the centre. This is what makes a hollow shell look like a dense volume, and it recomputes per camera angle so it holds up from every direction. |
| Surface churn | Shell vertices are displaced along their normals by a smooth sum-of-sines noise field, so the surface boils rather than sitting still. |
| Orbital rings | Seven `OrbitMath.Layer` ribbons on genuinely different planes — horizontal, both verticals, two diagonals, then freely oriented — each with its own radius, speed, **spin direction**, phase and breathing amplitude. A bright "head" chases around each ring so bands read as flowing rather than as static hoops. |
| Spherical helices | Three pole-to-pole helix trails winding several times around the core, tapered to nothing at both poles. No arrangement of flat circles can imitate this. |
| Electric streaks | Eight candidate streaks, each gated by a smooth noise function of time, so they flicker in and out on independent schedules and **fade** rather than pop. |
| Camera-facing ribbons | Every ribbon's width axis is `normalize(cross(tangent, toCamera))`, so a ring viewed edge-on still has visible width instead of vanishing. |

**Why it does not jitter.** Every vertex is a pure function of
`(seed, layerIndex, parameter, time)` where `time` is a float that already includes the frame's
partial tick. Nothing is integrated frame to frame, so there is no state to drift or snap —
between two ticks the geometry simply advances along a continuous curve. That is why it spins
smoothly at any frame rate, and why 20 Hz server ticks do not produce 20 Hz visual stepping.

**Hand tracking.** `HandAnchor` interpolates the player's position with the partial tick exactly
the way the entity renderer does, builds the offset from **body** yaw (not head yaw, so the sphere
doesn't fly around when the mouse flicks), applies damped pitch, and interpolates yaw the short
way around the circle so crossing 180° doesn't send the sphere on a lap around the player.

### Particles — `ClientEffects` + `EnergyParticle`

Five registered particle types (`intake`, `spark`, `aura_wisp`, `wisp`, `burst`) sharing one
`SingleQuadParticle` subclass whose behaviour is selected by a `Style` enum. Spawned **client-local
only**. Textures are white with an alpha ramp and tinted at spawn from the locked palette.

### Animation timeline

Phase boundaries are fractions of `cast_duration_ticks`, so changing that value rescales the whole
animation coherently.

| Window | Stage | What happens |
|---|---|---|
| `0.00 – 0.30` | **Cast preparation** | Palm glow appears; tiny motes curve inward into the hand with a tangential component so paths bend rather than falling straight in; body aura begins fading in. Starts on the frame `CastStart` arrives. |
| `0.30 – 0.75` | **Sphere formation** | Shells scale up (with a slight overshoot so formation snaps into place); rings and helices spin up; sparks and outer wisps begin shedding. |
| `0.75 – 1.00` | **Hold** | Full sphere at maximum instability. |
| at `1.00` | **Release** | Sphere glides from the palm to the impact point over 3 ticks on an ease-out curve — no visual teleport. |
| `+18 ticks` | **Impact** | Implosion collapsing to a point over 2 ticks, then an outward burst: an expanding spherical shockwave shell, three staggered expanding rings, ten curved spiral fragments, dense burst particles, and a short layered sound. Brightness is capped and the flash is brief so gameplay stays visible. |

The impact animation is driven by the `CastImpact` packet, independently of whether the target
survived, so **the full impact animation always plays even on a lethal hit**.

### Locked colour palette

`Palette` defines the only colours used: near-white `CORE`, white-blue `HIGHLIGHT`, `CYAN`,
`DEEP_CYAN` and `BLUE`. There is intentionally **no API that takes a player, team or seed and
returns a colour**. Randomness is applied to motion, timing and lifetime only — and where a
particle picks a colour, the random draw only chooses *which palette entry* and how far to blend
between two of them. It is structurally impossible to produce an off-palette hue or a per-player
colour.

### The caster body aura

The aura is drawn as **geometry, in the same submit pass as the hand sphere**, from the same
`ClientCast` record and therefore the same server `CastStart` trigger. The two cannot start at
different times or appear independently — they are emitted from one loop iteration in one frame.

It consists of seven helical streamers climbing the body, energy currents converging on the casting
hand, and intermittent outward sparks, all thin and sparse so the player's skin, armour, held item
and the world behind them stay readable. A particle layer adds extra texture on top.

> **Fixed in 1.1.0.** The aura previously existed *only* as particles, on a code path separate from
> the sphere's. Anything that suppressed particles — the vanilla Particles setting on `Minimal`, a
> server `particle_density` of 0, or the sphere's own density early-return, which sat *above* the
> aura block and returned before it — silently removed the aura while the sphere kept rendering.
> That asymmetry was the bug. The aura is now mesh-first and gated only on its own intensity.

### The aura is not permanent

The aura is emitted only while a cast is active, and stops the moment the cast is released, cancelled
or impacts. It does not appear while walking, standing still, or charging the POWER BAR. Intensity
fades out over a few ticks on every exit path, so it always disappears cleanly.

### The projectile's visual

The in-flight sphere is drawn by the *same* shell/ring/helix/streak code as the held sphere — it is
literally the same visual, anchored to a flying entity instead of a hand — plus a braided trail of
two counter-rotating ribbons along the reverse of its velocity, which is what gives the trail its
twist rather than a straight smear.

The entity type registers a vanilla `NoopRenderer` so Minecraft draws nothing for it; all of its
appearance comes from `SubmitCustomGeometryEvent`, the render path already proven by the held sphere.
That keeps one implementation of the energy sphere rather than a second one inside an
`EntityRenderer`.

### POWER BAR glow

The bar has its own glow, completely independent of the sphere and aura — it runs when no cast is
happening, because its job is to telegraph readiness. Two continuous effects, both pure functions of
time so neither can flicker or step:

- a **breathing** brightness across the filled portion, whose period also quickens as the bar fills;
- a **travelling shimmer**, a soft highlight sweeping repeatedly along the fill.

Both scale with the fill fraction **squared**, so the bar is nearly calm when empty and unmistakably
alive near 100%; at exactly 100% it settles into a steady strong pulse. Once past 75% a thin outer
halo appears around the bar. Painted as 32 vertical slices with per-slice alpha — a single rectangle
could only pulse uniformly, never sweep. Client-side cosmetic only; never synced.

---

## 7. Configuration

`config/rasengan-server.toml`, generated on first server start. This is a `SERVER` config, so
NeoForge syncs it to connecting clients automatically — the HUD and renderer read the same values
the server enforces.

```toml
[power_bar]
	# Real seconds for the POWER BAR to charge from 0% to 100%.
	# Default 150 seconds = 2 minutes 30 seconds.
	charge_duration_seconds = 150
	charge_while_dead = false
	reset_charge_on_death = false

[ability]
	# Damage of one direct hit, in health points (2 points = 1 heart).
	# Default 40.0 = 20 full hearts. Applied once, as a single immediate hit.
	damage = 40.0
	range = 4.0                          # strike reach in blocks, from the eyes
	hitbox_size = 1.0                    # radius of the swept hit volume
	cast_duration_ticks = 30             # activation -> impact (20 ticks = 1 second)
	cooldown_ticks = 0                   # extra lockout before charging resumes
	bypass_invulnerability_frames = true
	announce_cast = true                 # the global chat announcement

[projectile]
	speed = 0.85                         # blocks per tick (~17 blocks/second)
	lifetime_ticks = 100                 # max airborne time before it fizzles
	max_range = 64.0                     # max distance travelled before it fizzles
	# No gravity or drag options exist. Flight is always perfectly straight, by design.

[environment]
	block_damage_enabled = false         # OFF by default
	damage_radius = 2.0
	damage_strength = 3.0                # blocks above this explosion resistance survive
	drops_items = true

[effects]
	particle_density = 1.0               # 0.0 - 3.0
	aura_intensity = 1.0                 # 0.0 disables the body aura
	max_effect_distance = 64.0           # also the packet broadcast radius
	max_simultaneous_effects = 8         # per-client render cap
```

### Configuration examples

**Fast-paced PvP arena** — short charge, quick casts, no announcement spam:

```toml
[power_bar]
	charge_duration_seconds = 30
[ability]
	cast_duration_ticks = 16
	announce_cast = false
```

**Hardcore, destructive** — terrain damage on, tuned independently of entity damage:

```toml
[environment]
	block_damage_enabled = true
	damage_radius = 3.0
	damage_strength = 3.0     # leaves obsidian and reinforced deepslate intact
	drops_items = false
```

**Low-spec / high-population server** — cut cosmetic cost hard:

```toml
[effects]
	particle_density = 0.35
	aura_intensity = 0.0
	max_effect_distance = 24.0
	max_simultaneous_effects = 3
```

**Longer reach, lower damage** — 5 hearts instead of 20:

```toml
[ability]
	damage = 10.0
	range = 6.0
	hitbox_size = 1.5
```

### Damage: why exactly 40 lands

The mod registers its own damage type, `rasengan:rasengan`, and adds it to four vanilla tags:
`bypasses_armor`, `bypasses_enchantments`, `bypasses_effects` and `bypasses_resistance`. That means
the configured number is the number that actually lands, rather than something armour, Protection
enchantments or a Resistance potion quietly reduces. It is applied as **one** `hurtServer` call,
with the target's damage-immunity timer cleared first (`bypass_invulnerability_frames`) so the full
hit lands even if the target was struck a moment earlier.

There is no per-tick contact damage, no lingering effect and no particle-collision damage anywhere
in the mod — the only damage call in the codebase is that single one.

A server owner who *wants* armour to matter can override any of those tags with their own
datapack.

Block damage is off by default because it is destructive and irreversible on most servers. It is
implemented as a direct block-sphere walk rather than a vanilla explosion, specifically so it
cannot add a second source of entity damage on top of the single authoritative hit.

---

## 8. Performance

- **Network:** ~1 packet/second/player for the HUD, plus 3 per cast. Zero per-particle traffic.
- **Geometry:** roughly 1000 triangles for a full-detail sphere. At the default cap of 8
  simultaneous effects that is ~8k triangles — negligible for any GPU that runs the game.
- **Level of detail:** mesh quality steps down with camera distance (full inside 8 blocks, then
  0.6 / 0.35 / 0.18), reducing icosphere subdivision, ring segment counts, ring count and helix
  count. Particle counts scale by distance too, and past `max_effect_distance` nothing is drawn or
  even transmitted.
- **Player preference respected:** the vanilla Particles video setting scales counts
  (ALL / DECREASED / MINIMAL), and MINIMAL disables the body aura entirely. Clients can only scale
  *down* from the server's caps, never up.
- **Bounded state:** the client tracker is capped at `max_simultaneous_effects`; excess casts are
  skipped visually with no gameplay effect. No unbounded loops or particle spawns exist.
- **Allocation:** the renderer reuses static scratch vectors, and icospheres are generated once at
  class-init and shared.
- **Paused games:** effect and prediction ticking stop while the game is paused, so a paused
  single-player world does not keep spawning particles.

---

## 9. Building

```bash
git clone <this-repo>
cd rasengan
./gradlew build
```

Output: `build/libs/rasengan-1.4.0.jar`.

Requires **JDK 25** on `PATH` (or discoverable by Gradle's toolchain detection). The wrapper
fetches Gradle 9.7.1 automatically. The first build downloads and decompiles Minecraft, which
takes a few minutes; later builds are seconds.

If Gradle cannot find a required JDK, point it at your installations:

```bash
./gradlew build -Porg.gradle.java.installations.paths=/path/to/jdk-25,/path/to/jdk-21
```

Particle textures are generated, not hand-drawn. To regenerate them:

```bash
python3 tools/generate_particle_textures.py
```

### Running in development

```bash
./gradlew runClient    # dev client
./gradlew runServer    # dev dedicated server (accepts console commands)
./gradlew runClient2   # second client, for multiplayer testing
```

---

## 10. Testing

### Verified in this build

| Check | Result |
|---|---|
| `./gradlew build` | Passes; produces `rasengan-1.4.0.jar`. |
| Dedicated server boot | `Done (0.206s)!` on `minecraft server version 26.1.2`, mod loaded as `Rasengan 1.0.0 (rasengan)`. No `NoClassDefFoundError` / `ClassNotFoundException`. |
| Client-class isolation | `javap` over every compiled class: of 20 non-client classes, **zero** reference `net/minecraft/client/*`, and exactly **one** references `dev/rasengan/client/` — `Rasengan` → `RasenganClient`, inside the `Dist.CLIENT` branch. |
| Config generation | `config/rasengan-server.toml` written with every documented option and the correct defaults, including `block_damage_enabled = false`. |
| Damage correctness | Iron Golem (100 HP) given **Resistance IV** (80% reduction), then `damage @e[type=iron_golem,limit=1] 40 rasengan:rasengan` → health **exactly 60.0**. A non-bypassing source under Resistance IV would have dealt 8. Confirms the damage type registered, the bypass tags work, and 40 points land as one hit. |
| **Straight flight, zero drop** | Golem at `8 100 40`, projectile launched from `8 100 2` — 38 blocks downrange at **identical Y**. It hit, health 100.0 → **exactly 60.0**. This is the decisive test: with the old `gravity = 0.03` the sphere would have fallen ~30 blocks over that flight and missed entirely. |
| **Projectile cleanup on impact** | `IMPACT_DESPAWNED_OK` — the entity removes itself in the same tick as the blast. |
| **Despawn at max range** | Fired into open air: `ALIVE_AT_2S`, then `RANGE_DESPAWNED_OK`, and no `STILL_ALIVE_AFTER_8S`. Fizzles and cleans up with no damage. |
| **Single-sphere invariant (static)** | `emitShells` has exactly two call sites — the held sphere, gated on `sphereIntensity()`, and the projectile. `sphereIntensity()` returns a hard `0.0` when `released \|\| cancelled`, so the two cannot overlap for a frame. All release-glide code (`spherePosition`, `releaseOrigin`, `RELEASE_TRAVEL_TICKS`) is deleted. |
| **No legacy colour codes** | Repo-wide grep for `§` finds only the javadoc explaining why they aren't used. |
| **`/chargeit` registration** | `help chargeit` → `/chargeit [<target>]`, confirming both forms. `/chargeit` from console → *"A player is required to run this command here"*. `/chargeit NoSuchPlayer123` → *"No player was found"*. |
| Config generation | `[projectile]` section written with correct defaults (`speed = 1.2`, `lifetime_ticks = 100`, `max_range = 64.0`). |

Reproduce the damage check on a dev server:

```
forceload add 0 0
summon minecraft:iron_golem 0 100 0
effect give @e[type=iron_golem,limit=1] minecraft:resistance 999 4 true
damage @e[type=iron_golem,limit=1] 40 rasengan:rasengan
data get entity @e[type=iron_golem,limit=1] Health
```

Expected: `60.0f`.

Reproduce the **projectile** check:

```
forceload add -32 -32 47 47
summon minecraft:iron_golem 8 100 12 {NoGravity:1b,NoAI:1b}
summon rasengan:rasengan_projectile 8 100 2 {Motion:[0.0,0.0,1.2]}
data get entity @e[type=iron_golem,limit=1] Health
execute unless entity @e[type=rasengan:rasengan_projectile] run say DESPAWNED_OK
```

Expected: `60.0f`, then `DESPAWNED_OK`.

Two gotchas when testing headless, both of which cost me a false negative:

- Set `pause-when-empty-seconds=0` in `server.properties`. The server pauses when empty and entities
  stop ticking.
- **Forceload the chunk the projectile is actually in.** `forceload add 0 0` only covers blocks
  `0..15`; a projectile at `z=-6` sits in chunk `(0,-1)` and never ticks, which looks exactly like
  broken collision.

### Manual test checklist

Not yet exercised — these need a graphical client, which the build environment did not have:

- [ ] HUD appears bottom-centre, reads `POWER BAR`, and ticks up smoothly with no visible stepping.
- [ ] Bar takes 150 seconds to fill; pressing `R` below 100% does nothing at all.
- [ ] At 100%, `R` casts: bar snaps to 0% and immediately starts the next cycle.
- [ ] **Bar glow**: shimmer sweeps along the fill, breathing brightness rises as it fills, halo
      appears past ~75%, steady strong pulse at 100%. Smooth, never flickering or stepped.
- [ ] Exactly one `<name> casted Rasengan` per successful cast, seen by all players including the
      caster.
- [ ] **Aura appears the instant the cast begins**, at the same moment as the hand sphere.
- [ ] **Aura is visible on *other* players' bodies**, not just your own — have a second client watch.
- [ ] Aura is absent while walking/idle/charging, present only during the cast, gone cleanly after.
- [ ] Aura still appears with the Particles video setting on **Minimal** (it is geometry now).
- [ ] Sphere stays glued to the hand while running, turning and jumping.
- [ ] **Exactly one sphere on screen** at every moment — charge, hold, throw, flight. No duplicate,
      no lingering hand sphere fading out behind the throw.
- [ ] The thrown sphere looks **identical** to the held one: same size, layers and colours, with no
      pop, jump or gap at the moment of release.
- [ ] **Projectile** launches on release, keeps the full sphere visual plus twisting trail in flight,
      and is visible to nearby players.
- [ ] Flight is **visibly dead straight** with no droop, even at max range across open ground.
- [ ] Projectile hitting a mob removes 20 hearts in one step and detonates at the contact point.
- [ ] Projectile hitting a wall detonates there and deals no entity damage.
- [ ] Chat announcement appears **bold and cyan-coloured** for every player, not plain white.
- [ ] Impact animation plays fully even when the hit is lethal.
- [ ] Two clients side by side see the same sphere, the same projectile path, and the same impact.
- [ ] Casting, then disconnecting mid-cast, leaves no stuck effect for other players.
- [ ] `/chargeit` fills the bar in Creative; refuses with a clear message in Survival/Adventure.
- [ ] Several simultaneous casters and projectiles do not tank frame rate.

---

## Rasen Shuriken

A second technique on the same server-authoritative pipeline, bound to **`G`** by default. It shares
the charge state machine, the validation gate, the projectile entity and the single-hit guarantee -
only the tuning and the visuals differ, so the safety invariants live in one place.

### Silhouette

A dense near-white core inside a breathing translucent shell, with **four tapered blades at 90
degrees** forming a star. The blades are `0.62` blocks long against a `0.20` shell, so total extent
is ~`0.82` versus Rasengan's `0.30` - the star outline dominates at any distance, which is the stated
priority over surface detail.

Each blade is **three stacked ribbons**, not a flat cutout. They are offset along the spin axis for
thickness, that offset twists along the blade's length, and the offsets converge toward the tip so
the cross-section closes to an edge. Width tapers as `(1-f)^1.45` - concave, like a real edge - and
the trailing edge rakes backward so each point sweeps rather than sticking out as a spoke.

### Motion — deliberately the opposite of Rasengan

| | Rasengan | Rasen Shuriken |
|---|---|---|
| Outline | round, 0.30 radius | four-pointed star, 0.82 radius |
| Rotation | 7 independent orbit rings, each its own plane/speed/direction | one **rigid** assembly on a single axis |
| Rate | constant from frame one | **accelerates** through a wind-up, then holds |
| Detail | smooth churning surface | rigid body, chaotic vibrating edges |
| Impact | rounded implosion | snap-stop, blades detach outward |

The wind-up uses the **analytic integral** of a smoothstep-ramped angular velocity, not
`rate x elapsed`. Multiplying would make the blades jump backwards the instant the rate stopped
changing, because accumulated angle would be recomputed against a different rate. Integrating keeps
position continuous through the acceleration.

Micro-vibration is scaled by `f^2` along each blade, so the root is rock solid and only the tips
tremble - the silhouette never breaks. Each blade also flexes on its own phase, so the assembly reads
as alive rather than a rigid cross.

### Trailing wisps

Traced from each tip's **own motion history**. Because the spin angle is analytic, the exact tip
position at `t - delta` is computable, so a wisp is the real arc the tip swept - genuinely curved and
decaying, rather than a straight line emitted outward. It also lengthens automatically as the spin
accelerates.

### Formation beats

| Window | Beat |
|---|---|
| 0.00-0.18 | core forms as a bright point |
| 0.18-0.55 | shell expands, ease-out |
| 0.55-0.72 | **blades snap out** - double ease-out over a much shorter window, a mechanical unfurl |
| 0.55+ | spin wind-up begins, screech pitch and volume ramp with it |
| 0.72-1.00 | full rate reached, then the held loop |

The screech is a continuous rise built by re-triggering a short wind sample every 3 ticks with rising
pitch and volume - Minecraft cannot pitch-bend a playing sound, so this is the same trick vanilla
uses for the note-block glissando.

### Impact

Rotation does **not** decelerate: the blade angle is frozen at its contact value, so the stop reads
as violent. The four blades then detach and travel along the tangents they were already sweeping, on
decaying arcs - not a uniform circular scatter. A single thin flat ring sweeps outward in the disc
plane (a true plane-aligned ring reads as a slash; a camera-facing one would read as a bubble), with
a sharp front-loaded flash. Mist disperses on a cubic falloff so it is gone quickly.

### Performance

The core, shell and four blades are **never** culled. Under load or distance, LOD reduces trailing
wisps first (below quality 0.25 they stop), then mist (below 0.35), then blade segment count - the
silhouette survives to the lowest setting. Mist is hard-capped at `26 x quality` streaks per
instance. Mist and wisps are drawn as gated geometry rather than particles specifically so the count
is bounded and fully deterministic from the seed.

### Config

```toml
[rasen_shuriken]
	damage = 60.0        # 30 hearts, vs Rasengan's 40.0
	speed = 1.05         # blocks/tick
	hitbox_size = 1.6    # larger: the blades extend well past the core
	max_range = 80.0
```

---

## 11. Project layout

```
src/main/java/dev/rasengan/
├── Rasengan.java              entrypoint; payload registration; Dist.CLIENT gate
├── RasenganConfig.java        server config spec
├── RasenganParticles.java     particle type registry
├── RasenganEntities.java      projectile entity type registry
├── PowerState.java            the four-state enum
├── network/
│   └── RasenganPayloads.java  all 5 payloads + cosmetic-cap packing
├── server/                    ← server-authoritative gameplay
│   ├── PowerData.java             per-player charge, persisted
│   ├── RasenganAttachments.java   data attachment registration
│   ├── ServerPowerManager.java    charge tick, state machine, sync, lifecycle
│   ├── ActiveCast.java            transient in-flight cast record
│   ├── ServerCastManager.java     validation, timing, launch, chat
│   ├── RasenganProjectile.java    thrown sphere: flight, sweep collision, damage
│   └── ChargeCommand.java         /chargeit, Creative-gated server-side
└── client/                    ← never loaded on a dedicated server
    ├── RasenganClient.java        client bootstrap
    ├── PowerBarHud.java           the POWER BAR
    ├── ClientPowerState.java      predicted charge mirror
    ├── RasenganRenderer.java      3D geometry submission
    ├── SphereMesh.java            procedural icosphere generation
    ├── OrbitMath.java             deterministic orbital / helix / noise maths
    ├── HandAnchor.java            interpolated palm tracking
    ├── ClientCast.java            per-cast animation timeline
    ├── ClientCastTracker.java     active effects, bounded and self-cleaning
    ├── ClientEffects.java         particle emission
    ├── EnergyParticle.java        the particle implementation
    ├── ClientTuning.java          distance / preference level-of-detail
    ├── Palette.java               the locked colour palette
    └── RasenganKeys.java          keybind
```

---

## 12. Assets and originality

Every asset in this mod is original and generated:

- **No imported models.** The sphere is a procedurally generated icosphere built in code from an
  icosahedron by edge-midpoint subdivision. Rings, helices and ribbons are evaluated from
  parametric equations at runtime. There is no model file in the repository.
- **No imported textures.** The three particle textures are produced by
  `tools/generate_particle_textures.py`, which evaluates a mathematical falloff per pixel and
  writes the PNG with the Python standard library. They are white-with-alpha so the code tints
  them; the script is included so anyone can verify and regenerate them.
- **No imported sounds.** Impact and cast audio are layered stock Minecraft sound events, pitched
  and attenuated in code.

Nothing was taken from any other game, anime, mod or media source.

---

## 13. Licence

MIT. See `gradle.properties` for the declared licence field used in the mod metadata.
