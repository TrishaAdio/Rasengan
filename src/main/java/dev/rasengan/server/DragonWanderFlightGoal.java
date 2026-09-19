package dev.rasengan.server;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Untethered aerial wandering for a large flying mob.
 *
 * <h2>Design</h2>
 * Deliberately not a pathfinding goal. A 6x5 collision box cannot be routed by the flying node
 * evaluator - that was measured earlier, producing zero movement - so this picks destinations in open
 * air and hands them to {@link DragonFlightMoveControl}, which steers with a limited turn rate.
 *
 * <p>Three things make the result read as gliding rather than fidgeting:
 * <ul>
 *   <li><b>It commits.</b> A destination is 24-48 blocks away and is flown to until reached, blocked
 *       or timed out. The previous implementation re-targeted every ~4 ticks, which is what made the
 *       motion look random.</li>
 *   <li><b>Turns are shallow.</b> New destinations sit within {@link #MAX_TURN_DEGREES} of the current
 *       heading, so consecutive legs chain into long arcs instead of reversals.</li>
 *   <li><b>Altitude moves gradually.</b> Each leg changes target altitude by at most
 *       {@link #MAX_ALTITUDE_STEP} blocks, inside a band above the terrain.</li>
 * </ul>
 *
 * <h2>Not getting stuck</h2>
 * Two independent guards, because they catch different failures:
 * <ul>
 *   <li><b>Path clearance.</b> Candidate destinations are rejected if the straight line to them is
 *       obstructed, and the goal retries with a different bearing before falling back to a climb.</li>
 *   <li><b>Stall detection.</b> If the dragon covers less than {@link #STALL_DISTANCE} blocks over
 *       {@link #STALL_WINDOW} ticks - scraping a cliff, wedged under an overhang - it curves away and
 *       upward. Note it steers <em>around</em> rather than stopping to re-roll a random heading at the
 *       obstacle, which would just re-collide.</li>
 * </ul>
 *
 * <p>All state is per-instance. Two dragons share nothing.
 */
public class DragonWanderFlightGoal extends Goal {

    /** Destination distance range, in blocks. Long legs read as purposeful travel. */
    private static final double MIN_LEG = 24.0D;
    private static final double MAX_LEG = 48.0D;

    /** How far a new leg may deviate from the current heading. Keeps turns shallow. */
    private static final float MAX_TURN_DEGREES = 55.0F;

    /** Altitude band above the terrain surface. */
    private static final int MIN_CLEARANCE = 12;
    private static final int MAX_CLEARANCE = 52;
    /** Cap on altitude change per leg, so climbs and descents are gradual. */
    private static final int MAX_ALTITUDE_STEP = 14;

    /** Give up on a leg after this long even if never reached. */
    private static final int LEG_TIMEOUT = 200;

    /** Stall detection window and the distance that must be covered within it. */
    private static final int STALL_WINDOW = 40;
    private static final double STALL_DISTANCE = 6.0D;

    /** Floor on look-ahead distance; the real value scales with turn radius. See {@link #lookAhead()}. */
    private static final double LOOK_AHEAD = 20.0D;

    private final DragonEntity dragon;
    private Vec3 target;
    private int legTicks;
    private int stallTicks;
    private Vec3 stallAnchor;

    public DragonWanderFlightGoal(DragonEntity dragon) {
        this.dragon = dragon;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Hunting takes over entirely; this is only the idle behaviour.
        return dragon.getTarget() == null && dragon.isFlyingEnabled();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.legTicks = 0;
        this.stallTicks = 0;
        this.stallAnchor = dragon.position();
        this.target = chooseDestination(dragon.getYRot());
        push();
    }

    @Override
    public void stop() {
        this.target = null;
        if (dragon.getMoveControl() instanceof DragonFlightMoveControl control) {
            control.stopFlying();
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        legTicks++;

        // ---- stall detection: covered too little ground for too long ----
        if (++stallTicks >= STALL_WINDOW) {
            if (dragon.position().distanceTo(stallAnchor) < STALL_DISTANCE) {
                // Curve away and climb rather than stopping and re-rolling on the spot.
                this.target = escapeDestination();
                legTicks = 0;
                push();
            }
            stallTicks = 0;
            stallAnchor = dragon.position();
        }

        boolean arrived = target != null && dragon.position().distanceTo(target) <= arriveRadius();

        // ---- look-ahead: curve before hitting anything, tightening as it closes ----
        double urgency = obstacleUrgency();
        if (dragon.getMoveControl() instanceof DragonFlightMoveControl control) {
            // Clear air flies on the wide cruise arc; the closer the obstacle, the sharper the
            // permitted turn, up to 3x. Avoidance never halts the dragon, it only bends it.
            control.setTurnRateMultiplier(1.0F + (float) (urgency * 2.0D));
        }
        if (!arrived && urgency > 0.0D) {
            this.target = escapeDestination();
            legTicks = 0;
            push();
            return;
        }

        if (target == null || arrived || legTicks > LEG_TIMEOUT || dragon.horizontalCollision) {
            this.target = chooseDestination(dragon.getYRot());
            legTicks = 0;
        }
        push();
    }

    private double arriveRadius() {
        return Math.max(8.0D, dragon.getBbWidth() * 1.5D);
    }

    private void push() {
        if (target != null) {
            dragon.getMoveControl().setWantedPosition(target.x, target.y, target.z, 1.0D);
        }
    }

    /**
     * Picks the next destination: a shallow turn from the present heading, a long leg out, and a
     * small altitude change inside the terrain-clearance band. Retries a few bearings if the way is
     * obstructed, then falls back to a climb, which is always clear.
     */
    private Vec3 chooseDestination(float fromYaw) {
        for (int attempt = 0; attempt < 8; attempt++) {
            float yaw = fromYaw + (dragon.getRandom().nextFloat() * 2.0F - 1.0F) * MAX_TURN_DEGREES;
            double leg = Mth.lerp(dragon.getRandom().nextDouble(), MIN_LEG, MAX_LEG);
            float yawRad = yaw * ((float) Math.PI / 180.0F);
            double dx = -Mth.sin(yawRad) * leg;
            double dz = Mth.cos(yawRad) * leg;

            double x = dragon.getX() + dx;
            double z = dragon.getZ() + dz;
            int step = (int) ((dragon.getRandom().nextDouble() * 2.0D - 1.0D) * MAX_ALTITUDE_STEP);
            double y = clampToAltitudeBand(x, z, dragon.getY() + step);

            Vec3 candidate = new Vec3(x, y, z);
            if (isClear(dragon.position(), candidate)) {
                return candidate;
            }
        }
        return escapeDestination();
    }

    /**
     * A destination that curves away from whatever is in front and gains height. Used when the way
     * ahead is blocked or the dragon has stalled.
     */
    private Vec3 escapeDestination() {
        Vec3 from = dragon.position();
        // Banking turns, widening until one is actually clear for the full body. Tried in both
        // directions at each angle so it takes whichever side is open rather than guessing, and
        // every candidate also climbs - gaining height is what clears walls, ridges and trees.
        for (float angle : new float[] {55.0F, 80.0F, 110.0F, 145.0F, 180.0F}) {
            for (float sign : new float[] {1.0F, -1.0F}) {
                float yawRad = (dragon.getYRot() + angle * sign) * ((float) Math.PI / 180.0F);
                double leg = 30.0D;
                double x = dragon.getX() - Mth.sin(yawRad) * leg;
                double z = dragon.getZ() + Mth.cos(yawRad) * leg;
                double y = clampToAltitudeBand(x, z, dragon.getY() + 18.0D);
                Vec3 candidate = new Vec3(x, y, z);
                if (isClear(from, candidate)) {
                    return candidate;
                }
            }
        }
        // Boxed in on every heading: go straight up. Vertical is the one direction that is clear
        // from inside a canyon or courtyard, and it is always preferable to stopping.
        double ceiling = Math.min(dragon.level().getMaxY() - 8, dragon.getY() + 40.0D);
        return new Vec3(dragon.getX(), ceiling, dragon.getZ());
    }

    /** Keeps a target altitude inside a clearance band above the terrain and below the build limit. */
    private double clampToAltitudeBand(double x, double z, double desiredY) {
        Level level = dragon.level();
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                Mth.floor(x), Mth.floor(z));
        double floor = surface + MIN_CLEARANCE;
        double ceiling = Math.min(level.getMaxY() - 8, surface + MAX_CLEARANCE);
        if (ceiling < floor) {
            ceiling = floor;
        }
        return Mth.clamp(desiredY, floor, ceiling);
    }

    /** True if nothing solid lies on the straight line between two points. */
    private boolean rayClear(Vec3 from, Vec3 to) {
        return dragon.level().clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, dragon))
                .getType() == HitResult.Type.MISS;
    }

    /**
     * True if a corridor wide and tall enough for the <em>whole dragon</em> is clear.
     *
     * <h2>Why a single ray is not enough</h2>
     * The collision box is 6 blocks wide and 5 tall. A centre-line raycast happily reports a clear
     * path through a 1-block gap, so the dragon commits to a destination its body cannot fit
     * through, flies in, and wedges. This casts a bundle: centre, both wingtips at half-width, and
     * above and below, so clearance is judged against the silhouette that actually has to pass.
     */
    private boolean isClear(Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 1.0E-6D) {
            return true;
        }
        double half = dragon.getBbWidth() * 0.5D;
        double halfHeight = dragon.getBbHeight() * 0.5D;
        // Lateral axis perpendicular to travel, in the horizontal plane.
        Vec3 flat = new Vec3(direction.x, 0.0D, direction.z);
        Vec3 side = flat.lengthSqr() < 1.0E-6D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(-flat.z, 0.0D, flat.x).normalize();

        Vec3[] offsets = {
                Vec3.ZERO,
                side.scale(half),
                side.scale(-half),
                new Vec3(0.0D, halfHeight, 0.0D),
                new Vec3(0.0D, -halfHeight, 0.0D),
        };
        for (Vec3 offset : offsets) {
            if (!rayClear(from.add(offset), to.add(offset))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Look-ahead distance, scaled to what it actually takes to turn out of the way.
     *
     * <p>A fixed distance is wrong: the turn radius is {@code speed / turnRate}, roughly 8 blocks at
     * cruise, so a warning shorter than about twice that arrives too late to avoid the obstacle no
     * matter how sharply the dragon turns. Half the body width is added because the wingtips need to
     * clear it too.
     */
    private double lookAhead() {
        double radius = dragon.getMoveControl() instanceof DragonFlightMoveControl control
                ? control.turnRadius()
                : 8.0D;
        if (!Double.isFinite(radius)) {
            radius = 8.0D;
        }
        return Math.max(LOOK_AHEAD, radius * 2.5D + dragon.getBbWidth() * 0.5D);
    }

    /**
     * How close the obstruction ahead is, as a 0..1 urgency. 0 means clear.
     *
     * <p>Probed at several ranges rather than once, so avoidance can begin gently far out and
     * tighten only if the obstacle is genuinely close - the difference between easing around
     * something and yanking.
     */
    private double obstacleUrgency() {
        Vec3 velocity = dragon.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 1.0E-4D) {
            return 0.0D;
        }
        Vec3 from = dragon.position().add(0.0D, dragon.getBbHeight() * 0.5D, 0.0D);
        Vec3 heading = velocity.normalize();
        double max = lookAhead();
        for (double fraction : new double[] {0.34D, 0.67D, 1.0D}) {
            if (!isClear(from, from.add(heading.scale(max * fraction)))) {
                return 1.0D - fraction + 0.34D;
            }
        }
        return 0.0D;
    }

    /** Exposed for tracing: the current destination, or null. */
    public BlockPos currentTarget() {
        return target == null ? null : BlockPos.containing(target);
    }
}
