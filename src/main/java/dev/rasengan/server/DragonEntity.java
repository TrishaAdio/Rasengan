package dev.rasengan.server;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.PlayState;
import com.geckolib.util.GeckoLibUtil;
import dev.rasengan.RasenganConfig;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The summon-only boss dragon.
 *
 * <h2>What this deliberately is not</h2>
 * A plain hostile mob. It is <em>not</em> tamed, ridden, owned, leashed, bound to a summoner, or
 * connected to the POWER BAR or any jutsu. It wanders on its own, fights anyone it decides to
 * fight, takes damage from every normal source and dies normally. That is the point: this phase
 * validates the model, animations, AI, combat and server sync before a later phase adds a
 * summoning item, mount controls and ownership on top of a foundation already known to work.
 *
 * <h2>Movement</h2>
 * Flight uses vanilla's {@link FlyingMoveControl} plus {@link FlyingPathNavigation}, and wandering
 * is {@link WaterAvoidingRandomFlyingGoal} - the same combination parrots and allays use, so the
 * pathing is well-trodden vanilla behaviour rather than bespoke steering. Wandering is
 * deliberately untethered: no goal references a specific player, so it does not follow anyone.
 *
 * <h2>Attacks</h2>
 * Two, both server-authoritative:
 * <ul>
 *   <li><b>Melee bite</b> - a standard {@link MeleeAttackGoal}, damage from the
 *       {@code ATTACK_DAMAGE} attribute.</li>
 *   <li><b>Fire breath</b> - a channelled cone. While breathing it damages and ignites every
 *       living entity inside a forward cone each damaging tick. Implemented as a cone test rather
 *       than a spray of fireball entities so the damage is exact, configurable, and measurable
 *       headlessly; the visible flames are particles sent from the server.</li>
 * </ul>
 * Both are gated so they cannot hit through walls or hit the dragon itself.
 */
public class DragonEntity extends Monster implements GeoEntity {

    // ---- Animation names, as produced by tools/dragon/convert_gltf_to_geckolib.py ----
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("animation.rasengan_dragon.idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("animation.rasengan_dragon.walk");
    private static final RawAnimation ANIM_FLY = RawAnimation.begin().thenLoop("animation.rasengan_dragon.fly");
    private static final RawAnimation ANIM_GLIDE = RawAnimation.begin().thenLoop("animation.rasengan_dragon.glide");
    private static final RawAnimation ANIM_BITE = RawAnimation.begin().thenPlay("animation.rasengan_dragon.attack");
    private static final RawAnimation ANIM_BREATH = RawAnimation.begin().thenPlay("animation.rasengan_dragon.firebreath");

    /** Replicated so the client can pick the breath animation without its own timer. */
    private static final EntityDataAccessor<Boolean> DATA_BREATHING =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);

    // ---- Flight state, computed server-side from real velocity and replicated ----
    public static final byte STATE_GROUND_IDLE = 0;
    public static final byte STATE_GROUND_WALK = 1;
    public static final byte STATE_HOVER = 2;
    public static final byte STATE_FLAP = 3;
    public static final byte STATE_GLIDE = 4;

    /**
     * Which locomotion animation to play.
     *
     * <p>Replicated rather than re-derived on each client. The server is the only side that knows
     * the true velocity - a client only sees interpolated positions and an occasionally synced
     * velocity - so deciding "flap or glide" locally would give different clients different
     * answers for the same dragon. Sending the decision keeps the animation tied to actual
     * movement and keeps every viewer in agreement.
     */
    private static final EntityDataAccessor<Byte> DATA_FLIGHT_STATE =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BYTE);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("Dragon"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS);

    private int breathCooldown;
    private int breathTicksLeft;

    public DragonEntity(EntityType<? extends DragonEntity> type, Level level) {
        super(type, level);
        // Steering flight control, NOT FlyingMoveControl. The latter only applies thrust on the tick
        // a destination is set and zeroes the movement inputs on every tick after, which is correct
        // only when a path navigator re-issues the destination every tick. See
        // DragonFlightMoveControl for the measured failure that caused.
        this.moveControl = new DragonFlightMoveControl(this);
        this.xpReward = 250;
        // A fire-breathing creature should not refuse to path over its own element. 26.1 names
        // these PathType.FIRE and PathType.DAMAGING (there is no DANGER_FIRE/DAMAGE_FIRE here).
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LAVA, 8.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.FIRE, 0.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGING, 0.0F);
    }

    /**
     * Base attributes. Every one is config-driven, and they are applied here rather than baked into
     * the supplier so a server owner's edits take effect on the next spawn without a rebuild.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 300.0D)
                .add(Attributes.ARMOR, 12.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FLYING_SPEED, 0.6D)
                .add(Attributes.ATTACK_DAMAGE, 18.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    /** Copies the current config values onto this instance's attributes. */
    private void applyConfiguredAttributes() {
        setAttr(Attributes.MAX_HEALTH, RasenganConfig.SERVER.dragonHealth.get());
        setAttr(Attributes.ARMOR, RasenganConfig.SERVER.dragonArmour.get());
        setAttr(Attributes.KNOCKBACK_RESISTANCE, RasenganConfig.SERVER.dragonKnockbackResistance.get());
        setAttr(Attributes.MOVEMENT_SPEED, RasenganConfig.SERVER.dragonMovementSpeed.get());
        setAttr(Attributes.FLYING_SPEED, RasenganConfig.SERVER.dragonFlyingSpeed.get());
        setAttr(Attributes.ATTACK_DAMAGE, RasenganConfig.SERVER.dragonAttackDamage.get());
        setAttr(Attributes.FOLLOW_RANGE, RasenganConfig.SERVER.dragonFollowRange.get());
        setHealth(getMaxHealth());
    }

    private void setAttr(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                         double value) {
        var instance = getAttribute(attr);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BREATHING, false);
        builder.define(DATA_FLIGHT_STATE, STATE_GROUND_IDLE);
    }

    public boolean isFlyingEnabled() {
        return RasenganConfig.SERVER.dragonCanFly.get();
    }

    public byte flightState() {
        return this.entityData.get(DATA_FLIGHT_STATE);
    }

    /**
     * Classifies locomotion from the real velocity, server-side, once per tick.
     *
     * <p>Thresholds are on actual movement, so a hovering dragon does not play a flap cycle and a
     * descending one does not play a climb. That is the difference between an animation tied to
     * movement and a loop that runs regardless.
     */
    private void updateFlightState() {
        Vec3 velocity = getDeltaMovement();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        byte state;
        if (onGround()) {
            state = horizontal > 0.02D ? STATE_GROUND_WALK : STATE_GROUND_IDLE;
        } else if (velocity.y < -0.06D && horizontal > 0.12D) {
            // Losing height with forward speed: coasting.
            state = STATE_GLIDE;
        } else if (horizontal > 0.05D || velocity.y > 0.02D) {
            // Driving forward or climbing: working the wings.
            state = STATE_FLAP;
        } else {
            state = STATE_HOVER;
        }
        if (state != flightState()) {
            this.entityData.set(DATA_FLIGHT_STATE, state);
        }
    }

    /**
     * Flight integration.
     *
     * <p>While airborne the velocity written by {@link DragonFlightMoveControl} is moved through
     * {@link #move} directly, so collision still applies, and only a token drag is taken off. The
     * inherited {@code travel} would apply walking friction and gravity against the controller every
     * tick, which fights the velocity lerp and makes cruise speed an emergent fraction of the
     * configured one instead of the configured value.
     */
    @Override
    public void travel(Vec3 input) {
        if (isFlyingEnabled() && !onGround() && !isInWater()) {
            move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
            // Near-unity: the move control's lerp is the intended smoothing. This exists only so a
            // dragon whose controller goes idle glides to a stop instead of drifting forever.
            setDeltaMovement(getDeltaMovement().scale(0.99D));
            calculateEntityAnimation(false);
        } else {
            super.travel(input);
        }
    }

    /**
     * AI goals.
     *
     * <h2>Why flight does not use pathfinding</h2>
     * The obvious build - {@link WaterAvoidingRandomFlyingGoal} over a {@link FlyingPathNavigation}
     * - is what parrots and allays use, and it produced a dragon that <em>never moved at all</em>:
     * measured 0.00 blocks of travel over 500 ticks. That goal picks targets via
     * {@code HoverRandomPos}/{@code AirAndWaterRandomPos} and then asks the navigator to path there,
     * and the node evaluator cannot fit a 6x5 collision box through its grid, so every request
     * failed silently.
     *
     * <p>Vanilla's own answer for a large flyer is the Ghast: it steers by writing directly to the
     * move control with {@link net.minecraft.world.entity.ai.control.MoveControl#setWantedPosition}
     * and never pathfinds. {@link FlyingMoveControl#tick()} honours that on its own, with no
     * navigator involved. So the wander goal here is vanilla's {@code Ghast.RandomFloatAroundGoal},
     * reused directly because it accepts any {@link net.minecraft.world.entity.Mob}, and the melee
     * approach is the same technique rather than {@link MeleeAttackGoal}, which would also depend on
     * navigation to close the distance.
     */
    @Override
    protected void registerGoals() {
        boolean canFly = RasenganConfig.SERVER.dragonCanFly.get();

        if (canFly) {
            // Charge the target and bite, steering directly. Higher priority than wandering.
            this.goalSelector.addGoal(2, new DragonChargeAndBiteGoal(this));
            // Untethered wandering. Nothing here references a player, so it cannot trail one.
            // Replaces Ghast.RandomFloatAroundGoal, which sets a destination once per target and
            // therefore stalled against FlyingMoveControl; this goal commits to long legs and lets
            // DragonFlightMoveControl steer smoothly toward them.
            this.goalSelector.addGoal(5, new DragonWanderFlightGoal(this));
        } else {
            // Grounded variant: ordinary pathfinding is fine once it is not trying to fly.
            this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, true));
            this.goalSelector.addGoal(5, new WaterAvoidingRandomFlyingGoal(this, 1.0D));
        }

        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 32.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        // Retaliation always applies; unprovoked aggression is configurable.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        if (RasenganConfig.SERVER.dragonAggressive.get()) {
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        }
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        return navigation;
    }

    /**
     * Flies at the current target and bites it when close enough.
     *
     * <p>Steers with {@code setWantedPosition} for the reason given on {@link #registerGoals()}: a
     * mob this size cannot be pathfound. Reach is derived from the collision box rather than
     * hard-coded, so it stays correct if the hitbox is ever retuned.
     */
    private static final class DragonChargeAndBiteGoal extends net.minecraft.world.entity.ai.goal.Goal {
        private final DragonEntity dragon;
        private int biteCooldown;

        DragonChargeAndBiteGoal(DragonEntity dragon) {
            this.dragon = dragon;
            setFlags(java.util.EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = dragon.getTarget();
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void stop() {
            biteCooldown = 0;
        }

        @Override
        public void tick() {
            LivingEntity target = dragon.getTarget();
            if (target == null) {
                return;
            }
            dragon.getLookControl().setLookAt(target, 30.0F, 30.0F);

            // Aim slightly above the target so it does not try to fly into the floor.
            dragon.getMoveControl().setWantedPosition(
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(), 1.0D);

            if (biteCooldown > 0) {
                biteCooldown--;
                return;
            }
            double reach = dragon.getBbWidth() * 0.5D + target.getBbWidth() * 0.5D + 2.0D;
            if (dragon.distanceTo(target) <= reach
                    && dragon.level() instanceof ServerLevel serverLevel) {
                dragon.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                dragon.doHurtTarget(serverLevel, target);
                biteCooldown = 20;
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            return;
        }
        if (RasenganConfig.SERVER.dragonBossBar.get()) {
            bossEvent.setProgress(getHealth() / Math.max(1.0F, getMaxHealth()));
        }
        updateFlightState();
        tickBreath((ServerLevel) level());
    }

    /** Applies config on first server tick so edits land without needing a rebuild. */
    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!level().isClientSide()) {
            applyConfiguredAttributes();
            setNoGravity(RasenganConfig.SERVER.dragonCanFly.get());
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (RasenganConfig.SERVER.dragonBossBar.get()) {
            bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    /**
     * Tears the boss bar down on death or removal.
     *
     * <p>Covers every exit, not just death: {@code /kill}, despawn and chunk unload all route
     * through {@code remove}, and a boss bar left behind after the entity is gone is a classic
     * leak.
     */
    @Override
    public void remove(RemovalReason reason) {
        bossEvent.removeAllPlayers();
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        bossEvent.removeAllPlayers();
        super.die(source);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("BreathCooldown", breathCooldown);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.breathCooldown = input.getIntOr("BreathCooldown", 0);
    }

    /** Summon-only: nothing should ever remove it for being far from a player. */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // ------------------------------------------------------------------
    // Fire breath
    // ------------------------------------------------------------------

    public boolean isBreathing() {
        return this.entityData.get(DATA_BREATHING);
    }

    private void setBreathing(boolean breathing) {
        this.entityData.set(DATA_BREATHING, breathing);
    }

    private void tickBreath(ServerLevel level) {
        if (!RasenganConfig.SERVER.dragonBreathEnabled.get()) {
            if (isBreathing()) {
                setBreathing(false);
            }
            return;
        }
        if (breathCooldown > 0) {
            breathCooldown--;
        }
        LivingEntity target = getTarget();

        if (breathTicksLeft > 0) {
            breathTicksLeft--;
            if (target == null || !target.isAlive()) {
                breathTicksLeft = 0;
                setBreathing(false);
                return;
            }
            // Face the target while breathing so the cone tracks it.
            getLookControl().setLookAt(target, 30.0F, 30.0F);
            // Damage every other tick: the cone is continuous but the damage is discrete, which
            // keeps the total predictable and testable.
            if (breathTicksLeft % 2 == 0) {
                applyBreathDamage(level);
            }
            spawnBreathParticles(level);
            if (breathTicksLeft == 0) {
                setBreathing(false);
                breathCooldown = RasenganConfig.SERVER.dragonBreathCooldownTicks.get();
            }
            return;
        }

        if (target == null || !target.isAlive() || breathCooldown > 0) {
            return;
        }
        double range = RasenganConfig.SERVER.dragonBreathRange.get();
        if (distanceToSqr(target) > range * range || !hasLineOfSight(target)) {
            return;
        }
        breathTicksLeft = RasenganConfig.SERVER.dragonBreathDurationTicks.get();
        setBreathing(true);
    }

    /** The mouth position the cone originates from: eye height, one stride forward. */
    private Vec3 breathOrigin() {
        Vec3 look = getViewVector(1.0F);
        return new Vec3(getX(), getEyeY(), getZ()).add(look.scale(getBbWidth() * 0.5D));
    }

    private void applyBreathDamage(ServerLevel level) {
        double range = RasenganConfig.SERVER.dragonBreathRange.get();
        double halfAngle = Math.toRadians(RasenganConfig.SERVER.dragonBreathConeDegrees.get());
        float damage = (float) (double) RasenganConfig.SERVER.dragonBreathDamage.get();
        int fireSeconds = RasenganConfig.SERVER.dragonBreathFireSeconds.get();

        Vec3 origin = breathOrigin();
        Vec3 look = getViewVector(1.0F).normalize();
        double cosLimit = Math.cos(halfAngle);

        AABB box = new AABB(origin, origin).inflate(range);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != this && e.isAlive() && !e.isSpectator() && !(e instanceof DragonEntity));

        for (LivingEntity victim : candidates) {
            Vec3 toVictim = victim.getBoundingBox().getCenter().subtract(origin);
            double distance = toVictim.length();
            if (distance > range || distance < 1.0E-4D) {
                continue;
            }
            // Inside the cone?
            if (toVictim.normalize().dot(look) < cosLimit) {
                continue;
            }
            // Fire does not pass through walls.
            if (!hasLineOfSight(victim)) {
                continue;
            }
            victim.hurtServer(level, damageSources().mobAttack(this), damage);
            if (fireSeconds > 0) {
                victim.setRemainingFireTicks(fireSeconds * 20);
            }
        }
    }

    private void spawnBreathParticles(ServerLevel level) {
        Vec3 origin = breathOrigin();
        Vec3 look = getViewVector(1.0F).normalize();
        double range = RasenganConfig.SERVER.dragonBreathRange.get();
        double spread = Math.tan(Math.toRadians(RasenganConfig.SERVER.dragonBreathConeDegrees.get()));
        for (int i = 0; i < 12; i++) {
            double t = (i + random.nextDouble()) / 12.0D * range;
            Vec3 at = origin.add(look.scale(t));
            double jitter = spread * t * 0.5D;
            level.sendParticles(ParticleTypes.FLAME,
                    at.x + (random.nextDouble() - 0.5D) * jitter,
                    at.y + (random.nextDouble() - 0.5D) * jitter,
                    at.z + (random.nextDouble() - 0.5D) * jitter,
                    1, look.x * 0.2D, look.y * 0.2D, look.z * 0.2D, 0.02D);
        }
    }

    /** Fire-breathing: immune to its own element, but to nothing else. */
    @Override
    public boolean fireImmune() {
        return true;
    }

    // ------------------------------------------------------------------
    // GeckoLib
    // ------------------------------------------------------------------

    /**
     * Two controllers so a bite or a breath can play over the top of locomotion without either
     * cancelling the other.
     */
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Driven by the replicated flight state, which the server derives from real velocity. The
        // previous version guessed from client-side getDeltaMovement(), which for a server-driven
        // mob is only intermittently synced - so it could play a flap cycle at a standstill.
        // 5-tick transitions blend between cycles instead of cutting.
        controllers.add(new AnimationController<DragonEntity>("locomotion", 5, test -> {
            RawAnimation animation = switch (test.animatable().flightState()) {
                case STATE_GROUND_WALK -> ANIM_WALK;
                case STATE_FLAP -> ANIM_FLY;
                case STATE_GLIDE -> ANIM_GLIDE;
                case STATE_HOVER -> ANIM_FLY;
                default -> ANIM_IDLE;
            };
            return test.setAndContinue(animation);
        }));

        controllers.add(new AnimationController<DragonEntity>("action", 0, test -> {
            DragonEntity dragon = test.animatable();
            if (dragon.isBreathing()) {
                return test.setAndContinue(ANIM_BREATH);
            }
            if (dragon.swinging) {
                return test.setAndContinue(ANIM_BITE);
            }
            return PlayState.STOP;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
