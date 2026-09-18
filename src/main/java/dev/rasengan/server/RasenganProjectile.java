package dev.rasengan.server;

import dev.rasengan.AbilityType;
import dev.rasengan.PalmAnchor;
import dev.rasengan.RasenganConfig;
import dev.rasengan.RasenganEntities;
import dev.rasengan.network.RasenganPayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The thrown Rasengan: a server-authoritative energy sphere in flight.
 *
 * <h2>Not built on vanilla projectiles</h2>
 * This extends {@link Entity} directly and implements its own flight and collision. It is not an
 * {@code Arrow}, does not extend {@code AbstractArrow} or {@code Projectile}, and borrows no arrow
 * item, model or texture. The visual is the same procedural energy sphere used during the cast.
 *
 * <h2>Collision cannot be tunnelled through</h2>
 * The naive approach - move the entity, then ask what it overlaps - misses targets whenever the
 * per-tick step is larger than the target's hitbox, which is exactly the case for a fast
 * projectile. Instead, each tick this sweeps the <em>segment</em> from the current position to the
 * intended next position:
 * <ol>
 *   <li>Clip the segment against terrain to find how far it can actually travel.</li>
 *   <li>Test that segment against every nearby entity's hitbox, inflated by the configured
 *       {@code hitbox_size}, using a slab-method ray/box intersection.</li>
 *   <li>Take the nearest entity hit along the segment. If there is none, take the block hit.</li>
 * </ol>
 * Because the test is continuous along the path rather than a point sample, the first entity in
 * the way always registers regardless of speed.
 *
 * <h2>Damage happens exactly once</h2>
 * {@link #resolved} latches on the first impact, and the entity is discarded in the same tick.
 * Every damage path is behind that latch and behind a {@code !level().isClientSide} check, so the
 * hit cannot be applied twice or applied by a client.
 */
public class RasenganProjectile extends Entity {

    /** Owner, so the sphere cannot hit the person who threw it and death messages attribute. */
    private UUID ownerUuid;

    /** Cached owner entity id, used by clients purely to colour-match the caster's cast. */
    private int ownerId = -1;

    /** Deterministic visual seed, mirrored from the cast so the sphere looks continuous. */
    private long visualSeed;

    /**
     * Which technique this projectile is.
     *
     * <p>Stored in {@link SynchedEntityData}, not a plain field. A plain field is only ever set on
     * the server, and entity spawn packets do not carry NBT, so every client saw the default value -
     * which is why a thrown Rasen Shuriken rendered as a Rasengan sphere. Synched data is replicated
     * on spawn and on change, so all nearby clients get the real variant.
     */
    private static final EntityDataAccessor<Integer> DATA_ABILITY =
            SynchedEntityData.defineId(RasenganProjectile.class, EntityDataSerializers.INT);

    private int lifeTicks;
    private double travelled;
    private boolean resolved;

    public RasenganProjectile(EntityType<? extends RasenganProjectile> type, Level level) {
        super(type, level);
        // This entity does its own collision; vanilla step/push physics must not interfere.
        this.noPhysics = true;
        // Belt and braces on the straight-line guarantee: this stops vanilla applying gravity to
        // the entity independently of the tick logic below, and it is synced to clients so the
        // client-side copy does not sag between position updates either.
        setNoGravity(true);
    }

    // ------------------------------------------------------------------
    // Spawning
    // ------------------------------------------------------------------

    /**
     * Creates and adds a projectile travelling along {@code direction}. Server side only.
     *
     * @param from      launch position, normally the caster's palm
     * @param direction unit aim vector, already validated by the server
     */
    public static RasenganProjectile launch(ServerLevel level, ServerPlayer owner,
                                            Vec3 from, Vec3 direction, long seed,
                                            AbilityType ability) {
        RasenganProjectile projectile = new RasenganProjectile(RasenganEntities.PROJECTILE.get(), level);

        projectile.setPos(from.x, from.y, from.z);
        projectile.ownerUuid = owner.getUUID();
        projectile.ownerId = owner.getId();
        projectile.visualSeed = seed;
        projectile.setAbility(ability);

        double speed = RasenganConfig.projectileSpeed(ability);
        projectile.setDeltaMovement(direction.normalize().scale(speed));

        // Face the direction of travel so any orientation-dependent visual reads correctly.
        projectile.setYRot((float) (Math.atan2(direction.x, direction.z) * (180.0D / Math.PI)));
        projectile.setXRot((float) (Math.atan2(direction.y,
                Math.sqrt(direction.x * direction.x + direction.z * direction.z)) * (180.0D / Math.PI)));

        level.addFreshEntity(projectile);
        return projectile;
    }

    // ------------------------------------------------------------------
    // Entity plumbing
    // ------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // The ability MUST be here rather than a plain field: it is read client-side by the
        // renderer, and only synched data reaches the client on spawn.
        builder.define(DATA_ABILITY, AbilityType.RASENGAN.id());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.lifeTicks = input.getIntOr("LifeTicks", 0);
        this.travelled = input.getDoubleOr("Travelled", 0.0D);
        this.visualSeed = input.getLongOr("VisualSeed", 0L);
        this.ownerId = input.getIntOr("OwnerId", -1);
        setAbility(AbilityType.byId(input.getIntOr("Ability", 0)));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("LifeTicks", lifeTicks);
        output.putDouble("Travelled", travelled);
        output.putLong("VisualSeed", visualSeed);
        output.putInt("OwnerId", ownerId);
        output.putInt("Ability", ability().id());
    }

    /** The sphere is pure energy: nothing can damage it, so it can never be destroyed early. */
    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    /** Not pickable: arrows and other projectiles should pass through rather than collide. */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        double max = RasenganConfig.maxEffectDistance();
        return distanceSq < max * max;
    }

    /** Visual seed for the client renderer. Falls back to the UUID so it is always populated. */
    public long visualSeed() {
        return visualSeed != 0L ? visualSeed : getUUID().getLeastSignificantBits();
    }

    public int ownerId() {
        return ownerId;
    }

    /** Ticks this projectile has been airborne, for the client's trail and spin phase. */
    public int lifeTicks() {
        return lifeTicks;
    }

    /** Reads the replicated ability. Correct on both sides. */
    public AbilityType ability() {
        return AbilityType.byId(this.entityData.get(DATA_ABILITY));
    }

    private void setAbility(AbilityType ability) {
        this.entityData.set(DATA_ABILITY, ability.id());
    }

    /**
     * The spin clock the client renders with: continues the held sphere's count so the rotation
     * phase carries across the throw rather than restarting.
     */
    public float spinTicks() {
        return RasenganConfig.castDurationTicks() + lifeTicks;
    }

    // ------------------------------------------------------------------
    // Flight
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();

        // Clients only interpolate and render; every decision below is the server's.
        if (!(level() instanceof ServerLevel serverLevel)) {
            // Keep the client-side copy moving smoothly between position updates so the trail
            // does not stutter if a packet is late.
            setPos(getX() + getDeltaMovement().x,
                    getY() + getDeltaMovement().y,
                    getZ() + getDeltaMovement().z);
            lifeTicks++;
            return;
        }

        if (resolved) {
            return;
        }

        lifeTicks++;

        // ---- Lifetime and range limits: nothing is allowed to fly forever ----
        if (lifeTicks > RasenganConfig.projectileLifetimeTicks()
                || travelled > RasenganConfig.projectileMaxRange(ability())) {
            expire(serverLevel);
            return;
        }

        Vec3 start = position();
        Vec3 velocity = getDeltaMovement();
        Vec3 intendedEnd = start.add(velocity);

        // ---- 1. How far can we go before terrain stops us? ----
        BlockHitResult blockHit = serverLevel.clip(new ClipContext(
                start, intendedEnd,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this));

        Vec3 segmentEnd = blockHit.getType() != HitResult.Type.MISS
                ? blockHit.getLocation()
                : intendedEnd;

        // ---- 2. Continuous entity sweep along that segment ----
        double radius = RasenganConfig.hitboxSize(ability());
        EntityHit entityHit = sweepForEntity(serverLevel, start, segmentEnd, radius);

        if (entityHit != null) {
            onHitEntity(serverLevel, entityHit.target, entityHit.point);
            return;
        }

        if (blockHit.getType() != HitResult.Type.MISS) {
            onHitBlock(serverLevel, blockHit.getLocation());
            return;
        }

        // ---- 3. Nothing in the way: advance ----
        travelled += start.distanceTo(intendedEnd);
        setPos(intendedEnd.x, intendedEnd.y, intendedEnd.z);

        // Velocity is deliberately left untouched. The sphere flies dead straight along the aim
        // vector it was launched with: no gravity, no drag, no decay, no wobble, no homing. It is
        // compressed energy, not a thrown rock. Combined with setNoGravity(true) in the
        // constructor, nothing in the game can bend its path.
    }

    /** Result of a successful entity sweep. */
    private record EntityHit(LivingEntity target, Vec3 point) {}

    /**
     * Finds the nearest living entity whose inflated hitbox the segment {@code start -> end}
     * passes through.
     *
     * <p>The search box is the segment's bounds inflated by the hit radius plus a small margin, so
     * the candidate list is cheap; the precise test is then done per candidate.
     */
    private EntityHit sweepForEntity(ServerLevel level, Vec3 start, Vec3 end, double radius) {
        AABB searchBox = new AABB(start, end).inflate(radius + 2.0D);

        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                candidate -> (Entity) candidate != this
                        && candidate.isAlive()
                        && !candidate.isSpectator()
                        && !isOwner(candidate));

        LivingEntity best = null;
        Vec3 bestPoint = null;
        double bestDistance = Double.MAX_VALUE;

        for (LivingEntity candidate : candidates) {
            AABB box = candidate.getBoundingBox().inflate(radius);
            Vec3 hit = segmentIntersectsBox(start, end, box);
            if (hit == null) {
                continue;
            }
            double distance = start.distanceToSqr(hit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
                bestPoint = hit;
            }
        }
        return best == null ? null : new EntityHit(best, bestPoint);
    }

    /**
     * Slab-method segment/box intersection, written out rather than borrowed so the collision
     * behaviour is entirely this mod's own.
     *
     * @return the entry point on the box, or {@code null} if the segment misses it
     */
    private static Vec3 segmentIntersectsBox(Vec3 start, Vec3 end, AABB box) {
        Vec3 delta = end.subtract(start);

        // A zero-length step cannot enter anything it is not already inside.
        if (delta.lengthSqr() < 1.0E-12D) {
            return box.contains(start) ? start : null;
        }

        double tEnter = 0.0D;
        double tExit = 1.0D;

        // Test each axis pair of slabs in turn, narrowing the surviving t range.
        double[] originAxis = {start.x, start.y, start.z};
        double[] deltaAxis = {delta.x, delta.y, delta.z};
        double[] minAxis = {box.minX, box.minY, box.minZ};
        double[] maxAxis = {box.maxX, box.maxY, box.maxZ};

        for (int axis = 0; axis < 3; axis++) {
            double origin = originAxis[axis];
            double move = deltaAxis[axis];
            double min = minAxis[axis];
            double max = maxAxis[axis];

            if (Math.abs(move) < 1.0E-9D) {
                // Parallel to this slab: if we start outside it, we can never be inside.
                if (origin < min || origin > max) {
                    return null;
                }
                continue;
            }

            double t1 = (min - origin) / move;
            double t2 = (max - origin) / move;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }

            tEnter = Math.max(tEnter, t1);
            tExit = Math.min(tExit, t2);

            if (tEnter > tExit) {
                return null;
            }
        }
        return start.add(delta.scale(tEnter));
    }

    private boolean isOwner(Entity candidate) {
        return ownerUuid != null && ownerUuid.equals(candidate.getUUID());
    }

    private ServerPlayer owner(ServerLevel level) {
        if (ownerUuid == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(ownerUuid);
    }

    // ------------------------------------------------------------------
    // Impact
    // ------------------------------------------------------------------

    /** Direct entity hit: the full configured damage, once, then teardown. */
    private void onHitEntity(ServerLevel level, LivingEntity target, Vec3 point) {
        if (resolved) {
            return;
        }
        resolved = true;

        ServerPlayer owner = owner(level);

        // Capture the flight direction before anything zeroes our velocity.
        Vec3 flight = getDeltaMovement();

        // Damage FIRST. hurtServer applies its own knockback, so setting velocity beforehand would
        // simply be overwritten by the damage resolution in the same tick.
        applyDamage(level, owner, target);

        // Then the launch, so ours is the velocity that survives.
        if (ability().isShuriken()) {
            applyLaunch(level, target, flight);
        }

        // Broadcast before discarding, and regardless of whether the target survived, so the
        // full impact animation always plays.
        ServerCastManager.broadcastImpact(level, point,
                owner != null ? owner.getId() : getId(),
                RasenganPayloads.CastImpact.KIND_ENTITY, ability(), spinTicks());

        if (RasenganConfig.SERVER.blockDamageEnabled.get()) {
            ServerCastManager.applyEnvironmentDamage(level, owner, point);
        }
        cleanUp();
    }

    /** Terrain hit: visuals and optional block damage, but never entity damage. */
    private void onHitBlock(ServerLevel level, Vec3 point) {
        if (resolved) {
            return;
        }
        resolved = true;

        ServerPlayer owner = owner(level);
        ServerCastManager.broadcastImpact(level, point,
                owner != null ? owner.getId() : getId(),
                RasenganPayloads.CastImpact.KIND_TERRAIN, ability(), spinTicks());

        if (RasenganConfig.SERVER.blockDamageEnabled.get()) {
            ServerCastManager.applyEnvironmentDamage(level, owner, point);
        }
        cleanUp();
    }

    /** Ran out of range or lifetime without hitting anything: fizzle, no damage. */
    private void expire(ServerLevel level) {
        if (resolved) {
            return;
        }
        resolved = true;

        ServerPlayer owner = owner(level);
        ServerCastManager.broadcastImpact(level, position(),
                owner != null ? owner.getId() : getId(),
                RasenganPayloads.CastImpact.KIND_WHIFF, ability(), spinTicks());
        cleanUp();
    }

    private void applyDamage(ServerLevel level, ServerPlayer owner, LivingEntity target) {
        var holder = level.registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(ServerCastManager.DAMAGE_TYPE);

        // directEntity is the sphere, causingEntity is the caster, so kill attribution and
        // death messages both point at the player.
        DamageSource source = new DamageSource((net.minecraft.core.Holder<DamageType>) holder, this, owner);

        if (RasenganConfig.SERVER.bypassInvulnerabilityFrames.get()) {
            target.invulnerableTime = 0;
        }
        target.hurtServer(level, source, (float) RasenganConfig.damage(ability()));
    }

    /**
     * Launches the struck entity on a long ballistic arc.
     *
     * <h2>Why an absolute set, not a push</h2>
     * {@code push()} and vanilla knockback both <em>add</em> a small impulse and are scaled down by
     * the target's {@code KNOCKBACK_RESISTANCE} attribute - an iron golem or armoured mob absorbs
     * most of it. {@link Entity#setDeltaMovement} writes the velocity outright, so the launch is
     * exact and resistance-independent by construction.
     *
     * <h2>Why players need an explicit packet</h2>
     * Players simulate their own movement and reconcile against the server. A server-side velocity
     * write is invisible to them: their client keeps predicting from its own state and the launch is
     * discarded, so a hit player barely moves while mobs fly. {@link ClientboundSetEntityMotionPacket}
     * tells that client to adopt the velocity. For non-players, {@code hurtMarked} makes the tracker
     * broadcast the same packet to observers.
     *
     * <h2>Magnitude</h2>
     * Derived from Minecraft's own air physics rather than a guessed multiplier. Per tick a living
     * entity has {@code vy = (vy - 0.08) * 0.98} and {@code vx *= 0.91}, so horizontal travel
     * converges to {@code vx / (1 - 0.91)} - about {@code 11.1 * vx}. The defaults below were solved
     * numerically against that model for roughly 225 blocks with a ~20 block peak.
     */
    private void applyLaunch(ServerLevel level, LivingEntity target, Vec3 flight) {
        double horizontalSpeed = RasenganConfig.SERVER.shurikenLaunchSpeed.get();
        double lift = RasenganConfig.SERVER.shurikenLaunchLift.get();
        if (horizontalSpeed <= 0.0D && lift <= 0.0D) {
            return; // launch disabled by config
        }

        // Flatten the flight vector: the arc's direction comes from where the shuriken was
        // travelling, but its climb comes from `lift`, so a downward shot still launches upward
        // rather than driving the target into the ground.
        Vec3 horizontal = new Vec3(flight.x, 0.0D, flight.z);
        if (horizontal.lengthSqr() < 1.0E-6D) {
            horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        }
        horizontal = horizontal.normalize();

        Vec3 launch = new Vec3(
                horizontal.x * horizontalSpeed,
                lift,
                horizontal.z * horizontalSpeed);

        target.setDeltaMovement(launch);
        // Tells the entity tracker this velocity is authoritative and must be broadcast.
        target.hurtMarked = true;

        if (target instanceof ServerPlayer player) {
            // Without this the player's own prediction discards the launch entirely.
            player.connection.send(new ClientboundSetEntityMotionPacket(target));
        }
    }

    /** Removes the entity immediately so no scheduled state outlives the impact. */
    private void cleanUp() {
        setDeltaMovement(Vec3.ZERO);
        discard();
    }
}
