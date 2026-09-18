# Handoff context

Written for whoever picks this up next. Read §1 and §2 before touching anything — they will save you
several dead ends that already cost real time in this project.

---

## 1. What this is

A Minecraft **Java Edition 26.1.2** NeoForge mod with two server-authoritative abilities: **Rasengan**
(round energy sphere) and **Rasen Shuriken** (four-bladed spinning star).

| | |
|---|---|
| Repo | `github.com/TrishaAdio/Rasengan`, branch `main` |
| Version | `1.4.1` (head `834b4f6`) |
| Minecraft | `26.1.2` — **real**, released 2026-04-09; Mojang moved to year-based versioning, so this is *newer* than `1.21.x` |
| Loader | NeoForge `26.1.2.109` |
| Java | **25** (mandatory — 26.1 moved up from 21) |
| Gradle | `9.7.1` wrapper, ModDevGradle `2.0.147` |
| Size | ~6,300 lines Java across 33 files |

Build: `./gradlew build` → `build/libs/rasengan-1.4.1.jar`. A copy is committed to `dist/` because
release-asset upload is blocked in this environment.

---

## 2. Environment traps

These are not theoretical. Every one of them produced a false result at least once.

### The Gradle cache gets wiped between sessions
`/root/.gradle` disappears. `build/moddev/artifacts/` survives. To restore decompiled sources for
API lookups:

```bash
mkdir -p /root/mcref && cd /root/mcref && \
  unzip -q -o /projects/sandbox/rasengan/build/moddev/artifacts/minecraft-patched-26.1.2.109-sources.jar
```

NeoForge's own sources are a separate jar and need a Gradle run to re-download. **Look things up in
these sources rather than trusting memory** — 26.1 renamed a lot.

### `runServer` needs help
- Gradle does not forward stdin by default. `build.gradle` already sets
  `tasks.withType(JavaExec) { standardInput = System.in }` so piped console commands work.
- `downloadAssets` requires a **Java 21** toolchain even though the mod targets 25:
  ```
  -Porg.gradle.java.installations.paths="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
  ```

### Headless test traps — all of these caused false negatives
| Trap | Symptom | Fix |
|---|---|---|
| Server auto-pauses when empty | entities never tick; selectors find nothing | `pause-when-empty-seconds=0` in `server.properties` |
| `forceload add` caps at **256 chunks** | command silently fails, nothing ticks | request fewer; a narrow corridor works: `forceload add 0 -16 31 336` |
| Entity outside a forceloaded chunk | projectile "passes through" targets — looks exactly like broken collision | forceload the chunk the *entity* is in; `add 0 0` only covers blocks 0–15 |
| `NoAI:1b` | stops `travel()`, so velocity is set but never integrated — target sits still with `Motion` showing 20.5 | use `NoGravity:1b` *without* `NoAI` to hold a target in place but still let it move |
| Terrain at test altitude | target suffocates (~1 dmg/10 ticks), projectile hits rock early | test at `y=220`+ in open air |
| `Tags:["x"]` on `summon` | tag never applies; selector matches nothing | select by `type=` instead |
| Low-HP test target | dies before you can measure | Iron Golem (100 HP) — also has `KNOCKBACK_RESISTANCE 1.0`, so it doubles as a resistance-bypass test |

### GitHub
`gh api` REST works. **Do not** use `gh pr`, `gh issue`, or `gh release upload` — they are
GraphQL-backed and fail. Release *creation* via `gh api .../releases` works; asset *upload* does not
(`uploads.github.com` is not routed). Hence `dist/`.

---

## 3. Verified 26.1.2 API facts

Learned the hard way, all confirmed against decompiled sources.

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
  with `Commands.LEVEL_GAMEMASTERS` etc. `PermissionCheck` lives in
  `net.minecraft.server.permissions`, **not** `net.minecraft.commands`
- `ParticleStatus` is in `net.minecraft.server.level`, not `client`
- `Camera.position()`, not `getPosition()`
- Serverbound send is `ClientPacketDistributor.sendToServer(...)`; `PacketDistributor` has no such method
- `@EventBusSubscriber` has **no `bus` parameter** in this version — register listeners explicitly
- `FMLEnvironment.getDist()` is a **method**
- `@Nullable` for NeoForge code is `org.jspecify.annotations.Nullable`
- `AABB` has **no** segment-intersection method — `RasenganProjectile` implements its own slab test

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

**Two bugs already found and fixed here — do not reintroduce**

1. **`submitCustomGeometry` is deferred.** It stores `poseStack.last().copy()` plus the lambda, and
   `CustomFeatureRenderer` runs them later, *batched per RenderType*. Anything the lambda reads from
   shared mutable state sees whatever the **last** submit wrote. Always capture into `final` locals
   and establish per-effect state (e.g. camera-local) **inside** the callback. `EnergyGeometry` takes
   camera-local as an explicit parameter so it cannot recur there.

2. **`SoundEngine` discards zero-volume sounds at play time.**
   ```java
   if (volume == 0.0F && !instance.canStartSilent() && source != SoundSource.MUSIC)
       return PlayResult.NOT_STARTED;
   ```
   A fade-in starting at 0 silently kills the sound. Override `canStartSilent()` → `true`.

---

## 4. Architecture

```
dev/rasengan/
├── Rasengan.java            entrypoint; explicit listener registration; Dist.CLIENT gate
├── AbilityType.java         RASENGAN | RASEN_SHURIKEN discriminator
├── PalmAnchor.java          COMMON palm position — server launches from the same point the client draws
├── Palette.java             COMMON locked cyan/blue/white palette
├── RasenganConfig.java      all server config; ability-aware accessors
├── Rasengan{Entities,Particles,Sounds}.java   registries
├── network/RasenganPayloads.java   5 payloads
├── server/                  server-authoritative gameplay
│   ├── ServerPowerManager       POWER BAR state machine + sync
│   ├── ServerCastManager        validation, timing, launch, styled chat
│   ├── RasenganProjectile       flight, swept collision, damage, knockback
│   ├── ChargeCommand            /chargeit
│   └── PowerData, ActiveCast, RasenganAttachments
└── client/                  NEVER loaded on a dedicated server
    ├── RasenganRenderer         Rasengan geometry + dispatch to ShurikenRenderer
    ├── ShurikenRenderer         four-blade assembly, rigid spin, impact
    ├── EnergyGeometry           shared primitives (camera-local passed explicitly)
    ├── OrbitMath, SphereMesh    procedural maths and icosphere
    ├── ClientCast{,Tracker}     held-phase records
    ├── ClientImpactTracker      impacts as ownerless world-space events
    ├── ClientEffects            particles + sound triggers
    ├── ShurikenSoundInstance    entity-following tickable loop
    ├── PowerBarHud              HUD + glow
    └── ClientTuning, EnergyParticle, RasenganKeys, ClientPowerState
```

### Invariants worth preserving

- **Server owns everything.** The only client→server packet is `Activate`, which carries just an
  ability ordinal. Charge, aim, target, damage all read from server state.
- **Client isolation.** Of 27 non-client classes, **zero** reference `net.minecraft.client.*`. Exactly
  one gated reference exists (`Rasengan` → `RasenganClient`, behind `FMLEnvironment.getDist().isClient()`).
  Re-run the audit after any refactor:
  ```bash
  cd build/classes/java/main
  for f in $(find dev/rasengan -name "*.class" | grep -v "dev/rasengan/client/"); do
    javap -p -c "$f" | grep -oE "net/minecraft/client/[A-Za-z0-9/$]*" | sort -u | sed "s|^|$f -> |"
  done
  ```
- **One sphere per cast.** `ClientCast.sphereIntensity()` returns a hard `0.0` once released, so the
  held visual and the projectile cannot coexist. `emitShells` has exactly two call sites and they are
  mutually exclusive. A previous bug had a melee-era glide leaving a second sphere in the air.
- **Damage applies once.** `RasenganProjectile.resolved` latches and the entity is discarded in the
  same tick.
- **Ability must be in `SynchedEntityData`**, not a plain field — spawn packets carry no NBT, so a
  plain field left every client seeing `RASENGAN` and rendering the wrong visual.

---

## 5. Status: verified vs not

### Verified on a live dedicated server
| Check | Result |
|---|---|
| Rasengan damage | Golem 100.0 → **60.0** (40 pts) |
| Rasen Shuriken damage | Golem 100.0 → **40.0** (60 pts) |
| Damage bypasses armour/enchant/effects/resistance | Resistance IV target still took full 40 |
| Straight-line flight, zero drop | 38 blocks at identical Y, hit. Old `gravity=0.03` would have dropped ~30 blocks and missed |
| Knockback distance | **227.75 blocks** (z 8.5 → 236.25) on a `KNOCKBACK_RESISTANCE 1.0` golem; `Motion` after impact `[0.0, 2.0, 20.5]` |
| Projectile cleanup | despawns on impact **and** at max range |
| `/chargeit` | both forms registered; rejects console and unknown players |
| Dedicated server boot | clean, mod loads, no missing-class errors |
| Audio files | mono 44.1 kHz OGG, loop seams measured seamless, packaged in jar |

### NOT verified — no display or audio device in this environment
Everything visual and audible. Specifically: the four-blade silhouette, the wind-up acceleration,
tip vibration, trailing wisps, the aura, the HUD glow, the held→thrown handoff having no pop, the
3.5 s audio/blade sync landing on the same tick, and whether any sound is actually audible after the
`canStartSilent()` fix.

**Do not claim these work.** They are backed by geometry maths, static audits and measured file
properties — not by looking or listening.

---

## 6. Outstanding work

### The smoothness/accuracy pass was requested and NOT started
The last substantive request was a refinement pass (no new features). None of it has been done. It asked for:

1. **Interpolation audit.** Inventory every animated property — core scale, shell opacity, blade
   angle, orbit radius, particle position, camera-local billboard vectors, aura motion, HUD fill, HUD
   glow — and confirm each is a continuous analytic function of time or properly partial-tick
   interpolated. Specifically hunt for the `rate * ticksElapsed` bug class: if a rate changes over
   time, the accumulated value must come from the **analytic integral**, not step accumulation.
   `ShurikenRenderer.spinAngle()` already does this correctly and is the reference example.
2. **Phase-transition discontinuities.** Check each boundary for pops; state which are *intentionally*
   sharp (the shuriken snap-stop is deliberate) versus which must be smooth.
3. **Logged physical accuracy.** Per-tick trajectory logs proving a perfect straight line with no
   float drift; blade angles logged to prove all four stay exactly 90° apart through acceleration;
   wisp traced path spot-checked against true tip position at `t−δ`; camera-local aliasing re-tested
   at **3+** simultaneous casts; knockback parabola logged and distance confirmed across **multiple**
   trials, not one.
4. **Smoothness under load** — several concurrent casts plus mobs/chunk loading; HUD smoothness under
   variable tick rate; confirm particle staggering is genuinely staggered and not accidentally
   synchronised by a shared seed.
5. **Evidence, not impressions** — logged values, not "looks good."

Constraints given: **no gameplay value changes** (damage, charge duration, knockback targets, config
defaults), and no new rendering approaches unless a genuine bug requires it.

Much of §3 is testable headlessly by logging from server-side code and reading values back — that is
the highest-value place to start, since it does not need a display.

### Known rough edges
- `ShurikenSoundInstance` is used for **both** abilities and for one-shots as well as loops. The name
  is now misleading; consider renaming to something like `EntityBoundSoundInstance`.
- `RasenganRenderer` still has its own private copies of ribbon/shell primitives; `EnergyGeometry` was
  introduced as the canonical home but only `ShurikenRenderer` uses it. Consolidating would remove
  duplication, but `RasenganRenderer` is verified working — weigh the regression risk.
- `tools/generate_sounds.py` is the ElevenLabs generator. It is **unused**: the supplied key was
  free-tier, which does not grant redistribution rights.

---

## 7. Audio licensing — unresolved, read `AUDIO_CREDITS.md`

All four shipped `.ogg` files were **imported from third-party hosted files supplied by the project
owner**. What was screened and what was not:

- **Screened:** no MP4/DASH container brands (`major_brand=dash`), no ID3 title/artist frames. Two
  earlier candidate files *did* carry DASH brands — meaning they were demuxed from streaming video —
  and were rejected for that reason.
- **Not determinable from a file alone:** authorship and licence.

The repo is public under MIT and ships a jar, so redistribution rights matter. Absence of a rip
fingerprint is not clearance. Two remediation paths are documented in `AUDIO_CREDITS.md`:
regenerate originals (`tools/generate_shuriken_sound.py` already produces fully original audio), or
gitignore the files and have users run `tools/import_sounds.py` locally.

**If the owner cannot confirm rights, do not add more imported audio.**

---

## 8. Test recipes that actually work

Prefix all with:
```bash
JP="/opt/toolchains/.local/share/mise/installs/java/21.0.2,/opt/toolchains/.local/share/mise/installs/java/25.0.2"
printf 'eula=true\n' > run/server/eula.txt
printf 'online-mode=false\nlevel-name=world\npause-when-empty-seconds=0\n' > run/server/server.properties
```
then pipe commands into `./gradlew runServer --no-daemon --console=plain -Porg.gradle.java.installations.paths="$JP"`,
allowing ~95 s for startup before the first command.

**Ability damage** (expect `60.0` then `40.0`):
```
forceload add 0 -16 31 336
summon minecraft:iron_golem 8 220 30 {NoGravity:1b,NoAI:1b}
summon rasengan:rasengan_projectile 8 220 2 {Ability:0,Motion:[0.0,0.0,1.0]}
data get entity @e[type=iron_golem,limit=1] Health
```
`Ability:0` = Rasengan, `Ability:1` = Rasen Shuriken.

**Knockback** — note `NoGravity` *without* `NoAI`:
```
forceload add 0 -16 31 336
summon minecraft:iron_golem 8 230 8 {NoGravity:1b}
summon rasengan:rasengan_projectile 8 230 2 {Ability:1,Motion:[0.0,0.0,1.0]}
data get entity @e[type=iron_golem,limit=1] Motion
data get entity @e[type=iron_golem,limit=1] Pos
```

**Audio file validation:**
```bash
FF=$(python3 -c "import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())")
$FF -i file.ogg -f s16le -ac 1 -ar 44100 /tmp/a.raw -y
# then compare |d[0]-d[-1]| against the 99th-percentile |diff| for loop seam quality
```

---

## 9. Working style that has been expected

- **Verify, don't assume.** Look APIs up in decompiled sources; measure rather than trusting a spec
  number. Measuring the shuriken audio independently confirmed the 3.5 s figure instead of assuming it.
- **Investigate odd results rather than accepting them.** Health readings of 87/84 turned out to be
  suffocation from targets spawned inside terrain; a "not despawning" projectile was outside a
  forceloaded chunk. Both looked like code bugs and were test-harness faults.
- **Separate verified from unverified explicitly**, every time. The environment cannot render or play
  audio, and saying so plainly has been expected.
- **Report measured numbers**, not "it works."
- Keep secrets out of the repo. An API key was pasted in chat during this project; it was used via
  environment variable only, never committed, and the owner was told to rotate it.
