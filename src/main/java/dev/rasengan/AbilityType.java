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
