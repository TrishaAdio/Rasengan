package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rasengan.Palette;
import dev.rasengan.RasenganParticles;
import dev.rasengan.network.RasenganSummonPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The client half of the summoning cinematic: the seal, the smoke, and the camera.
 *
 * <h2>Everything is derived, nothing is streamed</h2>
 * One {@code SummonStart} packet gives the seal's centre, a seed and the timeline. Every ring, rune
 * and smoke puff is computed from {@code (seed, elapsedTicks)}, so two players watching the same
 * summon see the same thing without a single per-particle packet - the same rule the ability effects
 * follow.
 *
 * <h2>The camera cannot get stuck</h2>
 * This is the part most likely to trap a player, so it is defended three ways. The effect is a pure
 * function of elapsed ticks, so it returns to zero on its own rather than needing to be switched off.
 * The record self-expires past the sequence length plus a margin. And crucially <b>player input is
 * never taken away</b>: the camera is nudged - a roll, a brief FOV pull-back, a rumble on the reveal -
 * while look and movement stay under the player's control the whole time. A "cinematic" that
 * confiscates the controls for three seconds is also a cinematic the player cannot escape if anything
 * goes wrong.
 */
public final class SummonCinematic {

    /** One active sequence, keyed by summoner entity id. */
    private record Active(int summonerId, long seed, Vec3 origin, long startGameTime,
                          int totalTicks, int revealTick, int cameraStart, int cameraEnd,
                          boolean cameraEnabled) {}

    /** Margin past the declared end before the record is dropped regardless. */
    private static final int EXPIRY_MARGIN = 40;

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
        ACTIVE.put(payload.summonerId(), new Active(
                payload.summonerId(), payload.seed(),
                new Vec3(payload.x(), payload.y(), payload.z()),
                level.getGameTime(),
                payload.totalTicks(), payload.revealTick(),
                payload.cameraStart(), payload.cameraEnd(), payload.cameraEnabled()));
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private static float age(Active a, long gameTime, float partialTick) {
        return (gameTime - a.startGameTime) + partialTick;
    }

    /** Emits the stochastic half: chakra gather, smoke build, and the dispersal burst. */
    public static void clientTick(ClientLevel level) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        ACTIVE.values().removeIf(a -> age(a, gameTime, 0.0F) > a.totalTicks + EXPIRY_MARGIN);

        for (Active a : ACTIVE.values()) {
            float t = age(a, gameTime, 1.0F);
            RandomSource random = RandomSource.create(a.seed * 31L + gameTime);
            float density = ClientTuning.particleScale();
            if (density <= 0.0F) {
                continue;
            }
            float progress = Mth.clamp(t / a.revealTick, 0.0F, 1.0F);

            if (t < a.revealTick) {
                // Chakra drawn inward to the seal, thickening as it builds.
                int gather = Math.max(1, Math.round(4 * progress * density));
                for (int i = 0; i < gather; i++) {
                    double angle = random.nextDouble() * Math.TAU;
                    double radius = 3.0D + random.nextDouble() * 5.0D;
                    Vec3 from = a.origin.add(Math.cos(angle) * radius,
                            0.1D + random.nextDouble() * 1.5D, Math.sin(angle) * radius);
                    Vec3 toward = a.origin.subtract(from).normalize().scale(0.22D);
                    level.addParticle(RasenganParticles.INTAKE.get(),
                            from.x, from.y, from.z, toward.x, toward.y * 0.3D, toward.z);
                }
                // Smoke swelling from the circle itself.
                int smoke = Math.max(1, Math.round(6 * progress * progress * density));
                for (int i = 0; i < smoke; i++) {
                    double angle = random.nextDouble() * Math.TAU;
                    double radius = sealRadius(progress) * (0.4D + random.nextDouble() * 0.6D);
                    level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                            a.origin.x + Math.cos(angle) * radius,
                            a.origin.y + 0.1D,
                            a.origin.z + Math.sin(angle) * radius,
                            0.0D, 0.04D + random.nextDouble() * 0.05D, 0.0D);
                }
            } else if (t < a.revealTick + 10) {
                // Reveal: smoke thrown violently outward as the dragon arrives.
                int burst = Math.max(2, Math.round(18 * density));
                for (int i = 0; i < burst; i++) {
                    double angle = random.nextDouble() * Math.TAU;
                    double speed = 0.5D + random.nextDouble() * 0.9D;
                    level.addParticle(ParticleTypes.LARGE_SMOKE,
                            a.origin.x, a.origin.y + 0.4D, a.origin.z,
                            Math.cos(angle) * speed, 0.12D + random.nextDouble() * 0.2D,
                            Math.sin(angle) * speed);
                }
            }
        }
    }

    /** Seal radius over the buildup: eases outward, settling just before the reveal. */
    private static double sealRadius(float progress) {
        return 1.0D + 6.0D * OrbitMath.easeOut(Math.min(1.0F, progress / 0.85F));
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
            if (t < 0.0F || t > a.totalTicks) {
                continue;
            }
            float distanceFactor = ClientTuning.distanceFactor(cameraPos, a.origin, maxDistance);
            if (distanceFactor <= 0.0F) {
                continue;
            }
            float progress = Mth.clamp(t / a.revealTick, 0.0F, 1.0F);
            // Fade the seal out over the settle phase rather than cutting it.
            float fade = t <= a.revealTick
                    ? 1.0F
                    : Math.max(0.0F, 1.0F - (t - a.revealTick) / (float) Math.max(1, a.totalTicks - a.revealTick));
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

    /** 0 when no camera effect applies, otherwise 0..1 intensity for the local player's view. */
    private static float cameraIntensity(long gameTime, float partialTick) {
        float best = 0.0F;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0.0F;
        }
        for (Active a : ACTIVE.values()) {
            if (!a.cameraEnabled) {
                continue;
            }
            // Observers get the effect too, but only within a sensible radius.
            if (minecraft.player.position().distanceToSqr(a.origin) > 64.0D * 64.0D) {
                continue;
            }
            float t = age(a, gameTime, partialTick);
            if (t < a.cameraStart || t > a.cameraEnd) {
                continue;
            }
            float span = Math.max(1.0F, a.cameraEnd - a.cameraStart);
            float local = (t - a.cameraStart) / span;
            // Ease in and out so it never begins or ends with a jump.
            best = Math.max(best, (float) Math.sin(local * Math.PI));
        }
        return best;
    }

    /** Small roll plus a reveal rumble. Never touches yaw or pitch input, only the rendered angle. */
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
        // Slow roll for the cinematic tilt.
        event.setRoll(event.getRoll() + 2.4F * intensity);

        // Rumble, concentrated on the reveal beat and decaying quickly after it.
        for (Active a : ACTIVE.values()) {
            float t = age(a, gameTime, partialTick);
            float since = t - a.revealTick;
            if (since < 0.0F || since > 14.0F) {
                continue;
            }
            float shake = (1.0F - since / 14.0F);
            shake *= shake;
            float n1 = (OrbitMath.noise(1.7F, 3.1F, 5.3F, t * 2.4F, 0.0F) - 0.5F);
            float n2 = (OrbitMath.noise(4.3F, 1.9F, 2.7F, t * 2.9F, 0.5F) - 0.5F);
            event.setYaw(event.getYaw() + n1 * 2.6F * shake);
            event.setPitch(event.getPitch() + n2 * 2.0F * shake);
        }
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

    /** In third person, also pull the camera back so the scale of the arrival reads. */
    public static void onDetachedDistance(
            net.neoforged.neoforge.client.event.CalculateDetachedCameraDistanceEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || ACTIVE.isEmpty()) {
            return;
        }
        float intensity = cameraIntensity(level.getGameTime(), 1.0F);
        if (intensity <= 0.001F) {
            return;
        }
        event.setDistance(event.getDistance() * (1.0F + 1.5F * intensity));
    }

    public static boolean isActive() {
        return !ACTIVE.isEmpty();
    }
}
