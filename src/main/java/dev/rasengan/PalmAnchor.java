package dev.rasengan;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Computes where a caster's palm is in world space.
 *
 * <p>Deliberately common rather than client-only. The client renders the held sphere here, and the
 * server launches the projectile from here, so both agree on the handoff point to the block. If the
 * server launched from, say, "eyes plus a bit forward" while the client drew the sphere at the palm,
 * the throw would visibly jump at the moment of release.
 *
 * <h2>Why this is not just "player position plus an offset"</h2>
 * <ul>
 *   <li>Position is interpolated between the previous and current tick with the frame's partial
 *       tick, matching how the player model itself is drawn. Using the raw current position would
 *       make the sphere stutter relative to the body.</li>
 *   <li>The offset is built from the <em>body</em> yaw, not the head yaw, so the hand does not fly
 *       around the player when they flick the mouse. Pitch is applied separately and damped, which
 *       is what a real arm does.</li>
 *   <li>Yaw is interpolated the short way around the circle, so crossing the 180-degree boundary
 *       does not send the sphere on a full lap around the player.</li>
 * </ul>
 *
 * <p>This class contains no rendering code and touches no client-only type, so a dedicated server
 * loads it safely.
 */
public final class PalmAnchor {

    /** Forward reach from the chest, in blocks. */
    private static final double FORWARD = 0.52D;
    /** Sideways offset to the casting hand. */
    private static final double SIDE = 0.42D;
    /** Height below eye level where the hand sits. */
    private static final double DROP = 0.42D;

    private PalmAnchor() {}

    /**
     * Interpolated world position of the casting palm.
     *
     * @param mainHand    true if the ability is bound to the main hand
     * @param partialTick 0..1 within the current tick; the server passes 1.0
     */
    public static Vec3 palmPosition(Player player, boolean mainHand, float partialTick) {
        Vec3 body = interpolatedPosition(player, partialTick);

        float bodyYaw = lerpAngle(player.yBodyRotO, player.yBodyRot, partialTick);
        float pitch = lerpAngle(player.xRotO, player.getXRot(), partialTick);

        // An arm follows pitch, but only partially - the shoulder absorbs most of it.
        double pitchRad = Math.toRadians(pitch) * 0.55D;
        double yawRad = Math.toRadians(bodyYaw);

        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        // Minecraft yaw: 0 faces +Z. Forward = (-sin, _, cos). Right = (cos, _, sin).
        double forwardX = -sin;
        double forwardZ = cos;
        double rightX = cos;
        double rightZ = sin;

        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        double side = (mainHand == rightHanded) ? SIDE : -SIDE;

        double reach = FORWARD * Math.cos(pitchRad);
        double lift = -FORWARD * Math.sin(pitchRad);

        double eyeY = body.y + player.getEyeHeight();

        return new Vec3(
                body.x + forwardX * reach + rightX * side,
                eyeY - DROP + lift,
                body.z + forwardZ * reach + rightZ * side);
    }

    /** Position interpolated the same way the entity renderer does it. */
    public static Vec3 interpolatedPosition(Player player, float partialTick) {
        return new Vec3(
                lerp(player.xOld, player.getX(), partialTick),
                lerp(player.yOld, player.getY(), partialTick),
                lerp(player.zOld, player.getZ(), partialTick));
    }

    private static double lerp(double from, double to, float t) {
        return from + (to - from) * t;
    }

    /** Angle interpolation that takes the short way around, avoiding a 360-degree spin. */
    private static float lerpAngle(float from, float to, float t) {
        float delta = to - from;
        while (delta < -180.0F) {
            delta += 360.0F;
        }
        while (delta >= 180.0F) {
            delta -= 360.0F;
        }
        return from + delta * t;
    }
}
