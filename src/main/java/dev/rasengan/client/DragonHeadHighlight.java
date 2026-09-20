package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.rasengan.DragonAnchor;
import dev.rasengan.Palette;
import dev.rasengan.server.DragonEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Outlines the dragon's head when the summoner is looking at it.
 *
 * <h2>Why this had to exist</h2>
 * The mount region is a 1.36 x 1.88 x 1.81 block box on a creature 29.5 blocks across, and it is not a
 * vanilla hitbox - so it gets no crosshair highlight, no entity outline, nothing. The first build shipped
 * without any indicator at all, and the result was exactly what it deserved: a player clicking the head
 * they could plainly see, receiving no response, and no way to tell whether they were aiming wrongly, out
 * of range, or hitting a bug. Silence is not an acceptable answer to a click.
 *
 * <p>So the region draws itself. If the outline is not on screen, you are not aiming at it, and that is
 * now visible rather than inferred.
 *
 * <p>The box is drawn from the same {@link DragonAnchor} maths the server validates against, so it is a
 * picture of the actual region rather than a decorative approximation of it. It is only drawn for a player
 * who would actually be allowed to mount, so it doubles as the ownership indicator: if you are not the
 * summoner, it never appears.
 */
public final class DragonHeadHighlight {

    /** Scratch. Render thread only, never held across a submit boundary. */
    private static final Vector3f P0 = new Vector3f();
    private static final Vector3f P1 = new Vector3f();
    private static final Vector3f TANGENT = new Vector3f();
    private static final Vector3f CAMERA_LOCAL = new Vector3f();

    private DragonHeadHighlight() {}

    /**
     * Draws the outline for whichever head the local player is targeting.
     *
     * <p>Same deferred-submit discipline as every other effect here: camera-local is captured into finals
     * and written inside the callback, because submits are batched per render type and a callback reading
     * shared state would otherwise pick up whichever effect submitted last.
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos) {
        DragonEntity dragon = RasenganMountInput.targetedDragon();
        if (dragon == null) {
            return;
        }
        boolean airborne = !dragon.onGround();
        float age = (float) dragon.tickCount;
        Vec3 centre = DragonAnchor.headCentre(dragon.position(), dragon.getYRot(), dragon.getXRot(),
                age, airborne);
        DragonAnchor.Basis basis = DragonAnchor.basis(dragon.getYRot(), dragon.getXRot());
        AABB box = DragonAnchor.headBoxModel();

        // Half-extents in model space. The centre is already world-space, so the corners are built by
        // walking out along the dragon's own axes rather than along world axes - the box is oriented.
        double hx = box.getXsize() / 2.0D;
        double hy = box.getYsize() / 2.0D;
        double hz = box.getZsize() / 2.0D;

        poseStack.pushPose();
        poseStack.translate(centre.x - cameraPos.x, centre.y - cameraPos.y, centre.z - cameraPos.z);

        final float camX = (float) (cameraPos.x - centre.x);
        final float camY = (float) (cameraPos.y - centre.y);
        final float camZ = (float) (cameraPos.z - centre.z);
        final Vec3 right = basis.right().scale(hx);
        final Vec3 up = basis.up().scale(hy);
        final Vec3 forward = basis.forward().scale(hz);
        // Brighter when the player is actually able to act on it right now.
        final float alpha = RasenganMountInput.isMountable() ? 0.85F : 0.35F;
        final int rgb = RasenganMountInput.isMountable() ? Palette.HIGHLIGHT : Palette.DEEP_CYAN;

        collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
            CAMERA_LOCAL.set(camX, camY, camZ);
            emitBoxEdges(pose, buffer, right, up, forward, rgb, alpha);
        });
        poseStack.popPose();
    }

    /** The twelve edges of an oriented box, as thin camera-facing ribbons. */
    private static void emitBoxEdges(PoseStack.Pose pose,
                                     com.mojang.blaze3d.vertex.VertexConsumer buffer,
                                     Vec3 right, Vec3 up, Vec3 forward, int rgb, float alpha) {
        // Eight corners, indexed by sign bits so the edge list below is just a bit-difference table.
        Vec3[] corner = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            double sx = (i & 1) == 0 ? -1.0D : 1.0D;
            double sy = (i & 2) == 0 ? -1.0D : 1.0D;
            double sz = (i & 4) == 0 ? -1.0D : 1.0D;
            corner[i] = right.scale(sx).add(up.scale(sy)).add(forward.scale(sz));
        }
        // An edge joins two corners differing in exactly one bit.
        for (int a = 0; a < 8; a++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                int b = a | bit;
                if (b == a) {
                    continue; // that bit is already set; the pair is covered from the other end
                }
                edge(pose, buffer, corner[a], corner[b], rgb, alpha);
            }
        }
    }

    private static void edge(PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer,
                             Vec3 from, Vec3 to, int rgb, float alpha) {
        P0.set((float) from.x, (float) from.y, (float) from.z);
        P1.set((float) to.x, (float) to.y, (float) to.z);
        TANGENT.set(P1).sub(P0);
        if (TANGENT.lengthSquared() < 1.0E-10F) {
            return;
        }
        TANGENT.normalize();
        EnergyGeometry.ribbon(pose, buffer, P0, P1, TANGENT, 0.022F, CAMERA_LOCAL, rgb, alpha);
    }
}
