package dev.rasengan.client;

import dev.rasengan.AbilityType;
import dev.rasengan.client.OrbitMath.Layer;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side record of the <em>held</em> phase of one cast. Purely cosmetic.
 *
 * <h2>One sphere, one owner at a time</h2>
 * This record owns the sphere only while it is in the caster's hand. The moment the server says the
 * cast was released, {@link #markReleased(long)} is called and this record stops drawing a sphere
 * entirely - ownership of the single visual passes to the
 * {@link dev.rasengan.server.RasenganProjectile} entity.
 *
 * <p>That handoff is why this class no longer knows anything about impacts. Previously it tracked an
 * impact position and glided the held sphere toward it after release, which is exactly what produced
 * two spheres on screen: the glided hand sphere hanging in the air while the real projectile flew
 * off on its own. Impacts are now tracked separately in {@link ClientImpactTracker} and drawn at the
 * projectile's actual contact point.
 *
 * <h2>Timeline</h2>
 * Phase boundaries are fractions of the server-supplied {@code castDuration}, so changing
 * {@code cast_duration_ticks} rescales the whole animation coherently.
 * <pre>
 *   0.00 .. 0.30  PREPARE : palm glow, inward-pulling motes, aura fades in
 *   0.30 .. 0.75  FORM    : shell scales up, bands and trails spin up
 *   0.75 .. 1.00  HOLD    : full sphere at full radius
 *   1.00          RELEASE : sphere rendering STOPS here; the projectile continues it
 *   +6 ticks      aura finishes fading, record is discarded
 * </pre>
 */
public final class ClientCast {

    public static final float PHASE_FORM_START = 0.30F;
    public static final float PHASE_HOLD_START = 0.75F;

    /** Ticks the body aura takes to fade out after release or cancellation. */
    public static final float FADE_TICKS = 6.0F;

    /** Grace period before an un-ended cast self-expires, so nothing can ever stick. */
    private static final float ORPHAN_GRACE_TICKS = 40.0F;

    public final int casterId;
    public final long seed;
    public final int castDuration;
    public final boolean mainHand;
    /** Which technique this is, deciding which renderer draws it. */
    public final AbilityType ability;
    public final Vec3 direction;

    /** Level game time at which the CastStart packet was applied. */
    public final long startGameTime;

    /** Pre-generated orbital layers. Identical on every client for a given seed. */
    public final Layer[] layers;

    /** Cosmetic caps as sent by the server. */
    public final float particleDensity;
    public final float auraIntensity;

    // ---- Mutable progression ----
    private boolean released;
    private long releaseGameTime = Long.MIN_VALUE;
    private boolean cancelled;
    private long cancelGameTime = Long.MIN_VALUE;

    public ClientCast(int casterId, long seed, int castDuration, boolean mainHand,
                      Vec3 direction, long startGameTime, int layerCount,
                      float particleDensity, float auraIntensity, AbilityType ability) {
        this.ability = ability;
        this.casterId = casterId;
        this.seed = seed;
        this.castDuration = Math.max(2, castDuration);
        this.mainHand = mainHand;
        this.direction = direction;
        this.startGameTime = startGameTime;
        this.layers = OrbitMath.buildLayers(seed, layerCount);
        this.particleDensity = particleDensity;
        this.auraIntensity = auraIntensity;
    }

    /** Age in ticks including the frame's partial tick. Continuous, never stepped. */
    public float age(long gameTime, float partialTick) {
        return (gameTime - startGameTime) + partialTick;
    }

    /** Normalised cast progress 0..1 over the charge-to-release window. */
    public float progress(long gameTime, float partialTick) {
        return Math.clamp(age(gameTime, partialTick) / castDuration, 0.0F, 1.0F);
    }

    public boolean isReleased() {
        return released;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** The sphere has left the hand. Nothing here draws a sphere from this point on. */
    public void markReleased(long gameTime) {
        if (!this.released) {
            this.released = true;
            this.releaseGameTime = gameTime;
        }
    }

    public void applyCancel(long gameTime) {
        if (!this.cancelled) {
            this.cancelled = true;
            this.cancelGameTime = gameTime;
        }
    }

    /**
     * Whether this record should be discarded.
     *
     * <p>Three independent exits, so a lost or never-sent packet cannot leave a permanent effect:
     * release, cancellation, or the orphan timeout. Note how short the post-release window is - it
     * exists only to let the body aura fade, not to keep a sphere alive.
     */
    public boolean isExpired(long gameTime) {
        if (cancelled && cancelGameTime != Long.MIN_VALUE) {
            return (gameTime - cancelGameTime) > FADE_TICKS;
        }
        if (released && releaseGameTime != Long.MIN_VALUE) {
            return (gameTime - releaseGameTime) > FADE_TICKS;
        }
        return (gameTime - startGameTime) > castDuration + ORPHAN_GRACE_TICKS;
    }

    /**
     * Aura intensity 0..1: ramps in during PREPARE, holds, then falls off after release or
     * cancellation so the aura always disappears cleanly.
     *
     * <p>Only the aura uses this. The sphere is not faded on release - it is handed over.
     */
    public float auraFade(long gameTime, float partialTick) {
        if (cancelled) {
            float since = cancelGameTime == Long.MIN_VALUE ? 0.0F : (gameTime - cancelGameTime) + partialTick;
            return Math.max(0.0F, 1.0F - since / FADE_TICKS);
        }
        if (released) {
            float since = releaseGameTime == Long.MIN_VALUE ? 0.0F : (gameTime - releaseGameTime) + partialTick;
            return Math.max(0.0F, 1.0F - since / FADE_TICKS);
        }
        return OrbitMath.smoothstep(0.0F, PHASE_FORM_START * 0.8F, progress(gameTime, partialTick));
    }

    /**
     * Held-sphere brightness. Zero once released, because the projectile owns the visual from then
     * on and two overlapping sources would be a duplicate.
     */
    public float sphereIntensity(long gameTime, float partialTick) {
        if (released || cancelled) {
            return 0.0F;
        }
        return OrbitMath.smoothstep(0.0F, PHASE_FORM_START * 0.8F, progress(gameTime, partialTick));
    }

    /**
     * Held-sphere radius: grows through FORM then holds at exactly {@code fullRadius}.
     *
     * <p>Reaching precisely {@code fullRadius} by release matters - the projectile is drawn at that
     * same constant, so the handoff has no size pop.
     */
    public float radius(long gameTime, float partialTick, float fullRadius) {
        float progress = progress(gameTime, partialTick);
        float grow = OrbitMath.smoothstep(PHASE_FORM_START * 0.5F, PHASE_HOLD_START, progress);
        // A small overshoot just before hold makes the formation feel like it snaps into place.
        // It resolves back to exactly 1.0 well before release.
        float overshoot = 1.0F + 0.10F * OrbitMath.smoothstep(PHASE_HOLD_START - 0.12F, PHASE_HOLD_START, progress)
                * (1.0F - OrbitMath.smoothstep(PHASE_HOLD_START, PHASE_HOLD_START + 0.10F, progress));
        return fullRadius * grow * overshoot;
    }
}
