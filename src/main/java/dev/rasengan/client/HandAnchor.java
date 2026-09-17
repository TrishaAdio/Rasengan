package dev.rasengan.client;

import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Computes where the caster's palm is, in world space, for a given frame.
 *
 * <h2>Why this is not just "player position plus an offset"</h2>
 * The sphere has to stay glued to a moving, turning player without lagging or swimming. Three
 * things make that work:
 * <ul>
 *   <li>The player's position is interpolated between its previous and current tick using the
 *       frame's partial tick, matching exactly how the player model itself is drawn. Using the
 *       raw current position instead would make the sphere stutter relative to the body.</li>
 *   <li>The offset is built from the <em>body</em> yaw, not the head yaw, so the hand does not
 *       fly around the player when they flick the mouse. Pitch is applied separately and damped,
 *       which is what a real arm does.</li>
 *   <li>Yaw is interpolated the short way around the circle, so crossing the 180-degree boundary
 *       does not send the sphere on a full lap around the player.</li>
 * </ul>
 */
public final class HandAnchor {

    /** Forward reach from the chest, in blocks. */
    private static final double FORWARD = 0.52D;
    /** Sideways offset to the casting hand. */
    private static final double SIDE = 0.42D;
    /** Height below eye level where the hand sits. */
    private static final double DROP = 0.42D;

    private HandAnchor() {}

    /** Interpolated world position of the casting palm. */
    public static Vec3 palmPosition(Player player, ClientCast cast, float partialTick) {
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
        double side = (cast.mainHand == rightHanded) ? SIDE : -SIDE;

        double reach = FORWARD * Math.cos(pitchRad);
        double lift = -FORWARD * Math.sin(pitchRad);

        double eyeY = body.y + player.getEyeHeight();

        return new Vec3(
                body.x + forwardX * reach + rightX * side,
                eyeY - DROP + lift,
                body.z + forwardZ * reach + rightZ * side);
    }

    /**
     * Where the sphere actually renders this frame: the palm while held, then a smooth glide to
     * the impact point after release. The glide is eased so there is no visual teleport.
     */
    public static Vec3 spherePosition(Player player, ClientCast cast, long gameTime, float partialTick) {
        Vec3 palm = palmPosition(player, cast, partialTick);

        float progress = cast.progress(gameTime, partialTick);
        if (progress < 1.0F && !cast.isReleased()) {
            return palm;
        }

        // Remember where the strike started so the glide has a stable origin.
        cast.setReleaseOrigin(palm);
        Vec3 origin = cast.releaseOrigin() != null ? cast.releaseOrigin() : palm;

        Vec3 target = cast.hasImpact()
                ? cast.impactPos()
                : origin.add(cast.direction.scale(2.0D));

        float sinceRelease = cast.age(gameTime, partialTick) - cast.castDuration;
        float t = Math.clamp(sinceRelease / ClientCast.RELEASE_TRAVEL_TICKS, 0.0F, 1.0F);
        float eased = OrbitMath.easeOut(t);

        return origin.add(target.subtract(origin).scale(eased));
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
