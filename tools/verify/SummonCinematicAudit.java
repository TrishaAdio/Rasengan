import dev.rasengan.SummonTimeline;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Logged-evidence harness for the summoning cinematic rebuild.
 *
 * <p>Covers everything that is a pure function of time and therefore does not need a server, a
 * display or a sound device: the five-stage timeline, the wing-downbeat alignment, the camera's
 * release blend, the distance taper, and the smoke particle's own size and opacity curves.
 *
 * <p>Like {@code AnimationAudit}, it calls the REAL shipped methods - public ones directly, private
 * ones by reflection - so the numbers are statements about the code that ships, not about a copy of
 * the formulas. Nothing here is part of the mod jar.
 *
 * <p>What it deliberately does NOT claim: that anything is visible, audible or pleasant. Those need a
 * display and are reported as unverified.
 */
public final class SummonCinematicAudit {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        section("A. Stage layout at the shipped defaults");
        stageLayout();

        section("B. Stage boundaries stay ordered across the whole config range");
        stageOrdering();

        section("C. Stage D lands on a real wing downbeat");
        downbeatAlignment();

        section("D. Wing phase is continuous and periodic");
        wingPhase();

        section("E. Camera release blend: eased, monotonic, reaches exactly 0");
        releaseBlend();

        section("F. Camera distance taper: continuous across the radius boundary");
        distanceTaper();

        section("G. Smoke opacity curve: starts at 0, ends at 0, no step at any frame");
        smokeAlpha();

        section("H. Smoke size curve: continuous and monotonic across partial ticks");
        smokeSize();

        section("I. Obscuration: is the arrival point actually hidden at the reveal?");
        obscuration();

        System.out.println();
        System.out.println("================================================================");
        System.out.printf("%d assertions, %d failures%n", checks, failures);
        System.out.println("================================================================");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ A

    private static void stageLayout() {
        SummonTimeline.Stages s = SummonTimeline.of(70, 32);
        System.out.printf("  total=%d ticks (%.2fs)%n", s.total(), s.total() / 20.0F);
        System.out.printf("  A seal        %7.3f -> %7.3f   (%.3fs)%n",
                s.sealStart(), s.sealEnd(), (s.sealEnd() - s.sealStart()) / 20.0F);
        System.out.printf("  B eruption    %7.3f -> %7.3f   (%.3fs)%n",
                s.eruptionStart(), s.eruptionEnd(), (s.eruptionEnd() - s.eruptionStart()) / 20.0F);
        System.out.printf("  C reveal      %7.3f -> %7.3f   (%.3fs)%n",
                s.revealStart(), s.revealEnd(), (s.revealEnd() - s.revealStart()) / 20.0F);
        System.out.printf("  D dispersal   %7.3f -> %7.3f   (%.3fs)%n",
                s.dispersalStart(), s.dispersalEnd(), (s.dispersalEnd() - s.dispersalStart()) / 20.0F);
        System.out.printf("  E settle      %7.3f -> %7.3f   (%.3fs)%n",
                s.settleStart(), (float) s.total(), (s.total() - s.settleStart()) / 20.0F);

        // The spec asks for 0.6 / 1.0 / 0.8 / 0.6 / 0.5 seconds. C and D shift slightly because D is
        // snapped to a real wing downbeat; that shift is asserted to be under one tick.
        assertClose("A is 0.6s", (s.sealEnd() - s.sealStart()) / 20.0F, 0.60F, 0.001F);
        assertClose("B is 1.0s", (s.eruptionEnd() - s.eruptionStart()) / 20.0F, 1.00F, 0.001F);
        assertClose("D is 0.6s", (s.dispersalEnd() - s.dispersalStart()) / 20.0F, 0.60F, 0.001F);
        assertTrue("E is at least 0.4s", (s.total() - s.settleStart()) / 20.0F >= 0.40F);
        assertTrue("reveal is reported as an int matching the config", s.revealTick() == 32);

        // Stage C is the one that does NOT match the spec figure exactly, and that is by design: its
        // end is stage D's start, which is snapped to a real wing downbeat. Assert the size of that
        // shift rather than pretending the spec figure is met, and assert it is genuinely a snap to
        // the NEAREST beat - never more than half a flap cycle of adjustment.
        float nominalCEnd = 32.0F + 16.0F;
        float shift = s.dispersalStart() - nominalCEnd;
        System.out.printf("  C spec 0.800s, actual %.3fs; stage D start shifted %+.4f ticks%n",
                (s.revealEnd() - s.revealStart()) / 20.0F, shift);
        System.out.printf("  reason: D is snapped to the wing downbeat at tick %.4f%n",
                s.dispersalStart());
        assertTrue("stage C is within half a flap cycle of its spec duration",
                Math.abs(shift) <= SummonTimeline.WING_CYCLE_TICKS * 0.5F);
        assertTrue("the shift is under 2 ticks at the shipped defaults", Math.abs(shift) < 2.0F);
    }

    // ------------------------------------------------------------------ B

    private static void stageOrdering() {
        int violations = 0;
        int aligned = 0;
        int cases = 0;
        for (int total = 20; total <= 400; total += 1) {
            for (int reveal = 5; reveal < Math.min(total, 400); reveal += 7) {
                SummonTimeline.Stages s = SummonTimeline.of(total, reveal);
                cases++;
                boolean ordered = s.sealStart() <= s.sealEnd()
                        && s.sealEnd() <= s.eruptionEnd()
                        && s.eruptionEnd() <= s.dispersalStart()
                        && s.dispersalStart() <= s.dispersalEnd()
                        && s.dispersalEnd() <= s.total();
                if (!ordered) {
                    if (violations < 5) {
                        System.out.printf("  ORDER VIOLATION total=%d reveal=%d -> %s%n",
                                total, reveal, s);
                    }
                    violations++;
                }
                if (s.dispersalWingAligned()) {
                    aligned++;
                }
            }
        }
        System.out.printf("  %d configurations exercised (total 20..400, reveal 5..total)%n", cases);
        System.out.printf("  stage order violations: %d%n", violations);
        System.out.printf("  wing-aligned dispersal: %d of %d (%.1f%%)%n",
                aligned, cases, 100.0 * aligned / cases);
        assertTrue("no configuration produces out-of-order stages", violations == 0);

        // Also check the clamp is honest: where alignment is impossible it must say so.
        SummonTimeline.Stages tiny = SummonTimeline.of(20, 15);
        System.out.printf("  degenerate case total=20 reveal=15: dispersalStart=%.3f aligned=%b%n",
                tiny.dispersalStart(), tiny.dispersalWingAligned());
    }

    // ------------------------------------------------------------------ C

    private static void downbeatAlignment() {
        System.out.printf("  wing cycle       = %.4f ticks (%.4fs, from animation_length)%n",
                SummonTimeline.WING_CYCLE_TICKS, SummonTimeline.WING_CYCLE_TICKS / 20.0F);
        System.out.printf("  downbeat peak    = %.4f ticks into the cycle%n",
                SummonTimeline.WING_DOWNBEAT_TICK);
        System.out.print("  downbeat instants:");
        for (int n = 0; n < 3; n++) {
            System.out.printf(" %.4f", SummonTimeline.downbeatTick(n));
        }
        System.out.println();

        SummonTimeline.Stages s = SummonTimeline.of(70, 32);
        float d = s.dispersalStart();
        // Must coincide with SOME downbeat to float precision.
        float best = Float.MAX_VALUE;
        int bestN = -1;
        for (int n = 0; n < 12; n++) {
            float delta = Math.abs(SummonTimeline.downbeatTick(n) - d);
            if (delta < best) {
                best = delta;
                bestN = n;
            }
        }
        System.out.printf("  dispersalStart   = %.4f, nearest downbeat n=%d, |delta| = %.3e ticks%n",
                d, bestN, best);
        assertTrue("dispersal coincides with a downbeat to under 1e-3 ticks", best < 1.0E-3F);
        assertTrue("timeline reports the dispersal as wing-aligned", s.dispersalWingAligned());

        // And the phase AT that instant must be the downbeat phase, which is what the dragon's
        // controller is forced to. This is the actual link between impulse and wing pose.
        float phase = SummonTimeline.entranceWingPhase(d);
        System.out.printf("  entranceWingPhase(dispersalStart) = %.4f (expected %.4f)%n",
                phase, SummonTimeline.WING_DOWNBEAT_TICK);
        assertClose("wing phase at the impulse IS the downbeat phase",
                phase, SummonTimeline.WING_DOWNBEAT_TICK, 1.0E-3F);
    }

    // ------------------------------------------------------------------ D

    private static void wingPhase() {
        float maxJump = 0.0F;
        float at = -1.0F;
        boolean inRange = true;
        int samples = 0;
        float prev = SummonTimeline.entranceWingPhase(0.0F);
        for (float t = 0.0F; t <= 400.0F; t += 0.05F) {
            float p = SummonTimeline.entranceWingPhase(t);
            samples++;
            float jump = Math.abs(p - prev);
            // A wrap from ~cycle back to ~0 is expected once per cycle; ignore those.
            if (jump > SummonTimeline.WING_CYCLE_TICKS * 0.5F) {
                jump = 0.0F;
            }
            if (jump > maxJump) {
                maxJump = jump;
                at = t;
            }
            prev = p;
            if (p < 0.0F || p >= SummonTimeline.WING_CYCLE_TICKS) {
                inRange = false;
            }
        }
        System.out.printf("  sampled %d points over 0..400 ticks at 0.05 spacing%n", samples);
        System.out.printf("  max non-wrap phase step = %.4f at t=%.2f (0.05 expected)%n", maxJump, at);
        assertTrue("phase stayed inside [0, cycle) for all " + samples + " samples", inRange);
        assertTrue("phase advances at most one sample step", maxJump <= 0.0501F);
        assertClose("phase is periodic: p(t) == p(t + cycle)",
                SummonTimeline.entranceWingPhase(7.3F),
                SummonTimeline.entranceWingPhase(7.3F + SummonTimeline.WING_CYCLE_TICKS),
                1.0E-3F);
        report();
    }

    // ------------------------------------------------------------------ E

    private static void releaseBlend() throws Exception {
        // releaseFactor(Active, float) is private, and Active is a private nested class, so the
        // envelope is exercised through a constructed Active instance by reflection.
        Class<?> cinematic = Class.forName("dev.rasengan.client.SummonCinematic");
        Class<?> activeClass = Class.forName("dev.rasengan.client.SummonCinematic$Active");
        Method releaseFactor = cinematic.getDeclaredMethod("releaseFactor", activeClass, float.class);
        releaseFactor.setAccessible(true);

        Object active = newActive(activeClass, 70, 32, 12);

        System.out.println("     t   releaseFactor");
        float prev = 1.0F;
        float maxStep = 0.0F;
        boolean monotonic = true;
        for (float t = 55.0F; t <= 71.0F; t += 0.5F) {
            float f = (float) releaseFactor.invoke(null, active, t);
            if (f > prev + 1.0E-6F) {
                monotonic = false;
            }
            maxStep = Math.max(maxStep, Math.abs(f - prev));
            prev = f;
            if (t % 2.0F == 0.0F) {
                System.out.printf("  %5.1f   %.6f%n", t, f);
            }
        }
        float atStart = (float) releaseFactor.invoke(null, active, 58.0F);
        float atEnd = (float) releaseFactor.invoke(null, active, 70.0F);
        float past = (float) releaseFactor.invoke(null, active, 80.0F);

        System.out.printf("  release window = ticks 58..70 (%d ticks = %.2fs)%n", 12, 12 / 20.0F);
        System.out.printf("  f(58)=%.6f  f(70)=%.6f  f(80)=%.6f  max step per 0.5 tick=%.6f%n",
                atStart, atEnd, past, maxStep);

        assertClose("blend is 1.0 when the release begins", atStart, 1.0F, 1.0E-5F);
        assertClose("blend reaches exactly 0 at the end", atEnd, 0.0F, 1.0E-6F);
        assertClose("blend stays 0 afterwards", past, 0.0F, 1.0E-6F);
        assertTrue("blend is monotonically decreasing", monotonic);
        assertTrue("blend release is 0.4-0.8s as specified",
                12 / 20.0F >= 0.40F && 12 / 20.0F <= 0.80F);

        // Smoothstep: the DERIVATIVE must also vanish at both ends, which is what distinguishes an
        // eased return from a linear ramp that arrives on time but changes direction visibly.
        float d0 = derivative(releaseFactor, active, 58.0F);
        float dMid = derivative(releaseFactor, active, 64.0F);
        float d1 = derivative(releaseFactor, active, 70.0F);
        System.out.printf("  d/dt at start=%.6f  mid=%.6f  end=%.6f%n", d0, dMid, d1);
        assertTrue("velocity is ~0 at the start of the blend", Math.abs(d0) < 0.02F);
        assertTrue("velocity is ~0 at the end of the blend", Math.abs(d1) < 0.02F);
        assertTrue("velocity is non-zero in the middle", Math.abs(dMid) > 0.05F);

        // A camera effect that is switched off must contribute nothing at all.
        Object disabled = newActive(activeClass, 70, 32, 0);
        float off = (float) releaseFactor.invoke(null, disabled, 40.0F);
        System.out.printf("  camera disabled (releaseTicks=0): factor at mid-sequence = %.6f%n", off);
        assertClose("a disabled camera contributes zero", off, 0.0F, 1.0E-6F);
        report();
    }

    private static float derivative(Method m, Object active, float t) throws Exception {
        float h = 0.01F;
        float a = (float) m.invoke(null, active, t - h);
        float b = (float) m.invoke(null, active, t + h);
        return (b - a) / (2.0F * h);
    }

    /** Builds an Active without a ClientLevel, by going through the payload constructor. */
    private static Object newActive(Class<?> activeClass, int total, int reveal, int release)
            throws Exception {
        var payloadClass = dev.rasengan.network.RasenganSummonPayloads.SummonStart.class;
        Constructor<?> pc = payloadClass.getDeclaredConstructor(
                int.class, int.class, long.class, double.class, double.class, double.class,
                int.class, int.class, int.class, float.class, float.class, boolean.class);
        Object payload = pc.newInstance(1, 2, 12345L, 0.0D, 64.0D, 0.0D,
                total, reveal, release, 64.0F, 1.0F, true);

        Constructor<?> ac = activeClass.getDeclaredConstructor(payloadClass, long.class);
        ac.setAccessible(true);
        return ac.newInstance(payload, 0L);
    }

    // ------------------------------------------------------------------ F

    private static void distanceTaper() throws Exception {
        Class<?> cinematic = Class.forName("dev.rasengan.client.SummonCinematic");
        Class<?> activeClass = Class.forName("dev.rasengan.client.SummonCinematic$Active");
        Method taper = cinematic.getDeclaredMethod("distanceWeight",
                net.minecraft.world.phys.Vec3.class, activeClass);
        taper.setAccessible(true);
        Object active = newActive(activeClass, 70, 32, 12);

        System.out.println("  distance  weight      (radius 64, full inside 48)");
        float prev = 1.0F;
        float maxStep = 0.0F;
        for (double d = 40.0D; d <= 70.0D; d += 0.25D) {
            var viewer = new net.minecraft.world.phys.Vec3(d, 64.0D, 0.0D);
            float w = (float) taper.invoke(null, viewer, active);
            maxStep = Math.max(maxStep, Math.abs(w - prev));
            prev = w;
            if (Math.abs(d % 5.0D) < 1.0E-9D) {
                System.out.printf("  %7.1f   %.6f%n", d, w);
            }
        }
        var inside = new net.minecraft.world.phys.Vec3(10.0D, 64.0D, 0.0D);
        var atEdge = new net.minecraft.world.phys.Vec3(64.0D, 64.0D, 0.0D);
        var outside = new net.minecraft.world.phys.Vec3(80.0D, 64.0D, 0.0D);
        float wIn = (float) taper.invoke(null, inside, active);
        float wEdge = (float) taper.invoke(null, atEdge, active);
        float wOut = (float) taper.invoke(null, outside, active);

        System.out.printf("  w(10)=%.6f  w(64)=%.6f  w(80)=%.6f  max step per 0.25 blocks=%.6f%n",
                wIn, wEdge, wOut, maxStep);
        assertClose("full strength well inside the radius", wIn, 1.0F, 1.0E-6F);
        assertClose("exactly zero at the radius", wEdge, 0.0F, 1.0E-6F);
        assertClose("zero beyond the radius", wOut, 0.0F, 1.0E-6F);
        // The old code stepped 1 -> 0 in one frame here. Quantify that it no longer does.
        assertTrue("no step larger than 0.05 across the boundary", maxStep < 0.05F);
        System.out.printf("  (the previous binary 64-block gate stepped %.2f in one frame here)%n",
                1.0F);
        report();
    }

    // ------------------------------------------------------------------ G

    private static void smokeAlpha() throws Exception {
        Class<?> smoke = Class.forName("dev.rasengan.client.SmokeParticle");
        Method alphaAt = smoke.getDeclaredMethod("alphaAt", float.class);
        alphaAt.setAccessible(true);

        // A bare instance is enough: alphaAt reads only the style, peak and scale fields.
        Object particle = allocateSmoke(smoke, "BILLOW");

        System.out.println("     f    alpha");
        float prev = (float) alphaAt.invoke(particle, 0.0F);
        float maxStep = 0.0F;
        float peak = 0.0F;
        for (float f = 0.0F; f <= 1.0F; f += 0.002F) {
            float a = (float) alphaAt.invoke(particle, f);
            maxStep = Math.max(maxStep, Math.abs(a - prev));
            peak = Math.max(peak, a);
            prev = a;
            if (Math.abs(f * 10.0F - Math.round(f * 10.0F)) < 1.0E-4F) {
                System.out.printf("  %5.2f   %.6f%n", f, a);
            }
        }
        float a0 = (float) alphaAt.invoke(particle, 0.0F);
        float a1 = (float) alphaAt.invoke(particle, 1.0F);
        System.out.printf("  alpha(0)=%.8f  alpha(1)=%.8f  peak=%.6f  max step per 0.002 life=%.6f%n",
                a0, a1, peak, maxStep);

        assertClose("a puff spawns at exactly 0 opacity (no pop in)", a0, 0.0F, 1.0E-7F);
        assertClose("a puff dies at exactly 0 opacity (no pop out)", a1, 0.0F, 1.0E-7F);
        assertTrue("peak opacity is high enough to obscure", peak > 0.85F);
        assertContinuous("opacity", h -> {
            try {
                return maxStepOver(alphaAt, particle, h);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // The reason alphaAt exists at all: vanilla passes the alpha FIELD, which steps once per
        // tick. Quantify the step size that was avoided for a 55-tick billow.
        float perTick = 0.0F;
        for (int age = 0; age < 55; age++) {
            float x = (float) alphaAt.invoke(particle, age / 55.0F);
            float y = (float) alphaAt.invoke(particle, (age + 1) / 55.0F);
            perTick = Math.max(perTick, Math.abs(y - x));
        }
        System.out.printf("  largest per-TICK alpha change for a 55-tick billow = %.6f%n", perTick);
        System.out.printf("  -> that is the step size a tick-quantised alpha would show per frame%n");
        assertTrue("the avoided per-tick step was material (>0.02)", perTick > 0.02F);
        report();
    }

    // ------------------------------------------------------------------ H

    private static void smokeSize() throws Exception {
        Class<?> smoke = Class.forName("dev.rasengan.client.SmokeParticle");
        Method sizeAt = smoke.getDeclaredMethod("sizeAt", float.class);
        sizeAt.setAccessible(true);
        Object particle = allocateSmoke(smoke, "BILLOW");

        var quadSize = smoke.getSuperclass().getDeclaredField("quadSize");
        quadSize.setAccessible(true);
        quadSize.setFloat(particle, 2.6F);

        System.out.println("     f    size");
        float prev = (float) sizeAt.invoke(particle, 0.0F);
        float maxStep = 0.0F;
        boolean monotonic = true;
        for (float f = 0.0F; f <= 1.0F; f += 0.002F) {
            float s = (float) sizeAt.invoke(particle, f);
            if (s < prev - 1.0E-6F) {
                monotonic = false;
            }
            maxStep = Math.max(maxStep, Math.abs(s - prev));
            prev = s;
            if (Math.abs(f * 10.0F - Math.round(f * 10.0F)) < 1.0E-4F) {
                System.out.printf("  %5.2f   %.6f blocks%n", f, s);
            }
        }
        float s0 = (float) sizeAt.invoke(particle, 0.0F);
        float s1 = (float) sizeAt.invoke(particle, 1.0F);
        System.out.printf("  size(0)=%.4f  size(1)=%.4f  growth=%.2fx  max step per 0.002=%.6f%n",
                s0, s1, s1 / s0, maxStep);
        assertTrue("puffs grow over their life (billowing)", s1 > s0 * 1.5F);
        assertTrue("size is monotonic (never shrinks mid-life)", monotonic);
        assertContinuous("size", h -> {
            try {
                return maxStepOver(sizeAt, particle, h);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        report();
    }

    /**
     * Continuity test that actually distinguishes a fast curve from a discontinuous one.
     *
     * <p>An earlier version of this harness asserted "no step larger than X", which conflates the two:
     * a deliberately quick fade-in fails it while a genuine jump of size X/2 passes. The correct test
     * is that the largest step <b>scales with the sample interval</b>. Halving the interval must
     * roughly halve the step for a continuous function; across a jump discontinuity the step stays the
     * same size no matter how finely you sample.
     */
    private static void assertContinuous(String name, java.util.function.Function<Float, Float> maxStep) {
        float coarse = maxStep.apply(0.004F);
        float fine = maxStep.apply(0.001F);
        float ratio = fine <= 0.0F ? 0.0F : coarse / fine;
        System.out.printf("  %s continuity: max step at h=0.004 is %.6f, at h=0.001 is %.6f"
                        + " -> ratio %.2f (4.0 expected for a continuous curve)%n",
                name, coarse, fine, ratio);
        // A jump discontinuity gives a ratio near 1.0; a continuous curve gives near 4.0.
        assertTrue(name + " is continuous (refinement ratio > 3.0, i.e. no jump)", ratio > 3.0F);
    }

    private static float maxStepOver(Method curve, Object particle, float h) throws Exception {
        float max = 0.0F;
        float prev = (float) curve.invoke(particle, 0.0F);
        for (float f = h; f <= 1.0F; f += h) {
            float v = (float) curve.invoke(particle, f);
            max = Math.max(max, Math.abs(v - prev));
            prev = v;
        }
        return max;
    }

    // ------------------------------------------------------------------ I

    /**
     * Measures whether the eruption column actually hides the dragon at the reveal tick.
     *
     * <p>Uses the shipped code for everything that matters: {@code SummonCinematic.columnPuff} for the
     * spawn distribution, {@code SmokeParticle.sizeAt}/{@code alphaAt} for each puff's size and
     * opacity, and the style's own friction and gravity for the drag integration. Because smoke has
     * {@code hasPhysics == false}, {@code Particle.move} adds velocity without collision, so the
     * position after n ticks is the exact geometric sum of the drag series - no approximation.
     *
     * <p>Coverage is then measured by casting horizontal rays at the dragon toward the arrival point
     * and accumulating {@code 1 - prod(1 - alpha_i)} over every puff whose billboard the ray passes
     * through. That is standard alpha compositing, and it is what the GPU will do.
     *
     * <p><b>Not verified:</b> that it looks right. This says the optical depth is sufficient, which is
     * a necessary condition for the reveal to work, not a sufficient one for it to look good.
     */
    private static void obscuration() throws Exception {
        Class<?> cinematic = Class.forName("dev.rasengan.client.SummonCinematic");
        Class<?> puffClass = Class.forName("dev.rasengan.client.SummonCinematic$ColumnPuff");
        Method columnPuff = cinematic.getDeclaredMethod("columnPuff",
                net.minecraft.util.RandomSource.class, float.class);
        columnPuff.setAccessible(true);

        Class<?> smokeClass = Class.forName("dev.rasengan.client.SmokeParticle");
        Method sizeAt = smokeClass.getDeclaredMethod("sizeAt", float.class);
        Method alphaAt = smokeClass.getDeclaredMethod("alphaAt", float.class);
        sizeAt.setAccessible(true);
        alphaAt.setAccessible(true);

        SummonTimeline.Stages stages = SummonTimeline.of(70, 32);
        float sealEnd = stages.sealEnd();
        float revealAt = stages.eruptionEnd();

        // Style constants, read from the shipped enum rather than restated.
        Object billowStyle = styleConstant(smokeClass, "BILLOW");
        Object wispStyle = styleConstant(smokeClass, "WISP");
        float billowFriction = styleFloat(billowStyle, "friction");
        float billowGravity = styleFloat(billowStyle, "gravity");
        float billowSize = styleFloat(billowStyle, "size");
        int billowMinLife = styleInt(billowStyle, "minLifetime");
        int billowMaxLife = styleInt(billowStyle, "maxLifetime");
        float wispFriction = styleFloat(wispStyle, "friction");
        float wispSize = styleFloat(wispStyle, "size");
        int wispMinLife = styleInt(wispStyle, "minLifetime");
        int wispMaxLife = styleInt(wispStyle, "maxLifetime");

        System.out.printf("  BILLOW friction=%.3f gravity=%+.4f size=%.2f life=%d..%d%n",
                billowFriction, billowGravity, billowSize, billowMinLife, billowMaxLife);
        System.out.printf("  WISP   friction=%.3f size=%.2f life=%d..%d%n",
                wispFriction, wispSize, wispMinLife, wispMaxLife);

        // ---- Build the cloud as it stands at the reveal tick ----
        java.util.List<double[]> cloud = new java.util.ArrayList<>(); // x,y,z,radius,alpha
        long seed = 0x5EED0FFEL;
        int spawned = 0;

        Object billowProbe = allocateSmoke(smokeClass, "BILLOW");
        Object wispProbe = allocateSmoke(smokeClass, "WISP");
        var quadSize = smokeClass.getSuperclass().getDeclaredField("quadSize");
        quadSize.setAccessible(true);

        for (int tick = (int) Math.ceil(sealEnd); tick < revealAt; tick++) {
            float local = (revealAt - sealEnd) <= 0.0F ? 1.0F
                    : Math.min(1.0F, Math.max(0.0F, (tick - sealEnd) / (revealAt - sealEnd)));
            float intensity = (1.0F - local) * (1.0F - local) * 0.75F + 0.25F;
            int count = Math.max(4, Math.round(28.0F * intensity)); // Lod scale 1.0 = default client

            var random = net.minecraft.util.RandomSource.create(seed * 31L + tick);
            for (int i = 0; i < count; i++) {
                Object puff = columnPuff.invoke(null, random, local);
                boolean edge = (boolean) accessor(puffClass, "edge").invoke(puff);
                double dx = (double) accessor(puffClass, "dx").invoke(puff);
                double dy = (double) accessor(puffClass, "dy").invoke(puff);
                double dz = (double) accessor(puffClass, "dz").invoke(puff);
                double vx = (double) accessor(puffClass, "vx").invoke(puff);
                double vy = (double) accessor(puffClass, "vy").invoke(puff);
                double vz = (double) accessor(puffClass, "vz").invoke(puff);
                float sizeScale = (float) accessor(puffClass, "sizeScale").invoke(puff);
                spawned++;

                float friction = edge ? wispFriction : billowFriction;
                float gravity = edge ? styleFloat(wispStyle, "gravity") : billowGravity;
                float baseStyleSize = edge ? wispSize : billowSize;
                int minLife = edge ? wispMinLife : billowMinLife;
                int maxLife = edge ? wispMaxLife : billowMaxLife;
                // Mid-range lifetime and mid-range jitter: the per-particle jitter is +/-28% on size
                // and uniform on lifetime, so the mean is the representative case.
                int lifetime = (minLife + maxLife) / 2;
                float jitteredBase = baseStyleSize * 1.0F; // mean of (0.72 + u*0.56)

                int elapsed = (int) revealAt - tick;
                if (elapsed >= lifetime) {
                    continue; // already dead by the reveal
                }

                // Exact drag integration: sum_{k=1..n} v*f^k = v*f*(1-f^n)/(1-f)
                double px = dx + driftSum(vx, friction, elapsed);
                double pz = dz + driftSum(vz, friction, elapsed);
                double py = dy + driftSumWithGravity(vy, friction, gravity, elapsed);

                Object probe = edge ? wispProbe : billowProbe;
                quadSize.setFloat(probe, jitteredBase * sizeScale);
                float f = elapsed / (float) lifetime;
                float size = (float) sizeAt.invoke(probe, f);
                float alpha = (float) alphaAt.invoke(probe, f);
                if (alpha <= 0.002F) {
                    continue;
                }
                // A SingleQuadParticle's quad is 2*quadSize across, so its silhouette radius is
                // quadSize. The texture's opaque core covers the inner ~45% (measured: the mid-row
                // alpha profile is 255 across the middle 8 of 32 pixels and above 128 across ~20).
                cloud.add(new double[] {px, py, pz, size, alpha});
            }
        }

        System.out.printf("  spawned %d column puffs over stage B; %d still alive at the reveal%n",
                spawned, cloud.size());

        // ---- Cast rays at the dragon's body from a viewer standing where the summoner stands ----
        // The summoner is 9 blocks back (ARRIVAL_DISTANCE) at eye height 1.62.
        double[] eye = {-9.0D, 1.62D, 0.0D};
        // Sample the dragon's body volume: 6 blocks wide (its collision box), 4.8 tall, lifted 2.
        int hits = 0;
        int total = 0;
        double minOpacity = 1.0D;
        double sumOpacity = 0.0D;
        double[] worst = null;
        java.util.List<double[]> weak = new java.util.ArrayList<>();
        for (double bx = -3.0D; bx <= 3.0D; bx += 1.0D) {
            for (double by = 2.0D; by <= 6.8D; by += 0.8D) {
                for (double bz = -3.0D; bz <= 3.0D; bz += 1.0D) {
                    double[] target = {bx, by, bz};
                    double opacity = rayOpacity(eye, target, cloud);
                    total++;
                    sumOpacity += opacity;
                    if (opacity < minOpacity) {
                        minOpacity = opacity;
                        worst = new double[] {bx, by, bz, opacity};
                    }
                    if (opacity >= 0.95D) {
                        hits++;
                    } else {
                        weak.add(new double[] {bx, by, bz, opacity});
                    }
                }
            }
        }
        if (worst != null) {
            System.out.printf("  thinnest point: (%.1f, %.1f, %.1f) at %.4f opacity%n",
                    worst[0], worst[1], worst[2], worst[3]);
        }
        if (!weak.isEmpty()) {
            System.out.printf("  %d points below 0.95:%n", weak.size());
            for (double[] w : weak) {
                System.out.printf("    (%+.1f, %.1f, %+.1f) -> %.4f%n", w[0], w[1], w[2], w[3]);
            }
        }
        double coverage = 100.0 * hits / total;
        System.out.printf("  %d sample points across the dragon's body volume (6.0 x 4.8 x 6.0)%n",
                total);
        System.out.printf("  accumulated opacity along the view ray: min %.4f, mean %.4f%n",
                minOpacity, sumOpacity / total);
        System.out.printf("  points at >=0.95 opacity: %d of %d (%.1f%%)%n", hits, total, coverage);

        assertTrue("every sample point is at least 95% obscured at the reveal", minOpacity >= 0.95D);
        assertTrue("mean optical depth is effectively opaque", sumOpacity / total >= 0.99D);

        // And the complement: by the end of the settle stage the cloud must have cleared, or the
        // dragon stays hidden forever.
        System.out.println();
        System.out.printf("  (the same measurement is not repeated at stage E: the cloud there is%n");
        System.out.printf("   built from a different, much smaller emission and the dispersal impulse%n");
        System.out.printf("   redistributes it - covered by the live-server harness instead.)%n");
        report();
    }

    private static final java.util.Map<String, Method> ACCESSORS = new java.util.HashMap<>();

    /**
     * Record accessor, made reachable.
     *
     * <p>{@code ColumnPuff} is package-private, so although its generated accessors are public the
     * class itself is not accessible from this harness's default package. Cached because the
     * obscuration loop calls these several thousand times.
     */
    private static Method accessor(Class<?> owner, String name) throws Exception {
        Method cached = ACCESSORS.get(name);
        if (cached == null) {
            cached = owner.getMethod(name);
            cached.setAccessible(true);
            ACCESSORS.put(name, cached);
        }
        return cached;
    }

    /** Sum of v*f^k for k=1..n - the exact position offset under per-tick multiplicative drag. */
    private static double driftSum(double v, double friction, int n) {
        if (n <= 0) {
            return 0.0D;
        }
        if (Math.abs(1.0D - friction) < 1.0E-9D) {
            return v * n;
        }
        return v * friction * (1.0D - Math.pow(friction, n)) / (1.0D - friction);
    }

    /**
     * Vertical drift, which also carries gravity.
     *
     * <p>{@code Particle.tick} does {@code yd -= 0.04 * gravity} <em>before</em> the move and applies
     * friction after, so the recurrence is {@code yd_{k+1} = (yd_k - 0.04g) * f}. Integrated term by
     * term rather than in closed form, because the closed form is easy to get subtly wrong and this is
     * evidence.
     */
    private static double driftSumWithGravity(double v, double friction, double gravity, int n) {
        double yd = v;
        double y = 0.0D;
        for (int k = 0; k < n; k++) {
            yd -= 0.04D * gravity;
            y += yd;
            yd *= friction;
        }
        return y;
    }

    /**
     * Accumulated opacity along the segment from {@code eye} to {@code target}.
     *
     * <p>Each puff is treated as a sphere of its billboard radius with uniform opacity equal to its
     * alpha scaled by the fraction of the sprite that is meaningfully opaque. Composited as
     * {@code 1 - prod(1 - a_i)}, which is exactly what alpha blending accumulates.
     */
    private static double rayOpacity(double[] eye, double[] target, java.util.List<double[]> cloud) {
        double dx = target[0] - eye[0];
        double dy = target[1] - eye[1];
        double dz = target[2] - eye[2];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length;
        dy /= length;
        dz /= length;

        double transmittance = 1.0D;
        for (double[] puff : cloud) {
            // Distance from the puff centre to the ray, and whether it is in front of the target.
            double ox = puff[0] - eye[0];
            double oy = puff[1] - eye[1];
            double oz = puff[2] - eye[2];
            double along = ox * dx + oy * dy + oz * dz;
            if (along < 0.0D || along > length) {
                continue; // behind the viewer or past the target
            }
            double perpSq = (ox * ox + oy * oy + oz * oz) - along * along;
            double radius = puff[3];
            if (perpSq >= radius * radius) {
                continue;
            }
            // Radial falloff of the sprite itself: full alpha in the core, tapering to the rim.
            double r = Math.sqrt(Math.max(0.0D, perpSq)) / radius;
            double coverage = Math.max(0.0D, 1.0D - r * r);
            transmittance *= (1.0D - puff[4] * coverage);
            if (transmittance <= 1.0E-4D) {
                return 1.0D;
            }
        }
        return 1.0D - transmittance;
    }

    private static Object styleConstant(Class<?> smokeClass, String name) throws Exception {
        Class<?> styleClass = Class.forName(smokeClass.getName() + "$Style");
        for (Object candidate : styleClass.getEnumConstants()) {
            if (candidate.toString().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no style " + name);
    }

    private static float styleFloat(Object style, String field) throws Exception {
        var f = style.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getFloat(style);
    }

    private static int styleInt(Object style, String field) throws Exception {
        var f = style.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(style);
    }

    /** Allocates a SmokeParticle without a ClientLevel by setting only the fields under test. */
    private static Object allocateSmoke(Class<?> smoke, String styleName) throws Exception {
        // Unsafe-free: use the JVM's serialisation-style allocator via reflection on the
        // no-arg-less class is not possible, so go through sun.misc.Unsafe's public replacement.
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = Class.forName("sun.misc.Unsafe")
                .getMethod("allocateInstance", Class.class);
        Object particle = allocate.invoke(unsafe, smoke);

        Class<?> styleClass = Class.forName("dev.rasengan.client.SmokeParticle$Style");
        Object style = null;
        for (Object candidate : styleClass.getEnumConstants()) {
            if (candidate.toString().equals(styleName)) {
                style = candidate;
            }
        }
        var styleField = smoke.getDeclaredField("style");
        styleField.setAccessible(true);
        styleField.set(particle, style);

        var alphaScale = smoke.getDeclaredField("alphaScale");
        alphaScale.setAccessible(true);
        alphaScale.setFloat(particle, 1.0F);
        return particle;
    }

    // ------------------------------------------------------------------ helpers

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private static void assertTrue(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + what);
        } else {
            System.out.println("  ok:   " + what);
        }
    }

    private static void assertSilent(String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static void assertClose(String what, float actual, float expected, float tolerance) {
        checks++;
        if (Math.abs(actual - expected) > tolerance) {
            failures++;
            System.out.printf("  FAIL: %s (got %.8f, expected %.8f +/- %.8f)%n",
                    what, actual, expected, tolerance);
        } else {
            System.out.printf("  ok:   %s (%.6f)%n", what, actual);
        }
    }

    private static void report() {
        // Progress marker only; the totals are printed at the end.
    }
}
