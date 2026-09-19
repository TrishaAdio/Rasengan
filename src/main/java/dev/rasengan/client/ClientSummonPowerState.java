package dev.rasengan.client;

import dev.rasengan.PowerState;
import dev.rasengan.network.RasenganSummonPayloads;

/**
 * The client's mirror of the summoning POWER BAR.
 *
 * <p>Separate from {@link ClientPowerState} because the two bars are genuinely independent: they have
 * different durations and advance on different schedules, so sharing one predictor would make each
 * bar's local prediction wrong whenever the other synced.
 *
 * <p>Same prediction contract as the cast bar: advance locally each client tick, re-anchor on every
 * packet, and let the HUD add the partial tick so the drawn value is continuous in real time.
 */
public final class ClientSummonPowerState {

    private static int chargeTicks;
    private static int chargeDuration = 6000; // 300s default until the first packet lands
    private static int cooldownTicks;
    private static PowerState state = PowerState.CHARGING;
    private static boolean initialised;

    private ClientSummonPowerState() {}

    public static void accept(RasenganSummonPayloads.SummonPowerSync payload) {
        chargeTicks = payload.chargeTicks();
        chargeDuration = Math.max(1, payload.chargeDuration());
        cooldownTicks = payload.cooldownTicks();
        state = PowerState.byId(payload.state());
        initialised = true;
    }

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
                // Held; the server decides when these end.
            }
        }
    }

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

    public static float fraction(float partialTick) {
        if (state == PowerState.READY) {
            return 1.0F;
        }
        if (state == PowerState.CASTING || state == PowerState.COOLDOWN) {
            return 0.0F;
        }
        return Math.clamp((chargeTicks + partialTick) / chargeDuration, 0.0F, 1.0F);
    }

    public static int percent(float partialTick) {
        return Math.round(fraction(partialTick) * 100.0F);
    }
}
