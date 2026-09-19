package dev.rasengan.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.rasengan.AbilityType;
import dev.rasengan.Palette;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Draws the Rasen Shuriken: a dense core inside a breathing shell, with four tapered blades
 * projecting outward at 90 degrees, spinning as one rigid unit.
 *
 * <h2>How this reads as a different technique, not a recoloured Rasengan</h2>
 * The two effects are deliberately opposite in both silhouette and motion language:
 *
 * <table>
 *   <tr><th></th><th>Rasengan</th><th>Rasen Shuriken</th></tr>
 *   <tr><td>Outline</td><td>round, ~0.30 radius</td><td>four-pointed star, ~0.82 radius</td></tr>
 *   <tr><td>Motion</td><td>7 independent orbit rings on different planes, each with its own speed
 *       and direction - reads as a swirl</td><td>one rigid assembly on a single axis - reads as a
 *       weapon</td></tr>
 *   <tr><td>Rate</td><td>constant from frame one</td><td>accelerates through a wind-up, then
 *       holds</td></tr>
 *   <tr><td>Detail</td><td>smooth churning surface</td><td>rigid body plus chaotic vibrating
 *       edges</td></tr>
 * </table>
 *
 * The blades are more than three times the sphere's radius, so the star outline dominates the
 * silhouette at any distance - which is the stated priority over surface detail.
 *
 * <h2>Rigid spin with a real wind-up</h2>
 * Angular velocity ramps in over {@link #SPINUP_TICKS} and then holds. Crucially the renderer uses
 * the <em>analytic integral</em> of that ramp ({@link #spinAngle}) rather than multiplying the
 * current rate by elapsed time. Multiplying would make the blades visibly jump backwards the moment
 * the rate stops changing, because the accumulated angle would be recomputed against a different
 * rate. Integrating keeps position continuous through the acceleration, so the wind-up is smooth and
 * frame-rate independent.
 *
 * <h2>Determinism</h2>
 * Every element is a pure function of {@code (seed, time)} with time carrying the partial tick, so
 * all clients draw the same shuriken from the server's seed with no per-particle networking, and
 * nothing jitters between frames.
 */
public final class ShurikenRenderer {

    // ---- Silhouette proportions, in blocks ----
    /** Dense near-white anchor at the centre. Barely moves. */
    private static final float CORE_RADIUS = 0.105F;
    /** Translucent cyan shell wrapping the core. */
    private static final float SHELL_RADIUS = 0.20F;
    /** Blade length beyond the shell. Long, so the star outline dominates. */
    private static final float BLADE_LENGTH = 0.62F;
    /** Blade half-width at the root, tapering to nothing at the tip. */
    private static final float BLADE_ROOT_HALF_WIDTH = 0.058F;
    /** Ribbons stacked per blade to give it thickness rather than a flat cutout. */
    private static final int RIBBONS_PER_BLADE = 3;
    private static final float BLADE_THICKNESS = 0.030F;

    public static final int BLADE_COUNT = 4;

    /** Full held rotation rate, radians per tick. ~2.7 revolutions/second. */
    private static final float MAX_SPIN = 0.85F;
    /** Ticks the wind-up takes to reach full rate. */
    public static final float SPINUP_TICKS = 11.0F;

    /** Tip vibration amplitude in radians. Small enough never to break the silhouette. */
    private static final float VIBRATION_AMPLITUDE = 0.055F;

    // ---- Formation beats, in ABSOLUTE ticks from cast start ----
    /**
     * Blades snap out here. Aliased from {@link AbilityType#BLADE_SNAP_TICK}, which is the single
     * common definition shared with the server's in-flight spin clock and the screech pitch ramp.
     */
    public static final float BLADE_SNAP_TICK = AbilityType.BLADE_SNAP_TICK;
    private static final float BEAT_CORE_END_TICK = 14.0F;
    private static final float BEAT_SHELL_END_TICK = BLADE_SNAP_TICK;
    private static final float BEAT_BLADES_END_TICK = BLADE_SNAP_TICK + 8.0F;

    private ShurikenRenderer() {}

    // Scratch. Single-threaded render path; never held across a submit boundary.
    private static final Vector3f AXIS = new Vector3f();
    private static final Vector3f BASIS_U = new Vector3f();
    private static final Vector3f BASIS_V = new Vector3f();
    private static final Vector3f P0 = new Vector3f();
    private static final Vector3f P1 = new Vector3f();
    private static final Vector3f TANGENT = new Vector3f();
    private static final Vector3f CAM = new Vector3f();
    private static final Vector3f TIP_NOW = new Vector3f();
    private static final Vector3f TIP_PREV = new Vector3f();

    /**
     * Accumulated spin angle at time {@code t}, in radians.
     *
     * <p>The analytic integral of a smoothstep-ramped angular velocity. For {@code t < ramp} the
     * ramp contributes {@code ramp * (u^3 - u^4/2)}; past the ramp the accumulated wind-up is
     * exactly {@code ramp/2} plus constant-rate rotation.
     */
    public static float spinAngle(float t, float ramp) {
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t < ramp) {
            float u = t / ramp;
            return MAX_SPIN * ramp * (u * u * u - 0.5F * u * u * u * u);
        }
        return MAX_SPIN * (ramp * 0.5F + (t - ramp));
    }

    /** Current angular velocity, for driving the charge sound's pitch ramp. */
    public static float spinRate(float t, float ramp) {
        return MAX_SPIN * OrbitMath.smoothstep(0.0F, ramp, t);
    }

    /**
     * Submits one shuriken.
     *
     * @param worldPos  centre in world space
     * @param spinAxis  unit vector the assembly spins about; also the disc's normal, so the star
     *                  faces along the aim or flight direction
     * @param ageTicks  ticks since cast start, including the partial tick
     * @param spinTime  ticks since the blades began extending, driving the wind-up
     * @param quality   0..1 level of detail; only mist and wisps are reduced, never the silhouette
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector,
                             Vec3 worldPos, Vec3 cameraPos, Vec3 spinAxis,
                             float ageTicks, float spinTime, float intensity,
                             float quality, long seed, boolean inFlight) {
        if (intensity <= 0.01F) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(
                worldPos.x - cameraPos.x,
                worldPos.y - cameraPos.y,
                worldPos.z - cameraPos.z);

        // Captured as finals and applied inside the lambda: submits are deferred and batched, so
        // reading shared state from the callback would pick up another effect's values.
        final float camX = (float) (cameraPos.x - worldPos.x);
        final float camY = (float) (cameraPos.y - worldPos.y);
        final float camZ = (float) (cameraPos.z - worldPos.z);

        final float ax = (float) spinAxis.x;
        final float ay = (float) spinAxis.y;
        final float az = (float) spinAxis.z;

        final float fAge = ageTicks;
        final float fSpin = spinTime;
        final float fIntensity = intensity;
        final float fQuality = quality;
        final boolean fFlight = inFlight;

        collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
            CAM.set(camX, camY, camZ);
            setBasis(ax, ay, az);
            emitAssembly(pose, buffer, fAge, fSpin, fIntensity, fQuality, seed, fFlight);
        });

        poseStack.popPose();
    }

    /** Builds an orthonormal basis with {@code AXIS} as the disc normal. */
    private static void setBasis(float ax, float ay, float az) {
        AXIS.set(ax, ay, az);
        if (AXIS.lengthSquared() < 1.0E-8F) {
            AXIS.set(0.0F, 1.0F, 0.0F);
        }
        AXIS.normalize();
        OrbitMath.perpendicular(AXIS, BASIS_U);
        BASIS_V.set(AXIS).cross(BASIS_U).normalize();
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    private static void emitAssembly(PoseStack.Pose pose, VertexConsumer buffer,
                                     float ageTicks, float spinTime, float intensity,
                                     float quality, long seed, boolean inFlight) {

        // In flight the assembly is always complete, so the formation beats are skipped entirely
        // rather than being re-evaluated against a projectile's own age.
        float coreScale;
        float shellScale;
        float bladeScale;

        if (inFlight) {
            coreScale = 1.0F;
            shellScale = 1.0F;
            bladeScale = 1.0F;
        } else {
            // ---- Beat 1: core forms ----
            coreScale = OrbitMath.easeOut(OrbitMath.smoothstep(0.0F, BEAT_CORE_END_TICK, ageTicks));
            // ---- Beat 2: shell expands, ease-out, finishing exactly as the blades snap ----
            shellScale = OrbitMath.easeOut(
                    OrbitMath.smoothstep(BEAT_CORE_END_TICK * 0.8F, BEAT_SHELL_END_TICK, ageTicks));
            // ---- Beat 3: blades snap out - double ease-out over only 8 ticks ----
            float bladeRaw = OrbitMath.smoothstep(BEAT_SHELL_END_TICK, BEAT_BLADES_END_TICK, ageTicks);
            bladeScale = OrbitMath.easeOut(OrbitMath.easeOut(bladeRaw));
        }

        float angle = spinAngle(spinTime, SPINUP_TICKS);

        // ---- Inner core: dense, near-white, barely moving ----
        if (coreScale > 0.01F) {
            EnergyGeometry.shell(pose, buffer, SphereMesh.forQuality(quality),
                    CORE_RADIUS * coreScale,
                    Palette.CORE, Palette.HIGHLIGHT,
                    0.95F * intensity,
                    spinTime * 0.02F,      // almost static: it is the anchor
                    0.05F, spinTime, (seed & 0xFFFF) * 0.001F,
                    CAM, false);
        }

        // ---- Core shell: translucent cyan with a slow breathing brightness ----
        if (shellScale > 0.01F) {
            float breathe = 0.72F + 0.28F * (float) Math.sin(spinTime * 0.16F);
            EnergyGeometry.shell(pose, buffer, SphereMesh.LOW,
                    SHELL_RADIUS * shellScale,
                    Palette.CYAN, Palette.HIGHLIGHT,
                    0.30F * intensity * breathe,
                    -spinTime * 0.05F,
                    0.10F, spinTime, (seed + 977L & 0xFFFF) * 0.001F,
                    CAM, true);
        }

        if (bladeScale <= 0.01F) {
            return;
        }

        // ---- Four blades, rigid and evenly spaced at all times ----
        int segments = Math.max(4, Math.round(9 * Math.max(0.45F, quality)));
        for (int blade = 0; blade < BLADE_COUNT; blade++) {
            float baseAngle = angle + blade * (float) (Math.TAU / BLADE_COUNT);
            emitBlade(pose, buffer, blade, baseAngle, bladeScale, spinTime,
                    intensity, seed, segments);
        }

        // ---- Trailing wisps torn off the blade tips ----
        // LOD: first thing to go, and never the silhouette.
        if (quality > 0.25F) {
            int wispSteps = Math.max(3, Math.round(7 * quality));
            for (int blade = 0; blade < BLADE_COUNT; blade++) {
                emitTipWisp(pose, buffer, blade, angle, bladeScale, spinTime,
                        intensity, seed, wispSteps, inFlight);
            }
        }

        // ---- Wind-cutting mist, denser toward the blade edges ----
        if (quality > 0.35F) {
            emitMist(pose, buffer, angle, bladeScale, spinTime, intensity, quality, seed);
        }
    }

    /**
     * One blade: {@link #RIBBONS_PER_BLADE} stacked, twisted, tapered ribbons.
     *
     * <p>Three things stop it looking like a flat 2D cutout: the ribbons are offset along the spin
     * axis so the blade has thickness; that offset twists along the blade's length; and the offsets
     * converge toward the tip so the cross-section closes to an edge.
     */
    private static void emitBlade(PoseStack.Pose pose, VertexConsumer buffer, int bladeIndex,
                                  float baseAngle, float bladeScale, float spinTime,
                                  float intensity, long seed, int segments) {

        // Each blade flexes on its own phase, so the assembly feels alive rather than a rigid cross.
        float flexPhase = bladeFlexPhase(bladeIndex, seed);

        float length = BLADE_LENGTH * bladeScale;

        for (int ribbon = 0; ribbon < RIBBONS_PER_BLADE; ribbon++) {
            float lateral = (ribbon - (RIBBONS_PER_BLADE - 1) * 0.5F);
            float twistPhase = ribbon * 2.094F + flexPhase;

            for (int s = 0; s < segments; s++) {
                float f0 = s / (float) segments;
                float f1 = (s + 1) / (float) segments;

                bladePoint(f0, baseAngle, length, lateral, twistPhase,
                           spinTime, bladeIndex, seed, P0);
                bladePoint(f1, baseAngle, length, lateral, twistPhase,
                           spinTime, bladeIndex, seed, P1);

                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();

                // Concave taper: wide root narrowing to a point, like a real edge.
                float w0 = BLADE_ROOT_HALF_WIDTH * (float) Math.pow(1.0F - f0, 1.45D);
                float w1 = BLADE_ROOT_HALF_WIDTH * (float) Math.pow(1.0F - f1, 1.45D);

                // Hot near the core, cooling toward the tip; the outermost ribbon is dimmer so the
                // blade has internal shading rather than reading as one flat plate.
                float ribbonDim = 1.0F - 0.25F * Math.abs(lateral);
                int rgb0 = Palette.lerp(Palette.HIGHLIGHT, Palette.CYAN, f0);
                int rgb1 = Palette.lerp(Palette.HIGHLIGHT, Palette.DEEP_CYAN, f1);
                float a0 = 0.80F * intensity * ribbonDim;
                float a1 = 0.80F * intensity * ribbonDim * (1.0F - 0.35F * f1);

                EnergyGeometry.taperedRibbon(pose, buffer, P0, P1, TANGENT,
                        w0, w1, CAM, rgb0, rgb1, a0, a1);
            }
        }
    }

    /** Per-blade phase, so no two blades flex or twist in step. */
    private static float bladeFlexPhase(int bladeIndex, long seed) {
        return bladeIndex * 1.937F + (seed % 500L) * 0.002F;
    }

    /** Whole-blade flex, applied proportionally along the blade's length. */
    private static float bladeFlex(float spinTime, int bladeIndex, long seed) {
        return 0.030F * (float) Math.sin(spinTime * 0.42F + bladeFlexPhase(bladeIndex, seed));
    }

    /** Micro-vibration: scaled by {@code f*f} so the root is rock solid and only the tip trembles. */
    private static float bladeVibration(float spinTime, float f, int bladeIndex, long seed) {
        return (OrbitMath.noise(bladeIndex * 7.3F, f * 11.0F, bladeIndex * 3.1F,
                spinTime * 3.4F, (seed % 900L) * 0.001F) - 0.5F)
                * VIBRATION_AMPLITUDE * f * f;
    }

    /**
     * Total angular offset from a blade's rigid base angle to the point at fraction {@code f}:
     * the swept trailing edge, the whole-blade flex, and the tip vibration.
     *
     * <h2>Why this is factored out</h2>
     * The blade geometry and the trailing wisps must agree on where the tip actually is. They did
     * not: {@code tipAt} used only the rigid base angle plus the {@code -0.30} sweep, omitting the
     * flex and the vibration that {@code bladePoint} applies. The wisp therefore traced the arc of
     * an idealised rigid tip while the drawn tip trembled away from it - by up to 0.085 rad, which
     * at the 0.79 block tip radius is a 0.067 block gap, about 2.6x the wisp ribbon's own half-width.
     * Sharing one function makes the wisp trace the real swept path, which is what the effect is
     * documented to do, and makes the two impossible to drift apart again.
     *
     * @param f 0 at the shell surface, 1 at the tip
     */
    private static float bladeAngularOffset(float spinTime, float f, int bladeIndex, long seed) {
        // Swept trailing edge, so each point rakes backwards like a shuriken rather than sticking
        // out as a straight spoke.
        float sweep = -0.30F * f * f;
        return sweep + bladeFlex(spinTime, bladeIndex, seed) * f
                + bladeVibration(spinTime, f, bladeIndex, seed);
    }

    /**
     * A point along a blade.
     *
     * @param f       0 at the shell surface, 1 at the tip
     * @param lateral which stacked ribbon, centred on 0
     */
    private static void bladePoint(float f, float baseAngle, float length,
                                   float lateral, float twistPhase, float spinTime,
                                   int bladeIndex, long seed, Vector3f out) {
        float radius = SHELL_RADIUS * 0.85F + f * length;

        float angle = baseAngle + bladeAngularOffset(spinTime, f, bladeIndex, seed);

        // Thickness offset along the spin axis, twisting along the blade and converging at the tip.
        float twist = twistPhase + f * 2.6F;
        float thickness = lateral * BLADE_THICKNESS * (1.0F - 0.75F * f) * (float) Math.cos(twist);
        // A little in-plane splay too, so the ribbons are not coplanar.
        float splay = lateral * BLADE_THICKNESS * 0.5F * (1.0F - f) * (float) Math.sin(twist);

        float c = (float) Math.cos(angle) * radius;
        float s = (float) Math.sin(angle) * radius;
        // Perpendicular in-plane direction for the splay.
        float pc = (float) -Math.sin(angle) * splay;
        float ps = (float) Math.cos(angle) * splay;

        out.set(
                BASIS_U.x * (c + pc) + BASIS_V.x * (s + ps) + AXIS.x * thickness,
                BASIS_U.y * (c + pc) + BASIS_V.y * (s + ps) + AXIS.y * thickness,
                BASIS_U.z * (c + pc) + BASIS_V.z * (s + ps) + AXIS.z * thickness);
    }

    /**
     * A wisp torn off a blade tip.
     *
     * <p>Built from the tip's own <em>motion history</em>: because {@link #spinAngle} is analytic,
     * the exact tip position at {@code t - delta} is computable, so the streak traces the real arc
     * the tip swept. That is what makes it curve and decay like something torn loose, rather than a
     * straight line emitted outward. Sampling back in time also means it automatically lengthens as
     * the spin accelerates.
     */
    private static void emitTipWisp(PoseStack.Pose pose, VertexConsumer buffer, int bladeIndex,
                                    float currentAngle, float bladeScale, float spinTime,
                                    float intensity, long seed, int steps, boolean inFlight) {
        float length = BLADE_LENGTH * bladeScale;
        // Longer, more prominent trail while thrown.
        float span = inFlight ? 3.2F : 1.7F;

        for (int i = 0; i < steps; i++) {
            float t0 = spinTime - span * (i / (float) steps);
            float t1 = spinTime - span * ((i + 1) / (float) steps);
            if (t1 <= 0.0F) {
                break;
            }

            tipAt(t0, bladeIndex, length, seed, TIP_NOW);
            tipAt(t1, bladeIndex, length, seed, TIP_PREV);

            // Drift outward and decay as it ages, so it peels away instead of hugging the circle.
            float age0 = (spinTime - t0) / span;
            float age1 = (spinTime - t1) / span;
            TIP_NOW.mul(1.0F + 0.16F * OrbitMath.easeOut(age0));
            TIP_PREV.mul(1.0F + 0.16F * OrbitMath.easeOut(age1));

            TANGENT.set(TIP_PREV).sub(TIP_NOW);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            float fade0 = (1.0F - age0) * (1.0F - age0);
            float fade1 = (1.0F - age1) * (1.0F - age1);

            EnergyGeometry.taperedRibbon(pose, buffer, TIP_NOW, TIP_PREV, TANGENT,
                    0.026F * fade0, 0.026F * fade1, CAM,
                    Palette.HIGHLIGHT, Palette.BLUE,
                    0.55F * intensity * fade0, 0.55F * intensity * fade1);
        }
    }

    /**
     * Blade tip position at an arbitrary past time, used to trace wisps.
     *
     * <p>Uses the same {@link #bladeAngularOffset} at {@code f = 1} that the blade geometry itself
     * uses, evaluated at the historical time {@code t}, so the traced streak lands exactly on where
     * the tip really was - sweep, flex and vibration included.
     */
    private static void tipAt(float t, int bladeIndex, float length, long seed, Vector3f out) {
        float angle = spinAngle(t, SPINUP_TICKS)
                + bladeIndex * (float) (Math.TAU / BLADE_COUNT)
                + bladeAngularOffset(t, 1.0F, bladeIndex, seed);
        float radius = SHELL_RADIUS * 0.85F + length;
        float c = (float) Math.cos(angle) * radius;
        float s = (float) Math.sin(angle) * radius;
        out.set(BASIS_U.x * c + BASIS_V.x * s,
                BASIS_U.y * c + BASIS_V.y * s,
                BASIS_U.z * c + BASIS_V.z * s);
    }

    /**
     * Wind-cutting mist: short, fast streaks of shredded air.
     *
     * <p>Emitted as gated geometry rather than particles so the count is hard-bounded and fully
     * deterministic from the seed. Each streak is a noise-gated flicker with its own lifetime phase,
     * which produces the erratic in-and-out motion the effect needs while remaining reproducible on
     * every client. Density is weighted toward the blade edges, where the air is being cut.
     */
    private static void emitMist(PoseStack.Pose pose, VertexConsumer buffer, float angle,
                                 float bladeScale, float spinTime, float intensity,
                                 float quality, long seed) {
        // Hard cap, scaled by LOD. Mist is always the first thing sacrificed.
        int budget = Math.round(26 * quality);
        float length = BLADE_LENGTH * bladeScale;

        for (int i = 0; i < budget; i++) {
            float phase = i * 1.6180F + (seed % 1300L) * 0.001F;

            // Short individual lifetimes, cycling, so each streak flickers in and out.
            float life = positiveFraction(spinTime * 0.20F + phase);
            float gate = OrbitMath.noise(i * 4.7F, i * 2.9F, i * 6.1F, spinTime * 2.6F, phase);
            if (gate < 0.42F) {
                continue;
            }
            float strength = (gate - 0.42F) / 0.58F * (1.0F - life);

            // Bias toward the blades: land most streaks near a blade's angular position.
            int nearBlade = i % BLADE_COUNT;
            float bladeAngle = angle + nearBlade * (float) (Math.TAU / BLADE_COUNT);
            float angularSpread = (OrbitMath.noise(i * 3.3F, 1.0F, i * 1.7F, spinTime * 0.9F, phase) - 0.5F) * 0.9F;

            // Radially biased outward - denser at the edge than the core.
            float radialBias = 0.45F + 0.55F * (float) Math.sqrt(positiveFraction(phase * 7.3F));
            float radius = SHELL_RADIUS * 0.9F + radialBias * length;

            float a = bladeAngle + angularSpread;

            // Erratic outward-and-back motion: a signed radial dart driven by the streak's phase.
            float dart = (float) Math.sin(life * Math.TAU) * 0.11F;

            radialPoint(a, radius, P0);
            radialPoint(a + 0.05F * strength, radius + dart, P1);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            EnergyGeometry.ribbon(pose, buffer, P0, P1, TANGENT,
                    0.010F * (0.5F + strength), CAM,
                    Palette.lerp(Palette.HIGHLIGHT, Palette.CYAN, radialBias),
                    0.42F * intensity * strength);
        }
    }

    private static void radialPoint(float angle, float radius, Vector3f out) {
        float c = (float) Math.cos(angle) * radius;
        float s = (float) Math.sin(angle) * radius;
        out.set(BASIS_U.x * c + BASIS_V.x * s,
                BASIS_U.y * c + BASIS_V.y * s,
                BASIS_U.z * c + BASIS_V.z * s);
    }

    private static float positiveFraction(float value) {
        float f = value - (float) Math.floor(value);
        return f < 0.0F ? f + 1.0F : f;
    }

    // ------------------------------------------------------------------
    // Impact
    // ------------------------------------------------------------------

    /** Ticks the impact sequence runs for. */
    public static final float IMPACT_TICKS = 16.0F;

    /**
     * The impact: a snap-stop, four blades thrown off along their facing directions, and a single
     * flat wind-slash ring.
     *
     * <p>Deliberately not Rasengan's rounded implosion. Rotation does not decelerate - the blade
     * angle is frozen at the value it held on contact, so the stop reads as violent. The blades then
     * detach and travel along the tangent they were already sweeping, on decaying arcs, rather than
     * scattering uniformly in all directions.
     */
    public static void submitImpact(PoseStack poseStack, SubmitNodeCollector collector,
                                    Vec3 worldPos, Vec3 cameraPos, Vec3 spinAxis,
                                    float impactAge, float frozenSpinTime,
                                    float distanceFactor, float quality, long seed) {
        if (impactAge < 0.0F || impactAge > IMPACT_TICKS) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(
                worldPos.x - cameraPos.x,
                worldPos.y - cameraPos.y,
                worldPos.z - cameraPos.z);

        final float camX = (float) (cameraPos.x - worldPos.x);
        final float camY = (float) (cameraPos.y - worldPos.y);
        final float camZ = (float) (cameraPos.z - worldPos.z);
        final float ax = (float) spinAxis.x;
        final float ay = (float) spinAxis.y;
        final float az = (float) spinAxis.z;
        final float fAge = impactAge;
        final float fFrozen = frozenSpinTime;
        final float fDistance = distanceFactor;
        final float fQuality = quality;

        collector.submitCustomGeometry(poseStack, RenderTypes.dragonRays(), (pose, buffer) -> {
            CAM.set(camX, camY, camZ);
            setBasis(ax, ay, az);
            emitImpactSequence(pose, buffer, fAge, fFrozen, fDistance, fQuality, seed);
        });

        poseStack.popPose();
    }

    private static void emitImpactSequence(PoseStack.Pose pose, VertexConsumer buffer,
                                           float age, float frozenSpinTime,
                                           float distanceFactor, float quality, long seed) {
        float t = Math.clamp(age / IMPACT_TICKS, 0.0F, 1.0F);
        float fade = 1.0F - t;

        // Angle is FROZEN at the contact value - no spin-down.
        float angle = spinAngle(frozenSpinTime, SPINUP_TICKS);

        // ---- Sharp flash, front-loaded on the first couple of ticks ----
        float flash = Math.max(0.0F, 1.0F - age / 2.2F);
        if (flash > 0.01F) {
            EnergyGeometry.shell(pose, buffer, SphereMesh.LOW,
                    SHELL_RADIUS * (1.0F + 1.4F * (1.0F - flash)),
                    Palette.CORE, Palette.HIGHLIGHT,
                    0.85F * flash * distanceFactor,
                    0.0F, 0.0F, 0.0F, 0.0F, CAM, false);
        }

        // ---- Wind-slash shockwave: one flat ring in the disc plane, thin and fast ----
        float ringT = OrbitMath.easeOut(Math.min(1.0F, t * 1.9F));
        if (ringT < 1.0F) {
            float radius = 0.3F + ringT * 4.2F;
            float alpha = 0.70F * (1.0F - ringT) * (1.0F - ringT) * distanceFactor;
            EnergyGeometry.flatRing(pose, buffer, AXIS, radius,
                    0.030F * (1.0F - ringT * 0.6F),
                    Palette.lerp(Palette.HIGHLIGHT, Palette.CYAN, ringT),
                    alpha, CAM, Math.max(16, Math.round(30 * quality)));
        }

        // ---- Blade release burst: the four blades fly off along their facing tangents ----
        float travel = OrbitMath.easeOut(t) * 3.1F;
        for (int blade = 0; blade < BLADE_COUNT; blade++) {
            float baseAngle = angle + blade * (float) (Math.TAU / BLADE_COUNT);

            // Each blade keeps a decaying arc: it continues rotating slightly as it flies out,
            // which is what makes the burst look like released blades rather than a ring of shards.
            float arc = 0.55F * OrbitMath.easeOut(t);
            float a = baseAngle - 0.30F + arc;

            float rootR = SHELL_RADIUS * 0.85F + travel;
            float tipR = rootR + BLADE_LENGTH * (1.0F - 0.35F * t);

            radialPoint(a, rootR, P0);
            radialPoint(a + 0.10F, tipR, P1);

            TANGENT.set(P1).sub(P0);
            if (TANGENT.lengthSquared() < 1.0E-10F) {
                continue;
            }
            TANGENT.normalize();

            float alpha = 0.85F * fade * fade * distanceFactor;
            EnergyGeometry.taperedRibbon(pose, buffer, P0, P1, TANGENT,
                    BLADE_ROOT_HALF_WIDTH * fade, 0.004F, CAM,
                    Palette.HIGHLIGHT, Palette.BLUE, alpha, alpha * 0.4F);
        }

        // ---- Mist dispersal: scatters outward and fades fast rather than lingering ----
        if (quality > 0.3F) {
            int budget = Math.round(18 * quality);
            for (int i = 0; i < budget; i++) {
                float phase = i * 1.618F + (seed % 700L) * 0.001F;
                float spread = phase * 6.283F;
                float out = 0.4F + OrbitMath.easeOut(t) * (2.2F + 1.4F * positiveFraction(phase * 3.1F));

                radialPoint(spread, out, P0);
                radialPoint(spread + 0.06F, out + 0.14F, P1);

                TANGENT.set(P1).sub(P0);
                if (TANGENT.lengthSquared() < 1.0E-10F) {
                    continue;
                }
                TANGENT.normalize();

                // Cubic falloff: gone quickly, unlike Rasengan's lingering wisps.
                float alpha = 0.45F * fade * fade * fade * distanceFactor;
                EnergyGeometry.ribbon(pose, buffer, P0, P1, TANGENT, 0.009F, CAM,
                        Palette.CYAN, alpha);
            }
        }
    }
}
