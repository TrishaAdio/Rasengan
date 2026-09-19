package dev.rasengan.client;

import dev.rasengan.PowerState;
import dev.rasengan.network.RasenganPayloads;

/**
 * The client's mirror of the server-owned POWER BAR.
 *
 * <p>This is a <em>display</em> copy, never a source of truth. It exists so the HUD can show a
 * percentage that moves every frame instead of stepping once per packet.
 *
 * <h2>How smoothness is achieved</h2>
 * <ol>
 *   <li>The server pushes a {@link RasenganPayloads.PowerSync} on every state change and once a
 *       second otherwise.</li>
 *   <li>Between packets this class advances {@code chargeTicks} by one per client tick, which
 *       exactly matches what the server is doing.</li>
 *   <li>The HUD then adds the frame's partial tick on top, so the number it draws is a
 *       continuous function of real time rather than a stair-step.</li>
 *   <li>Every arriving packet snaps the local value back to the authoritative one, so error
 *       cannot accumulate. Because prediction and truth agree, the snap is invisible.</li>
 * </ol>
 */
public final class ClientPowerState {

    private static int chargeTicks;
    private static int chargeDuration = 3000; // 150s default until the first packet lands
    private static int cooldownTicks;
    private static PowerState state = PowerState.CHARGING;
    private static boolean initialised;

    // ------------------------------------------------------------------
    // HUD glow phases
    // ------------------------------------------------------------------

    /**
     * Accumulated phase of the HUD's breathing glow, in radians, and of its travelling shimmer, as
     * a 0..1 fraction along the bar.
     *
     * <h2>Why these are accumulated rather than computed from the world clock</h2>
     * Both effects deliberately quicken as the bar fills, so their angular rates are functions of
     * time. The HUD used to draw them as {@code sin(gameTime * rate)} and
     * {@code frac(gameTime * rate)} - current rate multiplied by total elapsed time, which is not
     * the integral of a changing rate. Differentiating {@code rate(t) * t} gives
     * {@code rate + t * drate/dt}: a spurious frequency term proportional to the <em>absolute age
     * of the world</em>. With {@code drate/dt} around 6.7e-5 per tick, a world at one million ticks
     * old carried roughly 67 rad/tick of bogus frequency - about ten strobe cycles per tick in place
     * of a 22 tick breath. A fresh world looked fine, which is exactly why it survived.
     *
     * <p>Accumulating the phase one tick at a time at the rate then current <em>is</em> the integral,
     * and it is bounded and world-age independent. The sub-tick remainder is added at read time so
     * the value the HUD draws is still continuous in real time at any frame rate.
     */
    private static float breathePhase;
    private static float shimmerPhase;

    /** Full turn, used to wrap the breathing phase without introducing a discontinuity. */
    private static final float TAU = (float) Math.TAU;

    private ClientPowerState() {}

    /** Breathing angular rate in radians per tick at charge fraction {@code f}. */
    private static float breatheRate(float f) {
        return 0.18F + 0.10F * f * f;
    }

    /** Shimmer sweep rate in bar-fractions per tick at charge fraction {@code f}. */
    private static float shimmerRate(float f) {
        return 0.016F + 0.020F * f * f;
    }

    /**
     * Breathing phase in radians, continuous in real time.
     *
     * @param partialTick fraction of the way through the current tick, 0..1
     */
    public static float breathePhase(float partialTick) {
        return breathePhase + breatheRate(fraction(partialTick)) * partialTick;
    }

    /**
     * Shimmer head position as a 0..1 fraction along the bar, continuous in real time.
     *
     * @param partialTick fraction of the way through the current tick, 0..1
     */
    public static float shimmerPhase(float partialTick) {
        return shimmerPhase + shimmerRate(fraction(partialTick)) * partialTick;
    }

    /** Applies an authoritative snapshot from the server. */
    public static void accept(RasenganPayloads.PowerSync payload) {
        chargeTicks = payload.chargeTicks();
        chargeDuration = Math.max(1, payload.chargeDuration());
        cooldownTicks = payload.cooldownTicks();
        state = PowerState.byId(payload.state());
        initialised = true;
    }

    /**
     * Advances the local prediction one tick. Called from the client tick handler.
     *
     * <p>Only CHARGING and COOLDOWN advance locally; READY and CASTING are held states whose
     * exit is decided by the server.
     */
    public static void clientTick() {
        if (!initialised) {
            return;
        }
        switch (state) {
            case CHARGING -> {
                if (chargeTicks < chargeDuration) {
                    chargeTicks++;
                }
            }
            case COOLDOWN -> {
                if (cooldownTicks > 0) {
                    cooldownTicks--;
                }
            }
            case READY, CASTING -> {
                // Held. Waiting on the server.
            }
        }

        // Advance the glow phases by one tick at the rate currently in force. This runs in every
        // state - including READY, where the charge value itself is pinned - so a full bar keeps
        // breathing instead of freezing.
        float f = fraction(0.0F);
        breathePhase += breatheRate(f);
        shimmerPhase += shimmerRate(f);

        // Wrap to keep float precision high over long sessions. Both wrap points are exact periods
        // of their consumers - a full turn for the sine, one bar length for the sweep - so wrapping
        // is invisible.
        if (breathePhase >= TAU) {
            breathePhase -= TAU;
        }
        if (shimmerPhase >= 1.0F) {
            shimmerPhase -= 1.0F;
        }
    }

    /** Clears state on disconnect so a stale bar cannot leak into the next session. */
    public static void reset() {
        chargeTicks = 0;
        cooldownTicks = 0;
        state = PowerState.CHARGING;
        initialised = false;
        breathePhase = 0.0F;
        shimmerPhase = 0.0F;
    }

    public static boolean isInitialised() {
        return initialised;
    }

    public static PowerState state() {
        return state;
    }

    public static int chargeDuration() {
        return chargeDuration;
    }

    /**
     * Charge fraction in 0..1, interpolated with the current frame's partial tick.
     *
     * @param partialTick fraction of the way through the current tick, 0..1
     */
    public static float fraction(float partialTick) {
        if (state == PowerState.READY) {
            return 1.0F;
        }
        if (state == PowerState.CASTING || state == PowerState.COOLDOWN) {
            return 0.0F;
        }
        float ticks = chargeTicks + partialTick;
        return Math.clamp(ticks / chargeDuration, 0.0F, 1.0F);
    }

    /** Whole-number percentage 0..100 for the label. */
    public static int percent(float partialTick) {
        return Math.round(fraction(partialTick) * 100.0F);
    }

    public static boolean isReady() {
        return state == PowerState.READY;
    }
}
