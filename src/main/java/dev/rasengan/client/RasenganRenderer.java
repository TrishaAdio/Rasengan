package dev.rasengan.client;

import dev.rasengan.AbilityType;
import dev.rasengan.PalmAnchor;
import dev.rasengan.Palette;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rasengan.RasenganConfig;
import dev.rasengan.client.OrbitMath.Layer;
import dev.rasengan.client.SphereMesh.Tri;
import dev.rasengan.server.RasenganProjectile;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
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

    // Dedicated basis for the projectile trail. These must NOT reuse TMP_A/B/C, because
    // emitRibbonSegment clobbers those on every segment it draws.
    private static final Vector3f TRAIL_AXIS = new Vector3f();
    private static final Vector3f TRAIL_U = new Vector3f();
    private static final Vector3f TRAIL_V = new Vector3f();

    public static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
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

        // Projectiles are independent of ClientCastTracker: one can still be in flight after the
        // cast record has expired, so this must run even when there are no active casts.
        renderProjectiles(level, poseStack, collector, cameraPos, partialTick, maxDistance);

        // Impacts are world-space events with no owner, so they too must run independently of
        // whether any cast record is still alive.
        renderImpacts(poseStack, collector, cameraPos, gameTime, partialTick, maxDistance);

        // Summoning seals are likewise world-space and owner-independent: the circle stays where it
        // was drawn even if the summoner walks away mid-sequence.
        SummonCinematic.submit(poseStack, collector, cameraPos, gameTime, partialTick, maxDistance);

        if (ClientCastTracker.isEmpty()) {
            return;
        }

        for (ClientCast cast : ClientCastTracker.active()) {
            Entity entity = level.getEntity(cast.casterId);
            if (!(entity instanceof Player player)) {
                continue;
            }

            // The held sphere lives at the palm and nowhere else. There is no post-release glide
            // any more - that glide is exactly what used to leave a second sphere hanging in the
            // air while the real projectile flew off.
            Vec3 spherePos = PalmAnchor.palmPosition(player, cast.mainHand, partialTick);

            float distanceFactor = ClientTuning.distanceFactor(cameraPos, spherePos, maxDistance);
            if (distanceFactor <= 0.0F) {
                continue; // beyond the server's max effect distance
            }

            // sphereIntensity() returns 0 once released, so the held sphere cannot coexist with
            // the projectile for even a single frame.
            float intensity = cast.sphereIntensity(gameTime, partialTick);
            float radius = cast.radius(gameTime, partialTick, FULL_RADIUS);
            float quality = ClientTuning.meshQuality(cameraPos, spherePos);
            float time = cast.age(gameTime, partialTick);

            boolean drawSphere = intensity > 0.01F && radius > 0.005F;

            // The Rasen Shuriken is a different shape with different motion, so it gets its own
            // renderer. It still runs off this same ClientCast record, so the aura, the release
            // handoff and the teardown are all shared - only the geometry differs.
            boolean shuriken = cast.ability.isShuriken();

            // ---- Stage 4: caster body aura, as geometry ----
            // Submitted from the same loop, off the same ClientCast, in the same frame as the
            // hand sphere. They share one trigger - the server's CastStart - so they cannot
            // start at different times or appear independently of one another.
            float auraIntensity = cast.auraIntensity * cast.auraFade(gameTime, partialTick) * distanceFactor;
            if (auraIntensity > 0.01F) {
                Vec3 feet = PalmAnchor.interpolatedPosition(player, partialTick);
                Vec3 palm = spherePos;

                poseStack.pushPose();
                poseStack.translate(
                        feet.x - cameraPos.x,
                        feet.y - cameraPos.y,
                        feet.z - cameraPos.z);

                final float auraCamX = (float) (cameraPos.x - feet.x);
                final float auraCamY = (float) (cameraPos.y - feet.y);
                final float auraCamZ = (float) (cameraPos.z - feet.z);

                final float bodyHeight = player.getBbHeight();
                final float bodyWidth = player.getBbWidth() * 0.5F;
                final float handX = (float) (palm.x - feet.x);
                final float handY = (float) (palm.y - feet.y);
                final float handZ = (float) (palm.z - feet.z);
                final float auraTime = time;
                final float auraStrength = auraIntensity;
                final float auraQuality = quality;

                collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                    CAMERA_LOCAL.set(auraCamX, auraCamY, auraCamZ);
                    emitBodyAura(pose, buffer, bodyHeight, bodyWidth,
                            handX, handY, handZ,
                            auraTime, auraStrength, auraQuality, cast.seed);
                });

                poseStack.popPose();
            }

            if (!drawSphere) {
                continue;
            }

            if (shuriken) {
                // Disc plane faces where the caster is aiming, so the star reads as a held weapon.
                // getViewVector(partialTick) interpolates the caster's rotation, where getLookAngle()
                // reads only the current tick's value - which snapped the whole disc plane 20 times a
                // second while the player turned, tilting a rigid spinning object in visible steps.
                Vec3 spinAxis = player.getViewVector(partialTick);
                // Absolute tick age, so the blade snap lands on AbilityType.BLADE_SNAP_TICK
                // (tick 70 = 3.5s) exactly, matching the audio transition.
                float spinTime = Math.max(0.0F, time - AbilityType.BLADE_SNAP_TICK);
                ShurikenRenderer.submit(poseStack, collector, spherePos, cameraPos, spinAxis,
                        time, spinTime, intensity * distanceFactor, quality,
                        cast.seed, false);
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(
                    spherePos.x - cameraPos.x,
                    spherePos.y - cameraPos.y,
                    spherePos.z - cameraPos.z);

            // Camera position expressed in this sphere's local space, for rim shading and
            // camera-facing ribbon orientation. Captured as finals and applied INSIDE the lambda -
            // see the class javadoc on deferred submission.
            final float camX = (float) (cameraPos.x - spherePos.x);
            final float camY = (float) (cameraPos.y - spherePos.y);
            final float camZ = (float) (cameraPos.z - spherePos.z);

            final float fIntensity = intensity * distanceFactor;
            final float fRadius = radius;
            final float fTime = time;
            final float fQuality = quality;

            collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                CAMERA_LOCAL.set(camX, camY, camZ);
                emitShells(pose, buffer, fRadius, fTime, fIntensity, fQuality, cast.seed);
                emitOrbitalRings(pose, buffer, cast.layers, fRadius, fTime, fIntensity, fQuality);
                emitHelices(pose, buffer, fRadius, fTime, fIntensity, fQuality, cast.seed);
                emitStreaks(pose, buffer, fRadius, fTime, fIntensity, cast.seed);
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
    // Stage 4: caster body aura (geometry)
    // ------------------------------------------------------------------

    /**
     * The body aura, drawn as real geometry in local space centred on the caster's feet.
     *
     * <p>Kept deliberately thin and sparse: it silhouettes the player rather than wrapping them, so
     * skin, armour, held items and anything behind them all stay readable. Every element is a
     * continuous function of time, so the aura breathes rather than flickering.
     *
     * @param height body height in blocks
     * @param width  body half-width in blocks
     * @param handX/handY/handZ casting palm position relative to the feet
     */
    private static void emitBodyAura(PoseStack.Pose pose, VertexConsumer buffer,
                                     float height, float width,
                                     float handX, float handY, float handZ,
                                     float time, float intensity, float quality, long seed) {

        // ---- Rising flame-like streamers ----
        // Helical ribbons climbing the body from the feet, each on its own phase and speed, so
        // they read as energy licking upward rather than as static decoration.
        int streamers = quality >= 0.5F ? 7 : 4;
        int segments = Math.max(6, Math.round(14 * quality));

        for (int s = 0; s < streamers; s++) {
            float phase = s * (float) (Math.TAU / streamers) + (seed % 360L) * 0.0175F;
            float climbRate = 0.055F + 0.020F * ((s * 37) % 5) / 4.0F;
            float twist = (s % 2 == 0) ? 1.0F : -1.0F;

            // Each streamer's own vertical scroll, wrapped, so they rise continuously.
            float scroll = positiveFraction(time * climbRate + s * 0.37F);

            for (int i = 0; i < segments; i++) {
                float f0 = i / (float) segments;
                float f1 = (i + 1) / (float) segments;

                // Local height along the streamer, offset by the scroll and wrapped.
                float y0 = positiveFraction(f0 + scroll);
                float y1 = positiveFraction(f1 + scroll);
                if (y1 < y0) {
                    continue; // this segment wraps around the top; skip rather than stretch it
                }

                float a0 = phase + twist * y0 * 5.2F + time * 0.06F * twist;
                float a1 = phase + twist * y1 * 5.2F + time * 0.06F * twist;

                // Radius bulges at mid-torso and pinches at feet and head.
                float r0 = width * (0.95F + 0.35F * (float) Math.sin(y0 * Math.PI));
                float r1 = width * (0.95F + 0.35F * (float) Math.sin(y1 * Math.PI));

                P0.set((float) Math.cos(a0) * r0, y0 * height, (float) Math.sin(a0) * r0);
                P1.set((float) Math.cos(a1) * r1, y1 * height, (float) Math.sin(a1) * r1);

                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();

                // Fade in near the feet, out near the head: a wisp that forms and dissolves.
                float taper = (float) Math.sin(Math.PI * y0);
                float alpha = 0.38F * intensity * taper;

                emitRibbonSegment(pose, buffer, P0, P1, TANGENT,
                        0.022F * (0.6F + taper),
                        Palette.lerp(Palette.CYAN, Palette.DEEP_CYAN, y0),
                        alpha);
            }
        }

        // ---- Energy currents converging on the casting hand ----
        // These visually explain where the sphere's power is coming from.
        int currents = quality >= 0.5F ? 5 : 3;
        for (int c = 0; c < currents; c++) {
            float gate = OrbitMath.noise(c * 5.7F, c * 2.3F, c * 4.1F, time * 1.6F, (seed % 700L) * 0.001F);
            if (gate < 0.45F) {
                continue;
            }
            float strength = (gate - 0.45F) / 0.55F;

            float angle = c * (float) (Math.TAU / currents) + time * 0.05F;
            float baseHeight = 0.30F + 0.55F * positiveFraction(c * 0.41F + time * 0.03F);

            P0.set((float) Math.cos(angle) * width * 1.25F,
                    baseHeight * height,
                    (float) Math.sin(angle) * width * 1.25F);

            // Pull most of the way toward the hand, not all the way, so the sphere stays the
            // brightest thing in the frame.
            float reach = 0.45F + 0.35F * strength;
            P1.set(P0.x + (handX - P0.x) * reach,
                    P0.y + (handY - P0.y) * reach,
                    P0.z + (handZ - P0.z) * reach);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            emitRibbonSegment(pose, buffer, P0, P1, TANGENT,
                    0.014F * (0.5F + strength),
                    Palette.HIGHLIGHT,
                    0.30F * intensity * strength);
        }

        // ---- Occasional short outward sparks ----
        int sparks = quality >= 0.5F ? 6 : 3;
        for (int k = 0; k < sparks; k++) {
            float gate = OrbitMath.noise(k * 3.1F, k * 6.7F, k * 1.9F, time * 2.8F, (seed % 400L) * 0.002F);
            if (gate < 0.66F) {
                continue;
            }
            float strength = (gate - 0.66F) / 0.34F;

            float angle = k * 2.399F + time * 0.11F;
            float y = 0.15F + 0.80F * positiveFraction(k * 0.29F + time * 0.017F);

            float rInner = width * 1.0F;
            float rOuter = rInner + 0.16F + 0.18F * strength;

            P0.set((float) Math.cos(angle) * rInner, y * height, (float) Math.sin(angle) * rInner);
            P1.set((float) Math.cos(angle) * rOuter, y * height + 0.05F, (float) Math.sin(angle) * rOuter);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            emitRibbonSegment(pose, buffer, P0, P1, TANGENT,
                    0.012F, Palette.HIGHLIGHT, 0.45F * intensity * strength);
        }
    }

    /** Fractional part, always in 0..1. */
    private static float positiveFraction(float value) {
        float f = value - (float) Math.floor(value);
        return f < 0.0F ? f + 1.0F : f;
    }

    /**
     * Orbital layers for a seed, cached.
     *
     * <p>A projectile draws the same layer set every frame, and layer generation allocates. The map
     * is tiny and bounded by the number of distinct in-flight seeds, and it is cleared on world
     * change along with everything else.
     */
    private static final java.util.Map<Long, Layer[]> LAYER_CACHE = new java.util.HashMap<>();

    private static Layer[] layersFor(long seed) {
        Layer[] cached = LAYER_CACHE.get(seed);
        if (cached == null) {
            if (LAYER_CACHE.size() > 64) {
                LAYER_CACHE.clear(); // bounded; regenerating is cheap and rare
            }
            cached = OrbitMath.buildLayers(seed, 7);
            LAYER_CACHE.put(seed, cached);
        }
        return cached;
    }

    /** Drops cached layer sets. Called on world unload / disconnect. */
    public static void clearCaches() {
        LAYER_CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Thrown projectile
    // ------------------------------------------------------------------

    /**
     * Draws every Rasengan projectile currently in flight.
     *
     * <p>The projectile is a real server-tracked entity, so its position arrives through vanilla
     * entity syncing and every client near it sees the same thing. Rendering reuses the exact same
     * shell/ring/helix/streak code as the held sphere - it is the same visual, just anchored to a
     * flying entity instead of a hand - plus a twisting trail behind it.
     *
     * <p>Registered with a {@code NoopRenderer} on the entity type so vanilla draws nothing for it;
     * all of its appearance comes from here, on the render path already proven by the held sphere.
     */
    private static void renderProjectiles(ClientLevel level, PoseStack poseStack,
                                          SubmitNodeCollector collector, Vec3 cameraPos,
                                          float partialTick, double maxDistance) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof RasenganProjectile projectile)) {
                continue;
            }

            // Interpolated position, matching how vanilla draws moving entities.
            Vec3 pos = new Vec3(
                    Mth.lerp(partialTick, projectile.xOld, projectile.getX()),
                    Mth.lerp(partialTick, projectile.yOld, projectile.getY()),
                    Mth.lerp(partialTick, projectile.zOld, projectile.getZ()));

            float distanceFactor = ClientTuning.distanceFactor(cameraPos, pos, maxDistance);
            if (distanceFactor <= 0.0F) {
                continue;
            }

            float quality = ClientTuning.meshQuality(cameraPos, pos);
            long seed = projectile.visualSeed();

            if (projectile.ability().isShuriken()) {
                // Spin axis is the flight direction, so the star spins about its own travel axis.
                Vec3 velocity0 = projectile.getDeltaMovement();
                Vec3 spinAxis = velocity0.lengthSqr() > 1.0E-6D
                        ? velocity0.normalize()
                        : new Vec3(0.0D, 1.0D, 0.0D);
                // spinTicks(partialTick), never spinTicks(): the raw form is whole-tick only and
                // would quantise the whole assembly - spin, flex, vibration, wisps, mist, breathing
                // - to 20 Hz, jumping 48.7 degrees per tick instead of rotating smoothly.
                ShurikenRenderer.submit(poseStack, collector, pos, cameraPos, spinAxis,
                        Float.MAX_VALUE, projectile.spinTicks(partialTick), distanceFactor, quality,
                        seed, true);
                continue;
            }

            // Continue the held sphere's animation clock rather than restarting at zero, so the
            // rings and helices do not jump to a different rotation phase at the moment of release.
            // The held sphere's clock reads castDuration ticks at release, so the projectile picks
            // up from exactly there. No extra syncing needed: lifeTicks advances on both sides.
            // Ability-aware: this branch only ever runs for RASENGAN, but reading the no-arg
            // accessor hard-coded that assumption into the clock. Asking for the projectile's own
            // ability keeps the handoff correct if the two durations are ever configured apart.
            float time = RasenganConfig.castDurationTicks(projectile.ability())
                    + projectile.lifeTicks() + partialTick;

            // Full size from frame one. Any spawn-in ramp here would read as a pop, because the
            // held sphere hands over at exactly FULL_RADIUS.
            float intensity = distanceFactor;
            float radius = FULL_RADIUS;

            Vec3 velocity = projectile.getDeltaMovement();

            poseStack.pushPose();
            poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);

            final float camX = (float) (cameraPos.x - pos.x);
            final float camY = (float) (cameraPos.y - pos.y);
            final float camZ = (float) (cameraPos.z - pos.z);

            final float fRadius = radius;
            final float fTime = time;
            final float fIntensity = intensity;
            final float fQuality = quality;
            // Cached per seed. Building these every frame allocated 7 records and 21 vectors per
            // projectile per frame, which is pure garbage churn on the render thread.
            final Layer[] layers = layersFor(seed);
            final float vx = (float) -velocity.x;
            final float vy = (float) -velocity.y;
            final float vz = (float) -velocity.z;

            collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                CAMERA_LOCAL.set(camX, camY, camZ);
                emitShells(pose, buffer, fRadius, fTime, fIntensity, fQuality, seed);
                emitOrbitalRings(pose, buffer, layers, fRadius, fTime, fIntensity, fQuality);
                emitHelices(pose, buffer, fRadius, fTime, fIntensity, fQuality, seed);
                emitStreaks(pose, buffer, fRadius, fTime, fIntensity, seed);
                emitTrail(pose, buffer, vx, vy, vz, fRadius, fTime, fIntensity, seed);
            });

            poseStack.popPose();
        }
    }

    /**
     * Draws every impact blast currently playing, at its own world position.
     *
     * <p>Independent of casts and projectiles by design - the blast belongs to the point in space
     * where contact happened, not to the caster.
     */
    private static void renderImpacts(PoseStack poseStack, SubmitNodeCollector collector,
                                      Vec3 cameraPos, long gameTime, float partialTick,
                                      double maxDistance) {
        if (ClientImpactTracker.isEmpty()) {
            return;
        }

        for (ClientImpactTracker.Impact impact : ClientImpactTracker.active()) {
            float age = impact.age(gameTime, partialTick);
            if (age < 0.0F || age > ClientImpactTracker.IMPACT_TICKS) {
                continue;
            }

            float distanceFactor = ClientTuning.distanceFactor(cameraPos, impact.pos(), maxDistance);
            if (distanceFactor <= 0.0F) {
                continue;
            }

            Vec3 pos = impact.pos();

            if (impact.ability().isShuriken()) {
                // Derive the disc plane from the flight direction: caster eye -> impact point.
                // Reconstructing it avoids sending an axis vector in every impact packet.
                Vec3 axis = new Vec3(0.0D, 1.0D, 0.0D);
                Entity caster = Minecraft.getInstance().level == null ? null
                        : Minecraft.getInstance().level.getEntity(impact.casterId());
                if (caster != null) {
                    Vec3 fromCaster = pos.subtract(caster.getEyePosition());
                    if (fromCaster.lengthSqr() > 1.0E-4D) {
                        axis = fromCaster.normalize();
                    }
                }
                ShurikenRenderer.submitImpact(poseStack, collector, pos, cameraPos, axis,
                        age, impact.spinTicks(), distanceFactor,
                        ClientTuning.meshQuality(cameraPos, pos), impact.seed());
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);

            final float camX = (float) (cameraPos.x - pos.x);
            final float camY = (float) (cameraPos.y - pos.y);
            final float camZ = (float) (cameraPos.z - pos.z);

            final float fAge = age;
            final float fDistance = distanceFactor;
            final long seed = impact.seed();

            collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
                CAMERA_LOCAL.set(camX, camY, camZ);
                emitImpact(pose, buffer, fAge, 1.0F, fDistance, seed);
            });

            poseStack.popPose();
        }
    }

    /**
     * A short twisting trail streaming out behind the projectile.
     *
     * <p>Built in the projectile's local space along the reverse of its velocity, so it always
     * points back down the flight path. Two counter-rotating ribbons braid around that axis, which
     * is what gives the trail its twist instead of looking like a straight smear.
     */
    private static void emitTrail(PoseStack.Pose pose, VertexConsumer buffer,
                                  float backX, float backY, float backZ,
                                  float radius, float time, float intensity, long seed) {
        // Direction the trail extends in.
        TRAIL_AXIS.set(backX, backY, backZ);
        if (TRAIL_AXIS.lengthSquared() < 1.0E-8F) {
            return;
        }
        float speed = TRAIL_AXIS.length();
        TRAIL_AXIS.normalize();

        // Two perpendicular axes to braid around.
        OrbitMath.perpendicular(TRAIL_AXIS, TRAIL_U);
        TRAIL_V.set(TRAIL_AXIS).cross(TRAIL_U).normalize();

        // Trail length scales with speed but is capped so it never becomes a laser.
        float length = Math.min(3.2F, speed * 2.6F);
        final int segments = 14;

        for (int braid = 0; braid < 2; braid++) {
            float spin = braid == 0 ? 1.0F : -1.0F;
            float phase = braid * 3.1416F + (seed % 500L) * 0.002F;

            for (int i = 0; i < segments; i++) {
                float f0 = i / (float) segments;
                float f1 = (i + 1) / (float) segments;

                trailPoint(f0, length, radius, spin, phase, time, P0);
                trailPoint(f1, length, radius, spin, phase, time, P1);

                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();

                // Thin and fade toward the tail so it dissolves rather than ending abruptly.
                float fade = 1.0F - f0;
                float alpha = 0.55F * intensity * fade * fade;

                emitRibbonSegment(pose, buffer, P0, P1, TANGENT,
                        0.030F * radius / FULL_RADIUS * (0.35F + fade),
                        Palette.lerp(Palette.HIGHLIGHT, Palette.BLUE, f0),
                        alpha);
            }
        }
    }

    /** One point on a braided trail strand, {@code f} running 0 (head) to 1 (tail). */
    private static void trailPoint(float f, float length, float radius, float spin,
                                   float phase, float time, Vector3f out) {
        float along = f * length;
        // Braid radius widens slightly then tapers, so the trail flares just behind the sphere.
        float braidRadius = radius * (0.55F + 0.75F * (float) Math.sin(f * Math.PI)) * 0.9F;
        float angle = phase + spin * (f * 9.0F - time * 0.55F);

        float c = (float) Math.cos(angle) * braidRadius;
        float s = (float) Math.sin(angle) * braidRadius;

        out.set(
                TRAIL_AXIS.x * along + TRAIL_U.x * c + TRAIL_V.x * s,
                TRAIL_AXIS.y * along + TRAIL_U.y * c + TRAIL_V.y * s,
                TRAIL_AXIS.z * along + TRAIL_U.z * c + TRAIL_V.z * s);
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
        float t = Math.clamp(impactAge / ClientImpactTracker.IMPACT_TICKS, 0.0F, 1.0F);
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
