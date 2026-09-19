# Handoff context

Written for whoever picks this up next. Read §1–§3 before touching anything — they will save you
several dead ends that already cost real time here.

---

## 1. What this is

A Minecraft **Java Edition 26.1.2** NeoForge mod with three server-authoritative abilities and one
boss mob:

| | |
|---|---|
| **Rasengan** — `R` | charged energy sphere, thrown projectile, 40 damage |
| **Rasen Shuriken** — `G` | four-bladed spinning star, 60 damage, huge knockback |
| **Summoning Jutsu** — `L` | 5.0 s cinematic that brings in the boss dragon |
| **Demonic Wingwalker** | GeckoLib boss dragon, 300 HP, flight AI, fire breath |

| | |
|---|---|
| Repo | `github.com/TrishaAdio/Rasengan` |
| `main` head | `4cdd797` (merge of PR #6) |
| Version | `mod_version=1.4.1` — **not bumped since two major features landed**, see §7 |
| Minecraft | `26.1.2` — **real**, released 2026-04-09. Mojang moved to year-based versioning, so this is *newer* than `1.21.x` |
| Loader | NeoForge `26.1.2.109` |
| Java | **25** (mandatory — 26.1 moved up from 21) |
| Gradle | `9.7.1` wrapper, ModDevGradle `2.0.147` |
| GeckoLib | `5.5.2`, pinned by **Modrinth version ID `xfVfPcoC`**, not by version number — see §3 |
| Size | ~9,400 lines Java across 44 files (24 non-client, 20 client) |

Build:

```bash
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
./gradlew build --no-daemon --console=plain -Porg.gradle.java.installations.paths="$JP"
```

### Per-feature documentation

| file | covers |
|---|---|
| `README.md` | the two abilities, POWER BAR, networking, rendering, config, `/chargeit` |
| `DRAGON.md` | the dragon mob: asset conversion, flight AI, attacks, measured flight quality |
| `SUMMONING.md` | the Summoning Jutsu: gating decisions, exact timings, voice lines, audit results |
| `ANIMATION_AUDIT.md` | the interpolation pass — 5 bugs found, with measured evidence |
| `AUDIO_CREDITS.md` | **read before adding audio.** Provenance of every sound; four files unresolved |
| `DRAGON_CREDITS.md` | **read before redistributing.** CC BY 4.0 attribution + residual design-rights risk |

---

## 2. Current branch and PR state

Everything below is merged to `main` **except** PR #7.

| PR | state | what |
|---|---|---|
| #1 | merged | Animation smoothness/accuracy pass — 5 interpolation bugs |
| #2 | merged | Revert dragon from main (pending asset licensing) |
| #3 | merged | Dragon mob re-landed, asset under CC BY 4.0 |
| #4 | merged | Dragon flight fix — stalling and jitter |
| #5, #6 | merged | Summoning Jutsu |
| **#7** | **open** | **`/chargeit summon`** — on `feature/chargeit-summon` |

PR #7 exists because #6 was merged before the `/chargeit summon` commit was pushed, so it never
reached `main`. It is that same commit rebased onto `main`, nothing more. **Merge it or close it — it
is the only unmerged work.**

Stale local/remote branches that can be deleted once #7 lands: `animation-smoothness-accuracy-pass`,
`revert/dragon-from-main`, `feature/dragon-mob`, `feature/summoning-jutsu`.

---

## 3. Environment traps

Not theoretical. Every one produced a false result at least once.

### `/root` does not persist between commands
Nor does `/tmp`, nor background processes started with a "run in background" flag. Put anything you
need to survive under **`/projects/sandbox`**.

### The Gradle cache gets wiped between sessions
`/root/.gradle` disappears; `build/moddev/artifacts/` survives. To restore sources for API lookups:

```bash
mkdir -p /projects/sandbox/mcsrc && cd /projects/sandbox/mcsrc && \
  unzip -q -o /projects/sandbox/rasengan/build/moddev/artifacts/minecraft-patched-26.1.2.109-sources.jar 'net/**'
```

NeoForge's own sources are a separate jar under `~/.gradle/caches/modules-2/.../neoforge-26.1.2.109-sources.jar`
and need one Gradle run to re-download. **Look things up in these sources rather than trusting
memory** — 26.1 renamed a lot, and NeoForge patches show up only in the NeoForge jar.

### `runServer` needs help
- Gradle does not forward stdin by default. `build.gradle` already sets
  `tasks.withType(JavaExec) { standardInput = System.in }` so piped console commands work.
- `downloadAssets` requires a **Java 21** toolchain even though the mod targets 25 — hence the `JP`
  variable above on every invocation.
- Boot takes **~130 s** before the first command will be accepted.

### GeckoLib must be pinned by Modrinth version ID
`maven.modrinth:geckolib:5.5.2` is **ambiguous** — six Modrinth versions share the number "5.5.2"
(neoforge/forge/fabric × 26.1.2/26.2). The coordinate resolves to the **Fabric** jar, and the server
dies with *"is a Fabric mod and cannot be loaded"* even though the build compiles. `xfVfPcoC` is the
NeoForge build for 26.1.2 specifically.

### Headless test traps — all of these caused false negatives
| Trap | Symptom | Fix |
|---|---|---|
| Server auto-pauses when empty | entities never tick; selectors find nothing | `pause-when-empty-seconds=0` |
| `forceload add` caps at **256 chunks** | command silently fails, nothing ticks | `forceload add -112 -112 112 112` = 225 chunks, just under the cap |
| Entity leaves the forceloaded region | **position, velocity *and* rotation freeze at byte-identical values** — looks exactly like a stuck-AI bug | detect structurally (nothing changed *at all*) and exclude; every false "freeze" sat at \|x\|≈96 or \|z\|≈112 |
| World persists between runs | terrain built by a previous run's obstacle test was still standing during the next run's "open sky" test | `rm -rf run/server/world` every run |
| `NoAI:1b` | stops `travel()`, so velocity is set but never integrated | use `NoGravity:1b` *without* `NoAI` |
| Terrain at test altitude | target suffocates (~1 dmg/10 ticks) | test at `y=220`+ in open air, or a flat world |
| `Tags:["x"]` on `summon` | tag never applies | select by `type=` instead |
| Low-HP test target | dies before you can measure | Iron Golem (100 HP), which also has `KNOCKBACK_RESISTANCE 1.0` |
| Datapack `pack.mcmeta` with `pack_format` | pack silently ignored | format is **101**, past the old ceiling — use `min_format` / `max_format` |
| Datapack tag functions | output suppressed, so `data get` prints nothing | use a macro `$say` to get values into the log |

### Audio tooling
There is no `ffmpeg` on the box. `pip install imageio-ffmpeg` provides one:
```bash
FF=$(python3 -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())")
```

### GitHub
`gh api` REST works. **Do not** use `gh pr`, `gh issue` or `gh release upload` — they are
GraphQL-backed and fail here. Release *creation* via `gh api .../releases` works; asset *upload* does
not (`uploads.github.com` is not routed). Hence `dist/`.

---

## 4. Verified 26.1.2 API facts

All confirmed against decompiled sources, not memory.

**Renames**
- `ResourceLocation` → **`net.minecraft.resources.Identifier`**
- `GuiGraphics` → **`GuiGraphicsExtractor`**; GUI moved to a render-state *extraction* model
  (`render` → `extractRenderState`, `drawString` → `text`)
- `RenderType` → `net.minecraft.client.renderer.rendertype.RenderType`, statics on `RenderTypes`
- `TextureSheetParticle` **deleted** → extend `SingleQuadParticle`
- `ItemRenderer` and `BlockRenderDispatcher` **removed**

**Gotchas that cost time**
- `GameProfile` is a **record** → `.name()`, `.id()`
- `ServerPlayer` has **no** `getServer()` → `((ServerLevel) player.level()).getServer()`
- Integer permission levels are **gone**. Use `.requires(Commands.hasPermission(PermissionCheck))`
  with `Commands.LEVEL_GAMEMASTERS`. `PermissionCheck` lives in `net.minecraft.server.permissions`,
  **not** `net.minecraft.commands`
- `ParticleStatus` is in `net.minecraft.server.level`, not `client`
- `Camera.position()`, not `getPosition()`
- Serverbound send is `ClientPacketDistributor.sendToServer(...)`; `PacketDistributor` has no such method
- `FMLEnvironment.getDist()` is a **method**
- `@Nullable` for NeoForge code is `org.jspecify.annotations.Nullable`
- `AABB` has **no** segment-intersection method — `RasenganProjectile` implements its own slab test
- `ServerLevel.getEntities(EntityTypeTest, Predicate)` returns `List<? extends T>`, not `List<T>`

**`@EventBusSubscriber`** — has no `bus` parameter in this version, and the mod registers its real
listeners explicitly. Annotation *scanning* does work, though: `@EventBusSubscriber(modid = "rasengan")`
on a class with `static @SubscribeEvent` methods is picked up with no registration call, which is how
the summon verification harness attaches without editing any shipped file.

**Data-driven resources**
- `SimpleJsonResourceReloadListener` constructor in 26.1 is **`(Codec<T>, FileToIdConverter)`**, not
  `(Gson, Codec, String)`. `SummonLines` uses `super(ExtraCodecs.JSON, FileToIdConverter.json("summon_lines"))`
  — keeping the raw `JsonElement` so one malformed file warns instead of aborting the whole reload.
- Registering one: `AddServerReloadListenersEvent.addRetainedListener(ListenerKey<T>, T)` with
  `ListenerKey.create(Identifier)`. **Not** `addListener(Identifier, …)`.

**Camera events** (all exist, all used by `SummonCinematic`)
- `ViewportEvent.ComputeCameraAngles` → `setYaw` / `setPitch` / `setRoll`
- `ViewportEvent.ComputeFov` → `setFOV`
- `CalculateDetachedCameraDistanceEvent` → `setDistance`

**`ServerPlayer.tick()` does NOT call `Player.tick()`** — the single most expensive thing learned
here. The level's entity tick runs only the light half. `Player.tick()`, which is where NeoForge fires
`PlayerTickEvent` and therefore where **both** charge bars advance, is reached from
`ServerPlayer.doTick()`, called once per tick by `ServerGamePacketListenerImpl.tickPlayer()` — i.e.
from the **network** tick. Any synthetic/fake player must call `doTick()` itself or its bars sit at 0
forever, which looks exactly like a charging bug and is not one.

**Rendering**
- Custom world geometry: NeoForge `SubmitCustomGeometryEvent` →
  `collector.submitCustomGeometry(PoseStack, RenderType, (pose, buffer) -> ...)`
- Coordinates are **camera-relative**: translate by `worldPos − cameraPos`. Camera is
  `levelRenderState.cameraRenderState.pos`
- Best render type for glowing energy: **`RenderTypes.dragonRays()`** — `POSITION_COLOR`, `TRIANGLES`,
  additive `BlendFunction.LIGHTNING`, depth-tested but not depth-written. Avoid `RenderTypes.lightning()`
  (renders to `WEATHER_TARGET`)
- HUD: `RegisterGuiLayersEvent` (mod bus) → `registerAbove(VanillaGuiLayers.HOTBAR, id, GuiLayer)`.
  NeoForge does **not** hide modded layers on F1 — guard on `options.hideGui` yourself
- Entities you draw yourself: register a vanilla `NoopRenderer` so MC is satisfied, then draw in
  `SubmitCustomGeometryEvent`

**Mob AI, learned from the dragon**
- `WaterAvoidingRandomFlyingGoal` produced **0.00 blocks of movement in 500 ticks** for this hitbox —
  pathfinding cannot route a mob this large. `Ghast.RandomFloatAroundGoal` works; the mod now ships
  `DragonWanderFlightGoal`.
- `FlyingMoveControl` **only applies thrust on the tick the target is set.** Paired with a
  once-per-target goal that yields 8% of the intended speed and 133 thrust spikes per 500 ticks.
  A flying move control must re-issue thrust **every tick** — hence `DragonFlightMoveControl`.

**Three bugs already found and fixed — do not reintroduce**

1. **`submitCustomGeometry` is deferred.** It stores `poseStack.last().copy()` plus the lambda, and
   `CustomFeatureRenderer` runs them later, *batched per RenderType*. Anything the lambda reads from
   shared mutable state sees whatever the **last** submit wrote. Always capture into `final` locals and
   establish per-effect state (e.g. camera-local) **inside** the callback.

2. **`SoundEngine` discards zero-volume sounds at play time.**
   ```java
   if (volume == 0.0F && !instance.canStartSilent() && source != SoundSource.MUSIC)
       return PlayResult.NOT_STARTED;
   ```
   A fade-in starting at 0 silently kills the sound. Override `canStartSilent()` → `true`.

3. **`rate * ticksElapsed` is wrong whenever the rate varies.** The accumulated value must come from
   the **analytic integral**. The HUD glow used `rate(t) · t` and strobed 163× faster at world age 1e6
   than at 0. `ShurikenRenderer.spinAngle()` is the correct reference. Full list in `ANIMATION_AUDIT.md`.

---

## 5. Architecture

```
dev/rasengan/
├── Rasengan.java            entrypoint; explicit listener registration; Dist.CLIENT gate
├── AbilityType.java         RASENGAN | RASEN_SHURIKEN discriminator
├── PalmAnchor.java          COMMON palm position — server launches where the client draws
├── Palette.java             COMMON locked cyan/blue/white palette
├── PowerState.java          CHARGING → READY → CASTING → COOLDOWN
├── RasenganConfig.java      all server config, incl. [dragon] and [summoning]
├── Rasengan{Entities,Particles,Sounds}.java   registries
├── network/
│   ├── RasenganPayloads.java         5 ability payloads
│   └── RasenganSummonPayloads.java   3 summon payloads, separate so the ability
│                                     wire format stays untouched
├── server/                  server-authoritative gameplay
│   ├── ServerPowerManager       cast POWER BAR state machine + sync
│   ├── ServerCastManager        validation, timing, launch, styled chat
│   ├── ServerSummonManager      summon validation, cinematic clock, dragon arrival
│   ├── SummonLines              awakening lines, datapack-loaded, /reload-aware
│   ├── RasenganProjectile       flight, swept collision, damage, knockback
│   ├── DragonEntity             GeckoLib boss: flight state, breath, entrance
│   ├── DragonFlightMoveControl  re-issues thrust every tick
│   ├── DragonWanderFlightGoal   untethered wander that actually moves
│   ├── ChargeCommand            /chargeit and /chargeit summon
│   ├── SpawnCommand             /spawn dragon
│   └── PowerData, ActiveCast, RasenganAttachments
└── client/                  NEVER loaded on a dedicated server
    ├── RasenganRenderer         Rasengan geometry + dispatch to ShurikenRenderer
    ├── ShurikenRenderer         four-blade assembly, rigid spin, impact
    ├── SummonCinematic          seal geometry, smoke, camera nudge
    ├── EnergyGeometry           shared primitives (camera-local passed explicitly)
    ├── OrbitMath, SphereMesh    procedural maths and icosphere
    ├── ClientCast{,Tracker}     held-phase records
    ├── ClientImpactTracker      impacts as ownerless world-space events
    ├── ClientEffects            particles + sound triggers
    ├── ShurikenSoundInstance    entity-following tickable loop
    ├── PowerBarHud              both bars + glow
    └── ClientTuning, EnergyParticle, RasenganKeys,
        ClientPowerState, ClientSummonPowerState
```

### Invariants worth preserving

- **Server owns everything.** Client→server traffic is two packets: `Activate` (one ability ordinal)
  and `SummonActivate` (**zero bytes**). Charge, aim, target, damage, line selection and every gate
  read from server state.
- **Client isolation.** Of **42** non-client classes, **zero** reference `net.minecraft.client.*`.
  Exactly one gated reference exists (`Rasengan` → `RasenganClient`, behind
  `FMLEnvironment.getDist().isClient()`). Re-run after any refactor:
  ```bash
  cd build/classes/java/main
  for f in $(find dev -name '*.class' | grep -v '/client/'); do
    n=$(echo "$f" | sed 's/\.class$//;s#/#.#g')
    javap -p -c "$n" 2>/dev/null | grep -o 'net/minecraft/client[a-zA-Z0-9/$]*' | sort -u | sed "s|^|$n -> |"
  done
  ```
- **The two POWER BARs are independent.** `SUMMON_POWER` is a *second attachment of the same
  `PowerData` type*, deliberately — so both bars charge, reset and serialise through identical code
  while staying separate instances. Do not merge them into one codec; that changes the live wire
  format and forces a `NETWORK_VERSION` bump.
- **No per-particle traffic.** One packet carries a **seed** plus timings; every client derives the
  visuals from `(seed, elapsedTicks)`. That is what keeps observers in step.
- **One sphere per cast.** `ClientCast.sphereIntensity()` returns a hard `0.0` once released, so the
  held visual and the projectile cannot coexist.
- **Damage applies once.** `RasenganProjectile.resolved` latches and the entity is discarded in the
  same tick.
- **Ability must be in `SynchedEntityData`**, not a plain field — spawn packets carry no NBT, so a
  plain field left every client rendering the wrong visual.
- **The summoner owns nothing.** `DragonEntity.summonerUuid` feeds the one-dragon-per-player check and
  nothing else: no taming, riding, mounting or loyalty. Keep it that way unless explicitly asked.

---

## 6. Status: verified vs not

### Verified on a live dedicated server

| Area | Evidence |
|---|---|
| Abilities | Rasengan 40 dmg, Shuriken 60 dmg, bypasses armour/resistance, straight-line flight, cleanup on impact and at max range |
| Knockback | **142.94 blocks** measured. The older "227.75" figure was a `NoGravity` harness artifact — a grounded target loses 45% on the first tick to *block* friction (0.6 × 0.91). The 200–250 claim in the brief was **not met**; see `ANIMATION_AUDIT.md` |
| Animation | lateral deviation exactly `0.000e+00`; `spinAngle` matches its analytic integral to 2.0 ulp; `tools/verify/evidence/animation-audit.txt` |
| Dragon | spawn, 300 HP, melee 88, breath ignites, **fire immunity**, generic/fall damage exact, dies, drops loot |
| Dragon flight | 461–563 blocks per scenario, 0.507–0.512 b/t, **0 stalled ticks**, 3/3 independent trajectories; `flight-evidence.txt` |
| Summoning | **91 assertions, 0 failures**; reveal at summon+70 exactly, line fires once and is byte-identical for summoner and observer, one-per-player refusal, gate reopens on death, `/reload` moved the pool 64→65 live; `summon-audit.txt` |
| `/chargeit summon` | real command text through the real Brigadier dispatcher: Creative gate, fill values, bar isolation **both** directions, mid-cinematic refusal, console target form |

### NOT verified — no display, no audio device, no second client

**Do not claim any of this works.** It is backed by geometry maths, static audits and measured file
properties — not by looking or listening.

- **Everything visual**: the sphere, the four-blade silhouette, the aura, the HUD (both bars), the
  dragon model and texture orientation, whether animations play rather than T-posing, the summoning
  seal and smoke, and whether the dragon appears to rise out of the circle.
- **The cinematic camera as an experience.** Whether 2.4° of roll and FOV ×1.16 read as cinematic or as
  nausea. The camera *state* provably cannot stick — it is a pure function of elapsed ticks with a
  self-expiry, and player input is never captured — but that is arithmetic, not an observation.
- **Whether any sound is audible.** Duration, container, codec, channel count and sample rate are
  measured; nothing was heard.
- **The dragon's boss bar.** A `ServerBossEvent` added in `startSeenByPlayer`; no real player ever
  connected. `/bossbar list` reports only `/bossbar`-created bars, so its silence proves nothing.
- **Multi-client replication under real latency.** The summon audit's synthetic players ran *inside*
  the server process, so identical payloads prove the server **sent** the same bytes — not that two
  real clients draw in step.

---

## 7. Outstanding work

### 1. `dist/rasengan-1.4.1.jar` is stale, and the version never moved
The committed jar was built before the Summoning Jutsu and **contains none of it** — no
`SummonLines`, no `summon_lines/awakening.json`, no summon sounds. Anyone installing from `dist/`
gets no summoning at all. It is also still called `1.4.1` despite the dragon *and* the summon landing
since.

Two decisions for the owner, deliberately not made unilaterally:
- what the version should become (the dragon and the summon are both feature additions);
- whether `dist/` should keep shipping a binary at all, given it drifts silently.

Refreshing it is one command once the version is decided:
```bash
./gradlew clean build -Porg.gradle.java.installations.paths="$JP" && cp build/libs/rasengan-*.jar dist/
```

### 2. PR #7 is unmerged
`/chargeit summon`. See §2.

### 3. Audio licensing — still unresolved
All four `rasengan_*` / `rasenshuriken_*` `.ogg` files were **imported from third-party hosted files
supplied by the project owner**. Screened: no MP4/DASH container brands, no ID3 frames — two earlier
candidates *did* carry DASH brands, meaning they were demuxed from streaming video, and were rejected.
Not determinable from a file alone: authorship and licence. The repo is public under MIT and ships a
jar, so redistribution rights matter, and absence of a rip fingerprint is not clearance.

The **two summon sounds are unaffected** — they are synthesised from scratch by
`tools/generate_summon_sounds.py` using only the standard library. That script is also the template
for replacing the imported audio if rights cannot be confirmed. Full detail and two remediation paths
in `AUDIO_CREDITS.md`.

**If the owner cannot confirm rights, do not add more imported audio.**

### 4. Dragon asset — residual design-rights layer
The model is **"Demonic Wingwalker" by CsDani50**, CC BY 4.0, attributed in four places, with changes
documented. The *copyright* position is clear. What is **not** cleared is the design layer: the upload
is marked "(THIS IS FANMADE)", so the underlying character design may carry third-party rights the
uploader could not license. Read `DRAGON_CREDITS.md` before redistributing commercially.

### 5. The one-dragon-per-player limit does not survive a restart
Found while fact-checking this document, so it is **not** covered by the 91 passing assertions — those
all run inside a single server lifetime.

`DragonEntity.addAdditionalSaveData` persists only `BreathCooldown`. **`summonerUuid` is not written**,
and `ServerSummonManager.ACTIVE_DRAGONS` is an in-memory map cleared on `ServerStoppingEvent`. So after
a restart, a player whose summoned dragon is still alive in the world can summon a second one, and
both will persist. The dragon also loses the link on the entity side, so nothing can rebuild it.

The fix is small and has a clear shape: write `summonerUuid` in `addAdditionalSaveData`, read it back,
and have `hasLiveDragon` fall back to scanning loaded levels for a dragon whose `summoner()` matches
instead of trusting the map alone. The reason it is written down rather than done is that it changes
the entity's save format, which deserves its own reviewed change rather than being slipped into a docs
pass. `SUMMONING.md` carries the same caveat so the guarantee is not overstated there either.

### 6. No CI exists
There is no workflow of any kind. Everything in §6 was run by hand. The three harnesses in
`tools/verify/` are scripted and would automate cleanly, but each needs a ~130 s server boot.

### Known rough edges
- `ShurikenSoundInstance` is used for **both** abilities and for one-shots as well as loops. The name
  misleads; consider `EntityBoundSoundInstance`.
- `RasenganRenderer` still has private copies of ribbon/shell primitives; `EnergyGeometry` is the
  canonical home but only `ShurikenRenderer` and `SummonCinematic` use it. Consolidating removes
  duplication — but `RasenganRenderer` is verified working, so weigh the regression risk.
- `DragonEntity.entranceTicks` is not persisted. Saving mid-entrance loses the flourish. A 1.5 s
  transient, judged not worth a codec field.
- `tools/generate_sounds.py` is the ElevenLabs generator and is **unused** — the supplied key was
  free-tier, which does not grant redistribution rights.
- `/chargeit summon` cannot target a player literally named "summon" (Brigadier matches the literal
  node first). `/chargeit summon summon` reaches them. Documented in the javadoc and README.

---

## 8. Test recipes that actually work

Prefix everything with:
```bash
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
rm -rf run/server/world
printf 'eula=true\n' > run/server/eula.txt
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\nlevel-type=minecraft:flat\nspawn-protection=0\n' > run/server/server.properties
```
then pipe console commands into
`./gradlew runServer --no-daemon --console=plain -Porg.gradle.java.installations.paths="$JP"`,
allowing **~130 s** for startup before the first command. Use real `sleep`s between commands.

### The three committed harnesses

| script | what it measures |
|---|---|
| `tools/verify/run_summon_test.sh` | the whole Summoning Jutsu — 91 assertions. Also the template for **how to fake a player** |
| `tools/verify/run_flight_test.sh` + `analyse_flight.py` | dragon flight across open sky, dense terrain, and 3 simultaneous dragons |
| `tools/verify/run_dragon_test.sh` | dragon spawn, damage, breath, fire immunity, loot |
| `tools/verify/run_server_trace.sh` + `analyse_trace.py` | per-tick projectile trajectory via a trace datapack |
| `tools/verify/AnimationAudit.java` | reflection harness calling the real render maths; no server needed |

**How the summon harness fakes a player**, since it is the reusable trick: `SummonAudit.java` inserts
real `ServerPlayer` instances into the real `PlayerList` (reflection on the private `players` and
`playersByUUID` fields) plus `level.addNewPlayer(...)`, with a `ServerGamePacketListenerImpl` subclass
whose `send()` **records** packets instead of writing them to a socket — over a socket-less
`Connection`, the same trick NeoForge's `FakePlayer` uses. It then calls `doTick()` itself (see §4).
The harness lives in `tools/verify/`, is staged into `src/main/java/dev/rasengan/verify/` only for the
duration of a run, and is deleted by an `EXIT` trap — confirmed absent from the built jar.

### Ability damage (expect `60.0` then `40.0`)
```
forceload add 0 -16 31 336
summon minecraft:iron_golem 8 220 30 {NoGravity:1b,NoAI:1b}
summon rasengan:rasengan_projectile 8 220 2 {Ability:0,Motion:[0.0,0.0,1.0]}
data get entity @e[type=iron_golem,limit=1] Health
```
`Ability:0` = Rasengan, `Ability:1` = Rasen Shuriken.

### Knockback — note `NoGravity` *without* `NoAI`
```
forceload add 0 -16 31 336
summon minecraft:iron_golem 8 230 8 {NoGravity:1b}
summon rasengan:rasengan_projectile 8 230 2 {Ability:1,Motion:[0.0,0.0,1.0]}
data get entity @e[type=iron_golem,limit=1] Motion
```
A grounded target gives ~143 blocks; `NoGravity` inflates it to ~228. Quote the grounded number.

### Audio file validation
```bash
FF=$(python3 -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())")
$FF -i file.ogg -f s16le -ac 1 -ar 44100 /tmp/a.raw -y
# compare |d[0]-d[-1]| against the 99th-percentile |diff| for loop seam quality
```

---

## 9. Working style that has been expected

- **Evidence, not impressions.** Report measured numbers. "It works" and "looks good" have been
  rejected every time.
- **Suspect the harness before the code.** This has been right more often than not. The catalogue:
  health readings of 87/84 were suffocation from targets inside terrain; a "not despawning"
  projectile was outside a forceloaded chunk; a 227-block knockback was a `NoGravity` artifact; every
  dragon "freeze" was forceload-boundary unloading; a velocity-based stall check called a
  wall-grinding dragon healthy; and both charge bars sitting at 0 was a synthetic player with no
  network tick. All six looked like code bugs.
- **Separate verified from unverified explicitly, every time.** This environment cannot render or play
  audio. Saying so plainly — and saying which specific claims are unbacked — has been expected.
- **State decisions and the alternative that lost.** Every non-obvious choice in `SUMMONING.md` and
  `DRAGON.md` records what was rejected and why, so it can be argued with rather than guessed at.
- **Correct your own documentation when it over-promises.** The `SummonLines` javadoc claimed
  datapacks could add lines by dropping in a new file; they cannot. That was found by *testing the
  claim* and then fixing the claim.
- **Verify APIs in decompiled sources** rather than trusting memory. 26.1 renamed a lot.
- **One changeset per PR.** PR #1 shipped two and had to be split retroactively via a revert.
- Keep secrets out of the repo. An API key was pasted in chat during this project; it was used via
  environment variable only, never committed, and the owner was told to rotate it.
