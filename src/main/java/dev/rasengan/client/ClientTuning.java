package dev.rasengan.client;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side level-of-detail policy.
 *
 * <p>The server sets the ceiling; this class only ever scales <em>down</em> from it. A client can
 * choose to draw less for performance, never more than the server allows.
 *
 * <p>Two independent reductions are applied:
 * <ul>
 *   <li><b>Player preference</b> - the vanilla Particles video setting is respected, so someone
 *       running "Minimal" is not forced to render the full effect. Using the vanilla option
 *       rather than a bespoke setting means it also works for players who never open a config.</li>
 *   <li><b>Distance</b> - both particle counts and mesh detail fall off with distance from the
 *       camera, and past the server's max effect distance nothing is drawn at all. A cast twenty
 *       blocks away does not need the same triangle count as one in your face.</li>
 * </ul>
 */
public final class ClientTuning {

    private ClientTuning() {}

    /** Multiplier from the player's own particle setting. */
    public static float particleScale() {
        Minecraft minecraft = Minecraft.getInstance();
        ParticleStatus status = minecraft.options.particles().get();
        return switch (status) {
            case ALL -> 1.0F;
            case DECREASED -> 0.5F;
            case MINIMAL -> 0.2F;
        };
    }

    /** Aura is the first thing to go when the player has asked for fewer particles. */
    public static float auraScale() {
        Minecraft minecraft = Minecraft.getInstance();
        ParticleStatus status = minecraft.options.particles().get();
        return switch (status) {
            case ALL -> 1.0F;
            case DECREASED -> 0.4F;
            case MINIMAL -> 0.0F;
        };
    }

    /**
     * Distance falloff in 0..1. Full detail up close, tapering to nothing at {@code maxDistance}.
     *
     * @return 0 when the effect should be skipped entirely
     */
    public static float distanceFactor(Vec3 cameraPos, Vec3 effectPos, double maxDistance) {
        double distanceSq = cameraPos.distanceToSqr(effectPos);
        double maxSq = maxDistance * maxDistance;
        if (distanceSq >= maxSq) {
            return 0.0F;
        }
        double distance = Math.sqrt(distanceSq);

        // Full detail within 12 blocks, then a linear taper to the cap.
        final double fullDetail = 12.0D;
        if (distance <= fullDetail) {
            return 1.0F;
        }
        double t = (distance - fullDetail) / Math.max(1.0D, maxDistance - fullDetail);
        return (float) Math.clamp(1.0D - t, 0.08D, 1.0D);
    }

    /**
     * Mesh quality 0..1 derived from distance. Drives icosphere subdivision level and ring
     * segment counts, so distant spheres cost a fraction of the triangles.
     */
    public static float meshQuality(Vec3 cameraPos, Vec3 effectPos) {
        double distance = cameraPos.distanceTo(effectPos);
        if (distance <= 8.0D) {
            return 1.0F;
        }
        if (distance <= 20.0D) {
            return 0.6F;
        }
        if (distance <= 40.0D) {
            return 0.35F;
        }
        return 0.18F;
    }
}
