package dev.rasengan.server;

import java.util.EnumSet;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Run away from one specific dragon, for a fixed number of ticks.
 *
 * <h2>Why not {@code AvoidEntityGoal}</h2>
 * Vanilla's goal avoids a <em>class</em> of entity and only while that entity is within its radius, so
 * it stops the moment the dragon flies off - which is most of the time, given this dragon cruises at
 * half a block per tick. The requirement is that a frightened mob flees for a configured duration and
 * then returns to normal, which is a timer, not a proximity test. This goal owns that timer, and is
 * keyed to a single dragon instance so two dragons do not interfere.
 *
 * <h2>Pathing</h2>
 * Destinations come from {@link DefaultRandomPos#getPosAway}, the same helper
 * {@code AvoidEntityGoal} uses, so "away" respects walkable terrain rather than driving the mob into
 * a wall. When no path can be found - a cornered mob, or one on a one-block ledge - the goal keeps
 * running rather than ending: a cornered mob that gave up would silently revert to attacking, and the
 * fear would look like it had failed. It retries on a short interval instead.
 *
 * <h2>Lifetime</h2>
 * This goal never outlives its welcome on its own terms: {@link #canContinueToUse()} goes false when
 * the timer expires or the dragon dies, and {@link DragonFearManager} additionally removes the
 * instance from the mob's selector so no dead goal accumulates. Both paths matter - the manager
 * handles the mob being unloaded, the goal handles the mob being ticked.
 */
public final class FleeFromDragonGoal extends Goal {

    /** Ticks between path attempts while fleeing. */
    private static final int REPATH_INTERVAL = 10;

    /** How far ahead a flee destination is picked, in blocks. */
    private static final int FLEE_HORIZONTAL = 16;
    private static final int FLEE_VERTICAL = 7;

    private final PathfinderMob mob;
    private final DragonEntity dragon;
    private final double speedModifier;

    private int ticksLeft;
    private int repathCountdown;

    public FleeFromDragonGoal(PathfinderMob mob, DragonEntity dragon, int durationTicks,
                              double speedModifier) {
        this.mob = mob;
        this.dragon = dragon;
        this.ticksLeft = durationTicks;
        this.speedModifier = speedModifier;
        // MOVE only. Deliberately not LOOK: a fleeing mob that also had its head locked by this goal
        // could not be seen reacting, and LOOK is where the existing look goals live.
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Ticks of fear remaining. Exposed so the manager and the harness can read progress. */
    public int ticksLeft() {
        return ticksLeft;
    }

    /** Ends the fear immediately, leaving the mob's navigation clear. */
    public void expire() {
        this.ticksLeft = 0;
        if (mob.getNavigation().isInProgress()) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public boolean canUse() {
        return ticksLeft > 0 && dragon.isAlive() && !dragon.isRemoved();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathCountdown = 0;
    }

    @Override
    public void stop() {
        ticksLeft = 0;
        if (mob.getNavigation().isInProgress()) {
            mob.getNavigation().stop();
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticksLeft--;

        // Face away from the dragon even when no path is available, so the reaction is legible
        // regardless of terrain.
        Vec3 away = mob.position().subtract(dragon.position());
        if (away.lengthSqr() > 1.0E-4D) {
            Vec3 lookAt = mob.position().add(away.normalize().scale(8.0D));
            mob.getLookControl().setLookAt(lookAt.x, mob.getEyeY(), lookAt.z);
        }

        if (repathCountdown > 0) {
            repathCountdown--;
            return;
        }
        if (mob.getNavigation().isInProgress()) {
            return;
        }
        repathCountdown = REPATH_INTERVAL;

        @Nullable Vec3 target = DefaultRandomPos.getPosAway(
                mob, FLEE_HORIZONTAL, FLEE_VERTICAL, dragon.position());
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, speedModifier);
        }
    }
}
