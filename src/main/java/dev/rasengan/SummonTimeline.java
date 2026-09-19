package dev.rasengan;

/**
 * The summoning sequence's stage layout - the single source of truth for both sides.
 *
 * <h2>Why this is COMMON</h2>
 * The server decides when the dragon spawns, when it becomes visible and when its wings beat. The
 * client decides when smoke erupts, thins and is blown away. Those have to agree exactly or the
 * reveal lands on the wrong frame and the dispersal stops looking caused by the wings. Rather than
 * two tick tables that must be kept in step by hand, both sides call into this class. It joins
 * {@link PalmAnchor} and {@link Palette} as shared cosmetic geometry: no client imports, so a
 * dedicated server loads it happily.
 *
 * <h2>The five stages</h2>
 * <pre>
 *   A  seal formation     rings spread from the palm-strike point, chakra drawn inward
 *   B  smoke eruption     the centrepiece: dense white column, fully obscuring the summon
 *   C  silhouette reveal  smoke thins, outline then wings then head; the roar lands here
 *   D  wing-beat dispersal the downbeat throws the remaining smoke outward
 *   E  settle             residual wisps fade, ground fog last, camera eases back
 * </pre>
 *
 * <h2>How the boundaries are derived</h2>
 * Two config values stay authoritative - {@code cinematic_ticks} (total) and {@code reveal_tick}
 * (when the dragon becomes visible, i.e. the A/B -> C boundary). Everything else is derived by
 * distributing the reference durations proportionally on each side of the reveal:
 *
 * <pre>
 *   pre-reveal   A 12 + B 20 = 32 ticks   (0.6s + 1.0s)
 *   post-reveal  C 16 + D 12 + E 10 = 38  (0.8s + 0.6s + 0.5s)
 *   reference total 70 ticks = 3.5s
 * </pre>
 *
 * At the shipped defaults (total 70, reveal 32) the stages land on exactly those durations. An owner
 * who lengthens either half gets the same proportions stretched to fit, so no configuration can
 * produce a zero-length or out-of-order stage. That is the reason for deriving rather than exposing
 * five more tick knobs: five independent values can be set to contradict each other, two cannot.
 *
 * <h2>Wing-beat timing</h2>
 * {@link #WING_CYCLE_TICKS} and {@link #WING_DOWNBEAT_TICK} are measured from the shipped
 * {@code animation.rasengan_dragon.fly} keyframes, not chosen. See {@link #WING_DOWNBEAT_TICK} for
 * the derivation. {@link #wingCycleStartTick} then places the start of the flight cycle so that the
 * downbeat's peak angular velocity coincides with the start of stage D - which is what makes the
 * dispersal genuinely caused by the wings rather than merely concurrent with them.
 */
public final class SummonTimeline {

    // ---- Reference durations, in ticks, at the reference total of 70 ----
    private static final float REF_SEAL = 12.0F;      // stage A, 0.6s
    private static final float REF_ERUPTION = 20.0F;  // stage B, 1.0s
    private static final float REF_REVEAL = 16.0F;    // stage C, 0.8s
    private static final float REF_DISPERSAL = 12.0F; // stage D, 0.6s
    private static final float REF_SETTLE = 10.0F;    // stage E, 0.5s

    private static final float REF_PRE = REF_SEAL + REF_ERUPTION;                       // 32
    private static final float REF_POST = REF_REVEAL + REF_DISPERSAL + REF_SETTLE;       // 38

    /** Reference total, 3.5s. Exposed so the config comment and this class cannot drift apart. */
    public static final int REFERENCE_TOTAL_TICKS = (int) (REF_PRE + REF_POST);

    /** Reference reveal tick, 1.6s. */
    public static final int REFERENCE_REVEAL_TICK = (int) REF_PRE;

    /**
     * Length of one full wing flap cycle, in ticks.
     *
     * <p>{@code animation.rasengan_dragon.fly} declares {@code animation_length = 1.9583} seconds;
     * 1.9583 x 20 = 39.1667 ticks. Kept as a float because it is not a whole number of ticks -
     * rounding it to 39 would drift the downbeat by a third of a tick per cycle.
     */
    public static final float WING_CYCLE_TICKS = 39.1667F;

    /**
     * Tick within the flap cycle at which the wings are sweeping downward fastest.
     *
     * <h2>Derivation</h2>
     * Taken from the {@code Lwing} rotation track of {@code animation.rasengan_dragon.fly}. Its Z
     * component runs -40 deg at t=0 through 0 at t=0.50s to +39.66 deg at t=0.958s, then back. The
     * extreme of angular velocity is therefore the zero crossing at <b>t = 0.500s = tick 10.0</b>,
     * where the track moves -5.221 -> +5.221 deg across 0.0834s (125 deg/s); the opposite crossing
     * at t = 1.500s is the same speed in the other direction.
     *
     * <p><b>Which crossing is the downstroke</b> is settled from geometry rather than assumed.
     * {@code tools/dragon/convert_gltf_to_geckolib.py} recovered GeckoLib's bake convention out of
     * {@code GeometryBone.bake} bytecode: {@code pivotX = -pivot.x} and
     * {@code baseRotZ = toRadians(+rotation.z)} - X is mirrored, Z is used as written. In the
     * geometry, {@code Lwing} pivots at x=+13 and its tip chain runs out to x=+119, so after the
     * X mirror the wing extends along <b>-X</b> in render space, giving a tip offset of dx=-106.
     * Rotating that about Z by theta moves the tip to dy = dx.sin(theta) = -106.sin(theta), so
     * <b>increasing Z lowers the tip</b>. The Z track increases across t=0 -> 0.958s, which makes
     * that half-cycle the downstroke and its midpoint, tick 10.0, the peak of the downbeat.
     *
     * <p>Cross-check: {@code Rwing} mirrors the track (z=+40 where Lwing is -40) and pivots at
     * x=-13 with its tip at x=-119, so after the mirror it extends along +X with dx=+106 and
     * dy = +106.sin(-theta) - the same physical direction. Both wings are up at t=0 and fully down
     * at t=0.958s, as a mirrored pair must be.
     *
     * <p><b>Not verified:</b> that the stroke reads as a downbeat on screen. The derivation is
     * geometric and follows the bake convention the converter proved by round-tripping against the
     * source glTF, but this environment has no display.
     */
    public static final float WING_DOWNBEAT_TICK = 10.0F;

    private SummonTimeline() {}

    /**
     * Wing phase, in ticks within the flap cycle, for a dragon of age {@code ageTicks}.
     *
     * <p>The summoned dragon is spawned on the cinematic's first tick, so its age <em>is</em> the
     * cinematic tick and the flap cycle can simply start at age 0. Both the forced animation phase in
     * {@code DragonEntity.registerControllers} and the dispersal instants in {@link #downbeatTick}
     * come from this one origin, which is what ties the two together.
     */
    public static float entranceWingPhase(float ageTicks) {
        float phase = ageTicks % WING_CYCLE_TICKS;
        return phase < 0.0F ? phase + WING_CYCLE_TICKS : phase;
    }

    /** Tick of the {@code n}th wing downbeat after spawn, {@code n} counting from 0. */
    public static float downbeatTick(int n) {
        return WING_DOWNBEAT_TICK + n * WING_CYCLE_TICKS;
    }

    /** Stage boundaries for one sequence, all in ticks from the start of the cinematic. */
    public record Stages(int total, float sealEnd, float eruptionEnd, float dispersalStart,
                         float dispersalEnd, boolean dispersalWingAligned) {

        /** Stage A: 0 -> sealEnd. */
        public float sealStart() {
            return 0.0F;
        }

        /** Stage B: sealEnd -> eruptionEnd. eruptionEnd is the reveal tick. */
        public float eruptionStart() {
            return sealEnd;
        }

        /** Stage C: the reveal beat. The dragon becomes visible and the roar lands. */
        public float revealStart() {
            return eruptionEnd;
        }

        /** Stage C ends where stage D begins - on the wing downbeat. */
        public float revealEnd() {
            return dispersalStart;
        }

        /** Stage E: residual wisps and ground fog fade; the camera eases back. */
        public float settleStart() {
            return dispersalEnd;
        }

        /** The reveal tick as an int, for the wire format and the server's own comparison. */
        public int revealTick() {
            return (int) eruptionEnd;
        }
    }

    /**
     * Derives the stage boundaries.
     *
     * <h2>Why stage D is snapped to a downbeat</h2>
     * The dispersal has to be <em>caused</em> by the wings, which means the impulse and the wing pose
     * must share an instant, not merely a neighbourhood. Rather than bending the animation to hit an
     * arbitrary tick, the stage boundary is moved to the nearest real downbeat - the animation stays
     * as authored and the timeline accommodates it. At the shipped defaults the nominal boundary is
     * tick 48 and the chosen downbeat is tick 49.17, a shift of 1.17 ticks.
     *
     * <p>An extreme configuration can leave no downbeat inside the sequence at all; the value is then
     * clamped to stay in order and {@link Stages#dispersalWingAligned()} reports false, so the
     * guarantee is never claimed when it does not hold.
     *
     * @param total  full sequence length in ticks
     * @param reveal tick at which the dragon becomes visible; clamped into {@code [1, total-1]} so a
     *               misconfiguration cannot collapse either half of the sequence
     */
    public static Stages of(int total, int reveal) {
        int safeTotal = Math.max(2, total);
        int safeReveal = Math.clamp(reveal, 1, safeTotal - 1);

        float preScale = safeReveal / REF_PRE;
        float postScale = (safeTotal - safeReveal) / REF_POST;

        float sealEnd = REF_SEAL * preScale;
        float nominalDispersal = safeReveal + REF_REVEAL * postScale;

        int beat = Math.max(0,
                Math.round((nominalDispersal - WING_DOWNBEAT_TICK) / WING_CYCLE_TICKS));
        float snapped = downbeatTick(beat);

        float lowerBound = safeReveal + 1.0F;
        float upperBound = safeTotal - 1.0F;
        float dispersalStart = Math.clamp(snapped, lowerBound, Math.max(lowerBound, upperBound));
        boolean aligned = Math.abs(dispersalStart - snapped) < 1.0E-3F;

        float dispersalEnd = Math.min(safeTotal,
                dispersalStart + REF_DISPERSAL * postScale);

        return new Stages(safeTotal, sealEnd, safeReveal, dispersalStart, dispersalEnd, aligned);
    }
}
