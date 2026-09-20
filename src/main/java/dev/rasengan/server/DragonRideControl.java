package dev.rasengan.server;

import dev.rasengan.RasenganConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra-style flight control for a ridden dragon. All of it server-side.
 *
 * <h2>Why this is server-authoritative for free</h2>
 * Not by a check bolted on top - by construction. Vanilla only lets a client drive a vehicle when
 * {@code vehicle.getControllingPassenger()} is the local player, and {@code Mob.getControllingPassenger}
 * returns a passenger only if it {@code instanceof Mob}. A {@link net.minecraft.world.entity.player.Player}
 * is not a {@code Mob}, so:
 * <ul>
 *   <li>{@code LocalPlayer} never sends {@code ServerboundMoveVehiclePacket} - it is gated on
 *       {@code vehicle.isLocalInstanceAuthoritative()};</li>
 *   <li>{@code ServerGamePacketListenerImpl.handleMoveVehicle} rejects one anyway - it requires
 *       {@code vehicle.getControllingPassenger() == this.player}.</li>
 * </ul>
 * So {@link DragonEntity} deliberately does <b>not</b> override {@code getControllingPassenger}. The
 * dragon's position is computed here and only here, and reaches clients through ordinary entity
 * tracking. The cost is that the rider has no client-side prediction, so steering carries one
 * round-trip of input latency; for a mount that turns at 4.5 degrees per tick that is a fair trade for
 * a mount that cannot be teleported by a modified client.
 *
 * <h2>What the client is still trusted for</h2>
 * Exactly two things, both of which are legitimately the player's: their <b>look direction</b>
 * (delivered by {@code ServerboundMovePlayerPacket.Rot}, which is their camera) and their <b>key
 * state</b> (delivered by {@code ServerboundPlayerInputPacket}, which is vanilla's own replicated
 * {@link Input} record and is sent whenever input changes regardless of riding). Neither carries a
 * position, a velocity or an "I launched" assertion. The double-tap is detected here by watching the
 * forward flag's rising edges - the client never says "I double-tapped".
 */
public final class DragonRideControl {

    /** Ride phase, replicated so the client can pick animations and draw the bank angle. */
    public static final byte PHASE_NONE = 0;
    /** Mounted but grounded: perched, waiting for a launch. */
    public static final byte PHASE_PERCHED = 1;
    /** The eased launch climb is running; steering is not yet handed over. */
    public static final byte PHASE_LAUNCHING = 2;
    /** Airborne under the rider's control. */
    public static final byte PHASE_FLYING = 3;

    private DragonRideControl() {}

    /**
     * Per-rider input tracking. Lives on the dragon, not in a static map, so it goes away with the
     * entity and cannot leak.
     */
    public static final class InputTracker {
        private boolean lastForward;
        /** Ticks since the first press of a candidate double-tap, or -1 when not armed. */
        private int armedFor = -1;
        /** Set for one tick when a valid double-tap completes. */
        private boolean launchRequested;

        /**
         * Feeds one tick of replicated input and updates the double-tap state machine.
         *
         * <p>Edge-triggered on purpose: a held W produces exactly one rising edge, so walking forward
         * can never accumulate into a launch. Two rising edges inside the window fire it.
         */
        public void tick(Input input, int windowTicks) {
            launchRequested = false;
            boolean forward = input.forward();
            boolean rising = forward && !lastForward;
            lastForward = forward;

            if (armedFor >= 0) {
                armedFor++;
                if (rising) {
                    launchRequested = true;
                    armedFor = -1;
                    return;
                }
                if (armedFor > windowTicks) {
                    armedFor = -1; // window lapsed; the next press arms a fresh candidate
                }
            }
            if (rising && armedFor < 0) {
                armedFor = 0;
            }
        }

        /** True for the single tick on which a valid double-tap completed. */
        public boolean consumeLaunch() {
            boolean value = launchRequested;
            launchRequested = false;
            return value;
        }

        public void reset() {
            lastForward = false;
            armedFor = -1;
            launchRequested = false;
        }
    }

    /**
     * Vertical speed of the launch climb at {@code t} ticks into it.
     *
     * <h2>Why this shape</h2>
     * A smooth-step bump: zero at both ends, peaking in the middle. Zero at the start means the climb
     * begins from the dragon's actual velocity instead of snapping to one, and zero at the end means it
     * hands over to the player's steering without a discontinuity in either direction - the same
     * reason the summoning camera's release uses a smoothstep rather than a linear ramp.
     *
     * <p>Scaled so the integral over the whole window equals the configured height exactly. For a
     * {@code sin^2} bump the mean value is 1/2, so the peak is {@code 2 * height / ticks}.
     */
    public static double launchVerticalSpeed(int elapsed, int totalTicks, double height) {
        if (totalTicks <= 0 || elapsed < 0 || elapsed >= totalTicks) {
            return 0.0D;
        }
        // sin^2 averages exactly 1/2 over a half period, so a peak of 2*height/ticks integrates to
        // height. Sampled at the tick's midpoint, which is the midpoint rule - exact for this shape to
        // within the second-order term rather than biased low like a left-edge sample.
        double k = (elapsed + 0.5D) / totalTicks;
        double bump = Math.sin(k * Math.PI);
        return (2.0D * height / totalTicks) * bump * bump;
    }

    /**
     * Steps one angle toward a target, never faster than {@code maxStep} degrees.
     *
     * <p>Wraps correctly through 180 degrees, which is the bug that makes naive yaw lerps spin the long
     * way round when a player turns past south.
     */
    public static float approachAngle(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        float clamped = Mth.clamp(delta, -maxStep, maxStep);
        return Mth.wrapDegrees(current + clamped);
    }

    /**
     * Bank angle for a given yaw rate.
     *
     * <p>Proportional to how hard the dragon is turning, saturating at the configured limit, and eased
     * rather than linear so it rolls in and out of a turn instead of hinging.
     */
    public static float bankFor(float yawDeltaDegrees, float turnRateLimit, float bankLimit) {
        if (turnRateLimit <= 1.0E-4F) {
            return 0.0F;
        }
        float ratio = Mth.clamp(yawDeltaDegrees / turnRateLimit, -1.0F, 1.0F);
        // Ease: sign-preserving smoothstep on the magnitude.
        float magnitude = Math.abs(ratio);
        float eased = magnitude * magnitude * (3.0F - 2.0F * magnitude);
        return Math.copySign(eased, ratio) * bankLimit;
    }

    /**
     * One tick of player-controlled flight.
     *
     * <p>Mutates the dragon's rotation and velocity. Returns the yaw delta actually applied, which the
     * caller uses for the bank angle.
     */
    public static float steer(DragonEntity dragon, ServerPlayer rider, Input input) {
        double maxSpeed = RasenganConfig.SERVER.mountMaxSpeed.get();
        double accel = RasenganConfig.SERVER.mountAcceleration.get();
        float turnRate = (float) (double) RasenganConfig.SERVER.mountTurnRateDegrees.get();
        float pitchRate = (float) (double) RasenganConfig.SERVER.mountPitchRateDegrees.get();

        // ---- heading: ease toward the rider's look, rate-limited ----
        float beforeYaw = dragon.getYRot();
        float targetYaw = rider.getYRot();
        float targetPitch = Mth.clamp(rider.getXRot(), -75.0F, 75.0F);

        float newYaw = approachAngle(beforeYaw, targetYaw, turnRate);
        float newPitch = approachAngle(dragon.getXRot(), targetPitch, pitchRate);
        float yawDelta = Mth.wrapDegrees(newYaw - beforeYaw);

        dragon.setYRot(newYaw);
        dragon.setYHeadRot(newYaw);
        dragon.yBodyRot = newYaw;
        dragon.setXRot(newPitch);

        // ---- thrust: W accelerates, releasing it coasts down ----
        // Both directions go through the same lerp, so there is no binary on/off step. Backward input
        // brakes rather than reversing: a dragon that flies backwards looks wrong and the animation has
        // no pose for it.
        double target = input.forward() ? maxSpeed : (input.backward() ? 0.0D : maxSpeed * 0.35D);

        Vec3 heading = new Vec3(
                -Mth.sin(newYaw * Mth.DEG_TO_RAD) * Mth.cos(newPitch * Mth.DEG_TO_RAD),
                -Mth.sin(newPitch * Mth.DEG_TO_RAD),
                Mth.cos(newYaw * Mth.DEG_TO_RAD) * Mth.cos(newPitch * Mth.DEG_TO_RAD));

        Vec3 current = dragon.getDeltaMovement();
        double currentSpeed = current.length();
        double newSpeed = Mth.lerp(accel, currentSpeed, target);

        // Jump lifts, shift descends - a small authority on top of the pitch-driven climb, so a rider
        // can hold altitude while looking level.
        double lift = 0.0D;
        if (input.jump()) {
            lift = maxSpeed * 0.30D;
        } else if (input.shift()) {
            lift = -maxSpeed * 0.30D;
        }

        Vec3 desired = heading.scale(newSpeed).add(0.0D, lift, 0.0D);
        // Lerp the velocity vector too, not just its magnitude: turning the heading instantly would
        // otherwise slew the whole velocity in one tick even though the rotation was rate-limited.
        dragon.setDeltaMovement(current.add(desired.subtract(current).scale(accel * 2.0D)));

        return yawDelta;
    }
}
