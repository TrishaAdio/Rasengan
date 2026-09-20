package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rasengan.Palette;
import dev.rasengan.RasenganParticles;
import dev.rasengan.SummonTimeline;
import dev.rasengan.network.RasenganSummonPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The summoning cinematic: seal, smoke, camera.
 *
 * <h2>The five stages</h2>
 * Boundaries come from {@link SummonTimeline}, shared with the server, so the reveal beat the server
 * fires and the moment the smoke thins are the same tick by construction rather than by agreement.
 *
 * <h2>Everything is a pure function of elapsed time</h2>
 * Nothing here integrates frame to frame. Seal radius, smoke emission rates, camera roll, FOV,
 * third-person distance and the release blend are all {@code f(seed, elapsedTicks)} where
 * {@code elapsedTicks} includes the frame's partial tick. That is what makes the camera smooth at any
 * frame rate and what lets two clients that received the same {@code SummonStart} draw the same
 * sequence - and it is why there are no per-tick camera corrections from the server.
 *
 * <h2>Why the camera could hitch, and what was actually wrong</h2>
 * Four candidate causes were checked against the previous implementation. Two were already fine and
 * were left alone; two were real:
 * <ul>
 *   <li><b>Already fine - not a server-driven camera.</b> The camera was, and is, entirely client
 *       local: one {@code SummonStart} packet, then a local timeline. There were never per-tick
 *       server corrections to stutter at low TPS.</li>
 *   <li><b>Already fine - no input suppression.</b> Only {@code ViewportEvent} render angles were
 *       ever touched, never the player's yaw/pitch, so no input was ever queued or discarded and
 *       there was nothing to flush on release.</li>
 *   <li><b>Real: the third-person distance stepped at 20 Hz.</b> {@code onDetachedDistance} called
 *       the intensity envelope with a hard-coded {@code partialTick = 1.0F}, so the pull-back moved
 *       once per tick while roll and FOV moved every frame. At 60+ fps that is a visible stair-step
 *       on the one part of the effect that actually translates the camera. Now every reader passes
 *       the frame's real partial tick.</li>
 *   <li><b>Real: a hard cut at the distance boundary.</b> Eligibility was a binary
 *       {@code distanceToSqr > 64*64} test, so an observer crossing 64 blocks mid-sequence had roll,
 *       FOV and distance snap to zero in one frame. Now the intensity tapers to nothing across the
 *       outer quarter of the radius, so crossing it is continuous.</li>
 *   <li><b>Real, and the worst of the four: an interrupted sequence never told the client.</b> The
 *       server dropped its pending entry and the client kept running the timeline - camera still
 *       rolling for a summon that had been abandoned because the summoner died. Handled now by
 *       {@code SummonEnd}; see {@link #onSummonEnd}.</li>
 * </ul>
 * The fourth candidate - heavy work landing on the release frame - was real too, but is fixed on the
 * server: the dragon is spawned hidden on tick 0 so no entity or model load lands on the reveal.
 *
 * <h2>The release blend</h2>
 * The envelope no longer ends by reaching zero incidentally; it has an explicit release phase. Over
 * the last {@code cameraReleaseTicks} the whole effect is multiplied by a smoothstep from 1 to 0, so
 * the view eases back rather than cutting. An interrupt starts that same blend immediately from
 * wherever the camera is, which is why an abort cannot leave a tilt behind.
 */
public final class SummonCinematic {

    /** One active sequence, keyed by summoner entity id. */
    private static final class Active {
        final int summonerId;
        final int dragonId;
        final long seed;
        final Vec3 origin;
        final long startGameTime;
        final SummonTimeline.Stages stages;
        final int releaseTicks;
        final float cameraRadius;
        final float smokeDensity;
        final boolean vignette;

        /**
         * Tick at which the release blend begins. Normally the natural end minus the blend length;
         * an interrupt moves it to now, so the camera eases back from wherever it is.
         */
        float releaseStart;

        /** Set once the sequence is aborted: stop emitting, but keep easing the camera back. */
        boolean aborted;

        /** Whether the stage-D wing downbeat impulse has been applied. Fires exactly once. */
        boolean dispersed;

        Active(RasenganSummonPayloads.SummonStart payload, long startGameTime) {
            this.summonerId = payload.summonerId();
            this.dragonId = payload.dragonId();
            this.seed = payload.seed();
            this.origin = new Vec3(payload.x(), payload.y(), payload.z());
            this.startGameTime = startGameTime;
            this.stages = SummonTimeline.of(payload.totalTicks(), payload.revealTick());
            this.releaseTicks = payload.cameraReleaseTicks();
            this.cameraRadius = payload.cameraRadius();
            this.smokeDensity = payload.smokeDensity();
            this.vignette = payload.vignette();
            this.releaseStart = Math.max(0.0F, payload.totalTicks() - releaseTicks);
        }

        boolean cameraEnabled() {
            return releaseTicks > 0;
        }

        /** Last tick at which this record does anything at all. */
        float endTick() {
            return Math.max(stages.total(), releaseStart + releaseTicks);
        }
    }

    /** Margin past the declared end before the record is dropped regardless. */
    private static final int EXPIRY_MARGIN = 40;

    /**
     * Radius the eruption column reaches by the reveal, in blocks.
     *
     * <p>Derived from what has to be hidden, not picked for looks. The dragon's collision box is 6
     * blocks wide and its body - torso, neck, head, wing roots - sits inside roughly the inner 10
     * blocks of its 29.5-block wingspan, so a 9-block radius covers the body with margin on both
     * sides. The outer wing membranes extend past it and emerge from the cloud's fringe, which is the
     * order stage C wants: outline first, then wings, then head.
     */
    private static final double COLUMN_RADIUS = 9.0D;

    /** Height the column reaches by the reveal. The dragon stands 4.8 blocks tall plus its lift. */
    private static final double COLUMN_HEIGHT = 7.5D;

    private static final java.util.Map<Integer, Active> ACTIVE = new java.util.LinkedHashMap<>();

    /** Scratch. Render thread only, and never held across a submit boundary. */
    private static final Vector3f P0 = new Vector3f();
    private static final Vector3f P1 = new Vector3f();
    private static final Vector3f TANGENT = new Vector3f();
    private static final Vector3f CAMERA_LOCAL = new Vector3f();

    private SummonCinematic() {}

    public static void onSummonStart(RasenganSummonPayloads.SummonStart payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ACTIVE.put(payload.summonerId(), new Active(payload, level.getGameTime()));
    }

    /**
     * The sequence is over.
     *
     * <p>A completed sequence is left to finish its own settle stage - it is already inside its
     * release blend by the time this arrives. An interrupted one is aborted: emission stops
     * immediately and the release blend starts from this instant, so the camera returns smoothly
     * from wherever it had got to instead of being abandoned mid-roll.
     */
    public static void onSummonEnd(RasenganSummonPayloads.SummonEnd payload) {
        Active active = ACTIVE.get(payload.summonerId());
        if (active == null) {
            return;
        }
        if (payload.reasonValue() == RasenganSummonPayloads.SummonEnd.Reason.COMPLETED) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            ACTIVE.remove(payload.summonerId());
            return;
        }
        active.aborted = true;
        float now = age(active, level.getGameTime(), 0.0F);
        // Only ever brings the release forward, never delays one already running.
        active.releaseStart = Math.min(active.releaseStart, now);

        // Cut the summoning audio. The buildup is a rising drone whose whole point is to stop on the
        // reveal beat; letting it finish after the sequence was abandoned leaves the player listening
        // to a swell that resolves into nothing. Stopped by sound id rather than by holding an
        // instance, because it was started server-side as a positional world sound.
        var sounds = Minecraft.getInstance().getSoundManager();
        sounds.stop(dev.rasengan.RasenganSounds.SUMMON_BUILDUP.getId(), SoundSource.PLAYERS);
        if (!active.dispersed) {
            // Only if the reveal had not already landed: past that the dragon is real and its arrival
            // roar belongs to it, not to the cancelled cinematic.
            sounds.stop(dev.rasengan.RasenganSounds.SUMMON_ROAR.getId(), SoundSource.HOSTILE);
        }
    }

    public static void clear() {
        ACTIVE.clear();
        SmokeParticle.clear();
    }

    private static float age(Active a, long gameTime, float partialTick) {
        return (gameTime - a.startGameTime) + partialTick;
    }

    /** The frame's partial tick, for the camera events that are not given one. */
    private static float framePartialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    // ------------------------------------------------------------------
    // Emission - stages A, B, C, D, E
    // ------------------------------------------------------------------

    /**
     * Emits the stochastic half of the effect, once per client tick.
     *
     * <p>Particle <em>counts</em> are stepped per tick because particles are discrete; everything
     * continuous about them - each one's size, opacity and rotation - is interpolated inside
     * {@link SmokeParticle}. Emission is staggered deliberately: spawning a burst on one frame reads
     * as a single expanding shell, where a staggered radial pattern reads as a churning volume.
     */
    public static void clientTick(ClientLevel level) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        ACTIVE.values().removeIf(a -> age(a, gameTime, 0.0F) > a.endTick() + EXPIRY_MARGIN);

        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();

        for (Active a : ACTIVE.values()) {
            if (a.aborted) {
                continue; // stop adding to a cloud whose summon no longer exists
            }
            float t = age(a, gameTime, 1.0F);
            if (t < 0.0F || t > a.stages.total()) {
                continue;
            }
            // Deterministic from the server seed plus the tick, so every client that received this
            // SummonStart emits the same particles on the same ticks.
            RandomSource random = RandomSource.create(a.seed * 31L + gameTime);

            Lod lod = Lod.of(cameraPos, a.origin, a.smokeDensity);
            if (lod.skip()) {
                continue;
            }

            SummonTimeline.Stages s = a.stages;
            if (t < s.sealEnd()) {
                emitSealStage(level, a, random, lod, t);
            } else if (t < s.eruptionEnd()) {
                emitEruptionStage(level, a, random, lod, t);
            } else if (t < s.dispersalStart()) {
                emitRevealStage(level, a, random, lod, t);
            } else if (t < s.dispersalEnd()) {
                emitDispersalStage(level, a, random, lod, t);
            } else {
                emitSettleStage(level, a, random, lod, t);
            }
        }
    }

    /**
     * Stage A - seal formation.
     *
     * <p>Chakra threads are drawn inward while the rings spread. The seal's own geometry is drawn in
     * {@link #submit}; this is only the particle half.
     */
    private static void emitSealStage(ClientLevel level, Active a, RandomSource random, Lod lod,
                                      float t) {
        float progress = Mth.clamp(t / Math.max(1.0F, a.stages.sealEnd()), 0.0F, 1.0F);

        int gather = lod.count(7.0F * progress, 1);
        for (int i = 0; i < gather; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double radius = 3.0D + random.nextDouble() * 6.0D;
            Vec3 from = a.origin.add(Math.cos(angle) * radius,
                    0.1D + random.nextDouble() * 1.8D, Math.sin(angle) * radius);
            Vec3 toward = a.origin.subtract(from).normalize().scale(0.26D);
            level.addParticle(RasenganParticles.INTAKE.get(),
                    from.x, from.y, from.z, toward.x, toward.y * 0.3D, toward.z);
        }

        // The first hint of smoke, low and thin, so the eruption has something to grow out of
        // rather than starting from nothing.
        int seep = lod.count(3.0F * progress * progress, 0);
        for (int i = 0; i < seep; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double radius = sealRadius(progress) * (0.3D + random.nextDouble() * 0.7D);
            spawnSmoke(RasenganParticles.GROUND_FOG.get(),
                    a.origin.x + Math.cos(angle) * radius, a.origin.y + 0.08D,
                    a.origin.z + Math.sin(angle) * radius,
                    0.0D, 0.012D, 0.0D,
                    0.55F, lod.alpha() * 0.5F, 0.7F);
        }
    }

    /**
     * Stage B - the smoke eruption. The centrepiece.
     *
     * <p>Density comes from overlap rather than from one big sprite: many large soft billboards at
     * varying scales, spawned in a staggered radial pattern across the whole stage, with strong
     * upward velocity, strong outward spread and heavy drag. Brightness peaks at the core and falls
     * off with radius, and the outermost ring is emitted as {@code SMOKE_WISP} so the boundary frays
     * instead of ending on a line.
     */
    private static void emitEruptionStage(ClientLevel level, Active a, RandomSource random, Lod lod,
                                          float t) {
        SummonTimeline.Stages s = a.stages;
        float span = Math.max(1.0F, s.eruptionEnd() - s.sealEnd());
        float local = Mth.clamp((t - s.sealEnd()) / span, 0.0F, 1.0F);

        // Front-loaded: the burst is violent at the start and settles into billowing. This is the
        // count curve; the deceleration the eye actually reads comes from the particles' drag.
        float intensity = (1.0F - local) * (1.0F - local) * 0.75F + 0.25F;

        // 28 rather than a round 20: the obscuration audit measures the optical depth this produces
        // along rays from the summoner's eye through the dragon's body, and 28 is where the thinnest
        // ray clears 0.95 with margin. Lower and the feet show through on the near side.
        int column = lod.count(28.0F * intensity, 4);
        for (int i = 0; i < column; i++) {
            ColumnPuff puff = columnPuff(random, local);
            spawnSmoke(puff.edge() ? RasenganParticles.SMOKE_WISP.get()
                            : RasenganParticles.SMOKE_BILLOW.get(),
                    a.origin.x + puff.dx(), a.origin.y + puff.dy(), a.origin.z + puff.dz(),
                    puff.vx(), puff.vy(), puff.vz(),
                    puff.brightness(), lod.alpha(), puff.sizeScale());
        }

        // The rolling ground layer: faster and thinner than the column, and it starts immediately so
        // it outruns the column rather than trailing it.
        int fog = lod.countOptional(9.0F * intensity, 0);
        for (int i = 0; i < fog; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double speed = 0.34D + random.nextDouble() * 0.40D;
            spawnSmoke(RasenganParticles.GROUND_FOG.get(),
                    a.origin.x + Math.cos(angle) * (0.5D + random.nextDouble() * 1.5D),
                    a.origin.y + 0.06D,
                    a.origin.z + Math.sin(angle) * (0.5D + random.nextDouble() * 1.5D),
                    Math.cos(angle) * speed, 0.012D, Math.sin(angle) * speed,
                    0.62F, lod.alpha() * 0.85F, 1.0F);
        }

        emitMotes(level, a, random, lod, 3.0F * intensity);
    }

    /**
     * Stage C - silhouette reveal.
     *
     * <p>No new column: the cloud is thinning, so emission drops to sustaining wisps only. The
     * revealing itself is the existing smoke fading and expanding on its own curves - that is what
     * uncovers the outline first, then the wings, then the head, because the column is densest at the
     * centre and the dragon's extremities sit further out.
     */
    private static void emitRevealStage(ClientLevel level, Active a, RandomSource random, Lod lod,
                                        float t) {
        SummonTimeline.Stages s = a.stages;
        float span = Math.max(1.0F, s.dispersalStart() - s.eruptionEnd());
        float local = Mth.clamp((t - s.eruptionEnd()) / span, 0.0F, 1.0F);
        float fading = 1.0F - local;

        int wisps = lod.countOptional(7.0F * fading, 0);
        for (int i = 0; i < wisps; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double radius = 1.5D + random.nextDouble() * 5.5D;
            spawnSmoke(RasenganParticles.SMOKE_WISP.get(),
                    a.origin.x + Math.cos(angle) * radius,
                    a.origin.y + 0.4D + random.nextDouble() * 4.0D,
                    a.origin.z + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.06D, 0.055D, Math.sin(angle) * 0.06D,
                    0.70F, lod.alpha() * fading, 1.25F);
        }
        emitMotes(level, a, random, lod, 1.5F * fading);
    }

    /**
     * Stage D - wing-beat dispersal.
     *
     * <p>The impulse is applied to the smoke that is <em>already there</em>, exactly once, on the
     * tick {@link SummonTimeline} identified as a wing downbeat. That is the difference between
     * dispersal that looks caused by the wings and a second burst that merely coincides with them:
     * the cloud the viewer has been watching for two seconds is what accelerates.
     */
    private static void emitDispersalStage(ClientLevel level, Active a, RandomSource random, Lod lod,
                                           float t) {
        if (!a.dispersed) {
            a.dispersed = true;

            Vec3 centre = dragonCentre(level, a);
            SmokeParticle.applyDownbeat(centre, 16.0D, 0.62D);

            // Ground dust ring pushed outward along the surface by the downbeat. Spawned rather than
            // impulsed because this is new material lifted off the floor, not smoke already aloft.
            int ring = lod.count(18.0F, 3);
            for (int i = 0; i < ring; i++) {
                double angle = (i / (double) Math.max(1, ring)) * Math.TAU
                        + random.nextDouble() * 0.25D;
                double speed = 0.66D + random.nextDouble() * 0.34D;
                spawnSmoke(RasenganParticles.GROUND_FOG.get(),
                        centre.x + Math.cos(angle) * 1.4D,
                        a.origin.y + 0.07D,
                        centre.z + Math.sin(angle) * 1.4D,
                        Math.cos(angle) * speed, 0.02D, Math.sin(angle) * speed,
                        0.70F, lod.alpha(), 1.15F);
            }
        }
    }

    /** Stage E - settle. Residual wisps only; the ground fog spawned earlier outlives them. */
    private static void emitSettleStage(ClientLevel level, Active a, RandomSource random, Lod lod,
                                        float t) {
        SummonTimeline.Stages s = a.stages;
        float span = Math.max(1.0F, s.total() - s.settleStart());
        float remaining = 1.0F - Mth.clamp((t - s.settleStart()) / span, 0.0F, 1.0F);

        int wisps = lod.countOptional(3.0F * remaining, 0);
        for (int i = 0; i < wisps; i++) {
            double angle = random.nextDouble() * Math.TAU;
            double radius = 2.5D + random.nextDouble() * 7.0D;
            spawnSmoke(RasenganParticles.SMOKE_WISP.get(),
                    a.origin.x + Math.cos(angle) * radius,
                    a.origin.y + 0.3D + random.nextDouble() * 2.5D,
                    a.origin.z + Math.sin(angle) * radius,
                    Math.cos(angle) * 0.04D, 0.03D, Math.sin(angle) * 0.04D,
                    0.62F, lod.alpha() * remaining * 0.8F, 1.35F);
        }
    }

    /** Screen-space-ish dust near the camera, for depth. Only worth it for close observers. */
    private static void emitMotes(ClientLevel level, Active a, RandomSource random, Lod lod,
                                  float rate) {
        if (!lod.nearCamera()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        int count = lod.countOptional(rate, 0);
        Vec3 eye = minecraft.player.getEyePosition();
        for (int i = 0; i < count; i++) {
            Vec3 at = eye.add((random.nextDouble() - 0.5D) * 4.0D,
                    (random.nextDouble() - 0.5D) * 2.2D,
                    (random.nextDouble() - 0.5D) * 4.0D);
            spawnSmoke(RasenganParticles.DUST_MOTE.get(),
                    at.x, at.y, at.z,
                    (random.nextDouble() - 0.5D) * 0.05D,
                    -0.01D - random.nextDouble() * 0.02D,
                    (random.nextDouble() - 0.5D) * 0.05D,
                    0.85F, lod.alpha(), 1.0F);
        }
    }

    /**
     * One puff of the eruption column: where it starts, how fast, how bright, how big.
     *
     * <p>Offsets are relative to the seal centre. Extracted as a pure function of
     * {@code (random, local)} rather than inlined so that the obscuration measurement in
     * {@code tools/verify/SummonCinematicAudit} can evaluate the <em>real</em> distribution instead
     * of a reimplementation of it - the coverage figure is only worth anything if it describes the
     * cloud that actually ships.
     */
    record ColumnPuff(double dx, double dy, double dz, double vx, double vy, double vz,
                      float brightness, float sizeScale, boolean edge) {}

    /**
     * Places one column puff.
     *
     * <p>Staggered radially rather than uniformly: biased toward the centre early and outward later,
     * so the column visibly grows across the stage instead of appearing at full width.
     *
     * <p>The radius it reaches is not arbitrary. The dragon's body - torso, neck, head, wing roots -
     * occupies roughly the inner 10 blocks of its 29.5-block wingspan, and the cloud has to cover that
     * by the reveal for the staged silhouette to work. {@link #COLUMN_RADIUS} is set from that, which
     * leaves the outer wing membranes emerging from the fringe - the order stage C wants anyway.
     */
    static ColumnPuff columnPuff(RandomSource random, float local) {
        double radiusFraction = Math.pow(random.nextDouble(), 1.6D - local * 0.9D);
        double radius = radiusFraction * (2.0D + (COLUMN_RADIUS - 2.0D) * local);
        double angle = random.nextDouble() * Math.TAU;

        // Height is biased toward the base rather than uniform over the column. Two reasons, and the
        // second was measured: every puff rises over its life, so a uniform spawn height leaves the
        // bottom of the cloud progressively thinner; and the summoner's eye is 1.62 blocks up, so the
        // rays that matter most are the near-horizontal ones through exactly that thin region. The
        // obscuration audit found the base at 0.88 opacity with a uniform distribution against 0.998
        // everywhere else - a faint silhouette of the dragon's feet showing through.
        double heightFraction = Math.pow(random.nextDouble(), 1.45D);
        double height = heightFraction * (0.8D + (COLUMN_HEIGHT - 0.8D) * local);

        double outward = (0.26D + random.nextDouble() * 0.44D) * (0.35D + radiusFraction);
        double up = 0.44D + random.nextDouble() * 0.48D - height * 0.030D;

        // Core is near-white, edges duller. The same fraction decides which particle type is used, so
        // brightness and fraying agree.
        float coreness = (float) (1.0D - radiusFraction);
        boolean edge = radiusFraction > 0.72D;

        return new ColumnPuff(
                Math.cos(angle) * radius, 0.15D + height, Math.sin(angle) * radius,
                Math.cos(angle) * outward, up, Math.sin(angle) * outward,
                0.72F + coreness * 0.28F,
                0.80F + (float) radiusFraction * 0.75F,
                edge);
    }

    /**
     * Spawns one smoke particle and applies the per-particle tuning the spawner owns.
     *
     * <p>Goes through the particle engine rather than {@code level.addParticle} because that returns
     * the instance, which is what allows brightness, opacity and scale to be set per particle. The
     * radial brightness gradient depends on it.
     */
    private static void spawnSmoke(net.minecraft.core.particles.SimpleParticleType type,
                                   double x, double y, double z,
                                   double xa, double ya, double za,
                                   float brightness, float alphaScale, float sizeScale) {
        var particle = Minecraft.getInstance().particleEngine
                .createParticle(type, x, y, z, xa, ya, za);
        // Null when the client's particle limiter refused it, which is a legitimate outcome for the
        // optional styles; there is simply nothing to tune.
        if (particle instanceof SmokeParticle smoke) {
            smoke.setBrightness(brightness);
            smoke.setAlphaScale(alphaScale);
            smoke.scaleSize(sizeScale);
        }
    }

    /** Where the dragon actually is, falling back to the seal if it is not loaded on this client. */
    private static Vec3 dragonCentre(ClientLevel level, Active a) {
        Entity dragon = level.getEntity(a.dragonId);
        if (dragon != null) {
            return dragon.position();
        }
        return a.origin;
    }

    /** Seal radius over the buildup: eases outward, settling just before the reveal. */
    private static double sealRadius(float progress) {
        return 1.0D + 6.0D * OrbitMath.easeOut(Math.min(1.0F, progress / 0.85F));
    }

    // ------------------------------------------------------------------
    // Level of detail
    // ------------------------------------------------------------------

    /**
     * Distance and preference based budget.
     *
     * <p>The policy the requirement asks for, made explicit: the seal and the main column are
     * <em>mandatory</em> - {@link #count} always returns at least its floor, because if the column is
     * culled the summon is not hidden and the reveal breaks. Wisps, ground fog and motes go through
     * {@link #countOptional}, which is allowed to reach zero.
     */
    private record Lod(float scale, float alpha, double distance) {

        /** Beyond this the effect is not drawn at all. */
        private static final double MAX_DISTANCE = 96.0D;

        static Lod of(Vec3 cameraPos, Vec3 origin, float serverDensity) {
            double distance = cameraPos.distanceTo(origin);
            if (distance >= MAX_DISTANCE) {
                return new Lod(0.0F, 0.0F, distance);
            }
            // Client preference, then distance. Never scales above the server's ceiling.
            float scale = ClientTuning.particleScale() * serverDensity;
            float alpha = 1.0F;
            if (distance > 24.0D) {
                float k = (float) ((distance - 24.0D) / (MAX_DISTANCE - 24.0D));
                scale *= Mth.lerp(k, 1.0F, 0.25F);
                // Fade rather than cut at the far boundary.
                alpha = Mth.clamp(1.0F - k * k, 0.0F, 1.0F);
            }
            return new Lod(scale, alpha, distance);
        }

        boolean skip() {
            return scale <= 0.0F || alpha <= 0.01F;
        }

        boolean nearCamera() {
            return distance < 18.0D;
        }

        /** Mandatory element: never below {@code floor}, even at minimal settings. */
        int count(float rate, int floor) {
            return Math.max(floor, Math.round(rate * scale));
        }

        /** Optional garnish: may legitimately reach zero. */
        int countOptional(float rate, int floor) {
            int n = Math.round(rate * scale);
            return Math.max(floor, n);
        }
    }

    // ------------------------------------------------------------------
    // Seal geometry
    // ------------------------------------------------------------------

    /**
     * Draws the seal: concentric rings plus radial rune ticks, flat on the ground.
     *
     * <p>Same deferred-submit discipline as every other effect here - camera-local is captured into
     * finals and written inside the callback, because submits are batched per render type and a
     * callback reading shared state would otherwise pick up whichever effect submitted last.
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos,
                              long gameTime, float partialTick, double maxDistance) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Active a : ACTIVE.values()) {
            float t = age(a, gameTime, partialTick);
            if (t < 0.0F || t > a.stages.total()) {
                continue;
            }
            float distanceFactor = ClientTuning.distanceFactor(cameraPos, a.origin, maxDistance);
            if (distanceFactor <= 0.0F) {
                continue;
            }
            float progress = Mth.clamp(t / Math.max(1.0F, a.stages.sealEnd()), 0.0F, 1.0F);

            // Full strength until the reveal, then fading across whatever remains. Continuous at
            // both the reveal boundary and the end.
            float fade = t <= a.stages.eruptionEnd()
                    ? 1.0F
                    : Math.max(0.0F, 1.0F - (t - a.stages.eruptionEnd())
                            / Math.max(1.0F, a.stages.total() - a.stages.eruptionEnd()));
            if (a.aborted) {
                // Aborted: fade the seal out over the release blend rather than leaving it lit.
                fade *= releaseFactor(a, t);
            }
            float alpha = distanceFactor * fade;
            if (alpha <= 0.01F) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(a.origin.x - cameraPos.x, a.origin.y - cameraPos.y,
                    a.origin.z - cameraPos.z);

            final float camX = (float) (cameraPos.x - a.origin.x);
            final float camY = (float) (cameraPos.y - a.origin.y);
            final float camZ = (float) (cameraPos.z - a.origin.z);
            final double radius = sealRadius(progress);
            final float fAlpha = alpha;
            final float fTime = t;
            final long seed = a.seed;

            collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                CAMERA_LOCAL.set(camX, camY, camZ);
                emitSeal(pose, buffer, radius, fTime, fAlpha, seed);
            });
            poseStack.popPose();
        }
    }

    private static void emitSeal(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer,
                                 double radius, float time, float alpha, long seed) {
        // Three concentric rings, counter-rotating, plus radial rune ticks on the outer ring.
        for (int ring = 0; ring < 3; ring++) {
            double r = radius * (0.45D + 0.275D * ring);
            float spin = (ring % 2 == 0 ? 1.0F : -1.0F) * time * 0.020F;
            int segments = 48;
            int rgb = ring == 0 ? Palette.HIGHLIGHT : (ring == 1 ? Palette.CYAN : Palette.DEEP_CYAN);
            for (int s = 0; s < segments; s++) {
                double a0 = s * (Math.TAU / segments) + spin;
                double a1 = (s + 1) * (Math.TAU / segments) + spin;
                P0.set((float) (Math.cos(a0) * r), 0.06F, (float) (Math.sin(a0) * r));
                P1.set((float) (Math.cos(a1) * r), 0.06F, (float) (Math.sin(a1) * r));
                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();
                EnergyGeometry.ribbon(pose, buffer, P0, P1, TANGENT, 0.045F, CAMERA_LOCAL,
                        rgb, 0.75F * alpha);
            }
        }

        // Rune ticks: short radial marks at fixed intervals, lengths varied by the seed so two
        // different summons are not identical.
        int runes = 16;
        for (int i = 0; i < runes; i++) {
            double angle = i * (Math.TAU / runes) - time * 0.010F;
            double inner = radius * 0.74D;
            double length = radius * (0.10D + 0.08D * (((seed >> i) & 3L) / 3.0D));
            P0.set((float) (Math.cos(angle) * inner), 0.06F, (float) (Math.sin(angle) * inner));
            P1.set((float) (Math.cos(angle) * (inner + length)), 0.06F,
                    (float) (Math.sin(angle) * (inner + length)));
            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();
            EnergyGeometry.ribbon(pose, buffer, P0, P1, TANGENT, 0.055F, CAMERA_LOCAL,
                    Palette.HIGHLIGHT, 0.85F * alpha);
        }
    }

    // ------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------

    /**
     * The release blend: 1 before the release begins, easing to 0 across {@code releaseTicks}.
     *
     * <p>Smoothstep rather than linear, so the camera's <em>velocity</em> is zero at both ends of the
     * blend as well as its position. A linear ramp returns the view on time but with a visible change
     * of direction at each end, which is exactly the "hitch" the effect is trying not to have.
     */
    private static float releaseFactor(Active a, float t) {
        if (a.releaseTicks <= 0) {
            return 0.0F;
        }
        if (t <= a.releaseStart) {
            return 1.0F;
        }
        float k = Mth.clamp((t - a.releaseStart) / a.releaseTicks, 0.0F, 1.0F);
        float eased = k * k * (3.0F - 2.0F * k);
        return 1.0F - eased;
    }

    /**
     * Distance weighting for an observer, tapering to nothing rather than switching off.
     *
     * <p>Full strength inside three quarters of the radius, then a smooth taper to zero. The previous
     * binary test meant walking across the boundary mid-sequence snapped the view in one frame.
     */
    private static float distanceWeight(Vec3 viewer, Active a) {
        double distance = viewer.distanceTo(a.origin);
        double radius = a.cameraRadius;
        if (distance >= radius) {
            return 0.0F;
        }
        double inner = radius * 0.75D;
        if (distance <= inner) {
            return 1.0F;
        }
        float k = (float) ((distance - inner) / Math.max(1.0E-6D, radius - inner));
        return 1.0F - (k * k * (3.0F - 2.0F * k));
    }

    /**
     * 0 when no camera effect applies, otherwise 0..1 intensity for the local player's view.
     *
     * <p>Shaped as: a sine arc over the framing window, multiplied by the distance taper and the
     * release blend. Every factor is continuous and every one reaches exactly zero at its own
     * boundary, so the composite cannot step.
     */
    private static float cameraIntensity(long gameTime, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0.0F;
        }
        Vec3 viewer = minecraft.player.position();
        float best = 0.0F;

        for (Active a : ACTIVE.values()) {
            if (!a.cameraEnabled()) {
                continue;
            }
            float weight = distanceWeight(viewer, a);
            if (weight <= 0.0F) {
                continue;
            }
            float t = age(a, gameTime, partialTick);
            if (t < 0.0F) {
                continue;
            }
            float release = releaseFactor(a, t);
            if (release <= 0.0F) {
                continue;
            }

            // Framing window: begins as the smoke erupts, holds through the reveal.
            float from = a.stages.sealEnd();
            float to = Math.max(from + 1.0F, a.releaseStart);
            float local = Mth.clamp((t - from) / (to - from), 0.0F, 1.0F);
            float arc = (float) Math.sin(local * Math.PI);
            // Hold near full through the middle instead of peaking and immediately falling: the
            // bare sine spends very little time at strength, which reads as a twitch.
            arc = Math.min(1.0F, arc * 1.6F);

            best = Math.max(best, arc * weight * release);
        }
        return best;
    }

    /**
     * Roll and rumble.
     *
     * <p>Never touches the player's yaw or pitch <em>input</em> - only the rendered angle for this
     * frame. That is why there is nothing to flush when the effect ends: the player has been steering
     * normally the whole time, and the offset simply goes to zero.
     */
    public static void onComputeCameraAngles(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ACTIVE.isEmpty()) {
            return;
        }
        float partialTick = (float) event.getPartialTick();
        long gameTime = level.getGameTime();
        float intensity = cameraIntensity(gameTime, partialTick);
        if (intensity <= 0.001F) {
            return;
        }
        event.setRoll(event.getRoll() + 2.4F * intensity);

        // Rumble: a short, high-frequency shake on the reveal, decaying fast, scaled by each
        // observer's own distance. Distinct from the roll, which is slow and wide.
        if (minecraft.player == null) {
            return;
        }
        Vec3 viewer = minecraft.player.position();
        for (Active a : ACTIVE.values()) {
            if (!a.cameraEnabled()) {
                continue;
            }
            float weight = distanceWeight(viewer, a);
            if (weight <= 0.0F) {
                continue;
            }
            float t = age(a, gameTime, partialTick);
            float shake = rumble(a, t) * weight * releaseFactor(a, t);
            if (shake <= 0.0F) {
                continue;
            }
            float n1 = OrbitMath.noise(1.7F, 3.1F, 5.3F, t * 2.4F, 0.0F) - 0.5F;
            float n2 = OrbitMath.noise(4.3F, 1.9F, 2.7F, t * 2.9F, 0.5F) - 0.5F;
            event.setYaw(event.getYaw() + n1 * 2.6F * shake);
            event.setPitch(event.getPitch() + n2 * 2.0F * shake);
        }
    }

    /**
     * Rumble envelope: one decaying pulse on the reveal, one sharper one on the wing downbeat.
     *
     * <p>Two separate beats rather than one long shake, because they are two different events - the
     * arrival impact and the wingbeat - and merging them into a continuous tremor loses both.
     */
    private static float rumble(Active a, float t) {
        float total = 0.0F;

        float sinceReveal = t - a.stages.revealStart();
        if (sinceReveal >= 0.0F && sinceReveal < 14.0F) {
            float k = 1.0F - sinceReveal / 14.0F;
            total += k * k;
        }
        // The downbeat shake: shorter and higher frequency, so it reads as an impact rather than a
        // continuation of the arrival.
        float sinceBeat = t - a.stages.dispersalStart();
        if (sinceBeat >= 0.0F && sinceBeat < 7.0F) {
            float k = 1.0F - sinceBeat / 7.0F;
            total += k * k * 0.85F;
        }
        return Math.min(1.6F, total);
    }

    /** Brief FOV widening, which reads as a pull-back without moving the player. */
    public static void onComputeFov(
            net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ACTIVE.isEmpty()) {
            return;
        }
        float intensity = cameraIntensity(level.getGameTime(), (float) event.getPartialTick());
        if (intensity <= 0.001F) {
            return;
        }
        event.setFOV(event.getFOV() * (1.0F + 0.16F * intensity));
    }

    /**
     * In third person, also pull the camera back so the scale of the arrival reads.
     *
     * <p>This event carries no partial tick, so one is taken from the frame's own delta tracker. The
     * previous implementation passed a hard-coded {@code 1.0F} here, which quantised the only
     * translating part of the effect to 20 Hz while roll and FOV ran per frame - a stair-step
     * precisely on release.
     */
    public static void onDetachedDistance(
            net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ACTIVE.isEmpty()) {
            return;
        }
        float intensity = cameraIntensity(level.getGameTime(), framePartialTick());
        if (intensity <= 0.001F) {
            return;
        }
        event.setDistance(event.getDistance() * (1.0F + 1.5F * intensity));
    }

    // ------------------------------------------------------------------
    // Vignette
    // ------------------------------------------------------------------

    /**
     * Strength of the reveal vignette for the local player, 0..1.
     *
     * <p>The only thing a player "feels" from the fear reaction, and deliberately so: mobs are pushed
     * around, players are not. Read by {@link SummonVignette}.
     */
    public static float vignetteStrength(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null || ACTIVE.isEmpty()) {
            return 0.0F;
        }
        long gameTime = level.getGameTime();
        Vec3 viewer = minecraft.player.position();
        float best = 0.0F;

        for (Active a : ACTIVE.values()) {
            if (!a.vignette) {
                continue;
            }
            float weight = distanceWeight(viewer, a);
            if (weight <= 0.0F) {
                continue;
            }
            float t = age(a, gameTime, partialTick);
            float sinceReveal = t - a.stages.revealStart();
            if (sinceReveal < 0.0F || sinceReveal > 18.0F) {
                continue;
            }
            // One pulse: rises over three ticks, decays over the rest. Smooth at both ends.
            float k = sinceReveal < 3.0F
                    ? sinceReveal / 3.0F
                    : 1.0F - (sinceReveal - 3.0F) / 15.0F;
            k = Mth.clamp(k, 0.0F, 1.0F);
            best = Math.max(best, k * k * weight * releaseFactor(a, t));
        }
        return best;
    }

    public static boolean isActive() {
        return !ACTIVE.isEmpty();
    }

    /** Number of live sequences. Exposed for the verification harness. */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
