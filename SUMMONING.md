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

### The summoner is recorded, but owns nothing

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

| tick | time | beat |
|---|---|---|
| 0 | 0.0 s | hand seal / cast pose; buildup sound; chakra drawn inward |
| 0 – 45 | 0.0 – 2.25 s | summoning circle expands and smoke thickens |
| **45 – 95** | **2.25 – 4.75 s** | **camera moment — 50 ticks = 2.5 s** |
| **70** | **3.5 s** | **REVEAL** — dragon spawns, reveal sound, screen rumble, awakening line |
| 70 – 100 | 3.5 – 5.0 s | smoke disperses, seal fades, camera released, dragon enters normal flight AI |
| **100** | **5.0 s** | sequence complete; the bar resumes charging |

**Total: 100 ticks = 5.0 s.** Camera moment: **2.5 s**, inside the brief's 1–3 s window. All four
numbers are config values (`cinematic_ticks`, `reveal_tick`, `camera_start_tick`,
`camera_end_tick`), and the reveal tick is clamped below the total at read time so a bad config
cannot produce a reveal that never fires.

The bar resumes charging only at tick 100, not at the reveal — otherwise the HUD would show a
refilling bar while the dragon was still arriving.

### Visible to everyone nearby, not just the summoner

`SummonStart` goes out via `sendToPlayersNear` over `max_effect_distance`, and it carries the seal
centre, the timeline and a **seed**. Every client derives every ring, rune and smoke puff from
`(seed, elapsedTicks)`, so observers and summoner draw the same cinematic with **no per-particle
traffic** — the same rule the ability effects follow. Measured: the summoner and an observer 6 blocks
away received *identical* `SummonStart` payloads, 41 bytes each.

The seal is pinned to where the player stood at the moment of casting, not to the player, so it does
not slide around if they walk off during the sequence.

### The camera cannot get stuck

This is the part most likely to trap a player, so it is defended four ways:

1. **Player input is never taken away.** Look and movement stay under the player's control for the
   whole five seconds. The effect is a *nudge*: 2.4° roll, FOV ×1.16, a rumble on the reveal beat,
   and in third person a ×2.5 camera pull-back. A cinematic that confiscates the controls for three
   seconds is also a cinematic the player cannot escape if anything goes wrong.
2. **It is a pure function of elapsed ticks.** Intensity is `sin(local × π)` across the window, so it
   returns to exactly zero on its own. There is no "off" to fail to send.
3. **The record self-expires** past `totalTicks + 40` regardless of what else happens.
4. **It can be switched off entirely** — `camera_effect = false` leaves every camera alone, for
   players who dislike any forced camera movement. When disabled the packed camera field is `0`,
   which the payload reports as disabled.

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

| file | duration | bytes | format |
|---|---|---|---|
| `summon_buildup.ogg` | 3.50 s | 38,091 | Vorbis, mono, 44.1 kHz |
| `summon_reveal.ogg` | 1.60 s | 20,149 | Vorbis, mono, 44.1 kHz |

There is no licensing question about either — see [`AUDIO_CREDITS.md`](AUDIO_CREDITS.md), where they
are recorded separately from the ability audio whose provenance is still unresolved.

Both are played by the server at the seal's coordinates rather than on the player, so observers hear
them coming from the circle, and both respect the `sound_volume` multiplier.

---

## 5. Config

All under `[summoning]` in the server config:

| key | default | meaning |
|---|---|---|
| `charge_duration_seconds` | `300` | POWER BAR — SUMMONING charge time (0% → 100%) |
| `cinematic_ticks` | `100` | total sequence length (100 = 5.0 s) |
| `reveal_tick` | `70` | tick the dragon appears and speaks (70 = 3.5 s) |
| `camera_effect` | `true` | enable the camera move at all |
| `camera_start_tick` | `45` | camera move begins |
| `camera_end_tick` | `95` | camera move ends and the view is fully released |
| `sound_volume` | `1.0` | multiplier for both summon sounds |
| `announce_globally` | `true` | line goes to every player, or only those within `max_effect_distance` |
| `lines_resource` | `rasengan:awakening` | which lines file is spoken from |

The keybind is **L** by default (`key.rasengan.summon`), registered through the same keybind pattern
as the existing ability keys and remappable in Controls like any other.

### Skipping the 300-second wait while testing

`/chargeit summon` fills POWER BAR — SUMMONING instantly, and `/chargeit summon <player>` does it for
someone else. Same gating as the existing `/chargeit`: Creative for yourself, gamemaster permission
plus a Creative *target* for anyone else — so it cannot hand a survival player a free boss. It refuses
mid-cinematic rather than cancelling a running sequence, and it does not bypass the
one-dragon-per-player rule. Full detail in [`README.md`](README.md#chargeit).

---

## 6. Verified on a dedicated server

`tools/verify/run_summon_test.sh`. Committed output:
[`tools/verify/evidence/summon-audit.txt`](tools/verify/evidence/summon-audit.txt).

**91 assertions, 91 pass, 0 fail.** The command forms are driven through the real Brigadier
dispatcher, as command text, so the grammar itself is under test and not just the handler.

| check | result |
|---|---|
| Fresh bar starts `CHARGING`, accumulates | charge = 3 after 3 ticks |
| The two bars are independent | cast bar at 8 ticks while summoning bar forced to 6000 |
| `trySummon` refused while `CHARGING` | refused, and explained: *"Your summoning power is still gathering."* |
| `trySummon` at `READY` | succeeds; bar → `CASTING`; charge spent immediately, not on completion |
| Second press mid-cinematic | ignored, and **silently** — no chat spam |
| `SummonStart` reaches a nearby observer | yes, and byte-identical to the summoner's |
| `SummonStart` contents | `total=100 reveal=70 camera=45..95`, summoner entity id, seal at the cast position |
| **Reveal tick** | **summon + 70, exactly** |
| Dragons spawned | exactly 1, ever; 8.25 blocks from the summoner; `isEntering()` true |
| **Awakening line fires** | **exactly once**, on the reveal tick |
| Line identical for both players | identical `Component`; no `§` codes |
| One dragon per player | second summon refused with a message; no second dragon; no cinematic started |
| Sequence cleanup | at summon + 100 the cinematic is gone and the bar is back to `CHARGING` |
| Hand-off to normal flight AI | 48.67 blocks over 99 ticks, 0.492 b/t mean, **0 stalled ticks**, `isEntering()` false |
| Gate reopens after death | dragon pruned, `trySummon` succeeds again |
| `/reload` re-reads the lines | 64 → **65** with no restart |
| Datapack override | 3 speakable / 5 loaded; the 2 unselected lines never spoken in 4000 draws |
| Payload codecs round-trip | `SummonStart` 41 bytes exact; `SummonPowerSync` exact; `SummonActivate` 0 bytes |
| `/chargeit summon` in Survival | refused; bar untouched |
| `/chargeit summon` in Creative | bar → `READY` at 6000/6000, cooldown cleared, pushed to the client at once |
| **Bar isolation, both directions** | `/chargeit summon` left the cast bar at `CHARGING`/5; `/chargeit` left the summoning bar at `CHARGING`/7 |
| The granted charge is real | a summon authorised purely by `/chargeit summon` was accepted and started a cinematic |
| `/chargeit summon` mid-cinematic | refused; the running cinematic and the `CASTING` state both intact |
| `/chargeit summon <player>` from console | works; and is refused when the *target* is in Survival |
| Every line reachable | 4000 draws yielded all 64 distinct |
| Client isolation intact | 41 non-client classes, **0** referencing `net.minecraft.client` |

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

Everything below is unverified, and nothing in the audit above should be read as covering it:

- **The entire cinematic as a visual.** That the seal renders at all, that the rings and rune ticks
  read as a summoning circle, that the smoke looks like smoke, that the dragon appears to rise out of
  the circle rather than clipping through the ground or the player. The geometry is submitted through
  the same path as the verified ability effects, which is an argument, not evidence.
- **The camera moment as an experience.** Whether 2.4° of roll and FOV ×1.16 read as cinematic or as
  nausea; whether the rumble lands on the reveal; whether the third-person pull-back frames the
  arrival. The *state* cannot get stuck — the effect is a pure function of elapsed ticks with a
  self-expiry, and input is never captured — but "returns to zero on its own" is a property of the
  arithmetic, not an observation of a real camera.
- **That either sound is audible or pleasant.** Duration, format, channel count and sample rate are
  measured. Nothing was heard.
- **Multi-client replication and real latency.** Both synthetic players ran in the server process, so
  the identical-`Component` and identical-`SummonStart` results prove the server *sent* the same
  bytes to both. They do not prove two real clients, on a real connection with real jitter, draw the
  cinematic in step. The design reason to expect they will — one seed, server-owned clock, everything
  else derived — is in §2, and it is a reason rather than a measurement.
- **The hand-seal cast pose** on the player model, and the second HUD bar's appearance.
