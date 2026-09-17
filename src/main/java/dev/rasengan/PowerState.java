package dev.rasengan;

/**
 * The POWER BAR state machine. The server owns this value; the client only mirrors it.
 *
 * <pre>
 *   CHARGING --(chargeTicks reaches chargeDuration)--> READY
 *   READY    --(player activates, server validates)--> CASTING
 *   CASTING  --(cast resolves: hit, expire, or cancel)--> COOLDOWN (or CHARGING if cooldown is 0)
 *   COOLDOWN --(cooldownTicks elapses)--> CHARGING
 * </pre>
 */
public enum PowerState {
    /** Accumulating charge. The ability cannot be used. */
    CHARGING,
    /** Charge is at 100%. The ability may be activated. */
    READY,
    /** A cast is in progress. Further activations are rejected. */
    CASTING,
    /** Post-cast lockout. Charge is 0 and not yet accumulating. */
    COOLDOWN;

    private static final PowerState[] VALUES = values();

    public static PowerState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : CHARGING;
    }

    public int id() {
        return ordinal();
    }
}
