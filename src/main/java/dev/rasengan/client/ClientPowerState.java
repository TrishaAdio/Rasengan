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

    private ClientPowerState() {}

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
    }

    /** Clears state on disconnect so a stale bar cannot leak into the next session. */
    public static void reset() {
        chargeTicks = 0;
        cooldownTicks = 0;
        state = PowerState.CHARGING;
        initialised = false;
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
