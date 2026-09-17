package dev.rasengan.client;

import dev.rasengan.client.OrbitMath.Layer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Client-side record of one cast in progress. Purely cosmetic - deleting one has no gameplay
 * effect whatsoever.
 *
 * <h2>Timeline</h2>
 * All phase boundaries are fractions of the server-supplied {@code castDuration}, so changing
 * {@code cast_duration_ticks} in the server config rescales the whole animation coherently.
 * <pre>
 *   0.00 .. 0.30  PREPARE : palm glow, inward-pulling motes, aura fades in
 *   0.30 .. 0.75  FORM    : shell scales up, bands and trails spin up
 *   0.75 .. 1.00  HOLD    : full sphere, maximum instability
 *   1.00          RELEASE : thrust forward toward the impact point
 *   +18 ticks     IMPACT  : implosion, burst, shockwave rings, then removal
 * </pre>
 */
public final class ClientCast {

    public static final float PHASE_FORM_START = 0.30F;
    public static final float PHASE_HOLD_START = 0.75F;

    /** Ticks the sphere takes to travel from the hand to the impact point. */
    public static final float RELEASE_TRAVEL_TICKS = 3.0F;

    /** Ticks the impact animation runs for after release. */
    public static final float IMPACT_TICKS = 18.0F;

    /** Grace period before an un-ended cast self-expires, so nothing can ever stick. */
    private static final float ORPHAN_GRACE_TICKS = 40.0F;

    public final int casterId;
    public final long seed;
    public final int castDuration;
    public final boolean mainHand;
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
    private Vec3 releaseOrigin;
    private Vec3 impactPos;
    private long impactGameTime = Long.MIN_VALUE;
    private int hitKind;
    private boolean cancelled;
    private long cancelGameTime = Long.MIN_VALUE;

    public ClientCast(int casterId, long seed, int castDuration, boolean mainHand,
                      Vec3 direction, long startGameTime, int layerCount,
                      float particleDensity, float auraIntensity) {
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

    public void markReleased() {
        this.released = true;
    }

    public void setReleaseOrigin(Vec3 origin) {
        if (this.releaseOrigin == null) {
            this.releaseOrigin = origin;
        }
    }

    public Vec3 releaseOrigin() {
        return releaseOrigin;
    }

    public void applyImpact(Vec3 pos, int kind, long gameTime) {
        this.impactPos = pos;
        this.hitKind = kind;
        this.impactGameTime = gameTime;
        this.released = true;
    }

    public void applyCancel(long gameTime) {
        this.cancelled = true;
        this.cancelGameTime = gameTime;
    }

    public Vec3 impactPos() {
        return impactPos;
    }

    public int hitKind() {
        return hitKind;
    }

    public boolean hasImpact() {
        return impactPos != null;
    }

    /** Ticks elapsed since impact, or -1 if it has not happened yet. */
    public float impactAge(long gameTime, float partialTick) {
        if (impactGameTime == Long.MIN_VALUE) {
            return -1.0F;
        }
        return (gameTime - impactGameTime) + partialTick;
    }

    /**
     * Whether this record should be discarded.
     *
     * <p>Three independent exits, so a lost or never-sent packet cannot leave a permanent effect:
     * the impact animation finishing, a cancel fading out, or the orphan timeout.
     */
    public boolean isExpired(long gameTime) {
        if (cancelled && cancelGameTime != Long.MIN_VALUE) {
            return (gameTime - cancelGameTime) > 5;
        }
        if (impactGameTime != Long.MIN_VALUE) {
            return (gameTime - impactGameTime) > IMPACT_TICKS;
        }
        return (gameTime - startGameTime) > castDuration + IMPACT_TICKS + ORPHAN_GRACE_TICKS;
    }

    /**
     * Overall visual intensity 0..1: ramps in during PREPARE, holds, then falls off after
     * release or cancellation so the aura always disappears cleanly.
     */
    public float intensity(long gameTime, float partialTick) {
        if (cancelled) {
            float since = cancelGameTime == Long.MIN_VALUE ? 0.0F : (gameTime - cancelGameTime) + partialTick;
            return Math.max(0.0F, 1.0F - since / 5.0F);
        }
        float impactAge = impactAge(gameTime, partialTick);
        if (impactAge >= 0.0F) {
            // The sphere itself is gone at impact; only the burst remains.
            return Math.max(0.0F, 1.0F - impactAge / 4.0F);
        }
        float progress = progress(gameTime, partialTick);
        return OrbitMath.smoothstep(0.0F, PHASE_FORM_START * 0.8F, progress);
    }

    /**
     * Sphere radius in blocks at this moment: grows through FORM, holds, then implodes sharply
     * in the last moments before the burst.
     */
    public float radius(long gameTime, float partialTick, float fullRadius) {
        float impactAge = impactAge(gameTime, partialTick);
        if (impactAge >= 0.0F) {
            // Implosion: collapse to a point over 2 ticks.
            float collapse = Math.max(0.0F, 1.0F - impactAge / 2.0F);
            return fullRadius * collapse * collapse;
        }
        float progress = progress(gameTime, partialTick);
        float grow = OrbitMath.smoothstep(PHASE_FORM_START * 0.5F, PHASE_HOLD_START, progress);
        // A small overshoot just before hold makes the formation feel like it snaps into place.
        float overshoot = 1.0F + 0.10F * OrbitMath.smoothstep(PHASE_HOLD_START - 0.12F, PHASE_HOLD_START, progress)
                * (1.0F - OrbitMath.smoothstep(PHASE_HOLD_START, PHASE_HOLD_START + 0.10F, progress));
        return fullRadius * grow * overshoot;
    }

    /** Scratch vector reused by the renderer to avoid per-frame allocation. */
    public final Vector3f scratch = new Vector3f();
}
