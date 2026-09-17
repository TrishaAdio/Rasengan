package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rasengan.RasenganConfig;
import dev.rasengan.client.OrbitMath.Layer;
import dev.rasengan.client.SphereMesh.Tri;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Vector3f;

/**
 * Draws the Rasengan as real 3D geometry.
 *
 * <h2>How it is rendered</h2>
 * Geometry is submitted through NeoForge's {@link SubmitCustomGeometryEvent}, which hands us the
 * level {@link PoseStack} and a {@link SubmitNodeCollector}. We submit with
 * {@link RenderTypes#dragonRays()}, whose pipeline is {@code POSITION_COLOR} triangles with
 * additive ("lightning") blending and depth-<em>testing</em> but no depth-<em>writing</em>. That
 * combination is what makes the effect read as light: overlapping layers accumulate brightness
 * instead of occluding one another, while still being correctly hidden behind solid blocks.
 *
 * <h2>Why it looks volumetric rather than flat</h2>
 * <ul>
 *   <li><b>Nested shells.</b> Three concentric procedurally generated icospheres at different
 *       radii, alphas and counter-rotations. Each vertex's alpha is modulated by a rim term -
 *       {@code 1 - |dot(normal, viewDirection)|} - so the silhouette glows brighter than the
 *       centre. That is what gives a hollow shell the appearance of a dense volume, and it
 *       recomputes per camera angle so it holds up from every direction.</li>
 *   <li><b>Real orbital rings.</b> Seven independent {@link Layer} ribbons on genuinely different
 *       planes - horizontal, both verticals, two diagonals, then free orientations - each with its
 *       own radius, speed, spin direction and phase. They are camera-facing ribbons, not lines,
 *       so they have visible width from any angle.</li>
 *   <li><b>Spherical helices.</b> Trails that wind pole-to-pole around the core, which no
 *       arrangement of flat circles can imitate.</li>
 *   <li><b>Surface displacement.</b> Shell vertices are pushed along their normals by a smooth
 *       sum-of-sines noise, so the surface churns instead of sitting still.</li>
 * </ul>
 *
 * <h2>Why it does not jitter</h2>
 * Every vertex is a pure function of {@code (seed, layer, parameter, time)} where time is a float
 * carrying the frame's partial tick. There is no frame-to-frame integration anywhere, so there is
 * no state to drift or snap. Between two ticks the geometry simply advances along a continuous
 * curve, which is why the sphere spins smoothly at any frame rate.
 */
public final class RasenganRenderer {

    /** Sphere radius at full formation, in blocks. Deliberately compact. */
    private static final float FULL_RADIUS = 0.30F;

    private static final int RING_SEGMENTS_HIGH = 40;
    private static final int HELIX_COUNT = 3;
    private static final int HELIX_SEGMENTS_HIGH = 44;

    private RasenganRenderer() {}

    // Scratch vectors. The submit callback runs on the render thread, one cast at a time.
    private static final Vector3f P0 = new Vector3f();
    private static final Vector3f P1 = new Vector3f();
    private static final Vector3f TANGENT = new Vector3f();
    private static final Vector3f WIDTH = new Vector3f();
    private static final Vector3f TO_CAMERA = new Vector3f();
    private static final Vector3f TMP_A = new Vector3f();
    private static final Vector3f TMP_B = new Vector3f();
    private static final Vector3f TMP_C = new Vector3f();
    private static final Vector3f TMP_D = new Vector3f();
    private static final Vector3f CAMERA_LOCAL = new Vector3f();

    public static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        if (ClientCastTracker.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }

        Vec3 cameraPos = event.getLevelRenderState().cameraRenderState.pos;
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long gameTime = level.getGameTime();
        double maxDistance = RasenganConfig.maxEffectDistance();

        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        for (ClientCast cast : ClientCastTracker.active()) {
            Entity entity = level.getEntity(cast.casterId);
            if (!(entity instanceof Player player)) {
                continue;
            }

            Vec3 spherePos = HandAnchor.spherePosition(player, cast, gameTime, partialTick);

            float distanceFactor = ClientTuning.distanceFactor(cameraPos, spherePos, maxDistance);
            if (distanceFactor <= 0.0F) {
                continue; // beyond the server's max effect distance
            }

            float intensity = cast.intensity(gameTime, partialTick);
            float radius = cast.radius(gameTime, partialTick, FULL_RADIUS);
            float impactAge = cast.impactAge(gameTime, partialTick);
            float quality = ClientTuning.meshQuality(cameraPos, spherePos);
            float time = cast.age(gameTime, partialTick);

            boolean drawSphere = intensity > 0.01F && radius > 0.005F;
            boolean drawImpact = impactAge >= 0.0F && impactAge <= ClientCast.IMPACT_TICKS;

            if (!drawSphere && !drawImpact) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(
                    spherePos.x - cameraPos.x,
                    spherePos.y - cameraPos.y,
                    spherePos.z - cameraPos.z);

            // Camera position expressed in this sphere's local space, for rim shading and
            // camera-facing ribbon orientation.
            CAMERA_LOCAL.set(
                    (float) (cameraPos.x - spherePos.x),
                    (float) (cameraPos.y - spherePos.y),
                    (float) (cameraPos.z - spherePos.z));

            final float fIntensity = intensity * distanceFactor;
            final float fRadius = radius;
            final float fTime = time;
            final float fQuality = quality;
            final float fImpactAge = impactAge;
            final boolean fDrawSphere = drawSphere;
            final boolean fDrawImpact = drawImpact;

            collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                if (fDrawSphere) {
                    emitShells(pose, buffer, fRadius, fTime, fIntensity, fQuality, cast.seed);
                    emitOrbitalRings(pose, buffer, cast.layers, fRadius, fTime, fIntensity, fQuality);
                    emitHelices(pose, buffer, fRadius, fTime, fIntensity, fQuality, cast.seed);
                    emitStreaks(pose, buffer, fRadius, fTime, fIntensity, cast.seed);
                }
                if (fDrawImpact) {
                    emitImpact(pose, buffer, fImpactAge, fIntensity <= 0.0F ? 1.0F : 1.0F,
                            distanceFactor, cast.seed);
                }
            });

            poseStack.popPose();
        }
    }

    // ------------------------------------------------------------------
    // Stage 2: layered energy shells
    // ------------------------------------------------------------------

    /**
     * Three nested icospheres: a dense white-blue core, a cyan mid shell and a translucent outer
     * shell. They counter-rotate and are displaced by smooth noise so the surface churns.
     */
    private static void emitShells(PoseStack.Pose pose, VertexConsumer buffer,
                                   float radius, float time, float intensity,
                                   float quality, long seed) {
        List<Tri> core = SphereMesh.forQuality(quality);
        List<Tri> shell = SphereMesh.LOW;

        // Core: bright, nearly solid, spins one way.
        emitShell(pose, buffer, core, radius * 0.44F, Palette.CORE, Palette.HIGHLIGHT,
                0.85F * intensity, time * 0.9F, 0.10F, seed, false);

        // Mid: cyan, rim-weighted, spins the other way.
        emitShell(pose, buffer, shell, radius * 0.76F, Palette.CYAN, Palette.HIGHLIGHT,
                0.34F * intensity, -time * 0.6F, 0.16F, seed + 977L, true);

        // Outer: faint deep cyan falling off to blue, the "compressed air" boundary.
        emitShell(pose, buffer, shell, radius * 1.0F, Palette.DEEP_CYAN, Palette.BLUE,
                0.18F * intensity, time * 0.35F, 0.22F, seed + 5501L, true);
    }

    /**
     * Emits one shell.
     *
     * @param rimWeighted when true, alpha is boosted at the silhouette, which is what makes a
     *                    hollow shell look like a solid glowing volume
     */
    private static void emitShell(PoseStack.Pose pose, VertexConsumer buffer, List<Tri> tris,
                                  float radius, int innerRgb, int outerRgb, float alpha,
                                  float spin, float noiseAmp, long seed, boolean rimWeighted) {
        if (alpha <= 0.003F) {
            return;
        }
        float cos = (float) Math.cos(spin * 0.1F);
        float sin = (float) Math.sin(spin * 0.1F);
        float noiseOffset = (seed & 0xFFFF) * 0.001F;

        for (Tri tri : tris) {
            emitShellVertex(pose, buffer, tri.a(), radius, innerRgb, outerRgb, alpha, cos, sin, noiseAmp, noiseOffset, spin, rimWeighted);
            emitShellVertex(pose, buffer, tri.b(), radius, innerRgb, outerRgb, alpha, cos, sin, noiseAmp, noiseOffset, spin, rimWeighted);
            emitShellVertex(pose, buffer, tri.c(), radius, innerRgb, outerRgb, alpha, cos, sin, noiseAmp, noiseOffset, spin, rimWeighted);
        }
    }

    private static void emitShellVertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f unit,
                                        float radius, int innerRgb, int outerRgb, float alpha,
                                        float cos, float sin, float noiseAmp, float noiseOffset,
                                        float time, boolean rimWeighted) {
        // Spin the unit sphere about Y.
        float x = unit.x * cos - unit.z * sin;
        float z = unit.x * sin + unit.z * cos;
        float y = unit.y;

        // Radial displacement: the surface breathes and churns.
        float n = OrbitMath.noise(x * 3.0F, y * 3.0F, z * 3.0F, time, noiseOffset);
        float r = radius * (1.0F + noiseAmp * (n - 0.5F) * 2.0F);

        TMP_A.set(x * r, y * r, z * r);

        float vertexAlpha = alpha;
        float mix = n;

        if (rimWeighted) {
            // Rim term: bright where the surface is edge-on to the camera.
            TO_CAMERA.set(CAMERA_LOCAL).sub(TMP_A);
            if (TO_CAMERA.lengthSquared() > 1.0E-8F) {
                TO_CAMERA.normalize();
                TMP_B.set(x, y, z); // unit normal
                float facing = Math.abs(TMP_B.dot(TO_CAMERA));
                float rim = 1.0F - facing;
                vertexAlpha = alpha * (0.20F + 1.35F * rim * rim);
                mix = Math.clamp(mix * 0.5F + rim * 0.5F, 0.0F, 1.0F);
            }
        }

        int rgb = Palette.lerp(innerRgb, outerRgb, mix);
        vertex(pose, buffer, TMP_A, rgb, vertexAlpha);
    }

    // ------------------------------------------------------------------
    // Stage 3: true 3D orbital movement
    // ------------------------------------------------------------------

    /** Camera-facing ribbon rings on distinct planes, each with its own motion. */
    private static void emitOrbitalRings(PoseStack.Pose pose, VertexConsumer buffer, Layer[] layers,
                                        float radius, float time, float intensity, float quality) {
        int segments = Math.max(10, Math.round(RING_SEGMENTS_HIGH * quality));
        int layerCount = quality >= 0.5F ? layers.length : Math.max(3, layers.length / 2);

        for (int i = 0; i < layerCount; i++) {
            Layer layer = layers[i];

            // Bands nearer the core are hotter.
            int innerRgb = layer.radius() < 0.8F ? Palette.HIGHLIGHT : Palette.CYAN;
            int outerRgb = layer.radius() < 0.8F ? Palette.CYAN : Palette.DEEP_CYAN;
            float baseAlpha = 0.55F * intensity;

            for (int s = 0; s < segments; s++) {
                float s0 = (float) s / segments;
                float s1 = (float) (s + 1) / segments;

                layer.point(s0, time, radius, P0);
                layer.point(s1, time, radius, P1);
                layer.tangent(s0, time, TANGENT);

                // A bright head that chases around the ring, so the band reads as flowing
                // rather than as a static hoop.
                float head = (float) ((time * layer.speed() * layer.direction() * 0.16F) % 1.0F + 1.0F) % 1.0F;
                float along = Math.abs(s0 - head);
                along = Math.min(along, 1.0F - along);
                float flow = 0.45F + 0.85F * (1.0F - Math.clamp(along * 3.2F, 0.0F, 1.0F));

                float halfWidth = layer.width() * radius * (0.7F + 0.6F * flow);
                emitRibbonSegment(pose, buffer, P0, P1, TANGENT, halfWidth,
                        Palette.lerp(innerRgb, outerRgb, 1.0F - flow), baseAlpha * flow);
            }
        }
    }

    /** Spherical helices wrapping the core, each spinning at a different rate. */
    private static void emitHelices(PoseStack.Pose pose, VertexConsumer buffer, float radius,
                                    float time, float intensity, float quality, long seed) {
        int segments = Math.max(12, Math.round(HELIX_SEGMENTS_HIGH * quality));
        int count = quality >= 0.5F ? HELIX_COUNT : 1;

        for (int h = 0; h < count; h++) {
            float phase = (float) ((seed >> (h * 7)) & 0x3FF) * 0.0061F;
            float direction = (h % 2 == 0) ? 1.0F : -1.0F;
            int windings = 3 + h;
            float radiusScale = 0.80F + 0.10F * h;

            for (int s = 0; s < segments; s++) {
                float s0 = (float) s / segments;
                float s1 = (float) (s + 1) / segments;

                OrbitMath.helixPoint(s0, time, radius, windings, phase, direction, radiusScale, P0);
                OrbitMath.helixPoint(s1, time, radius, windings, phase, direction, radiusScale, P1);

                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();

                // Taper to nothing at both poles so the trail dissolves instead of ending flat.
                float taper = (float) Math.sin(Math.PI * s0);
                float halfWidth = 0.030F * radius * (0.5F + taper);
                float alpha = 0.50F * intensity * taper;

                emitRibbonSegment(pose, buffer, P0, P1, TANGENT, halfWidth,
                        Palette.lerp(Palette.HIGHLIGHT, Palette.CYAN, s0), alpha);
            }
        }
    }

    /**
     * Intermittent electric streaks.
     *
     * <p>Each candidate streak is gated by a smooth noise function of time, so they flicker in and
     * out on their own schedule rather than all appearing on the same frame. The gate is
     * continuous, so a streak fades rather than popping.
     */
    private static void emitStreaks(PoseStack.Pose pose, VertexConsumer buffer, float radius,
                                    float time, float intensity, long seed) {
        final int candidates = 8;
        for (int i = 0; i < candidates; i++) {
            float offset = (seed % 1000L) * 0.001F + i * 1.37F;
            float gate = OrbitMath.noise(i * 4.1F, i * 2.7F, i * 3.3F, time * 2.4F, offset);
            if (gate < 0.62F) {
                continue;
            }
            float strength = (gate - 0.62F) / 0.38F;

            // Two points on the shell, joined by a bright thin ribbon.
            float a1 = i * 2.399F + time * 0.31F + offset * 6.0F;
            float a2 = i * 1.117F - time * 0.27F + offset * 3.0F;

            P0.set(
                    (float) (Math.cos(a1) * Math.sin(a2)),
                    (float) Math.cos(a2),
                    (float) (Math.sin(a1) * Math.sin(a2)));
            if (P0.lengthSquared() < 1.0E-8F) {
                continue;
            }
            P0.normalize().mul(radius * 0.85F);

            float b1 = a1 + 1.1F + 0.5F * strength;
            float b2 = a2 + 0.7F;
            P1.set(
                    (float) (Math.cos(b1) * Math.sin(b2)),
                    (float) Math.cos(b2),
                    (float) (Math.sin(b1) * Math.sin(b2)));
            if (P1.lengthSquared() < 1.0E-8F) {
                continue;
            }
            P1.normalize().mul(radius * 1.05F);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            emitRibbonSegment(pose, buffer, P0, P1, TANGENT,
                    0.018F * radius * (0.6F + strength),
                    Palette.HIGHLIGHT, 0.85F * intensity * strength);
        }
    }

    // ------------------------------------------------------------------
    // Stage 5: impact
    // ------------------------------------------------------------------

    /**
     * Expanding shockwave rings plus curved spiral fragments.
     *
     * <p>Brightness is capped and the flash is short so the impact never whites out the screen.
     */
    private static void emitImpact(PoseStack.Pose pose, VertexConsumer buffer, float impactAge,
                                   float intensity, float distanceFactor, long seed) {
        float t = Math.clamp(impactAge / ClientCast.IMPACT_TICKS, 0.0F, 1.0F);
        float fade = 1.0F - t;

        // ---- Spherical shockwave shell ----
        float waveRadius = 0.25F + OrbitMath.easeOut(t) * 2.4F;
        float waveAlpha = 0.30F * fade * fade * distanceFactor;
        if (waveAlpha > 0.004F) {
            emitShell(pose, buffer, SphereMesh.LOW, waveRadius,
                    Palette.CYAN, Palette.BLUE, waveAlpha, impactAge * 0.4F, 0.06F, seed, true);
        }

        // ---- Flat expanding rings, three staggered so the burst has structure ----
        for (int ring = 0; ring < 3; ring++) {
            float delay = ring * 0.12F;
            float rt = (t - delay) / (1.0F - delay);
            if (rt <= 0.0F || rt >= 1.0F) {
                continue;
            }
            float r = 0.2F + OrbitMath.easeOut(rt) * (1.9F + ring * 0.5F);
            float alpha = 0.55F * (1.0F - rt) * (1.0F - rt) * distanceFactor;
            float tilt = ring * 0.8F + (seed & 0xFF) * 0.004F;
            emitRing(pose, buffer, r, 0.045F + 0.02F * ring, tilt,
                    Palette.lerp(Palette.HIGHLIGHT, Palette.CYAN, rt), alpha);
        }

        // ---- Curved spiral fragments dispersing outward ----
        final int fragments = 10;
        for (int f = 0; f < fragments; f++) {
            float phase = f * 0.6283F + (seed % 500L) * 0.002F;
            float speed = 1.4F + (f % 4) * 0.35F;
            float spin = 2.2F + (f % 3) * 0.9F;

            float rA = OrbitMath.easeOut(t) * speed;
            float rB = OrbitMath.easeOut(Math.max(0.0F, t - 0.06F)) * speed;

            float latitude = 0.5F + 0.9F * (float) Math.sin(phase * 2.1F);
            float angleA = phase + t * spin;
            float angleB = phase + Math.max(0.0F, t - 0.06F) * spin;

            P0.set((float) (Math.cos(angleA) * rA),
                    latitude * rA * 0.55F,
                    (float) (Math.sin(angleA) * rA));
            P1.set((float) (Math.cos(angleB) * rB),
                    latitude * rB * 0.55F,
                    (float) (Math.sin(angleB) * rB));

            TANGENT.set(P0).sub(P1);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            float alpha = 0.70F * fade * fade * distanceFactor;
            emitRibbonSegment(pose, buffer, P1, P0, TANGENT, 0.030F * (0.4F + fade),
                    Palette.lerp(Palette.CORE, Palette.DEEP_CYAN, t), alpha);
        }
    }

    /** A single camera-facing ring of given radius, tilted about X by {@code tilt} radians. */
    private static void emitRing(PoseStack.Pose pose, VertexConsumer buffer, float radius,
                                 float halfWidth, float tilt, int rgb, float alpha) {
        if (alpha <= 0.004F) {
            return;
        }
        final int segments = 28;
        float cosT = (float) Math.cos(tilt);
        float sinT = (float) Math.sin(tilt);

        for (int s = 0; s < segments; s++) {
            float a0 = (float) (s * Math.TAU / segments);
            float a1 = (float) ((s + 1) * Math.TAU / segments);

            ringPoint(a0, radius, cosT, sinT, P0);
            ringPoint(a1, radius, cosT, sinT, P1);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();
            emitRibbonSegment(pose, buffer, P0, P1, TANGENT, halfWidth, rgb, alpha);
        }
    }

    private static void ringPoint(float angle, float radius, float cosT, float sinT, Vector3f out) {
        float x = (float) Math.cos(angle) * radius;
        float z = (float) Math.sin(angle) * radius;
        // Tilt about the X axis.
        out.set(x, -z * sinT, z * cosT);
    }

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------

    /**
     * Emits one quad (as two triangles) between {@code p0} and {@code p1}, widened perpendicular
     * to both the segment tangent and the view direction.
     *
     * <p>Orienting the width toward the camera is what keeps thin ribbons visible from every
     * angle. Without it a ring viewed edge-on would vanish.
     */
    private static void emitRibbonSegment(PoseStack.Pose pose, VertexConsumer buffer,
                                          Vector3f p0, Vector3f p1, Vector3f tangent,
                                          float halfWidth, int rgb, float alpha) {
        if (alpha <= 0.004F || halfWidth <= 0.0F) {
            return;
        }
        // Midpoint-to-camera is accurate enough for a short segment and halves the maths.
        TO_CAMERA.set(CAMERA_LOCAL)
                .sub((p0.x + p1.x) * 0.5F, (p0.y + p1.y) * 0.5F, (p0.z + p1.z) * 0.5F);
        if (TO_CAMERA.lengthSquared() < 1.0E-8F) {
            return;
        }
        TO_CAMERA.normalize();

        WIDTH.set(tangent).cross(TO_CAMERA);
        if (WIDTH.lengthSquared() < 1.0E-8F) {
            // Segment points straight at the camera; any perpendicular will do.
            OrbitMath.perpendicular(tangent, WIDTH);
        } else {
            WIDTH.normalize();
        }
        WIDTH.mul(halfWidth);

        TMP_A.set(p0).sub(WIDTH);
        TMP_B.set(p0).add(WIDTH);
        TMP_C.set(p1).add(WIDTH);
        TMP_D.set(p1).sub(WIDTH);

        vertex(pose, buffer, TMP_A, rgb, alpha);
        vertex(pose, buffer, TMP_B, rgb, alpha);
        vertex(pose, buffer, TMP_C, rgb, alpha);

        vertex(pose, buffer, TMP_A, rgb, alpha);
        vertex(pose, buffer, TMP_C, rgb, alpha);
        vertex(pose, buffer, TMP_D, rgb, alpha);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f p,
                               int rgb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        buffer.addVertex(pose, p.x, p.y, p.z)
                .setColor(Palette.red(rgb), Palette.green(rgb), Palette.blue(rgb), a);
    }
}
