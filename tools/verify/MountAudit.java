import dev.rasengan.DragonAnchor;
import dev.rasengan.server.DragonRideControl;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Logged-evidence harness for the dragon mount/ride system.
 *
 * <p>Everything here is a pure function, so it needs no server, no display and no rider: the head
 * geometry, the launch curve, the turn-rate limiter, the bank easing, and the double-tap state machine.
 * It calls the REAL shipped methods so the numbers describe the code that ships.
 *
 * <p>What it deliberately does NOT claim: that riding feels good, that the rider is drawn standing on the
 * head, or that steering looks smooth on screen. Those need a display.
 */
public final class MountAudit {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        section("A. Head geometry comes from the asset");
        headGeometry();

        section("B. The head region is separate from the body, and hits only the head");
        headRayTests();

        section("C. Basis is orthonormal and the model/world transform round-trips");
        basisTests();

        section("D. Rider anchor tracks the animated head");
        anchorTracking();

        section("E. Launch curve: eased, no snap, integrates to the configured height");
        launchCurve();

        section("F. Turn-rate limiting never exceeds the cap and wraps through 180");
        turnRate();

        section("G. Bank angle: eased, symmetric, saturating");
        banking();

        section("H. Double-tap detection is edge-triggered and windowed");
        doubleTap();

        System.out.println();
        System.out.println("================================================================");
        System.out.printf("%d assertions, %d failures%n", checks, failures);
        System.out.println("================================================================");
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ A

    private static void headGeometry() {
        var box = DragonAnchor.headBoxModel();
        System.out.printf("  head box (model space, blocks): x[%.5f,%.5f] y[%.5f,%.5f] z[%.5f,%.5f]%n",
                box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
        System.out.printf("  size: %.4f wide x %.4f tall x %.4f deep%n",
                box.getXsize(), box.getYsize(), box.getZsize());

        Vec3 rest = DragonAnchor.riderPointRest();
        System.out.printf("  rider anchor (rest pose): up %.5f, forward %.5f%n", rest.y, rest.z);

        // The dragon's collision box is 6.0 x 5.0, so it reaches 3.0 blocks forward. The head is
        // further out than that, which is the whole reason mounting cannot use vanilla attack targeting.
        double headForward = -((box.minZ + box.maxZ) / 2.0D);
        System.out.printf("  head centre is %.4f blocks forward; the collision box reaches 3.0%n",
                headForward);
        assertTrue("the head sits OUTSIDE the 6.0-wide collision box, so vanilla picking cannot see it",
                headForward > 3.0D);
        assertTrue("the head box is much smaller than the body (under 2 blocks in every axis)",
                box.getXsize() < 2.0D && box.getYsize() < 2.0D && box.getZsize() < 2.0D);
        assertTrue("the rider anchor is inside the head box vertically",
                rest.y > box.minY && rest.y <= box.maxY);
        assertTrue("the rider anchor is NOT at the horn tips (below the box top)",
                rest.y < box.maxY - 0.5D);
    }

    // ------------------------------------------------------------------ B

    private static void headRayTests() {
        Vec3 pos = new Vec3(0.0D, 64.0D, 0.0D);
        float yaw = 0.0F; // facing +Z
        float pitch = 0.0F;
        float age = 0.0F;

        // The head at yaw 0 is ~4.22 blocks along +Z and ~4.25 up. Stand in front of it and look at it.
        Vec3 eyeFront = new Vec3(0.0D, 68.25D, 9.0D);
        boolean hitHead = DragonAnchor.rayHitsHead(pos, yaw, pitch, age, false,
                eyeFront, new Vec3(0.0D, 0.0D, -1.0D), 6.0D);
        System.out.printf("  looking level at the head from 4.8 blocks in front: %s%n", hitHead);
        assertTrue("a ray along the head's axis hits it", hitHead);

        // Same spot, but looking at the BODY: the body centre is at the entity origin, well below/behind.
        boolean hitBodyAim = DragonAnchor.rayHitsHead(pos, yaw, pitch, age, false,
                new Vec3(0.0D, 65.0D, 9.0D), new Vec3(0.0D, 0.0D, -1.0D), 6.0D);
        System.out.printf("  looking level at the body from the same distance:  %s%n", hitBodyAim);
        assertTrue("aiming at the body does NOT register as the head", !hitBodyAim);

        // Behind the dragon, where the tail is.
        boolean hitBehind = DragonAnchor.rayHitsHead(pos, yaw, pitch, age, false,
                new Vec3(0.0D, 68.25D, -9.0D), new Vec3(0.0D, 0.0D, 1.0D), 6.0D);
        System.out.printf("  looking from behind (tail end):                    %s%n", hitBehind);
        assertTrue("aiming from behind does NOT hit the head", !hitBehind);

        // Out of reach.
        boolean outOfReach = DragonAnchor.rayHitsHead(pos, yaw, pitch, age, false,
                new Vec3(0.0D, 68.25D, 40.0D), new Vec3(0.0D, 0.0D, -1.0D), 6.0D);
        System.out.printf("  correct aim but 35 blocks away:                    %s%n", outOfReach);
        assertTrue("reach is enforced", !outOfReach);

        // The region must rotate with the dragon: the same world ray must MISS once it turns away.
        boolean afterTurn = DragonAnchor.rayHitsHead(pos, 90.0F, pitch, age, false,
                eyeFront, new Vec3(0.0D, 0.0D, -1.0D), 6.0D);
        System.out.printf("  same ray after the dragon yaws 90 degrees:         %s%n", afterTurn);
        assertTrue("the head region rotates with the body rather than being world-aligned", !afterTurn);

        // And should hit again from the new front.
        boolean newFront = DragonAnchor.rayHitsHead(pos, 90.0F, pitch, age, false,
                new Vec3(-9.0D, 68.25D, 0.0D), new Vec3(1.0D, 0.0D, 0.0D), 6.0D);
        System.out.printf("  aiming at the new front after that yaw:            %s%n", newFront);
        assertTrue("the head is hittable from wherever it now points", newFront);

        // Sweep: how much of a horizontal fan around the head registers? Should be a narrow window.
        int hits = 0;
        int samples = 0;
        for (double dx = -8.0D; dx <= 8.0D; dx += 0.25D) {
            Vec3 dir = new Vec3(dx, 0.0D, -9.0D).normalize();
            samples++;
            if (DragonAnchor.rayHitsHead(pos, yaw, pitch, age, false,
                    new Vec3(0.0D, 68.25D, 9.0D), dir, 12.0D)) {
                hits++;
            }
        }
        System.out.printf("  horizontal fan test: %d of %d directions hit (%.1f%%)%n",
                hits, samples, 100.0 * hits / samples);
        assertTrue("the head is a narrow target, not a wide one (under 30% of the fan)",
                hits > 0 && hits < samples * 0.30);
    }

    // ------------------------------------------------------------------ C

    private static void basisTests() {
        double worstOrtho = 0.0D;
        double worstNorm = 0.0D;
        double worstRound = 0.0D;
        for (float yaw = -180.0F; yaw <= 180.0F; yaw += 7.0F) {
            for (float pitch = -80.0F; pitch <= 80.0F; pitch += 11.0F) {
                var basis = DragonAnchor.basis(yaw, pitch);
                worstNorm = Math.max(worstNorm, Math.abs(basis.forward().length() - 1.0D));
                worstNorm = Math.max(worstNorm, Math.abs(basis.up().length() - 1.0D));
                worstNorm = Math.max(worstNorm, Math.abs(basis.right().length() - 1.0D));
                worstOrtho = Math.max(worstOrtho, Math.abs(basis.forward().dot(basis.up())));
                worstOrtho = Math.max(worstOrtho, Math.abs(basis.forward().dot(basis.right())));
                worstOrtho = Math.max(worstOrtho, Math.abs(basis.up().dot(basis.right())));

                Vec3 model = new Vec3(0.37D, 4.56D, -3.88D);
                Vec3 back = basis.toModel(basis.toWorld(model.x, model.y, model.z));
                worstRound = Math.max(worstRound, back.subtract(model).length());
            }
        }
        System.out.printf("  sampled %d (yaw, pitch) pairs%n", (int) ((361 / 7 + 1) * (161 / 11 + 1)));
        System.out.printf("  worst |len-1| over the three axes: %.3e%n", worstNorm);
        System.out.printf("  worst |dot| between axes:          %.3e%n", worstOrtho);
        System.out.printf("  worst model->world->model error:   %.3e blocks%n", worstRound);
        assertTrue("the basis is normalised", worstNorm < 1.0E-6D);
        assertTrue("the basis is orthogonal", worstOrtho < 1.0E-6D);
        assertTrue("toWorld and toModel are exact inverses", worstRound < 1.0E-6D);

        // At yaw 0 the dragon faces +Z, so 'forward in the model' must map to +Z in the world.
        var level = DragonAnchor.basis(0.0F, 0.0F);
        Vec3 ahead = level.toWorld(0.0D, 0.0D, -4.0D);
        System.out.printf("  at yaw 0, model-forward 4 blocks -> world %s%n", fmt(ahead));
        assertClose("the model's -Z maps to world +Z at yaw 0", (float) ahead.z, 4.0F, 1.0E-5F);
        assertClose("with no sideways component", (float) ahead.x, 0.0F, 1.0E-5F);
    }

    // ------------------------------------------------------------------ D

    private static void anchorTracking() {
        // The anchor must MOVE with the animation - a static offset would be the bug this replaces.
        double minFly = Double.MAX_VALUE;
        double maxFly = -Double.MAX_VALUE;
        double maxStep = 0.0D;
        double prev = DragonAnchor.riderPointModel(0.0F, true).y;
        for (float t = 0.0F; t <= 200.0F; t += 0.25F) {
            double y = DragonAnchor.riderPointModel(t, true).y;
            minFly = Math.min(minFly, y);
            maxFly = Math.max(maxFly, y);
            maxStep = Math.max(maxStep, Math.abs(y - prev));
            prev = y;
        }
        System.out.printf("  flight anchor height: %.5f .. %.5f (swing %.5f blocks)%n",
                minFly, maxFly, maxFly - minFly);
        System.out.printf("  max change per 0.25 ticks: %.5f blocks%n", maxStep);
        assertTrue("the anchor genuinely bobs with the flight animation (over 1 block of swing)",
                maxFly - minFly > 1.0D);
        assertTrue("and does so continuously, not in jumps", maxStep < 0.04D);

        double minIdle = Double.MAX_VALUE;
        double maxIdle = -Double.MAX_VALUE;
        for (float t = 0.0F; t <= 200.0F; t += 0.25F) {
            double y = DragonAnchor.riderPointModel(t, false).y;
            minIdle = Math.min(minIdle, y);
            maxIdle = Math.max(maxIdle, y);
        }
        System.out.printf("  idle anchor height:   %.5f .. %.5f (swing %.5f blocks)%n",
                minIdle, maxIdle, maxIdle - minIdle);
        assertTrue("the idle bob is present but smaller than the flight bob",
                maxIdle - minIdle > 0.2D && (maxIdle - minIdle) < (maxFly - minFly));

        // Periodicity: the table must wrap cleanly, or the rider would jolt once per cycle.
        float cycle = DragonAnchor.cycleTicks(true);
        double a = DragonAnchor.riderPointModel(3.7F, true).y;
        double b = DragonAnchor.riderPointModel(3.7F + cycle, true).y;
        System.out.printf("  flight cycle %.4f ticks; y(3.7)=%.6f y(3.7+cycle)=%.6f%n", cycle, a, b);
        assertClose("the anchor is periodic across the cycle boundary", (float) a, (float) b, 1.0E-4F);

        // The anchor must never fall below the head box, or the rider stands inside the skull.
        var box = DragonAnchor.headBoxModel();
        boolean above = true;
        for (float t = 0.0F; t <= 200.0F; t += 0.25F) {
            if (DragonAnchor.riderPointModel(t, true).y < box.minY) {
                above = false;
            }
        }
        assertTrue("the anchor never drops below the head box at any phase", above);
    }

    // ------------------------------------------------------------------ E

    private static void launchCurve() {
        int total = 30;
        double height = 22.0D;

        double sum = 0.0D;
        double peak = 0.0D;
        double maxStep = 0.0D;
        double prev = 0.0D;
        System.out.println("   tick   vy");
        for (int t = 0; t < total; t++) {
            double vy = DragonRideControl.launchVerticalSpeed(t, total, height);
            sum += vy;
            peak = Math.max(peak, vy);
            maxStep = Math.max(maxStep, Math.abs(vy - prev));
            prev = vy;
            if (t % 5 == 0) {
                System.out.printf("  %5d   %.6f%n", t, vy);
            }
        }
        double first = DragonRideControl.launchVerticalSpeed(0, total, height);
        double last = DragonRideControl.launchVerticalSpeed(total - 1, total, height);
        double outside = DragonRideControl.launchVerticalSpeed(total, total, height);

        System.out.printf("  integral over %d ticks = %.4f blocks (configured %.1f)%n",
                total, sum, height);
        System.out.printf("  peak %.6f b/t, first tick %.6f, last tick %.6f, after the end %.6f%n",
                peak, first, last, outside);
        System.out.printf("  max change per tick %.6f b/t%n", maxStep);

        assertTrue("the climb reaches the configured height within 2%",
                Math.abs(sum - height) / height < 0.02D);
        assertTrue("it does NOT start with a velocity snap (first tick under 8% of peak)",
                first < peak * 0.08D);
        assertTrue("it eases out rather than cutting (last tick under 8% of peak)",
                last < peak * 0.08D);
        assertClose("it contributes nothing once finished", (float) outside, 0.0F, 1.0E-9F);
        assertTrue("no single tick changes vertical speed by more than 12% of peak",
                maxStep < peak * 0.12D);

        // Scaling: doubling the window must still reach the same height.
        double sum60 = 0.0D;
        for (int t = 0; t < 60; t++) {
            sum60 += DragonRideControl.launchVerticalSpeed(t, 60, height);
        }
        System.out.printf("  with a 60-tick window the integral is %.4f blocks%n", sum60);
        assertTrue("the height is independent of the window length",
                Math.abs(sum60 - height) / height < 0.02D);
    }

    // ------------------------------------------------------------------ F

    private static void turnRate() {
        float cap = 4.5F;
        float worst = 0.0F;
        for (float from = -180.0F; from <= 180.0F; from += 13.0F) {
            for (float to = -180.0F; to <= 180.0F; to += 13.0F) {
                float next = DragonRideControl.approachAngle(from, to, cap);
                float step = Math.abs(Mth.wrapDegrees(next - from));
                worst = Math.max(worst, step);
            }
        }
        System.out.printf("  largest single-tick yaw change over a full sweep: %.6f (cap %.2f)%n",
                worst, cap);
        assertTrue("the turn rate is never exceeded", worst <= cap + 1.0E-4F);

        // The wrap case: turning from 170 to -170 is a 20-degree turn, not a 340-degree one.
        float next = DragonRideControl.approachAngle(170.0F, -170.0F, cap);
        System.out.printf("  170 -> -170 steps to %.4f (the short way is +, i.e. past 180)%n", next);
        assertTrue("it takes the short way round the 180 boundary", next > 170.0F || next < -170.0F);

        // Convergence: it must actually arrive, and monotonically.
        float angle = 0.0F;
        int ticks = 0;
        while (Math.abs(Mth.wrapDegrees(120.0F - angle)) > 0.01F && ticks < 1000) {
            angle = DragonRideControl.approachAngle(angle, 120.0F, cap);
            ticks++;
        }
        System.out.printf("  0 -> 120 degrees took %d ticks at %.2f deg/tick (%.1f expected)%n",
                ticks, cap, 120.0F / cap);
        assertTrue("it converges on the target", ticks < 1000);
        assertTrue("in about the predicted number of ticks",
                Math.abs(ticks - 120.0F / cap) <= 2.0F);

        // Already there: no jitter.
        assertClose("no movement when already aligned",
                DragonRideControl.approachAngle(45.0F, 45.0F, cap), 45.0F, 1.0E-5F);
    }

    // ------------------------------------------------------------------ G

    private static void banking() {
        float rate = 4.5F;
        float limit = 32.0F;

        System.out.println("   yawDelta  bank");
        float prev = DragonRideControl.bankFor(-rate, rate, limit);
        float maxStep = 0.0F;
        for (float d = -rate; d <= rate; d += 0.05F) {
            float bank = DragonRideControl.bankFor(d, rate, limit);
            maxStep = Math.max(maxStep, Math.abs(bank - prev));
            prev = bank;
            if (Math.abs(d % 1.5F) < 0.03F) {
                System.out.printf("  %9.3f  %.5f%n", d, bank);
            }
        }
        float atZero = DragonRideControl.bankFor(0.0F, rate, limit);
        float atMax = DragonRideControl.bankFor(rate, rate, limit);
        float beyond = DragonRideControl.bankFor(rate * 4.0F, rate, limit);
        float negative = DragonRideControl.bankFor(-rate, rate, limit);

        System.out.printf("  bank(0)=%.6f  bank(max)=%.4f  bank(4x max)=%.4f  bank(-max)=%.4f%n",
                atZero, atMax, beyond, negative);
        assertClose("level flight means no bank", atZero, 0.0F, 1.0E-6F);
        assertClose("a full-rate turn banks to the limit", atMax, limit, 1.0E-4F);
        assertClose("and saturates rather than exceeding it", beyond, limit, 1.0E-4F);
        assertClose("banking is symmetric", negative, -limit, 1.0E-4F);
        assertTrue("the bank curve has no steps", maxStep < 1.0F);

        // Eased, not linear. Checked at the quarter and three-quarter points, NOT at the midpoint: a
        // smoothstep is symmetric about its centre and therefore passes exactly through the linear value
        // there. An earlier version of this assertion tested the midpoint and failed a correct curve.
        float quarter = DragonRideControl.bankFor(rate * 0.25F, rate, limit);
        float threeQuarter = DragonRideControl.bankFor(rate * 0.75F, rate, limit);
        float midpoint = DragonRideControl.bankFor(rate * 0.5F, rate, limit);
        System.out.printf("  bank at 25%%/50%%/75%% of turn rate: %.4f / %.4f / %.4f%n",
                quarter, midpoint, threeQuarter);
        System.out.printf("  linear would give:                 %.4f / %.4f / %.4f%n",
                limit * 0.25F, limit * 0.5F, limit * 0.75F);
        assertTrue("the bank eases IN (below linear in the first half)", quarter < limit * 0.25F);
        assertTrue("the bank eases OUT (above linear in the second half)",
                threeQuarter > limit * 0.75F);
        assertClose("and is symmetric about the midpoint", midpoint, limit * 0.5F, 1.0E-4F);
    }

    // ------------------------------------------------------------------ H

    private static void doubleTap() {
        int window = 6;

        // A single tap must not launch.
        var tracker = new DragonRideControl.InputTracker();
        feed(tracker, window, true);
        feed(tracker, window, false);
        for (int i = 0; i < 20; i++) {
            feed(tracker, window, false);
        }
        assertTrue("a single W press never launches", !tracker.consumeLaunch());

        // Held W must not launch - this is the one that would fire on ordinary walking.
        tracker = new DragonRideControl.InputTracker();
        boolean firedWhileHeld = false;
        for (int i = 0; i < 60; i++) {
            if (fed(tracker, window, true)) {
                firedWhileHeld = true;
            }
        }
        System.out.printf("  W held for 60 ticks: launched = %b%n", firedWhileHeld);
        assertTrue("holding W forward never launches - the detector is edge-triggered",
                !firedWhileHeld);

        // Two taps inside the window must launch, and exactly once.
        tracker = new DragonRideControl.InputTracker();
        int fires = 0;
        fed(tracker, window, true);
        fed(tracker, window, false);
        if (fed(tracker, window, true)) {
            fires++;
        }
        for (int i = 0; i < 10; i++) {
            if (fed(tracker, window, false)) {
                fires++;
            }
        }
        System.out.printf("  press, release, press (2 ticks apart): fired %d time(s)%n", fires);
        assertTrue("a double-tap inside the window launches exactly once", fires == 1);

        // Two taps spaced beyond the window must not launch.
        tracker = new DragonRideControl.InputTracker();
        fires = 0;
        fed(tracker, window, true);
        fed(tracker, window, false);
        for (int i = 0; i < window + 3; i++) {
            if (fed(tracker, window, false)) {
                fires++;
            }
        }
        if (fed(tracker, window, true)) {
            fires++;
        }
        System.out.printf("  two taps %d ticks apart (window %d): fired %d time(s)%n",
                window + 4, window, fires);
        assertTrue("a slow double-tap does NOT launch", fires == 0);

        // Boundary: exactly at the window edge should still fire.
        tracker = new DragonRideControl.InputTracker();
        fires = 0;
        fed(tracker, window, true);
        for (int i = 0; i < window - 1; i++) {
            fed(tracker, window, false);
        }
        if (fed(tracker, window, true)) {
            fires++;
        }
        System.out.printf("  two taps exactly at the window edge: fired %d time(s)%n", fires);
        assertTrue("the window boundary is inclusive", fires == 1);

        // Reset must disarm a half-finished tap, so remounting cannot inherit one.
        tracker = new DragonRideControl.InputTracker();
        fed(tracker, window, true);
        tracker.reset();
        fires = 0;
        if (fed(tracker, window, true)) {
            fires++;
        }
        System.out.printf("  press, reset, press: fired %d time(s)%n", fires);
        assertTrue("reset disarms a pending tap, so a fresh mount starts clean", fires == 0);
    }

    private static void feed(DragonRideControl.InputTracker tracker, int window, boolean forward) {
        fed(tracker, window, forward);
    }

    private static boolean fed(DragonRideControl.InputTracker tracker, int window, boolean forward) {
        tracker.tick(new Input(forward, false, false, false, false, false, false), window);
        return tracker.consumeLaunch();
    }

    // ------------------------------------------------------------------ helpers

    private static String fmt(Vec3 v) {
        return String.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

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
}
