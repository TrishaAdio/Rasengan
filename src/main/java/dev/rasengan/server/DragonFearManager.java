package dev.rasengan.server;

import dev.rasengan.Rasengan;
import dev.rasengan.RasenganConfig;
import dev.rasengan.RasenganParticles;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Makes nearby hostile mobs run from the dragon when it arrives.
 *
 * <h2>Real behaviour, not a debuff</h2>
 * A slowness effect would say "something frightening happened" without anything <em>looking</em>
 * frightened. This inserts a live {@link FleeFromDragonGoal} at priority 0 - above every goal the mob
 * already has - so the mob genuinely turns and runs, using its own navigation. Optionally its
 * targeting is switched off for the duration as well, so it does not stop mid-retreat to fight.
 *
 * <h2>Cleanup is the hard part, so it has three independent paths</h2>
 * A temporary goal left behind is a mob that flees forever, which is far worse than no fear at all.
 * Every applied goal is therefore removed by:
 * <ol>
 *   <li><b>expiry</b> - the per-tick sweep here removes the goal once its timer runs out;</li>
 *   <li><b>the dragon ending</b> - {@link FleeFromDragonGoal#canUse()} returns false as soon as the
 *       dragon dies or is removed, so the goal stops running even before the sweep reaches it;</li>
 *   <li><b>the mob leaving</b> - the sweep drops entries whose mob is removed or whose level no
 *       longer has it, which covers death, despawn and chunk unload. The mob's own goal selector goes
 *       with the entity, so an unloaded mob cannot leak a goal into anything that survives.</li>
 * </ol>
 * {@link #clearAll()} additionally empties the register on shutdown, so nothing is carried between
 * server lifetimes.
 *
 * <h2>Server-authoritative</h2>
 * Goals, durations and the radius are applied here and nowhere else. The only client involvement is
 * the particle tell, which is sent as a normal server particle broadcast.
 */
public final class DragonFearManager {

    /** Speed multiplier applied while fleeing. Above 1 so a frightened mob visibly bolts. */
    private static final double FLEE_SPEED = 1.35D;

    /** One frightened mob. */
    private record Frightened(Mob mob, FleeFromDragonGoal goal, boolean targetingSuppressed) {}

    private static final List<Frightened> AFFECTED = new ArrayList<>();

    private DragonFearManager() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(DragonFearManager::onServerTick);
    }

    /**
     * Frightens every eligible hostile within the configured radius of {@code dragon}.
     *
     * @return how many mobs were affected - returned so the verification harness can assert a number
     *         rather than inferring success from the absence of an error
     */
    public static int frighten(ServerLevel level, DragonEntity dragon) {
        if (!RasenganConfig.SERVER.fearEnabled.get()) {
            return 0;
        }
        double radius = RasenganConfig.SERVER.fearRadius.get();
        int duration = RasenganConfig.SERVER.fearDurationTicks.get();
        boolean suppress = RasenganConfig.SERVER.fearSuppressAttacks.get();
        boolean bossImmune = RasenganConfig.SERVER.fearBossImmune.get();

        AABB box = dragon.getBoundingBox().inflate(radius);
        int affected = 0;

        for (Mob mob : level.getEntitiesOfClass(Mob.class, box,
                candidate -> isEligible(candidate, dragon, bossImmune))) {
            // Radius is measured as a sphere, not the inflated box the broad-phase query used.
            if (mob.distanceToSqr(dragon) > radius * radius) {
                continue;
            }
            if (!(mob instanceof PathfinderMob pathfinder)) {
                // Nothing to flee with: no navigation. Left alone rather than given a goal that
                // could never do anything.
                continue;
            }
            if (isAlreadyFrightened(mob)) {
                continue;
            }
            apply(level, pathfinder, dragon, duration, suppress);
            affected++;
        }
        return affected;
    }

    /**
     * Whether a mob should be frightened.
     *
     * <p>{@link Enemy} is the marker vanilla uses for "hostile", which is the right test: it catches
     * modded hostiles too, without this class holding a list of entity types that would go stale.
     */
    private static boolean isEligible(Mob mob, DragonEntity dragon, boolean bossImmune) {
        if (mob == dragon || !mob.isAlive() || mob.isRemoved()) {
            return false;
        }
        if (!(mob instanceof Enemy)) {
            return false;
        }
        if (bossImmune && isBoss(mob)) {
            return false;
        }
        return true;
    }

    /**
     * Entity types exempt from fear when {@code boss_immune} is on.
     *
     * <p>A tag rather than an {@code instanceof} chain, and a tag defined by this mod rather than a
     * vanilla one, because <b>26.1 has no boss entity-type tag</b> - {@code EntityTypeTags} contains
     * nothing boss-related, so there was nothing to reuse. Shipping our own has the better property
     * anyway: a server owner can add a modpack's bosses to it from a datapack without touching code.
     */
    public static final TagKey<EntityType<?>> FEAR_IMMUNE =
            TagKey.create(Registries.ENTITY_TYPE, Rasengan.id("fear_immune"));

    private static boolean isBoss(Mob mob) {
        // 26.1 has no EntityType.is(TagKey); the tag test goes through the registry holder, which is
        // how vanilla itself does it (see FuelValues and HarvestFarmland).
        return mob.getType().builtInRegistryHolder().is(FEAR_IMMUNE);
    }

    private static boolean isAlreadyFrightened(Mob mob) {
        for (Frightened entry : AFFECTED) {
            if (entry.mob() == mob) {
                return true;
            }
        }
        return false;
    }

    private static void apply(ServerLevel level, PathfinderMob mob, DragonEntity dragon,
                              int duration, boolean suppress) {
        FleeFromDragonGoal goal = new FleeFromDragonGoal(mob, dragon, duration, FLEE_SPEED);
        // Priority 0: above everything the mob already has, so the flee wins the MOVE flag.
        mob.goalSelector.addGoal(0, goal);

        boolean suppressed = false;
        if (suppress) {
            // Drop whatever it is currently attacking and stop target goals from re-acquiring.
            mob.setTarget(null);
            mob.setLastHurtByMob(null);
            mob.targetSelector.disableControlFlag(Goal.Flag.TARGET);
            suppressed = true;
        }
        AFFECTED.add(new Frightened(mob, goal, suppressed));

        // The visual tell: a short burst above the head, so a player can see which mobs are afraid.
        level.sendParticles(RasenganParticles.FEAR.get(),
                mob.getX(), mob.getEyeY() + 0.45D, mob.getZ(),
                5, 0.22D, 0.10D, 0.22D, 0.02D);

        // A brief recoil away from the dragon. Small, and only on the first tick: this is a stagger,
        // not knockback, and a large impulse here would fight the flee pathing.
        var away = mob.position().subtract(dragon.position());
        if (away.horizontalDistanceSqr() > 1.0E-4D) {
            var push = away.normalize().scale(0.34D);
            mob.setDeltaMovement(mob.getDeltaMovement().add(push.x, 0.18D, push.z));
            mob.hurtMarked = true; // makes the server send the velocity to clients
        }
    }

    /** Per-tick sweep: expires finished fear and drops entries whose mob is gone. */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (AFFECTED.isEmpty()) {
            return;
        }
        for (Iterator<Frightened> it = AFFECTED.iterator(); it.hasNext(); ) {
            Frightened entry = it.next();
            Mob mob = entry.mob();

            boolean gone = mob.isRemoved() || !mob.isAlive();
            boolean finished = entry.goal().ticksLeft() <= 0;

            if (!gone && !finished) {
                continue;
            }
            it.remove();
            if (gone) {
                // The goal selector belongs to the entity and dies with it. Nothing to undo.
                continue;
            }
            release(entry);
        }
    }

    /** Restores one mob to normal behaviour. */
    private static void release(Frightened entry) {
        Mob mob = entry.mob();
        entry.goal().expire();
        mob.goalSelector.removeGoal(entry.goal());
        if (entry.targetingSuppressed()) {
            mob.targetSelector.enableControlFlag(Goal.Flag.TARGET);
        }
    }

    /**
     * Ends all fear immediately. Called on shutdown, and by the harness between scenarios.
     *
     * <p>Restores mobs that are still loaded, so a reload does not leave a permanently fleeing mob.
     */
    public static void clearAll() {
        for (Frightened entry : AFFECTED) {
            if (!entry.mob().isRemoved()) {
                release(entry);
            }
        }
        AFFECTED.clear();
    }

    /** Number of mobs currently frightened. Exposed for the verification harness. */
    public static int frightenedCount() {
        return AFFECTED.size();
    }

    /** Ticks of fear left on a specific entity, or -1 if it is not frightened. */
    public static int ticksLeftFor(Entity entity) {
        for (Frightened entry : AFFECTED) {
            if (entry.mob() == entity) {
                return entry.goal().ticksLeft();
            }
        }
        return -1;
    }

    /** Whether a specific entity currently has its targeting suppressed. */
    public static boolean isTargetingSuppressed(LivingEntity entity) {
        for (Frightened entry : AFFECTED) {
            if (entry.mob() == entity) {
                return entry.targetingSuppressed();
            }
        }
        return false;
    }
}
