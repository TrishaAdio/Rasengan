package dev.rasengan.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The summoning smoke.
 *
 * <p>Separate from {@link EnergyParticle} because the two have opposite jobs. Energy particles are
 * small, additive and emissive - they <em>add</em> light. Smoke is large, alpha-blended and mostly
 * unlit, because the reveal depends on it <em>hiding</em> what is behind it, and additive blending
 * can only ever brighten. That single requirement - "the summon location is fully obscured at peak" -
 * is why this is a new class rather than another {@link EnergyParticle.Style}.
 *
 * <h2>What makes it read as smoke rather than as grey balls</h2>
 * <ul>
 *   <li><b>Drag, not constant velocity.</b> {@code Particle.tick()} applies
 *       {@code xd *= friction} every tick, which is the exact discrete integration of linear drag.
 *       A low friction therefore bursts fast and decelerates hard, so the cloud leaps out and then
 *       billows slowly. That deceleration curve is the single most important cue.</li>
 *   <li><b>Individual rotation.</b> Each billboard gets a random initial {@link #roll} and its own
 *       slow spin rate, so the cloud churns. Without it, a stack of billboards slides as one flat
 *       sheet. {@code SingleQuadParticle.extract} interpolates {@code oRoll -> roll} across the
 *       partial tick for free, so the spin is smooth at any frame rate.</li>
 *   <li><b>Frayed sprites.</b> Four noise-perturbed puff textures, chosen at random per particle,
 *       so there is no repeating silhouette and no circular outline. See
 *       {@code tools/generate_particle_textures.py}.</li>
 *   <li><b>Growth.</b> Every puff expands over its life, so the cloud keeps inflating after the
 *       velocity has died.</li>
 * </ul>
 *
 * <h2>Continuity</h2>
 * Size and alpha are <em>pure functions</em> of the continuous life fraction
 * {@code (age + partialTick) / lifetime}, evaluated at render time - not values stepped once per
 * tick. This matters specifically here: vanilla's {@code extractRotatedQuad} interpolates position
 * and roll across the partial tick but passes {@code this.alpha} and {@code getQuadSize} straight
 * through, so a field updated in {@code tick()} would step at 20 Hz. On a two-second fade across a
 * six-block billboard that steps visibly. {@link #extractRotatedQuad} is overridden to evaluate both
 * curves at the frame's exact time instead, which is the same discipline as
 * {@code ShurikenRenderer.spinAngle}: no accumulated state, so nothing can drift or strobe.
 */
public class SmokeParticle extends SingleQuadParticle {

    /** Flat horizontal billboard, for the rolling ground layer. */
    private static final FacingCameraMode FLAT =
            (target, camera, partialTick) -> target.rotationX(Mth.HALF_PI);

    /** Per-type behaviour. */
    public enum Style {
        /**
         * The main column. Heavy drag, strong growth, long life - it has to survive from the
         * eruption all the way to the wing-beat dispersal.
         */
        BILLOW(0.855F, -0.004F, 2.6F, 1.15F, 46, 64, 0.92F, 0.10F, 0.020F, 0.14F),
        /**
         * Fraying edge wisps. Thinner, faster, shorter, and they stretch more as they dissolve, so
         * the cloud's boundary breaks into tendrils instead of ending on a line.
         */
        WISP(0.895F, -0.010F, 1.5F, 1.55F, 26, 42, 0.52F, 0.16F, 0.045F, 0.10F),
        /**
         * The rolling ground layer: faster and thinner than the column, spreading outward along the
         * surface. Drawn as a flat quad, and {@code speedUpWhenYMotionIsBlocked} makes it accelerate
         * sideways when it meets the floor - which is exactly the behaviour wanted, so it hugs the
         * ground and runs outward rather than piling up.
         */
        GROUND_FOG(0.915F, 0.004F, 2.2F, 1.30F, 34, 52, 0.46F, 0.12F, 0.012F, 0.06F),
        /** Near-camera dust motes, purely for depth. Tiny, quick, barely there. */
        MOTE(0.94F, 0.020F, 0.12F, 0.20F, 12, 22, 0.40F, 0.30F, 0.090F, 0.00F),
        /**
         * The fear tell above a frightened mob's head. Pale, small, rises and fades fast - legible
         * at a glance without competing with the summon itself.
         */
        FEAR(0.92F, -0.012F, 0.26F, 0.45F, 14, 20, 0.70F, 0.22F, 0.070F, 0.35F);

        final float friction;
        final float gravity;
        final float size;
        final float growth;
        final int minLifetime;
        final int maxLifetime;
        final float peakAlpha;
        /** Fraction of life spent fading in. Never 0: a puff appearing at full alpha pops. */
        final float fadeIn;
        /** Radians per tick of spin, before per-particle jitter. */
        final float spin;
        /** How emissive, 0..1. Smoke is mostly lit by the world; the eruption core glows a little. */
        final float emissive;

        Style(float friction, float gravity, float size, float growth,
              int minLifetime, int maxLifetime, float peakAlpha, float fadeIn,
              float spin, float emissive) {
            this.friction = friction;
            this.gravity = gravity;
            this.size = size;
            this.growth = growth;
            this.minLifetime = minLifetime;
            this.maxLifetime = maxLifetime;
            this.peakAlpha = peakAlpha;
            this.fadeIn = fadeIn;
            this.spin = spin;
            this.emissive = emissive;
        }
    }

    /**
     * Live particles, so the wing downbeat can push smoke that already exists.
     *
     * <h2>Why a registry rather than just spawning outward particles at stage D</h2>
     * The requirement is that the dispersal is visibly <em>caused</em> by the wings. Spawning a fresh
     * outward burst would be a new cloud that happens to appear on the right tick; the smoke already
     * hanging in the air would drift on unchanged, and the eye reads that as two unrelated events.
     * Accelerating the existing cloud is the thing that looks caused. That needs a handle on the
     * live particles, which the particle engine does not expose, so they are tracked here.
     *
     * <p>Bounded by construction: entries are dropped as soon as a particle reports dead, the list
     * is pruned on every impulse and every 20th spawn, and {@link #clear()} empties it on level
     * unload and disconnect. Client-side only, single-threaded.
     */
    private static final List<SmokeParticle> LIVE = new ArrayList<>();

    private static int spawnsSinceSweep;

    private final Style style;
    private final float baseSize;
    private final float spinRate;
    private float brightness = 1.0F;
    private float alphaScale = 1.0F;

    /** Extra drag relaxation while an impulse is carrying, so the push is not instantly eaten. */
    private int impulseTicks;

    protected SmokeParticle(ClientLevel level, double x, double y, double z,
                            double xa, double ya, double za,
                            SpriteSet sprites, Style style, RandomSource random) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D, sprites.get(random));
        this.style = style;

        // Smoke passes through geometry rather than colliding: a large billboard that respects
        // collision jams against walls and stops mid-air, which looks like a bug. The ground layer
        // is the exception - it needs the floor to spread along.
        this.hasPhysics = style == Style.GROUND_FOG;
        this.speedUpWhenYMotionIsBlocked = style == Style.GROUND_FOG;
        this.friction = style.friction;
        this.gravity = style.gravity;

        this.baseSize = style.size * (0.72F + random.nextFloat() * 0.56F);
        this.quadSize = baseSize;

        // Random initial rotation and an individual spin direction and rate. This is what makes the
        // cloud churn instead of sliding.
        this.roll = random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.spinRate = style.spin * (0.45F + random.nextFloat() * 1.1F)
                * (random.nextBoolean() ? 1.0F : -1.0F);

        setLifetime(style.minLifetime
                + random.nextInt(Math.max(1, style.maxLifetime - style.minLifetime)));
        setParticleSpeed(xa, ya, za);

        LIVE.add(this);
        if (++spawnsSinceSweep >= 20) {
            spawnsSinceSweep = 0;
            LIVE.removeIf(p -> !p.isAlive());
        }
    }

    // ------------------------------------------------------------------
    // Spawner-side tuning
    // ------------------------------------------------------------------

    /**
     * Scales the particle's white toward grey.
     *
     * <p>Used for the radial brightness gradient: the eruption core is near-white and the outer
     * billows are dimmer, which is what stops a large white mass reading as a flat cut-out.
     */
    public void setBrightness(float value) {
        this.brightness = Mth.clamp(value, 0.0F, 1.0F);
        setColor(brightness, brightness, brightness);
    }

    /** Scales peak opacity, for distance LOD and for thinning the cloud as it clears. */
    public void setAlphaScale(float value) {
        this.alphaScale = Mth.clamp(value, 0.0F, 1.0F);
    }

    /** Multiplies the base size. Lets one style cover the whole column-to-wisp size range. */
    public void scaleSize(float factor) {
        this.quadSize = baseSize * factor;
    }

    /**
     * The wing downbeat: pushes existing smoke outward from {@code centre}.
     *
     * <p>Falls off with distance so the near cloud is thrown hardest, and adds a downward-then-out
     * component rather than a pure radial one, because a downbeat drives air at the ground and the
     * ground turns it outward. Friction is relaxed for a few ticks so the push actually carries
     * instead of being eaten by drag on the next tick.
     *
     * @return how many particles were affected - returned so the verification harness can assert the
     *         dispersal did something rather than silently matching nothing
     */
    public static int applyDownbeat(Vec3 centre, double radius, double strength) {
        LIVE.removeIf(p -> !p.isAlive());
        int affected = 0;
        double radiusSq = radius * radius;
        for (SmokeParticle particle : LIVE) {
            if (particle.style == Style.MOTE || particle.style == Style.FEAR) {
                continue; // motes are camera-local depth cues; fear tells belong to mobs
            }
            double dx = particle.x - centre.x;
            double dy = particle.y - centre.y;
            double dz = particle.z - centre.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            // Falloff smooth at the boundary so there is no shell of unaffected smoke.
            double falloff = 1.0D - (dist / radius);
            falloff *= falloff;

            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double ux = horizontal < 1.0E-4D ? 0.0D : dx / horizontal;
            double uz = horizontal < 1.0E-4D ? 0.0D : dz / horizontal;

            double push = strength * falloff;
            particle.xd += ux * push;
            particle.zd += uz * push;
            // Slight downward bias near the centre, flattening outward: air driven down and out.
            particle.yd += push * (particle.y < centre.y + 1.5D ? 0.10D : -0.22D);
            particle.impulseTicks = 6;
            affected++;
        }
        return affected;
    }

    /** Drops every tracked particle. Called on level unload and disconnect. */
    public static void clear() {
        LIVE.clear();
        spawnsSinceSweep = 0;
    }

    /** Exposed for the verification harness. */
    public static int liveCount() {
        LIVE.removeIf(p -> !p.isAlive());
        return LIVE.size();
    }

    // ------------------------------------------------------------------
    // Curves - pure functions of continuous time, never stepped fields
    // ------------------------------------------------------------------

    /** Continuous life fraction in 0..1 at the frame's exact time. */
    private float life(float partialTick) {
        if (lifetime <= 0) {
            return 1.0F;
        }
        return Mth.clamp((age + partialTick) / lifetime, 0.0F, 1.0F);
    }

    /**
     * Opacity at continuous life fraction {@code f}.
     *
     * <p>Fades in over {@code style.fadeIn} on a smoothstep, holds, then fades out on a squared
     * curve so the cloud lingers and then clears rather than dimming linearly. Both ends reach
     * exactly 0, so a puff never appears or vanishes on a step.
     */
    private float alphaAt(float f) {
        float in = style.fadeIn <= 0.0F ? 1.0F : Mth.clamp(f / style.fadeIn, 0.0F, 1.0F);
        in = in * in * (3.0F - 2.0F * in);
        float out = Mth.clamp((1.0F - f) / Math.max(1.0E-4F, 1.0F - style.fadeIn), 0.0F, 1.0F);
        out *= out;
        return style.peakAlpha * alphaScale * in * out;
    }

    /** Size at continuous life fraction {@code f}: eased outward growth, fastest early. */
    private float sizeAt(float f) {
        float eased = 1.0F - (1.0F - f) * (1.0F - f);
        return quadSize * (1.0F + style.growth * eased);
    }

    @Override
    public float getQuadSize(float partialTick) {
        return sizeAt(life(partialTick));
    }

    @Override
    public FacingCameraMode getFacingCameraMode() {
        return style == Style.GROUND_FOG ? FLAT : FacingCameraMode.LOOKAT_XYZ;
    }

    @Override
    protected Layer getLayer() {
        // Alpha-blended, on the particle atlas. Translucent rather than additive is the whole point:
        // additive smoke cannot obscure, and the reveal depends on obscuring.
        return Layer.TRANSLUCENT;
    }

    @Override
    public int getLightCoords(float partialTick) {
        int world = super.getLightCoords(partialTick);
        return style.emissive <= 0.0F
                ? world
                : LightCoordsUtil.addSmoothBlockEmission(world, style.emissive);
    }

    /**
     * Overridden solely to evaluate alpha and size at the frame's exact time.
     *
     * <p>Vanilla's implementation passes the {@code alpha} <em>field</em> into the render state, so
     * any fade written in {@code tick()} arrives quantised to 20 Hz. Everything else here is
     * identical to the superclass.
     */
    @Override
    protected void extractRotatedQuad(QuadParticleRenderState state, Quaternionf rotation,
                                      float x, float y, float z, float partialTick) {
        float f = life(partialTick);
        state.add(
                getLayer(),
                x, y, z,
                rotation.x, rotation.y, rotation.z, rotation.w,
                sizeAt(f),
                getU0(), getU1(), getV0(), getV1(),
                ARGB.colorFromFloat(alphaAt(f), rCol, gCol, bCol),
                getLightCoords(partialTick));
    }

    @Override
    public void tick() {
        // oRoll before super.tick() advances age, so the render-time lerp spans this tick exactly.
        this.oRoll = this.roll;

        if (impulseTicks > 0) {
            impulseTicks--;
            // Relax drag while the downbeat is carrying, then hand back to the style's own drag.
            this.friction = Mth.lerp(0.35F, style.friction, 0.985F);
        } else {
            this.friction = style.friction;
        }

        super.tick();
        this.roll += spinRate;

        // Kept in step for anything that reads the field directly; the drawn value comes from
        // alphaAt() at render time.
        this.alpha = alphaAt(life(1.0F));
    }

    /** Provider bound to one {@link Style}. */
    public record Provider(SpriteSet sprites, Style style) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xa, double ya, double za,
                                       RandomSource random) {
            return new SmokeParticle(level, x, y, z, xa, ya, za, sprites, style, random);
        }
    }
}
