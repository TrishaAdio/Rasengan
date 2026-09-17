package dev.rasengan.client;

import dev.rasengan.RasenganParticles;
import dev.rasengan.network.RasenganPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Spawns the stochastic half of the effect straight into the local particle engine.
 *
 * <p>The mesh in {@link RasenganRenderer} handles everything that must be smooth and structural.
 * This class handles everything that should feel irregular and alive: motes pulled into the palm,
 * sparks thrown off the shell, wisps that form and dissolve, the body aura, and the impact burst.
 *
 * <h2>No particle ever crosses the network</h2>
 * Every spawn here is client-local. Determinism across clients comes from seeding a
 * {@link RandomSource} with the cast's server-issued seed combined with the current game tick, so
 * two clients rendering tick 1234 of the same cast spawn the same particles without a single
 * packet being exchanged for them.
 *
 * <h2>Budget</h2>
 * Per-tick counts are small and every one is scaled by the server's particle density, the
 * player's own particle setting, and distance. Nothing here loops unbounded.
 */
public final class ClientEffects {

    private ClientEffects() {}

    /** Per-tick emission for one active cast. */
    public static void tickCast(ClientLevel level, Player player, ClientCast cast, long gameTime) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();

        Vec3 palm = HandAnchor.palmPosition(player, cast, 1.0F);
        Vec3 sphere = HandAnchor.spherePosition(player, cast, gameTime, 1.0F);

        float distanceFactor = ClientTuning.distanceFactor(cameraPos, sphere,
                dev.rasengan.RasenganConfig.maxEffectDistance());
        if (distanceFactor <= 0.0F) {
            return;
        }

        float density = cast.particleDensity * distanceFactor;
        if (density <= 0.0F) {
            return;
        }

        // Seeded per tick: identical on every client, different every tick.
        RandomSource random = RandomSource.create(cast.seed * 31L + gameTime);

        float progress = cast.progress(gameTime, 1.0F);
        boolean released = cast.isReleased() || cast.hasImpact();

        if (cast.isCancelled() || released) {
            return; // aura and sphere emissions stop cleanly the moment the cast ends
        }

        // ---- Stage 1: inward-pulling intake motes ----
        if (progress < ClientCast.PHASE_HOLD_START) {
            int count = scale(3, density);
            for (int i = 0; i < count; i++) {
                // Start out in nearby space and curve inward toward the palm.
                Vec3 offset = randomUnit(random).scale(0.8D + random.nextDouble() * 0.9D);
                Vec3 from = palm.add(offset);
                Vec3 velocity = palm.subtract(from).normalize().scale(0.16D + random.nextDouble() * 0.10D);
                // A tangential component is what makes the path curve rather than fall straight in.
                Vec3 tangential = randomUnit(random).scale(0.05D);
                spawn(level, RasenganParticles.INTAKE.get(), from, velocity.add(tangential));
            }
        }

        // ---- Stage 2/3: sparks and wisps off the shell ----
        if (progress >= ClientCast.PHASE_FORM_START) {
            float formed = OrbitMath.smoothstep(ClientCast.PHASE_FORM_START, ClientCast.PHASE_HOLD_START, progress);

            int sparks = scale(Math.round(2 * formed), density);
            for (int i = 0; i < sparks; i++) {
                Vec3 dir = randomUnit(random);
                Vec3 from = sphere.add(dir.scale(0.26D));
                Vec3 velocity = dir.scale(0.10D + random.nextDouble() * 0.14D);
                spawn(level, RasenganParticles.SPARK.get(), from, velocity);
            }

            int wisps = scale(Math.round(2 * formed), density);
            for (int i = 0; i < wisps; i++) {
                Vec3 dir = randomUnit(random);
                Vec3 from = sphere.add(dir.scale(0.30D));
                // Slow, curling outward drift: these are the fading outer wisps.
                Vec3 velocity = dir.scale(0.045D).add(
                        (random.nextDouble() - 0.5D) * 0.05D,
                        0.02D + random.nextDouble() * 0.03D,
                        (random.nextDouble() - 0.5D) * 0.05D);
                spawn(level, RasenganParticles.WISP.get(), from, velocity);
            }
        }

        // ---- Stage 4: caster body aura ----
        if (cast.auraIntensity > 0.0F) {
            emitBodyAura(level, player, cast, palm, random, cast.auraIntensity * distanceFactor);
        }
    }

    /**
     * The body aura: flame-like wisps rising along the legs, torso and shoulders, a slow spiral
     * around the body, and faint currents that stream toward the casting hand.
     *
     * <p>Kept deliberately sparse so it never hides the player's skin, armour or held item.
     */
    private static void emitBodyAura(ClientLevel level, Player player, ClientCast cast,
                                     Vec3 palm, RandomSource random, float intensity) {
        if (intensity <= 0.0F) {
            return;
        }
        Vec3 feet = HandAnchor.interpolatedPosition(player, 1.0F);
        double height = player.getBbHeight();
        double width = player.getBbWidth() * 0.5D;

        // ---- Rising wisps from feet, legs, torso, shoulders ----
        int rising = scale(2, intensity);
        for (int i = 0; i < rising; i++) {
            // Bias toward the lower body so the aura looks like it is climbing.
            double heightFraction = Math.pow(random.nextDouble(), 0.7D);
            double angle = random.nextDouble() * Math.TAU;
            double radial = width * (0.55D + random.nextDouble() * 0.55D);

            Vec3 from = new Vec3(
                    feet.x + Math.cos(angle) * radial,
                    feet.y + heightFraction * height,
                    feet.z + Math.sin(angle) * radial);

            Vec3 velocity = new Vec3(
                    Math.cos(angle) * 0.012D,
                    0.055D + random.nextDouble() * 0.045D,
                    Math.sin(angle) * 0.012D);
            spawn(level, RasenganParticles.AURA_WISP.get(), from, velocity);
        }

        // ---- Slow spiral around the body ----
        if (random.nextFloat() < 0.7F * intensity) {
            double spiralAngle = (level.getGameTime() % 40L) / 40.0D * Math.TAU + random.nextDouble() * 0.4D;
            double spiralHeight = random.nextDouble() * height;
            double radial = width * 1.15D;
            Vec3 from = new Vec3(
                    feet.x + Math.cos(spiralAngle) * radial,
                    feet.y + spiralHeight,
                    feet.z + Math.sin(spiralAngle) * radial);
            // Tangential velocity gives the orbit; slight lift keeps it moving.
            Vec3 velocity = new Vec3(
                    -Math.sin(spiralAngle) * 0.09D,
                    0.018D,
                    Math.cos(spiralAngle) * 0.09D);
            spawn(level, RasenganParticles.AURA_WISP.get(), from, velocity);
        }

        // ---- Currents flowing toward the casting hand ----
        if (random.nextFloat() < 0.55F * intensity) {
            double angle = random.nextDouble() * Math.TAU;
            double radial = width * (1.0D + random.nextDouble() * 0.8D);
            Vec3 from = new Vec3(
                    feet.x + Math.cos(angle) * radial,
                    feet.y + 0.35D + random.nextDouble() * (height - 0.35D),
                    feet.z + Math.sin(angle) * radial);
            Vec3 velocity = palm.subtract(from).normalize().scale(0.10D + random.nextDouble() * 0.06D);
            spawn(level, RasenganParticles.INTAKE.get(), from, velocity);
        }

        // ---- Occasional short outward spark ----
        if (random.nextFloat() < 0.28F * intensity) {
            Vec3 dir = randomUnit(random);
            Vec3 from = new Vec3(feet.x, feet.y + height * 0.6D, feet.z).add(dir.scale(width));
            spawn(level, RasenganParticles.SPARK.get(), from, dir.scale(0.13D));
        }
    }

    // ------------------------------------------------------------------
    // Impact
    // ------------------------------------------------------------------

    /**
     * The outward burst at the impact point: a dense shell of fragments plus a few longer streaks.
     * The mesh renderer supplies the implosion and the shockwave rings; this adds the grit.
     */
    public static void spawnImpactBurst(ClientLevel level, Vec3 at, int hitKind,
                                        long seed, float density) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().position();
        float distanceFactor = ClientTuning.distanceFactor(cameraPos, at,
                dev.rasengan.RasenganConfig.maxEffectDistance());
        if (distanceFactor <= 0.0F) {
            return;
        }

        float effective = density * distanceFactor;
        RandomSource random = RandomSource.create(seed ^ 0x5DEECE66DL);

        int burst = scale(hitKind == RasenganPayloads.CastImpact.KIND_WHIFF ? 14 : 34, effective);
        for (int i = 0; i < burst; i++) {
            Vec3 dir = randomUnit(random);
            double speed = 0.18D + random.nextDouble() * 0.42D;
            spawn(level, RasenganParticles.BURST.get(), at.add(dir.scale(0.1D)), dir.scale(speed));
        }

        int streaks = scale(8, effective);
        for (int i = 0; i < streaks; i++) {
            Vec3 dir = randomUnit(random);
            double speed = 0.45D + random.nextDouble() * 0.55D;
            spawn(level, RasenganParticles.SPARK.get(), at, dir.scale(speed));
        }

        int wisps = scale(10, effective);
        for (int i = 0; i < wisps; i++) {
            Vec3 dir = randomUnit(random);
            spawn(level, RasenganParticles.WISP.get(), at.add(dir.scale(0.25D)),
                    dir.scale(0.07D + random.nextDouble() * 0.08D));
        }
    }

    /** A short, controlled impact sound. Uses stock game sounds - nothing imported. */
    public static void playImpactSound(ClientLevel level, Vec3 at) {
        // Layered stock sounds, pitched up and kept quiet, so the hit reads without being harsh.
        level.playLocalSound(at.x, at.y, at.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.55F, 1.65F, false);
        level.playLocalSound(at.x, at.y, at.z,
                SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.30F, 1.90F, false);
        level.playLocalSound(at.x, at.y, at.z,
                SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 0.45F, 0.80F, false);
    }

    /** A quiet rising tone as the sphere forms. */
    public static void playCastStartSound(ClientLevel level, Vec3 at) {
        level.playLocalSound(at.x, at.y, at.z,
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.35F, 1.75F, false);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void spawn(ClientLevel level, SimpleParticleType type, Vec3 at, Vec3 velocity) {
        level.addParticle(type, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z);
    }

    /** Uniformly distributed unit vector. */
    private static Vec3 randomUnit(RandomSource random) {
        double z = random.nextDouble() * 2.0D - 1.0D;
        double angle = random.nextDouble() * Math.TAU;
        double r = Math.sqrt(Math.max(0.0D, 1.0D - z * z));
        return new Vec3(Math.cos(angle) * r, z, Math.sin(angle) * r);
    }

    /**
     * Scales a base count, rounding probabilistically-free: a scale of 0.4 on a base of 1 still
     * yields 1 rather than silently dropping to 0, so low-density settings thin the effect out
     * instead of deleting it.
     */
    private static int scale(int base, float factor) {
        if (base <= 0 || factor <= 0.0F) {
            return 0;
        }
        int scaled = Math.round(base * factor);
        return Math.max(base > 0 ? 1 : 0, Math.min(scaled, base * 3));
    }
}
