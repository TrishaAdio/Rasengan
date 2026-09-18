package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rasengan.Palette;
import java.util.List;
import org.joml.Vector3f;

/**
 * Shared additive-geometry primitives: camera-facing ribbons, icosphere shells and flat rings.
 *
 * <h2>Why camera-local is an explicit parameter</h2>
 * {@code submitCustomGeometry} is <em>deferred</em> - it stores {@code poseStack.last().copy()} plus
 * the callback, and {@code CustomFeatureRenderer} invokes them later, batched per {@code RenderType}.
 * Anything the callback reads from shared mutable state therefore sees whatever the <em>last</em>
 * submit wrote, not its own value. That silently mis-orients billboarded ribbons and mis-shades rims
 * as soon as two effects are on screen at once.
 *
 * <p>Every method here takes the camera position in local space as an argument instead of reading a
 * static, so the bug is structurally impossible rather than merely avoided by convention.
 *
 * <p>Scratch vectors are static because level rendering is single-threaded and each method finishes
 * with them before returning; they never persist across a submit boundary.
 */
public final class EnergyGeometry {

    private EnergyGeometry() {}

    private static final Vector3f TO_CAMERA = new Vector3f();
    private static final Vector3f WIDTH = new Vector3f();
    private static final Vector3f V0 = new Vector3f();
    private static final Vector3f V1 = new Vector3f();
    private static final Vector3f V2 = new Vector3f();
    private static final Vector3f V3 = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();
    private static final Vector3f RING_A = new Vector3f();
    private static final Vector3f RING_B = new Vector3f();
    private static final Vector3f RING_U = new Vector3f();
    private static final Vector3f RING_V = new Vector3f();

    /** One POSITION_COLOR vertex. */
    public static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f p,
                              int rgb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        buffer.addVertex(pose, p.x, p.y, p.z)
                .setColor(Palette.red(rgb), Palette.green(rgb), Palette.blue(rgb), a);
    }

    /**
     * A quad (two triangles) from {@code p0} to {@code p1}, widened perpendicular to both the
     * segment tangent and the view direction, so a thin ribbon stays visible edge-on.
     *
     * <p>Per-end widths allow a tapered ribbon in a single call, which is what lets a blade narrow
     * toward its tip without stacking many uniform segments.
     */
    public static void taperedRibbon(PoseStack.Pose pose, VertexConsumer buffer,
                                     Vector3f p0, Vector3f p1, Vector3f tangent,
                                     float halfWidth0, float halfWidth1,
                                     Vector3f cameraLocal,
                                     int rgb0, int rgb1, float alpha0, float alpha1) {
        if ((alpha0 <= 0.004F && alpha1 <= 0.004F) || (halfWidth0 <= 0.0F && halfWidth1 <= 0.0F)) {
            return;
        }
        TO_CAMERA.set(cameraLocal)
                .sub((p0.x + p1.x) * 0.5F, (p0.y + p1.y) * 0.5F, (p0.z + p1.z) * 0.5F);
        if (TO_CAMERA.lengthSquared() < 1.0E-8F) {
            return;
        }
        TO_CAMERA.normalize();

        WIDTH.set(tangent).cross(TO_CAMERA);
        if (WIDTH.lengthSquared() < 1.0E-8F) {
            OrbitMath.perpendicular(tangent, WIDTH);
        } else {
            WIDTH.normalize();
        }

        V0.set(WIDTH).mul(halfWidth0);
        V1.set(WIDTH).mul(halfWidth1);

        V2.set(p0).sub(V0);
        V3.set(p0).add(V0);
        vertex(pose, buffer, V2, rgb0, alpha0);
        vertex(pose, buffer, V3, rgb0, alpha0);
        V2.set(p1).add(V1);
        vertex(pose, buffer, V2, rgb1, alpha1);

        V2.set(p0).sub(V0);
        vertex(pose, buffer, V2, rgb0, alpha0);
        V2.set(p1).add(V1);
        vertex(pose, buffer, V2, rgb1, alpha1);
        V2.set(p1).sub(V1);
        vertex(pose, buffer, V2, rgb1, alpha1);
    }

    /** Uniform-width, uniform-colour convenience form. */
    public static void ribbon(PoseStack.Pose pose, VertexConsumer buffer,
                              Vector3f p0, Vector3f p1, Vector3f tangent,
                              float halfWidth, Vector3f cameraLocal, int rgb, float alpha) {
        taperedRibbon(pose, buffer, p0, p1, tangent, halfWidth, halfWidth, cameraLocal,
                rgb, rgb, alpha, alpha);
    }

    /**
     * An icosphere shell.
     *
     * @param rimWeighted brightens the silhouette via {@code 1 - |dot(normal, view)|}, which is what
     *                    makes a hollow shell read as a dense volume from any angle
     * @param spin        rotation about Y in radians
     */
    public static void shell(PoseStack.Pose pose, VertexConsumer buffer, List<SphereMesh.Tri> tris,
                             float radius, int innerRgb, int outerRgb, float alpha,
                             float spin, float noiseAmp, float noiseTime, float noiseOffset,
                             Vector3f cameraLocal, boolean rimWeighted) {
        if (alpha <= 0.003F || radius <= 0.0F) {
            return;
        }
        float cos = (float) Math.cos(spin);
        float sin = (float) Math.sin(spin);

        for (SphereMesh.Tri tri : tris) {
            shellVertex(pose, buffer, tri.a(), radius, innerRgb, outerRgb, alpha, cos, sin,
                    noiseAmp, noiseTime, noiseOffset, cameraLocal, rimWeighted);
            shellVertex(pose, buffer, tri.b(), radius, innerRgb, outerRgb, alpha, cos, sin,
                    noiseAmp, noiseTime, noiseOffset, cameraLocal, rimWeighted);
            shellVertex(pose, buffer, tri.c(), radius, innerRgb, outerRgb, alpha, cos, sin,
                    noiseAmp, noiseTime, noiseOffset, cameraLocal, rimWeighted);
        }
    }

    private static void shellVertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f unit,
                                    float radius, int innerRgb, int outerRgb, float alpha,
                                    float cos, float sin, float noiseAmp, float noiseTime,
                                    float noiseOffset, Vector3f cameraLocal, boolean rimWeighted) {
        float x = unit.x * cos - unit.z * sin;
        float z = unit.x * sin + unit.z * cos;
        float y = unit.y;

        float mix = 0.5F;
        float r = radius;
        if (noiseAmp > 0.0F) {
            float n = OrbitMath.noise(x * 3.0F, y * 3.0F, z * 3.0F, noiseTime, noiseOffset);
            r = radius * (1.0F + noiseAmp * (n - 0.5F) * 2.0F);
            mix = n;
        }

        V0.set(x * r, y * r, z * r);
        float vertexAlpha = alpha;

        if (rimWeighted) {
            TO_CAMERA.set(cameraLocal).sub(V0);
            if (TO_CAMERA.lengthSquared() > 1.0E-8F) {
                TO_CAMERA.normalize();
                NORMAL.set(x, y, z);
                float rim = 1.0F - Math.abs(NORMAL.dot(TO_CAMERA));
                vertexAlpha = alpha * (0.20F + 1.35F * rim * rim);
                mix = Math.clamp(mix * 0.5F + rim * 0.5F, 0.0F, 1.0F);
            }
        }
        vertex(pose, buffer, V0, Palette.lerp(innerRgb, outerRgb, mix), vertexAlpha);
    }

    /**
     * A flat ring lying in the plane whose normal is {@code axis}.
     *
     * <p>Used for the wind-slash shockwave: a true disc-plane ring aligned to the spin axis reads as
     * a slash sweeping outward, where a camera-facing or tilted ring would read as a bubble.
     */
    public static void flatRing(PoseStack.Pose pose, VertexConsumer buffer, Vector3f axis,
                                float radius, float halfWidth, int rgb, float alpha,
                                Vector3f cameraLocal, int segments) {
        if (alpha <= 0.004F || radius <= 0.0F) {
            return;
        }
        OrbitMath.perpendicular(axis, RING_U);
        RING_V.set(axis).cross(RING_U).normalize();

        for (int s = 0; s < segments; s++) {
            float a0 = (float) (s * Math.TAU / segments);
            float a1 = (float) ((s + 1) * Math.TAU / segments);
            ringPoint(a0, radius, RING_A);
            ringPoint(a1, radius, RING_B);

            NORMAL.set(RING_B).sub(RING_A);
            if (NORMAL.lengthSquared() < 1.0E-10F) {
                continue;
            }
            NORMAL.normalize();
            ribbon(pose, buffer, RING_A, RING_B, NORMAL, halfWidth, cameraLocal, rgb, alpha);
        }
    }

    private static void ringPoint(float angle, float radius, Vector3f out) {
        float c = (float) Math.cos(angle) * radius;
        float s = (float) Math.sin(angle) * radius;
        out.set(RING_U.x * c + RING_V.x * s,
                RING_U.y * c + RING_V.y * s,
                RING_U.z * c + RING_V.z * s);
    }
}
