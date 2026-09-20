# Riding the dragon

The mount/ride phase, built on top of the standalone boss ([`DRAGON.md`](DRAGON.md)) and the summoning
cinematic ([`SUMMONING.md`](SUMMONING.md)).

You left-click the dragon's head, you end up **standing on it**, you double-tap **W** to launch, and
from then on it flies where you look — elytra-style. It answers only to the player who summoned it.

---

## 1. Decisions, stated explicitly

Each of these had a plausible alternative. The alternative is recorded so it can be argued with.

### The rider stands, locked to one anchor point

The anchor is a single fixed point on the skull plate. **You cannot walk around on the head.** This is
a deliberate simplification for this phase: a walkable surface on a body that banks 32°, pitches 75°
and beats its wings through a 1.23-block vertical arc would need its own collision surface moving with
the bones, and the realistic outcome is a rider who slides off during the first hard turn. One stable
anchor is the honest version of "standing on the head" until that surface exists.

### The rider stays upright; the dragon banks beneath them

Banking is applied to the **model only**. `DragonAnchor.basis` deliberately excludes roll, so the rider
does not roll with the turn. Rolling them would swing a standing player sideways through the air on
every turn and reliably clip them into the model — and for a standing rider, staying level is also just
what looks right.

### Left-click while riding does nothing to the mount

It stays a completely ordinary attack swing, so a rider can still fight from the dragon's head. It is
not forwarded as a mount request and is never reinterpreted as a command to the mount. It also cannot
hit the dragon underneath you: vanilla will not target your own vehicle, and the head is outside the
collision box anyway.

### A second "WW" while already airborne is ignored

The launch is a scripted eased climb that overrides steering. Re-entering it mid-flight would wrench
the camera away from a player who was mid-turn, so it is ignored once airborne. The alternative — an
extra vertical boost — was rejected for that reason; **holding jump** gives a controllable climb
instead, which is the same capability without the hijack.

### Dismounting mid-air applies normal fall damage

Stepping off a flying mount is exactly as dangerous as stepping off anything else that high, and making
it free would turn the dragon into a no-cost elevator. `[mount] dismount_fall_damage = false` waives it
by *resetting the fall distance* rather than absorbing damage — the fall simply is not counted.

One exception, deliberately not configurable: a player who **disconnects** while riding is set down
with their fall distance cleared. That config governs a *choice* to dismount, and losing your
connection is not one.

### A launch cuts the grounded perch short, but never the damage immunity

Two independent clocks. Immunity runs its full 10 seconds no matter what, because the guarantee is
"zero damage for exactly 10 seconds after the summon". The grounded hold ends at the same moment *or*
when the rider launches, whichever is first — holding a player who has just double-tapped W on the
ground for the remaining seconds would read as the mount being broken.

---

## 2. The head region

The head is a **genuinely separate region**, sized from the asset, not a reuse of the body hitbox.

| | model space, blocks |
|---|---|
| head box | x ±0.680, y 3.313 … 5.189, z −5.125 … −3.313 |
| size | 1.360 wide × 1.877 tall × 1.812 deep |
| rider anchor (rest pose) | 4.5625 up, 3.875 forward |

Every number comes from `tools/dragon/measure_head_anchor.py`, which reads the shipped geometry.

**The anchor is the skull plate, not the highest point.** The highest point is the horn pair — top
y 5.189, but only 0.25 blocks wide per horn and set out at |x| 0.43…0.68. Standing a player there would
look like a bug. The broad cubes between the horns top out at 4.5625, and that flat span is a surface.

**The head sits outside the collision box.** The box is 6.0 × 5.0, so it reaches 3.0 blocks forward; the
head centre is 4.22 blocks forward — measured 5.99 blocks from the entity origin on a live server. This
is not a detail, it is the reason for the whole mount packet: **vanilla picks entities for attack by
their collision box, so a left-click on the head never produces an `AttackEntityEvent` for the dragon at
all.** There is nothing to intercept.

> **Rejected alternative:** making the head a real multipart hitbox, as the Ender Dragon does. That
> would give vanilla something to pick, but it also re-routes *damage* through the parts and changes the
> hurtbox of the entire boss — a combat change smuggled in behind a mount feature, and one that would
> invalidate the existing damage evidence in `DRAGON.md`.

The hit test transforms the ray into the dragon's own frame and clips it against the exact box, rather
than inflating a world-aligned box to cover every orientation — which would make "the head" a 1.9-block
cube that also covered a chunk of the neck. Measured: the head occupies **20%** of a 16-block-wide
horizontal fan aimed at it, hits from the front, misses from the body, misses from the tail end, misses
once the dragon yaws 90°, and hits again from the new front.

---

## 3. Tracking the animated head

The rider's feet follow the head as it bobs. That is harder than it sounds, because **GeckoLib
evaluates bones only in the render path** — a dedicated server never runs an animation, and the mount is
server-authoritative.

`DragonAnchor` (COMMON, like `PalmAnchor` and `SummonTimeline`) answers it for both sides. Two findings
shaped it, both measured rather than assumed:

- **The whole neck-to-head chain animates on X only.** Maximum |Y| or |Z| rotation anywhere in
  Torso→Neck→bone→bone2→Head, across both `idle` and `fly`: **0.000000°**. The head never yaws or rolls
  relative to the body, so no per-bone quaternion chain is needed.
- **A sinusoid is not good enough.** A best-fit single sine tracks the flight bob to **1.92 cm** but
  misses the idle bob by **42.17 cm**, because idle head motion is not sinusoidal.

So the height is a **32-sample table of the real animation**, linearly interpolated.

| | worst-case error vs the exact animated position |
|---|---|
| flight, vertical | **2.46 cm** |
| flight, horizontal | 0.26 cm |
| idle, vertical | 0.54 cm |
| idle, horizontal | 0.09 cm |

That is the gap between the rider's feet and the *rendered* skull plate. The rider's position itself is
exact, because every observer draws them where the server put them.

### The animation phase is forced

The tables are indexed by animation phase, and the server only knows the phase because
`DragonEntity.registerControllers` **forces** it via `AnimationController.setAnimationTime` from the
entity's age. Left to free-run, GeckoLib anchors the phase to the first frame *each client* rendered the
dragon (read out of `AnimationController.checkControllerState`: `initializeNewAnimation` sets
`timelineTime = 0` on that first call regardless of entity age). A player who looked away at the wrong
moment would get a different phase from everyone else, and the rider would be drawn floating above or
sunk into the head by up to the full 1.23-block bob — differently on every screen.

Forcing it costs nothing: a phase derived from entity age *is* a free run, just with an origin everyone
agrees on. The summoning cinematic already did this for the wing downbeat; this extends it to always.

### The mount step-up

Mounting without a blend teleports the player 4.6 blocks upward in one tick. The anchor eases from the
base of the head up onto the plate over **8 ticks (0.4 s)** on a smoothstep.

The start point is *derived*, not remembered — the head's horizontal position at the dragon's own foot
height — because that is computable on both sides from replicated state alone. The blend clock is a
single replicated absolute tick (`DATA_MOUNT_TICK`) rather than a counting-down timer, so it costs one
datawatcher write per mount instead of one per tick, and both sides compute the same position from the
same clock. A counter ticked independently per side would drift, and the rider would be part-way up the
head on one screen and on top of it on another.

---

## 4. Server authority — by construction, not by a check

The requirement is that the riding client cannot be trusted with the dragon's position. That is
satisfied **structurally**, and it is worth being precise about why.

Vanilla only lets a client drive a vehicle when `vehicle.getControllingPassenger()` is the local player,
and `Mob.getControllingPassenger()` returns a passenger only if it `instanceof Mob`. A `Player` is not a
`Mob`. Therefore:

- `LocalPlayer` never sends `ServerboundMoveVehiclePacket` — it is gated on
  `vehicle.isLocalInstanceAuthoritative()`;
- `ServerGamePacketListenerImpl.handleMoveVehicle` would reject one anyway — it requires
  `vehicle.getControllingPassenger() == this.player`.

So `DragonEntity` deliberately **does not** override `getControllingPassenger`. The dragon's position is
computed server-side and reaches clients through ordinary entity tracking.

**What the client is still trusted for** is exactly two things, both legitimately the player's:

| | delivered by | why it is fine |
|---|---|---|
| look direction | `ServerboundMovePlayerPacket.Rot` | it is their camera |
| key state | `ServerboundPlayerInputPacket` → `ServerPlayer.getLastClientInput()` | vanilla's own replicated `Input` record |

Neither carries a position, a velocity, or an "I launched" assertion. **The double-tap is detected on the
server** by watching the forward flag's rising edges — the client never says "I double-tapped".

The cost is real and worth stating: the rider has **no client-side prediction**, so steering carries one
round-trip of input latency. For a mount that turns at 4.5°/tick that is a fair trade for a mount that
cannot be teleported by a modified client.

The mount request itself is a **fieldless** packet, the same contract as `SummonActivate`. It does not
name a dragon, carry a hit position, or claim the head was hit. The client's local ray-trace only decides
whether to *send* it, so an unrelated swing does not generate traffic; the server repeats the whole test
against its own view. Both sides run the same `DragonAnchor` code, so they agree.

---

## 5. Two traps that cost real time

Recorded because both produced behaviour that looked like a different bug.

### `setNoAi(true)` would have frozen the mount solid

The obvious way to stop the AI competing with the rider for the velocity. It does not work:
`Mob.isEffectiveAi()` returns false when `NoAi` is set, and `LivingEntity.aiStep` only calls `travel()`
when `isEffectiveAi()`. A ridden dragon with `NoAi` has the rider's velocity written and then **never
integrated** — it hangs motionless in the air.

The AI is suspended with goal **control flags** plus a suspended move control instead. And the move
control needed a real suspension, not just `stopFlying()`: with no destination its `tick()` still runs
`coast()`, which scales velocity by 0.90 every tick and would quietly bleed 10% per tick off the rider's
thrust.

### The dragon never landed, which silently disabled the launch

Found by the harness, not by reading. The perch turns gravity back on so the dragon lands — but
`DragonEntity.travel()` routes a flying dragon through a branch that applies **no gravity at all**, so it
just hovered at the 2 blocks above the seal it arrived at. `onGround()` stayed false, a rider mounting
during the grace was classified as **already airborne**, the ride went straight to `PHASE_FLYING`, and
the double-tap launch was unreachable. The harness reported `ridePhase=3` at mount and "the launch cut
the grounded perch short: FAIL".

Two fixes: `travel()` hands a perched dragon to the normal walking path, and `onMounted()` decides
"perched" from the **grace clock** first rather than from ground contact.

Its mirror image appeared on the way out: after the grace, gravity was still on and the dragon was
sitting on the ground, where the flying branch is unreachable because it requires `!onGround()`. Nothing
ever lifted it — the harness measured **9.53 blocks of travel in 99 ticks** against a 0.51 b/t cruise,
because it was walking. The grace now ends with an actual takeoff: gravity off per config plus one
upward impulse to break ground contact. Travel went back to **47.30 blocks, 0.478 b/t, 0 stalled ticks**.

---

## 6. Config

All under `[mount]`:

| key | default | meaning |
|---|---|---|
| `enabled` | `true` | allow riding at all |
| `summoner_only` | `true` | only the summoner may mount |
| `double_tap_window_ticks` | `6` | max ticks between the two W presses (300 ms) |
| `launch_ticks` | `30` | length of the eased launch climb (1.5 s) |
| `launch_height` | `22.0` | blocks of altitude the climb gains |
| `max_speed` | `0.9` | top speed under player control, blocks/tick |
| `acceleration` | `0.08` | fraction of the speed gap closed per tick |
| `turn_rate_degrees` | `4.5` | max yaw change per tick |
| `pitch_rate_degrees` | `3.5` | max pitch change per tick |
| `bank_limit_degrees` | `32.0` | max roll during a turn (cosmetic) |
| `spawn_immunity_seconds` | `10` | grounded, undamageable window after the summon |
| `dismount_fall_damage` | `true` | whether a mid-air dismount hurts |

`summoner_only = false` lets **any** player mount any summoned dragon — there is no trusted-player list
yet, so false means everyone. A future trusted list would slot in here without changing anything else.

---

## 7. Verified

Two harnesses. `tools/verify/run_summon_test.sh` → **154 assertions, 0 fail**
([`summon-audit.txt`](tools/verify/evidence/summon-audit.txt)), and the server-free
`MountAudit` → **46 assertions, 0 fail** ([`mount-audit.txt`](tools/verify/evidence/mount-audit.txt)).

Rider input in the live harness goes through `ServerPlayer.setLastClientInput` — vanilla's own replicated
`Input`, the same field the real packet writes. The harness presses W and lets the server's detector
decide; it never calls the launch directly.

| check | result |
|---|---|
| **Damage immunity window** | last blocked at grace+**195t**, first landed at grace+**200t**, configured **200t** |
| Damage attempts | 46, across player attack / fire / fall / generic; 3 landed, all after expiry |
| Immunity armed on the reveal | 199t of 200 on the tick after the reveal |
| Mounting during the grace | allowed, and did **not** end the immunity |
| Ownership | summoner may mount; a second player may not, and is refused cleanly with no damage |
| Summoner persisted | the one-per-player limit **survives the in-memory map being cleared** |
| Head region separation | **5.99 blocks** from the entity origin, outside the 6.0-wide collision box |
| Head hit discrimination | front hits; body, tail-end, out-of-reach and post-yaw rays all miss |
| Head is a narrow target | **20%** of a 16-block horizontal fan |
| Mount on head click | mounted, `PHASE_PERCHED`, rider is the summoner |
| **WW launch** | `PHASE_LAUNCHING`, perch cut to **0t**, immunity still **189t** |
| **Launch is a curve** | first vy **0.00402**, peak **1.46265**, max step **0.15247**, no snap |
| Launch altitude | **21.82 blocks** gained against 22.0 configured |
| Launch integral | reaches the configured height within 2%, independent of window length |
| Turn-rate limit | never exceeded over a full 360° sweep; wraps the short way past 180° |
| Turn convergence | 0→120° in **27 ticks** at 4.5°/tick (26.7 predicted) |
| Bank curve | 0 at level, saturates at the limit, symmetric, eases in *and* out |
| Double-tap | held W for 60 ticks **never** launches; two taps in window launch **once**; slow taps never |
| Anchor bob | **1.227 blocks** of flight swing, max **0.0246** blocks per quarter-tick, periodic |
| Basis correctness | orthonormal to **1.3e-16**, model↔world round-trip **2.9e-15 blocks** |
| Dismount at altitude | rider released, `PHASE_NONE`, AI resumed, not left frozen |
| Dragon reverts to standalone AI | **47.30 blocks** over 99 ticks, 0.478 b/t, **0 stalled ticks** |
| Client isolation | **55** non-client classes, **0** referencing `net.minecraft.client` |

### Two real bugs the audits caught

- **The basis `up` vector had inverted x and z signs.** Worst |dot| between axes 0.9975 and a
  **5.97-block** model→world→model round-trip error. It would have put the rider metres off the head at
  any non-zero pitch. Also switched from `Mth.sin/cos` (table-based, ~1e-4 error) to `Math.sin/cos`,
  taking normalisation drift from 7.7e-05 to 2.2e-16.
- **The perch/landing bug** described in §5, which silently made the launch unreachable.

And one bad *assertion*, corrected rather than worked around: the bank easing was first tested at the
midpoint, where a smoothstep passes exactly through the linear value by symmetry, so a correct curve
failed. It is now tested at the quarter and three-quarter points.

---

## 8. NOT verified — no display, no second client

- **That the rider looks like they are standing on the head.** The anchor is within 2.46 cm of the
  rendered plate *by calculation*; nobody has seen a player stand there. Whether they appear to clip the
  horns, or sit slightly proud of the skull, is unobserved.
- **That the step-up reads as stepping up** rather than as a short float, and whether 0.4 s is right.
- **That steering feels elytra-like.** The turn-rate limit, the eased thrust and the banking are all
  measured as curves. Whether they feel weighty or sluggish — and in particular whether the missing
  client-side prediction is noticeable at a real ping — is not measured and cannot be here.
- **That banking looks right.** The roll is applied to the model in `preRenderPass`; nobody has seen it,
  and a wrong axis would roll it about the wrong direction without failing anything above.
- **Camera behaviour while riding.** The camera is left entirely alone — no code touches it for mounting —
  so there is nothing to get stuck, but "looks fine elevated on a moving mount" is an observation nobody
  made.
- **Multiple real clients.** Both harness players ran inside the server process. Server authority is
  established structurally (§4), and the forced animation phase is the part most likely to hold up under
  latency since it is anchored to entity age — but no two real clients have drawn a rider in step.
- **The fear reaction, the smoke, and every other visual** inherited from earlier phases remain as
  unverified as `SUMMONING.md` records.

### Known limitation

`hasLiveDragon`'s fallback scan walks **loaded** entities only. A dragon in an unloaded chunk cannot be
found, so immediately after a restart — before the chunk containing a player's dragon has loaded — that
player could summon a second one. The persisted `summonerUuid` makes the link recoverable, and the map is
rebuilt the moment the scan does find it, but the window exists. Closing it properly needs a persisted
server-level index rather than an entity scan.
