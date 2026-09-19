package dev.rasengan;

/**
 * Which technique a cast is. Sent with every cast packet so clients know which renderer to use.
 *
 * <p>Both abilities deliberately share one server-side pipeline - the same charge state machine,
 * the same validation gate, the same projectile entity, the same single-hit guarantee. Only the
 * tuning values and the client-side visuals differ. That keeps the server-authoritative invariants
 * in one place rather than duplicated per ability, where they could drift apart.
 */
public enum AbilityType {
    /** The round, softly orbiting sphere. */
    RASENGAN("Rasengan"),
    /** The four-bladed shuriken: rigid, mechanical, higher damage. */
    RASEN_SHURIKEN("Rasen Shuriken");

    private static final AbilityType[] VALUES = values();

    /**
     * Absolute tick from cast start at which the Rasen Shuriken's blades snap out and the spin
     * clock starts running.
     *
     * <p>Deliberately absolute rather than a fraction of the cast window: it is locked to the 3.5 s
     * transition in the formation audio (tick 70 = 3.5 s at 20 ticks/s), and a fraction would
     * silently drift that sync the moment anyone changed {@code cast_duration_ticks}.
     *
     * <p>Lives here, in common code, because three separate places need it and they must agree
     * exactly: the renderer's formation beats, the server's in-flight spin clock (which continues
     * the held clock across the throw), and the client's screech pitch ramp. Each of those used to
     * carry its own copy of the value - one of them as {@code castDuration * 0.55}, which evaluated
     * to 55 instead of 70 and put the audio ramp 15 ticks ahead of the blades.
     */
    public static final float BLADE_SNAP_TICK = 70.0F;

    private final String displayName;

    AbilityType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public int id() {
        return ordinal();
    }

    public static AbilityType byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : RASENGAN;
    }

    public boolean isShuriken() {
        return this == RASEN_SHURIKEN;
    }
}
