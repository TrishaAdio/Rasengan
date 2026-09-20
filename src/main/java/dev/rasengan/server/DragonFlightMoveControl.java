package dev.rasengan.server;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/**
 * Steering flight control for a large flying mob.
 *
 * <h2>Why this exists instead of {@link net.minecraft.world.entity.ai.control.FlyingMoveControl}</h2>
 * {@code FlyingMoveControl} only steers for the <em>single tick</em> on which a wanted position is
 * set. Its {@code tick()} flips {@code operation} to {@code WAIT} on entry to the {@code MOVE_TO}
 * branch, and on every later tick it falls into the else branch and calls {@code setYya(0)} and
 * {@code setZza(0)}, zeroing all movement input. That is fine for bees and parrots, because they are
 * driven by {@code FlyingPathNavigation}, which re-issues {@code setWantedPosition} every single tick
 * while following a path. It is wrong for a mob steered by a goal that sets a destination once.
 *
 * <p>Pairing a once-per-target goal with it produced measurable jitter rather than flight: mean speed
 * 0.049 blocks/tick against a 0.6 {@code FLYING_SPEED} attribute (8.2% of it), 133 separate thrust
 * spikes in 500 ticks - one every 3.8 ticks - and 29 vertical direction reversals. One tick of
 * thrust, a stall, then an immediate re-target, forever.
 *
 * <h2>What this does differently</h2>
 * <ul>
 *   <li><b>Holds its destination</b> across ticks. {@code active} is owned here, so a destination set
 *       once keeps being flown toward until it is reached, replaced, or cleared.</li>
 *   <li><b>Turn-rate limited.</b> Heading approaches the bearing to the target by at most a few
 *       degrees per tick via {@link #rotlerp}, so course changes are banked arcs rather than snaps.
 *       This is the single largest contributor to flight reading as smooth.</li>
 *   <li><b>Flies along its own heading, not straight at the target.</b> Thrust follows where the
 *       dragon is currently pointing, so while the heading is still catching up it curves. Driving
 *       velocity directly at the target vector is what makes a mob appear to strafe sideways.</li>
 *   <li><b>Velocity is lerped, never assigned.</b> Each tick the current velocity moves a fraction
 *       {@link #accel} toward the desired velocity, so speed and direction are continuous.</li>
 *   <li><b>Vertical is eased separately</b> and clamped, so altitude corrections are gentle instead
 *       of stepping.</li>
 * </ul>
 *
 * <p>All state is per-instance; nothing here is static, so any number of dragons steer independently.
 */
public class DragonFlightMoveControl extends MoveControl {

    /** Considered arrived within this many blocks. Scaled to the dragon's own width. */
    private static final double ARRIVE_RADIUS = 6.0D;

    /** Max heading change per tick, degrees. 4 deg/tick = 80 deg/s: a wide, deliberate bank. */
    private static final float MAX_YAW_TURN = 4.0F;
    /** Max pitch change per tick, degrees. */
    private static final float MAX_PITCH_TURN = 3.0F;
    /** Pitch is clamped so it never points straight up or dives vertically. */
    private static final float MAX_PITCH = 35.0F;

    /**
     * Fraction of the gap between current and desired velocity closed each tick.
     * 0.08 gives a time constant of roughly 12 ticks - visibly smooth acceleration without
     * feeling unresponsive.
     */
    private static final double ACCEL = 0.08D;

    /** Vertical proportional gain, and the cap on climb/descent rate in blocks per tick. */
    private static final double VERTICAL_GAIN = 0.08D;
    private static final double MAX_CLIMB_RATE = 0.22D;

    private final Mob mob;
    private final double accel;
    private boolean active;
    private float turnRateMultiplier = 1.0F;

    /**
     * Fully disables this control, for when something else owns the velocity.
     *
     * <p>Needed for the ridden dragon. {@link #stopFlying()} is not enough: with no destination,
     * {@code tick()} still runs {@link #coast()}, which scales the velocity by 0.90 every tick - that
     * would quietly bleed 10% per tick off a rider's carefully computed thrust and make the mount feel
     * like it was flying through treacle. Suspension has to mean "touch nothing".
     *
     * <p>Deliberately not implemented by setting {@code NoAi} instead: {@code Mob.isEffectiveAi()}
     * returns false when {@code NoAi} is set, and {@code LivingEntity.aiStep} only calls
     * {@code travel()} when {@code isEffectiveAi()}, so a ridden dragon with {@code NoAi} would have
     * its velocity set and then never integrated - it would hang motionless in the air.
     */
    private boolean suspended;

    public DragonFlightMoveControl(Mob mob) {
        super(mob);
        this.mob = mob;
        this.accel = ACCEL;
    }

    /**
     * Temporarily tightens the turn rate, for evading terrain.
     *
     * <p>A single fixed turn rate cannot serve both goals. 4 deg/tick gives the wide, deliberate
     * arcs that make cruising look like gliding, but it also implies a turn radius of
     * {@code speed / rate} - about 8 blocks at cruise - which is too wide to get out of the way of a
     * wall spotted late. Rather than compromise the cruise, avoidance asks for a sharper rate only
     * while it needs one.
     */
    public void setTurnRateMultiplier(float multiplier) {
        this.turnRateMultiplier = Mth.clamp(multiplier, 1.0F, 4.0F);
    }

    /** Radius of the circle this mob can turn in at its current speed, in blocks. */
    public double turnRadius() {
        double perTickRadians = Math.toRadians(MAX_YAW_TURN * this.turnRateMultiplier);
        double speed = this.mob.getDeltaMovement().horizontalDistance();
        return perTickRadians < 1.0E-6D ? Double.MAX_VALUE : speed / perTickRadians;
    }

    @Override
    public void setWantedPosition(double x, double y, double z, double speedModifier) {
        super.setWantedPosition(x, y, z, speedModifier);
        this.active = true;
    }

    /**
     * True while a destination is being flown toward.
     *
     * <p>Overridden because the inherited version reports {@code operation == MOVE_TO}, and this
     * control deliberately does not keep the operation in that state. A goal asking "are you still
     * going somewhere?" must get the real answer, or it will re-target every tick.
     */
    @Override
    public boolean hasWanted() {
        return this.active;
    }

    public void stopFlying() {
        this.active = false;
    }

    /** Suspends or resumes this control. While suspended it writes nothing at all. */
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
        if (suspended) {
            this.active = false;
        }
    }

    public boolean isSuspended() {
        return this.suspended;
    }

    public Vec3 wantedPosition() {
        return new Vec3(this.wantedX, this.wantedY, this.wantedZ);
    }

    public double distanceToWanted() {
        return this.mob.position().distanceTo(wantedPosition());
    }

    public boolean hasArrived() {
        return distanceToWanted() <= arriveRadius();
    }

    private double arriveRadius() {
        return Math.max(ARRIVE_RADIUS, this.mob.getBbWidth());
    }

    @Override
    public void tick() {
        // Consume any externally set MOVE_TO so nothing else re-interprets it, but keep flying
        // off our own `active` flag rather than losing the destination after one tick.
        if (this.operation == MoveControl.Operation.MOVE_TO) {
            this.operation = MoveControl.Operation.WAIT;
        }
        if (this.suspended) {
            return; // a rider owns the velocity; not even coast() may touch it
        }
        if (!this.active) {
            coast();
            return;
        }

        Vec3 toTarget = wantedPosition().subtract(this.mob.position());
        double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        if (toTarget.length() <= arriveRadius()) {
            this.active = false;
            coast();
            return;
        }

        // ---- 1. Turn-rate-limited heading ----
        float desiredYaw = (float) (Mth.atan2(toTarget.z, toTarget.x) * (180.0F / (float) Math.PI)) - 90.0F;
        this.mob.setYRot(rotlerp(this.mob.getYRot(), desiredYaw, MAX_YAW_TURN * this.turnRateMultiplier));
        // Keep the body aligned with the flight path; otherwise the model yaws independently of
        // the direction of travel and reads as sliding sideways.
        this.mob.yBodyRot = this.mob.getYRot();
        this.mob.setYHeadRot(this.mob.getYRot());

        float desiredPitch = (float) (-(Mth.atan2(toTarget.y, horizontal) * (180.0F / (float) Math.PI)));
        desiredPitch = Mth.clamp(desiredPitch, -MAX_PITCH, MAX_PITCH);
        this.mob.setXRot(rotlerp(this.mob.getXRot(), desiredPitch, MAX_PITCH_TURN * this.turnRateMultiplier));

        // ---- 2. Desired velocity along the CURRENT heading ----
        double cruise = this.speedModifier * this.mob.getAttributeValue(Attributes.FLYING_SPEED);
        float yawRad = this.mob.getYRot() * ((float) Math.PI / 180.0F);
        Vec3 heading = new Vec3(-Mth.sin(yawRad), 0.0D, Mth.cos(yawRad)).normalize();
        Vec3 desired = heading.scale(cruise);

        // ---- 3. Eased vertical, independent of the horizontal drive ----
        double desiredY = Mth.clamp(toTarget.y * VERTICAL_GAIN, -MAX_CLIMB_RATE, MAX_CLIMB_RATE);
        desired = new Vec3(desired.x, desiredY, desired.z);

        // ---- 4. Lerp velocity toward desired. Never assign it. ----
        Vec3 velocity = this.mob.getDeltaMovement();
        Vec3 next = velocity.add(desired.subtract(velocity).scale(this.accel));

        // ---- 5. Do not grind into geometry ----
        // If the last move() was refused horizontally, holding a full cruise velocity into the
        // obstruction keeps the entity pinned: the velocity vector stays saturated so the lerp can
        // never rotate it away. Bleeding the horizontal component lets the new heading take over,
        // which is what turns a wedge into a curve.
        if (this.mob.horizontalCollision) {
            // Eased, not clamped. Hard-flooring the vertical component to a climb produced velocity
            // steps of 0.17 blocks/tick against 0.03 in clear air - a visible jolt every time a
            // wingtip touched something. Lerping toward a gentle climb keeps the recovery smooth
            // while still generating the lift needed to clear the obstruction.
            double lift = next.y + (0.12D - next.y) * 0.25D;
            next = new Vec3(next.x * 0.55D, lift, next.z * 0.55D);
        }
        this.mob.setDeltaMovement(next);
    }

    /** No destination: bleed off speed gently into a hover rather than stopping dead. */
    private void coast() {
        Vec3 velocity = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(velocity.scale(0.90D));
        this.mob.setYya(0.0F);
        this.mob.setZza(0.0F);
    }
}
