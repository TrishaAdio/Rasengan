# Summoning Jutsu

Press **L** and, five seconds later, the boss dragon arrives and says something unimpressed about it.

The dragon itself is documented in [`DRAGON.md`](DRAGON.md). This file covers only the summon: the
gating decisions, the timeline, the voice lines, and what was and was not measured.

---

## 1. Decisions, stated explicitly

Each of these had a plausible alternative. The alternative and the reason it lost are recorded so the
choice can be argued with rather than guessed at.

### It consumes its own POWER BAR

**POWER BAR — SUMMONING** is a second, fully independent charge bar. Charging the cast bar does
nothing to it and vice versa. Default charge time **300 s**, deliberately double the 150 s cast bar,
because this one arrives with a boss.

It is implemented as a *second attachment of the existing `PowerData` type*
(`RasenganAttachments.SUMMON_POWER`), not as a parallel state class. The two bars therefore charge,
reset, sync and serialise through byte-identical code while remaining separate instances — they
cannot drift apart in behaviour as two hand-maintained copies would.

Rejected: adding fields to the existing `PowerData` codec and `PowerSync` payload. That would have
changed the live wire format and forced a `NETWORK_VERSION` bump for a feature that does not need it.

### One dragon per player, enforced by refusal

While a player's summoned dragon is alive, a new summon is **rejected** with a message:

> Your dragon is already here. It will not come twice.

Rejected: silently despawning the old dragon. That deletes a boss the player may be mid-fight with,
and worse, it does so in response to a keypress the player may have made by accident. Refusing is
recoverable; deleting a boss mid-fight is not.

The real limiter is therefore the dragon's *life*, not the bar. Killing it reopens the gate
immediately (`pruneDeadDragons` runs every server tick).

> **Known limitation: the limit does not survive a server restart.** `summonerUuid` is not written to
> the dragon's save data, and the manager's tracking map is in-memory and cleared on shutdown. A player
> whose dragon is still alive across a restart can therefore summon a second one. This is outside the
> verified set — every assertion below runs inside one server lifetime — and is recorded in
> [`HANDOFF.md`](HANDOFF.md#7-outstanding-work) with the fix it needs. Do not read "one dragon per
> player" as holding across restarts until that lands.

### The summoner is recorded, and now owns the ride

> **Updated.** This section originally said the summoner owned *nothing*. That was true when the dragon
> was purely a standalone boss. The mount phase changes it: `summonerUuid` now gates who may ride, and it
> is **persisted with the entity**, so the link survives a restart. See [`MOUNT.md`](MOUNT.md).
>
> What it still confers: nothing else. No taming, no loyalty, no command over its targeting. A ridden
> dragon is as hostile to everyone else as an unridden one, and it is as hostile to its summoner the
> moment they step off.
>
> Persisting it also closed the restart hole recorded in earlier handoffs: the one-dragon-per-player limit
> used to live only in an in-memory map cleared on shutdown, so a restart let a player summon a second
> dragon while the first was still alive. `hasLiveDragon` now falls back to scanning loaded levels for a
> dragon whose `summoner()` matches. Verified by clearing the map mid-run and confirming the limit still
> holds. One residual limit, stated in `MOUNT.md`: the scan sees loaded entities only.

### The summoner is recorded, but owns nothing (original rationale)

`DragonEntity.summonerUuid` exists solely for the one-per-player check. It grants no control, no
taming, no riding and no loyalty — the dragon is exactly as hostile to its summoner as to anyone
else. Mount, ride and taming mechanics remain out of scope, unchanged from
[`DRAGON.md`](DRAGON.md#future-work--deliberately-not-built).

### The dragon persists until killed

No timer, no removal on summoner death or disconnect — identical to a `/spawn`ed dragon. This was the
brief's recommendation and it is also the only option consistent with "the summoner owns nothing": a
dragon that vanishes when its summoner logs out is a pet.

### No permission gate

This is a player ability, not an admin tool. `/spawn dragon` remains the gamemaster path and keeps
its `LEVEL_GAMEMASTERS` gate.

### Server-authoritative, with nothing to forge

The client sends `SummonActivate`, a **fieldless** packet — measured at **0 bytes** on the wire. It
carries no position, no target, no timing. Charge state, liveness, spectator status, the pending-
cinematic check and the one-dragon-per-player limit are all read from server state. A modified client
can press the button faster; it cannot change the answer.

---

## 2. Timeline — exact durations

The server owns the clock. It broadcasts one `SummonStart` and then counts ticks itself, so the
dragon's arrival and the chat line happen at a server-decided moment rather than whenever a given
client's animation happens to finish.

The sequence is **five stages**, and both sides derive their boundaries from
`dev.rasengan.SummonTimeline` — a COMMON class, so the server's reveal beat and the client's smoke
cannot drift apart. It joins `PalmAnchor` and `Palette` as shared cosmetic geometry.

| stage | tick | time | beat |
|---|---|---|---|
| — | **0** | 0.0 s | **SPAWN** — the dragon is created here, *hidden*, frozen, invulnerable. Buildup sound starts. |
| **A** seal | 0 – 12 | 0.0 – 0.6 s | rune circle spreads outward from the palm-strike point on an ease-out; chakra threads drawn inward; sub-bass begins |
| **B** eruption | 12 – 32 | 0.6 – 1.6 s | **the centrepiece** — dense white column erupts and fully obscures the arrival point; rolling ground fog outruns it |
| **C** reveal | **32** – 49.17 | **1.6** – 2.46 s | **REVEAL** — the dragon becomes visible, hittable and audible. Roar, awakening line, fear reaction. Smoke thins to uncover outline → wings → head |
| **D** dispersal | 49.17 – 61.17 | 2.46 – 3.06 s | the **wing downbeat** throws the standing smoke violently outward; ground dust ring; short high-frequency rumble |
| **E** settle | 61.17 – 70 | 3.06 – 3.5 s | residual wisps fade, ground fog last; camera eases back; flight AI takes over |
| — | **70** | **3.5 s** | sequence complete; `SummonEnd` sent; the bar resumes charging |

**Total: 70 ticks = 3.5 s.** Only two of those numbers are config values — `cinematic_ticks` and
`reveal_tick`. The rest are *derived*, by distributing the reference stage durations proportionally on
each side of the reveal. That is deliberate: five independent tick knobs can be set to contradict each
other and produce a zero-length or out-of-order stage, two cannot. Measured across **11 322
configurations** (`cinematic_ticks` 20–400 × `reveal_tick` 5–total): **0 stage-order violations**.

### Why stage D's boundary is 49.17 and not 48

Stage D has to be *caused* by the wing downbeat, which means the impulse and the wing pose must share
an instant rather than a neighbourhood. Rather than bend the animation to hit a round tick, the stage
boundary moves to the nearest real downbeat — the animation stays as authored and the timeline
accommodates it. The nominal boundary is tick 48; the chosen downbeat is 49.1667, a shift of **1.1667
ticks**, which is why stage C measures 0.858 s against its 0.8 s target.

The downbeat instants come from the shipped animation, not from taste. `animation.rasengan_dragon.fly`
declares `animation_length = 1.9583 s` = **39.1667 ticks**, and its `Lwing` Z-rotation track runs
−40° → 0 → +39.66° → back, so peak angular velocity is at the zero crossings: **tick 10.0** and tick
30.0 of the cycle.

Which crossing is the *down*stroke is settled from geometry, not assumed.
`tools/dragon/convert_gltf_to_geckolib.py` recovered GeckoLib's bake convention out of
`GeometryBone.bake` bytecode: `pivotX = -pivot.x`, `baseRotZ = toRadians(+rotation.z)` — X is
mirrored, Z is used as written. In the geometry `Lwing` pivots at x = +13 and its tip chain runs to
x = +119, so after the X mirror the wing extends along **−X** in render space with a tip offset of
dx = −106. Rotating that about Z by θ moves the tip to dy = dx·sin θ = −106·sin θ, so **increasing Z
lowers the tip**. The Z track increases across t = 0 → 0.958 s, making that half-cycle the downstroke
and tick 10.0 its peak. Cross-check: `Rwing` mirrors the track and pivots at x = −13 with its tip at
x = −119, giving dx = +106 and the same physical direction — both wings up at t = 0, both fully down
at t = 0.958 s, as a mirrored pair must be.

The wing phase is then **forced** rather than left to free-run. During the arrival,
`DragonEntity.registerControllers` calls `AnimationController.setAnimationTime` with
`SummonTimeline.entranceWingPhase(age)`, so the rendered pose is a pure function of the dragon's own
age on every client. Left alone, GeckoLib anchors the phase to whenever the controller was first
initialised — which, read out of `AnimationController.checkControllerState`, is the first frame *that
client* actually rendered the dragon. A player who was looking elsewhere at the reveal and turned
around later would have got a different phase from everyone else and seen their wings beat out of step
with their own smoke. Forcing it also cancels the transition offset, since `AnimationTimeline.create`
prepends the controller's transition as a stage and pushes the animation's own t = 0 that much later.

Measured: at the shipped defaults the dispersal instant coincides with downbeat *n* = 1 to
**0.000e+00 ticks**, and `entranceWingPhase(dispersalStart)` returns exactly **10.0000**.

### Why the dragon is spawned on tick 0, hidden

Creating a 300 HP GeckoLib boss is not free: attribute setup, goal construction, a chunk touch
server-side, and on each client the first-ever upload of a 1024×1024 texture. Doing all of it on the
reveal frame put the cost on the one frame the player is most likely watching closely, and a
frame-time spike there is indistinguishable from a camera fault. So the entity is created on tick 0
and held hidden, frozen (`setNoAi`) and invulnerable until the reveal.

`DragonRenderer.shouldRender` refuses it while hidden — refused there rather than drawn transparent,
because a transparent draw still costs a full model submission and still binds the texture, putting
back exactly the cost this moves. The boss bar is added at the reveal rather than on spawn, so no bar
appears above an invisible entity and gives the reveal away.

One thing this does *not* fix, checked rather than assumed: GeckoLib geometry and animations are
**already** warm. `GeckoLibResources` is a `PreparableReloadListener` that bakes every model at
resource-reload time, and `BakedModelCache.getModel` only reads from the map it populated — it logs
and returns a placeholder rather than baking on demand. The texture is the genuinely lazy part, and
`DragonAssetWarmup` touches it when `SummonStart` arrives.

The seal, the smoke column and the dragon are all **concentric**, 9 blocks in front of the summoner.
The previous version centred the seal on the player and spawned the dragon 8 blocks away, which put
the dragon outside its own smoke cloud — whatever the cloud did, the dragon would have been plainly
visible beside it. Measured horizontal offset between dragon and seal centre: **0.00e+00 blocks**.

The bar resumes charging only at tick 70, not at the reveal — otherwise the HUD would show a
refilling bar while the dragon was still arriving.

### Visible to everyone nearby, not just the summoner

`SummonStart` goes out via `sendToPlayersNear` over `max_effect_distance`, and it carries the seal
centre, the timeline and a **seed**. Every client derives every ring, rune and smoke puff from
`(seed, elapsedTicks)`, so observers and summoner draw the same cinematic with **no per-particle
traffic** — the same rule the ability effects follow. Measured: the summoner and an observer 6 blocks
away received *identical* `SummonStart` payloads, 41 bytes each.

The seal is pinned to where the player stood at the moment of casting, not to the player, so it does
not slide around if they walk off during the sequence.

### The camera hitch on release — what was actually wrong

The camera used to lag on return. Four candidate causes were checked against the code before anything
was tuned. **Two were already fine and were left alone**, which is worth recording so nobody
"re-fixes" them:

- **Not server-driven.** The camera was already entirely client-local: one `SummonStart` packet, then
  a local timeline read at render time. There were never per-tick server corrections to stutter at low
  or variable TPS.
- **No input suppression.** Only `ViewportEvent` render angles were ever touched, never the player's
  actual yaw or pitch. So no input was queued or discarded, and there was nothing to flush on release
  — which is also why there is no snap when the effect ends: the offset simply goes to zero.

Three were real:

1. **The third-person distance stepped at 20 Hz.** `onDetachedDistance` called the intensity envelope
   with a hard-coded `partialTick = 1.0F`, while roll and FOV were called with the frame's real
   partial tick. So the one part of the effect that actually *translates* the camera moved once per
   tick while everything else moved every frame — a visible stair-step, worst exactly where the
   envelope is steepest. `CalculateDetachedCameraDistanceEvent` carries no partial tick, so one is now
   taken from the frame's own `DeltaTracker`.
2. **A hard cut at the distance boundary.** Eligibility was a binary `distanceToSqr > 64*64` test, so
   an observer crossing 64 blocks mid-sequence had roll, FOV and distance snap from full value to zero
   **in one frame**. Replaced with a smoothstep taper across the outer quarter of the radius.
   Measured: max change **0.0234** per 0.25 blocks of movement, against **1.00 in a single frame**
   before.
3. **An interrupted sequence never told the client** — the worst of the three. The server dropped its
   pending entry and said nothing, so every nearby client ran the timeline to its natural end, still
   rolling the camera for a summon that had been abandoned because the summoner died. Now there is a
   `SummonEnd` packet; see below.

The fourth candidate — heavy work landing on the release frame — was real too, and is fixed on the
server side by the hidden pre-spawn described above.

### The release blend

The envelope no longer reaches zero incidentally; it has an explicit release phase. Over the last
`camera_release_ticks` (default **12 ticks = 0.6 s**, inside the requested 0.4–0.8 s band) the whole
effect is multiplied by a smoothstep from 1 to 0.

Smoothstep rather than linear matters: it makes the camera's **velocity** zero at both ends of the
blend as well as its position. A linear ramp returns the view on time but with a visible change of
direction at each end, which is itself the hitch it is trying to remove. Measured on the shipped
curve: `f(58) = 1.000000`, `f(70) = 0.000000`, monotonic throughout, with
`d/dt` = **−0.000104** at the start, **−0.125003** in the middle and **−0.000104** at the end.

### The camera cannot get stuck

Defended five ways now:

1. **Player input is never taken away.** Look and movement stay under the player's control for the
   whole sequence. The effect is a *nudge*: 2.4° roll, FOV ×1.16, two short rumbles, and in third
   person a ×2.5 pull-back.
2. **It is a pure function of elapsed ticks.** Every factor — the framing arc, the distance taper and
   the release blend — is continuous and reaches exactly zero at its own boundary, so the composite
   cannot step. There is no "off" to fail to send.
3. **Interruption is explicit.** `SummonEnd/INTERRUPTED` is broadcast to the whole level (not a
   radius — a player who was in range at the start and has since walked out still has a live record,
   and telling them is the entire point). The client aborts emission and starts the release blend
   *from wherever the camera currently is*. Covered on the server for: summoner died, disconnected,
   turned spectator, changed dimension, or the dragon was removed.
4. **The record self-expires** past its end plus 40 ticks regardless of what else happens, and
   `LevelEvent.Unload` clears everything — which matters for a dimension change, because the new
   level's game time jumps arbitrarily and an inherited record could place the camera anywhere in its
   window.
5. **It can be switched off entirely** — `camera_effect = false` leaves every camera completely alone
   while **all** smoke, seal and fog visuals still play. On the wire that is `cameraReleaseTicks = 0`,
   which the payload reports as disabled and the release factor evaluates to `0.000000` at every tick.

---

## 3. The awakening lines

**64 original lines**, all written for this mod. Sassy, reluctant, world-weary. Examples:

> **[Demonic Wingwalker]** Someone drew a circle and expected miracles. Again.

> **[Demonic Wingwalker]** Make this quick and I won't mention it to the other elders.

Content rules, all checked: no profanity, no targeted harassment, no real-world political or
religious references, nothing copied from an existing work.

### Selection is server-side and fires exactly once

The server picks the line and sends **finished text**. If each client picked its own, two players
watching the same summon would read different dialogue — exactly the desync the rest of this mod
exists to avoid. Measured: the summoner and a nearby observer received the *identical* `Component`,
and the line fires once per summon, on the reveal tick.

Styled through text components — `[Demonic Wingwalker]` in dark grey, the line in bold ember orange
`0xFF7A29`, deliberately distinct from the cyan ability palette so the dragon reads as its own voice.
**No legacy `§` codes anywhere**, asserted across all 64 lines and across the assembled message.

### Stored as a datapack resource

`data/rasengan/summon_lines/awakening.json`, loaded by a `SimpleJsonResourceReloadListener`. A long
list of prose does not belong in a TOML config: it would be unreadable and awkward to quote.

`/reload` applies edits with **no restart** — measured: adding a line to the file moved the speakable
count from 64 to 65 on reload.

There are exactly three supported ways to change the dialogue:

1. edit the shipped `rasengan:awakening` file;
2. override that same path from a datapack (measured: a datapack override replaced all 64 with its
   own 3);
3. ship a new file and point `lines_resource` at it.

Adding an *unrelated* extra file is deliberately **not** one of them. Every file under the directory
is loaded, but only the one named by `lines_resource` is spoken from — that is what makes the config
option mean anything. Measured: with an extra 2-line file present, 5 lines were loaded and 3 were
speakable, and the 2 extras never appeared in 4000 draws.

Pooling every loaded file is the *fallback* only, for when the configured file is missing or empty. A
typo in the config makes the dragon say the wrong lines, not fall silent; and if no file loads at all
there are 3 hardcoded lines behind that, because a broken datapack should not silence a boss.

---

## 4. Sound

Two sounds, both **generated from scratch** by
[`tools/generate_summon_sounds.py`](tools/generate_summon_sounds.py) using only the Python standard
library — stdlib oscillators and shaped noise, no samples, no third-party audio:

| file | duration | bytes | phase |
|---|---|---|---|
| `summon_buildup.ogg` | 1.60 s | 19,691 | rumble under stage A, swelling through B, cuts on the reveal |
| `summon_reveal.ogg` | 1.45 s | 18,792 | eruption/arrival impact at the reveal, spanning C and D |

Both are Vorbis, mono, 44.1 kHz. **Mono is not cosmetic** — Minecraft plays stereo sounds
*non-positionally*, so a stereo file would ignore distance attenuation entirely and be as loud at 40
blocks as at 2.

Both durations were **regenerated** for the five-stage rebuild, and are now derived at the top of the
script from the same reference layout as `SummonTimeline` rather than hard-coded. They were 3.50 s and
1.60 s when the reveal sat at tick 70; a 3.50 s buildup would now drone straight through the reveal,
the dispersal and the settle — the precise thing its abrupt cut exists to avoid.

Both are played by the server **positionally, at the dragon's arrival point**, so every listener hears
them attenuate from where the creature actually is, and both respect the `sound_volume` multiplier.

There is no licensing question about either — see [`AUDIO_CREDITS.md`](AUDIO_CREDITS.md), where they
are recorded separately from the ability audio whose provenance is still unresolved.

### The optional roar is not shipped

A third sound was supplied for the summon. It **could not be cleared for redistribution** and is not
in this repository. The short version: its container carries no DASH brands, which is the screen that
rejected two earlier candidates — but it was written by FFmpeg (`encoder: Lavf60.16.100`), and an
FFmpeg remux replaces the container and therefore erases exactly those brands, so their absence proves
only that FFmpeg touched it. Full metadata table and reasoning in
[`AUDIO_CREDITS.md`](AUDIO_CREDITS.md).

It is wired as a local-only asset instead: `tools/install_summon_roar.sh <file-or-url>` converts a
file you supply and writes a gitignored `summon_roar.ogg`. Playing it needs no guard — a missing sound
file logs one warning at resource load and the event becomes a silent no-op — so the mod is complete
without it and nobody who skips it sees an error.

Interrupting the sequence **cuts the audio**. `SummonEnd/INTERRUPTED` stops the buildup by sound id,
because a rising drone that resolves into nothing is worse than silence. The roar is only stopped if
the reveal had not yet landed: past that the dragon is real and its arrival roar belongs to it, not to
the cancelled cinematic.

---

## 5. Config

All under `[summoning]` in the server config:

| key | default | meaning |
|---|---|---|
| `charge_duration_seconds` | `300` | POWER BAR — SUMMONING charge time (0% → 100%) |
| `cinematic_ticks` | `70` | total sequence length (70 = 3.5 s) |
| `reveal_tick` | `32` | tick the dragon becomes visible and speaks (32 = 1.6 s) |
| `camera_effect` | `true` | enable the camera move at all; **all visuals still play when false** |
| `camera_release_ticks` | `12` | ticks over which the camera eases back (12 = 0.6 s) |
| `camera_radius` | `64.0` | blocks within which an observer gets the camera move, with a taper |
| `smoke_density` | `1.0` | multiplier on smoke particle counts |
| `sound_volume` | `1.0` | multiplier for the summon sounds |
| `announce_globally` | `true` | line goes to every player, or only those within `max_effect_distance` |
| `lines_resource` | `rasengan:awakening` | which lines file is spoken from |

And under `[fear]`:

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | enable the fear reaction |
| `radius` | `24.0` | blocks from the dragon within which hostiles are frightened |
| `duration_ticks` | `140` | how long a frightened mob flees (140 = 7 s) |
| `suppress_attacks` | `true` | also switch off targeting while fleeing |
| `boss_immune` | `true` | exempt entities in the `rasengan:fear_immune` tag |
| `player_vignette` | `true` | brief screen-edge vignette pulse for nearby players |

**Removed from `[summoning]`:** `camera_start_tick` and `camera_end_tick`. The framing window is now
derived from the stage layout, and `camera_release_ticks` replaces them. An existing config file with
the old keys still loads; NeoForge drops keys the spec does not declare on the next write.

`smoke_density` below about **0.6** will start leaving the dragon visible through the cloud — the
default is tuned against the obscuration measurement, not chosen for looks.

The keybind is **L** by default (`key.rasengan.summon`), registered through the same keybind pattern
as the existing ability keys and remappable in Controls like any other.

---

## 6. The smoke

The defining visual of a summoning is a dense burst of white smoke that swallows the summon and then
dissipates to reveal the creature already standing there. The smoke is the star, not the seal — so it
gets its own particle class rather than another `EnergyParticle.Style`.

`SmokeParticle` is **alpha-blended**, unlike everything else this mod draws. That single requirement
drives the split: the energy particles are additive and emissive because their job is to *add* light,
and additive blending can only ever brighten. The reveal depends on the dragon being **hidden**, and
you cannot hide something by adding light to it.

What makes it read as smoke rather than as a cluster of grey balls:

- **Drag, not constant velocity.** `Particle.tick` applies `xd *= friction` every tick, which is the
  exact discrete integration of linear drag. `BILLOW` runs `friction = 0.855`, so it leaps out and
  decelerates hard — bursts fast, then billows slowly. That deceleration curve is the single most
  important cue.
- **Individual rotation.** Each billboard gets a random initial `roll` and its own spin rate and
  direction, so the cloud churns instead of sliding as a flat sheet.
  `SingleQuadParticle.extract` already interpolates `oRoll → roll` across the partial tick, so it is
  smooth at any frame rate for free.
- **Frayed sprites.** Four noise-perturbed puff textures, chosen at random per particle. The alpha is
  a radial falloff whose *radius* is displaced by fractal noise, rather than a clean disc multiplied by
  noise — perturbing the radius makes the boundary itself irregular, where multiplying merely mottles
  the interior and leaves a detectable circular edge. Generated by
  `tools/generate_particle_textures.py`, stdlib only.
- **Growth.** Every puff expands **2.15×** over its life, so the cloud keeps inflating after its
  velocity has died.
- **A flat ground layer.** `GROUND_FOG` is drawn as a horizontal quad via a custom
  `FacingCameraMode`, has `hasPhysics` and `speedUpWhenYMotionIsBlocked` set, and so accelerates
  sideways when it meets the floor — vanilla behaviour that happens to be exactly what a rolling fog
  layer wants.

### Continuity

Size and opacity are **pure functions** of the continuous life fraction, evaluated at render time.
This matters specifically here: vanilla's `extractRotatedQuad` interpolates position and roll across
the partial tick but passes the `alpha` **field** straight through, so a fade written in `tick()`
arrives quantised to 20 Hz. For a 55-tick billow the largest per-tick alpha change is **0.246** — very
visible as a pulse on a six-block billboard. `SmokeParticle` overrides that method to evaluate both
curves at the frame's exact time.

Continuity is asserted by refinement rather than by a step threshold, which is the test that actually
distinguishes a fast curve from a discontinuous one: halving the sample interval must halve the
largest step. Measured ratios, where 4.0 is ideal and 1.0 would mean a jump: **opacity 4.00, size
3.99**. Both curves reach exactly `0.00000000` at spawn and death, so no puff pops in or out.

### Obscuration — measured, not asserted

The reveal only works if the arrival point is genuinely hidden at peak. `SummonCinematicAudit`
measures it: it builds the cloud as it stands at the reveal tick using the **shipped**
`SummonCinematic.columnPuff` distribution and the shipped `sizeAt`/`alphaAt` curves, integrates the
drag exactly (smoke has `hasPhysics == false`, so position is the exact geometric sum of the drag
series), then casts rays from the summoner's eye through the dragon's body volume and composites
`1 − Π(1 − αᵢ)`.

| | |
|---|---|
| column puffs alive at the reveal | **289** |
| sample points across the body volume (6.0 × 4.8 × 6.0) | **343** |
| accumulated opacity, minimum | **0.9725** |
| accumulated opacity, mean | **0.9997** |
| points ≥ 0.95 | **343 of 343 (100.0%)** |

This measurement **found a real gap**. With a uniform spawn-height distribution the base of the cloud
measured **0.8833** while everywhere else was at 0.998 — a faint silhouette of the dragon's feet
showing through, concentrated on the near side. Every puff rises over its life, so a uniform height
leaves the bottom progressively thinner, and the summoner's eye at 1.62 blocks is looking through
exactly that region. Fixed by biasing the spawn height toward the base (`pow(random, 1.45)`) and
raising the column rate to 28/tick.

### Level of detail

Made explicit rather than implicit. The seal and the main column are **mandatory** — `Lod.count` never
returns below its floor, because a culled column means the summon is not hidden and the reveal breaks.
Wisps, ground fog and near-camera motes go through `Lod.countOptional`, which may legitimately reach
zero. `SMOKE_BILLOW` is also registered with `overrideLimiter = true` so the client's particle-count
limiter cannot drop the load-bearing part; density is still scaled by the player's own particle
setting, so "Minimal" is respected — it just cannot cull the column to nothing.

---

## 7. The fear effect

On the reveal beat, hostile mobs within `[fear] radius` visibly panic.

**Implemented as real behaviour, not a debuff.** A slowness effect would say that something
frightening happened without anything *looking* frightened. `DragonFearManager` inserts a live
`FleeFromDragonGoal` at **priority 0** — above every goal the mob already has, so the flee wins the
`MOVE` flag — and the mob turns and runs using its own navigation. With `suppress_attacks` on, its
target is cleared and `targetSelector.disableControlFlag(Goal.Flag.TARGET)` stops it re-acquiring, so
it does not stop mid-retreat to fight.

Not `AvoidEntityGoal`: that avoids a *class* of entity and only while that entity is inside its radius,
so it would stop the moment the dragon flew off — which is most of the time, at half a block per tick.
The requirement is a duration, which is a timer, not a proximity test. Destinations still come from
`DefaultRandomPos.getPosAway`, the same helper vanilla's goal uses, so "away" respects walkable
terrain. A cornered mob that cannot path keeps the goal running and retries rather than giving up,
because giving up would silently revert it to attacking and look like the fear had failed.

**Cleanup has three independent paths**, because a temporary goal left behind is a mob that flees
forever — far worse than no fear at all:

1. **expiry** — the per-tick sweep removes the goal when its timer runs out;
2. **the dragon ending** — `canUse()` goes false the moment the dragon dies or is removed, so the goal
   stops even before the sweep reaches it;
3. **the mob leaving** — the sweep drops entries whose mob is removed, covering death, despawn and
   chunk unload. The goal selector belongs to the entity and dies with it, so an unloaded mob cannot
   leak a goal into anything that survives.

`clearAll()` empties the register on shutdown as well, so nothing carries between server lifetimes.

**Boss immunity is a tag, `rasengan:fear_immune`** — and a tag this mod defines, because **26.1 has no
boss entity-type tag**; `EntityTypeTags` contains nothing boss-related, so there was nothing to reuse.
Shipping our own is better anyway: a server owner can add a modpack's bosses from a datapack without
touching code. Ships with our dragon, the Ender Dragon, the Wither, the Warden and the Elder Guardian.

**Players are never force-moved.** Taking the controls away from a player to represent an emotion is a
bad trade, and this mod's rule is that the player always steers. Their share is entirely cosmetic: a
brief screen-edge vignette pulse (`SummonVignette`, four edge gradients, no asset, behind
`[fear] player_vignette`) and the distance-scaled camera rumble (behind `[summoning] camera_effect`).
Measured on a live server:

| | |
|---|---|
| hostiles inside the radius, frightened | **3 of 3** |
| hostile outside the radius | **not frightened** |
| the dragon itself | **did not frighten itself** |
| furthest a frightened mob got from the dragon | **+7.78 blocks** |
| fear duration | **141 ticks** against 140 configured |
| leftover flee goals after expiry | **0** |
| mobs left with targeting suppressed | **0** |
| fear applied by a sequence that never reached its reveal | **0** |

---

## 8. Verified on a dedicated server

`tools/verify/run_summon_test.sh`. Committed output:
[`tools/verify/evidence/summon-audit.txt`](tools/verify/evidence/summon-audit.txt).

**123 assertions, 123 pass, 0 fail.**

There is also a second, much faster harness that needs no server, no display and no sound device,
because everything it measures is a pure function of elapsed time:
`tools/verify/run_cinematic_audit.sh` → [`tools/verify/evidence/cinematic-audit.txt`](tools/verify/evidence/cinematic-audit.txt).
**37 assertions, 0 failures** — the stage layout, the downbeat alignment, the camera release blend and
distance taper, the smoke curves, and the obscuration measurement. Run that one while iterating; the
server harness costs a ~135 s boot.

### New in the rebuild

| check | result |
|---|---|
| Dragon exists on the cinematic's first tick | yes — hidden, invulnerable, `NoAi` |
| `SummonStart` carries the pre-spawned dragon id | yes, id 12 matched the live entity |
| Seal / smoke / dragon concentric | **0.00e+00 blocks** horizontal offset |
| Seal placed in front of the summoner | **9.00 blocks**, not underfoot |
| **Reveal tick** | **summon + 32, exactly** |
| Dragon becomes hittable at the reveal | invulnerability and `NoAi` both lifted on that tick |
| `SummonEnd` on normal completion | exactly 1, to both players, byte-identical, `COMPLETED` |
| `SummonEnd` on interruption | `INTERRUPTED`, sent to the observer |
| Never-revealed dragon after interruption | **discarded** — 0 dragons left in the world |
| Unknown `SummonEnd` reason | falls back to `INTERRUPTED`, so a version mismatch releases the camera rather than stranding it |
| Stage order across 11 322 configurations | **0 violations**; 89.3% keep a wing-aligned dispersal |
| Dispersal coincides with a real downbeat | **0.000e+00 ticks** of error; wing phase there is exactly 10.0000 |
| Camera release blend | `f(start) = 1.000000`, `f(end) = 0.000000`, monotonic, velocity ≈ 0 at both ends |
| Camera distance taper | max **0.0234** change per 0.25 blocks, against **1.00 in one frame** before |
| Camera disabled (`camera_effect = false`) | release factor **0.000000** at every tick; visuals unaffected |
| Smoke obscuration at the reveal | min **0.9725**, mean **0.9997**, **343/343** points ≥ 0.95 |
| Smoke curve continuity (refinement ratio) | opacity **4.00**, size **3.99** — continuous, no jumps |
| Fear applied / cleaned up | 3/3 in radius, 0 outside, **+7.78 blocks** fled, **0** leftover goals |
| `fear_immune` tag loaded | dragon/wither/ender_dragon true, zombie false |

### From the original audit, still passing

| check | result |
|---|---|
| Fresh bar starts `CHARGING`, accumulates | charge = 3 after 3 ticks |
| The two bars are independent | cast bar at 8 ticks while summoning bar forced to 6000 |
| `trySummon` refused while `CHARGING` | refused, and explained: *"Your summoning power is still gathering."* |
| `trySummon` at `READY` | succeeds; bar → `CASTING`; charge spent immediately, not on completion |
| Second press mid-cinematic | ignored, and **silently** — no chat spam |
| `SummonStart` reaches a nearby observer | yes, and byte-identical to the summoner's |
| `SummonStart` contents | `total=70 reveal=32 release=12 radius=64.0 density=1.0`, summoner and dragon entity ids |
| **Awakening line fires** | **exactly once**, on the reveal tick |
| Line identical for both players | identical `Component`; no `§` codes |
| One dragon per player | second summon refused with a message; no second dragon; no cinematic started |
| Sequence cleanup | at summon + 70 the cinematic is gone and the bar is back to `CHARGING` |
| Hand-off to normal flight AI | **0 stalled ticks**, `isEntering()` false afterwards |
| Gate reopens after death | dragon pruned, `trySummon` succeeds again |
| *(not covered)* | the one-per-player limit **across a server restart** — see the limitation noted in §1 |
| `/reload` re-reads the lines | 64 → **65** with no restart |
| Datapack override | 3 speakable / 5 loaded; the 2 unselected lines never spoken in 4000 draws |
| Payload codecs round-trip | `SummonStart` exact; `SummonEnd` 2 bytes each reason; `SummonActivate` 0 bytes |
| Every line reachable | 4000 draws yielded all 64 distinct |
| Client isolation intact | **48** non-client classes, **0** referencing `net.minecraft.client` |

### How a player was simulated with no display

No real client can connect here, so the harness inserts two genuine `ServerPlayer` instances into the
real `PlayerList` and the real level, with a packet listener that *records* outbound packets instead
of writing them to a socket. Everything the summon code touches — player-list membership,
distance-based broadcast, attachments, the tick event, the real payload codecs — is therefore the real
implementation. The harness lives in `tools/verify/` and is staged into the source tree only for the
duration of a run, then deleted by an `EXIT` trap, so it cannot reach a commit or a published jar.

### Two measurement traps worth recording

Both of these initially looked like mod bugs and were not.

- **`ServerPlayer.tick()` does not call `Player.tick()`.** In 26.1 the level's entity tick runs only
  the light half of the player tick. `Player.tick()` — where NeoForge fires `PlayerTickEvent`, and
  therefore where *both* charge bars advance — is reached from `ServerPlayer.doTick()`, which is
  called once per tick by `ServerGamePacketListenerImpl.tickPlayer()`. A synthetic player has no
  network tick, so both bars sat at 0 forever and looked exactly like a charging bug. The harness now
  stands in for precisely that one call.
- **Testing the ownership gate during the cinematic tests the wrong branch.** `trySummon` returns
  early on "already mid-cinematic", which is silent by design, *before* it reaches the
  one-dragon-per-player check. A refusal test placed at reveal + 4 measured that branch and reported
  the ownership gate as unexplained. It has to run after the sequence ends, with the dragon alive.

### Two real issues the audit caught

- **The reveal sound played twice.** `DragonEntity` replayed the same 1.6 s `summon_reveal` sample
  0.3 s after `ServerSummonManager` had already played it, at pitch 0.85 and bypassing the
  `sound_volume` config. Two overlapped copies of one sample is comb filtering, not layering.
  Removed; the entrance flourish is now silent and the server owns the single reveal sound.
- **The lines loader over-promised.** Its javadoc claimed a datapack could *add* lines by dropping in
  a new file. It cannot: `pick()` prefers the `lines_resource` file, so an added file is loaded and
  never spoken. The documentation was corrected to the three ways that do work, and the misleading
  `size()` — which reported the merged total the dragon could not actually reach — was split into
  `poolSize()` and `loadedTotal()`.

### NOT verified — no display, no audio device, no second client

Everything below is unverified, and nothing in the audit above should be read as covering it. This
list matters more than usual for this change, because the change is mostly *visual*.

- **The frame-time claim is not measured.** The camera hitch was diagnosed by reading the code, and
  the two arithmetic defects — a 20 Hz step in the third-person distance and a one-frame cut at the
  distance boundary — are measured *as arithmetic* above. Whether the hitch a player felt is now gone
  is **not measured**, because measuring frame times needs a display and there is none here. The task
  asked for frame timings across the transition and they cannot be produced in this environment. What
  is established: the stepping and the cut are gone from the curves, and the dragon's construction no
  longer lands on the reveal frame. Whether any *remaining* spike exists is unknown.
- **That the smoke looks like smoke.** The obscuration number is real — 343/343 rays at ≥0.95 opacity
  — but that is optical depth, which is a *necessary* condition for the reveal to work and not a
  sufficient one for it to look good. Whether the cloud churns, whether the fraying reads as wisps,
  whether 289 overlapping billboards read as volume or as mush: not observed. Nor is the frame cost of
  drawing them.
- **The staged silhouette reveal.** That the outline appears first, then the wings, then the head. The
  *reason* to expect that ordering is geometric — the column is densest at the centre and the dragon's
  extremities sit further out — but nobody watched it happen.
- **That the dispersal reads as caused by the wings.** The impulse and the forced wing phase provably
  share an instant (0.000e+00 ticks of error, phase exactly 10.0000), and the downbeat direction is
  derived from the model's geometry under GeckoLib's own bake convention. But whether the stroke *looks
  like* a downbeat on screen, and whether the smoke appears pushed by it, are unobserved. If the
  derivation's sign is wrong somewhere, the dispersal would land on the **upstroke** — visually wrong,
  and nothing in this environment would catch it.
- **The camera moment as an experience.** Whether 2.4° of roll and FOV ×1.16 read as cinematic or as
  nausea; whether 0.6 s is the right release length; whether two rumbles are better than one. The
  *state* cannot get stuck — every factor is a pure function of elapsed ticks, reaches exactly zero,
  and is now also explicitly cancelled by `SummonEnd` — but that is a property of the arithmetic, not
  an observation of a real camera.
- **The vignette.** That four edge gradients read as a vignette rather than as black bars, and that it
  does not obscure the thing it is reacting to.
- **That any sound is audible or pleasant.** Duration, container, codec, channel count and sample rate
  are measured. Nothing was heard. The supplied roar was never played.
- **The fear reaction as a spectacle.** The mobs demonstrably flee — +7.78 blocks, measured — and the
  goals demonstrably clean up. Whether the fear particles above their heads are legible, and whether
  the stagger reads as a recoil rather than as a glitch, are not observed.
- **Multi-client replication and real latency.** Both synthetic players ran in the server process, so
  the identical `SummonStart` and `SummonEnd` payloads prove the server *sent* the same bytes to both.
  They do not prove two real clients, on a real connection with real jitter, draw the cinematic in
  step. The design reason to expect they will — one seed, one server-owned clock, a shared
  `SummonTimeline`, everything else derived — is a reason rather than a measurement. The forced wing
  phase is the part most likely to hold up under latency, since it is anchored to entity age rather
  than to arrival time.
- **The hand-seal cast pose** on the player model, and the second HUD bar's appearance.
