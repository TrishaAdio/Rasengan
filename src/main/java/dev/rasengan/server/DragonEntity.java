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
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jspecify.annotations.Nullable;
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

    /**
     * True while the dragon exists but must not be drawn.
     *
     * <p>Used for the summoning pre-spawn: the entity is created on the cinematic's <em>first</em>
     * tick and held hidden until the reveal beat, so that entity construction, attribute setup, chunk
     * work and - on the client - GeckoLib model and texture loading all happen while the screen is
     * full of smoke, instead of landing on the frame the smoke clears. That frame used to carry the
     * whole spawn cost, which is a real frame-time spike and is indistinguishable from a camera hitch.
     */
    private static final EntityDataAccessor<Boolean> DATA_HIDDEN =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * Total length of the summoning cinematic this dragon was spawned by, or 0 if it was not.
     *
     * <p>Replicated because the client needs it to drive the entrance wing phase. While
     * {@code tickCount < entranceTotal} the dragon is inside its arrival sequence, and because it is
     * spawned on cinematic tick 0, {@code tickCount} <em>is</em> the cinematic tick - so both sides
     * share a clock without another packet or a second counter to keep in step.
     */
    private static final EntityDataAccessor<Integer> DATA_ENTRANCE_TOTAL =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.INT);

    /**
     * Spawn damage-immunity, replicated purely so the client can show it.
     *
     * <p>The authoritative check is {@link #isInvulnerableTo}, which reads the server-side counter -
     * this flag is a mirror for rendering and must never be the thing that decides whether damage
     * lands.
     */
    private static final EntityDataAccessor<Boolean> DATA_IMMUNE =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BOOLEAN);

    /** Ride phase; see {@link DragonRideControl}. Replicated for animation and the bank angle. */
    private static final EntityDataAccessor<Byte> DATA_RIDE_PHASE =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.BYTE);

    /**
     * {@code tickCount} at which the current rider mounted, or -1.
     *
     * <p>Replicated as a single absolute value rather than as a counting-down blend, so it costs one
     * datawatcher write per mount instead of one per tick - and, more importantly, so both sides compute
     * the same blend position from the same clock. A counter ticked independently on each side would
     * drift, and the rider would be drawn part-way up the head on one screen and on top of it on another.
     */
    private static final EntityDataAccessor<Integer> DATA_MOUNT_TICK =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.INT);

    /**
     * Bank angle in degrees, replicated for the renderer.
     *
     * <p>Entities have no roll field, so this is carried separately and applied by
     * {@code DragonRenderer}. It deliberately does <em>not</em> affect the rider's anchor - see
     * {@link dev.rasengan.DragonAnchor#basis}.
     */
    private static final EntityDataAccessor<Float> DATA_BANK =
            SynchedEntityData.defineId(DragonEntity.class, EntityDataSerializers.FLOAT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("Dragon"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.PROGRESS);

    private int breathCooldown;
    private int breathTicksLeft;

    /**
     * Length of the mount step-up blend, in ticks.
     *
     * <p>8 ticks = 0.4 s. Long enough to read as stepping up, short enough that a player who mounted
     * mid-combat is not left drifting.
     */
    public static final int MOUNT_BLEND_TICKS = 8;

    /**
     * Ticks of spawn damage-immunity left. Server-side and authoritative.
     *
     * <p>Counted down once per tick in {@link #tick()} and never re-armed except by
     * {@link #beginSpawnGrace}, which is called exactly once per summon from the reveal. Combined with
     * the one-dragon-per-player rule, there is no path by which a player can refresh it.
     */
    private int spawnImmunityTicks;

    /** Ticks the dragon stays grounded at the summon point. Cut short by a launch. */
    private int perchTicks;

    /** Launch progress, 0 while not launching. */
    private int launchTicks;

    private final DragonRideControl.InputTracker rideInput = new DragonRideControl.InputTracker();

    /**
     * Who summoned this dragon, if anyone.
     *
     * <p>Bookkeeping for the one-dragon-per-player limit and nothing else. It confers no control, no
     * taming, no riding and no loyalty - the dragon is as hostile to its summoner as to anyone else.
     * Those mechanics remain out of scope.
     */
    private UUID summonerUuid;

    /** Ticks remaining of the arrival flourish, during which it holds position and roars. */
    private int entranceTicks;

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
        builder.define(DATA_HIDDEN, false);
        builder.define(DATA_ENTRANCE_TOTAL, 0);
        builder.define(DATA_IMMUNE, false);
        builder.define(DATA_RIDE_PHASE, DragonRideControl.PHASE_NONE);
        builder.define(DATA_BANK, 0.0F);
        builder.define(DATA_MOUNT_TICK, -1);
    }

    public boolean isFlyingEnabled() {
        return RasenganConfig.SERVER.dragonCanFly.get();
    }

    public void setSummoner(UUID summoner) {
        this.summonerUuid = summoner;
    }

    public UUID summoner() {
        return this.summonerUuid;
    }

    /**
     * Begins the arrival sequence, hidden.
     *
     * <p>Called on the cinematic's first tick, well before the dragon is visible. The entity holds
     * position, takes no damage and runs no AI until {@link #revealFromSummon()}, so the whole of its
     * construction cost is paid during stages A and B rather than on the reveal frame.
     *
     * @param cinematicTicks total length of the summoning sequence, in ticks
     */
    public void beginHiddenEntrance(int cinematicTicks) {
        this.entranceTicks = cinematicTicks;
        this.entityData.set(DATA_ENTRANCE_TOTAL, cinematicTicks);
        this.entityData.set(DATA_HIDDEN, true);
        this.entityData.set(DATA_FLIGHT_STATE, STATE_FLAP);
        setInvulnerable(true);
        setNoAi(true);
    }

    /**
     * The reveal beat: becomes visible and takes over its own fate.
     *
     * <p>Invulnerability and the AI freeze are lifted here, not at the end of the sequence, so that
     * from the instant a player can see the dragon it is a real, hittable boss. The boss bar is also
     * added here rather than on spawn, so no bar appears above an invisible entity.
     */
    public void revealFromSummon() {
        this.entityData.set(DATA_HIDDEN, false);
        setInvulnerable(false);
        setNoAi(false);
        if (RasenganConfig.SERVER.dragonBossBar.get() && level() instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.players()) {
                if (player.distanceToSqr(this) <= 64.0D * 64.0D) {
                    bossEvent.addPlayer(player);
                }
            }
        }
    }

    /** Aborts a sequence that will never reach its reveal, leaving nothing behind. */
    public void cancelHiddenEntrance() {
        this.entranceTicks = 0;
        this.entityData.set(DATA_ENTRANCE_TOTAL, 0);
        bossEvent.removeAllPlayers();
        discard();
    }

    /** True while drawn nowhere: pre-reveal, or aborted. */
    public boolean isHiddenForSummon() {
        return this.entityData.get(DATA_HIDDEN);
    }

    /** Total cinematic length, or 0 if this dragon was not summoned by one. */
    public int entranceTotalTicks() {
        return this.entityData.get(DATA_ENTRANCE_TOTAL);
    }

    public boolean isEntering() {
        return this.entranceTicks > 0;
    }

    // ------------------------------------------------------------------
    // Spawn grace: grounded, and immune to everything
    // ------------------------------------------------------------------

    /**
     * Starts the post-summon grace period. Called once, from the reveal.
     *
     * <p>Two separate clocks, deliberately:
     * <ul>
     *   <li><b>Immunity</b> runs the full configured duration no matter what. The guarantee is
     *       "zero damage for exactly 10 seconds after the summon", and a rider launching early must not
     *       be able to shorten it.</li>
     *   <li><b>The grounded perch</b> ends at the same moment <em>or</em> when the rider launches,
     *       whichever comes first. Holding a player who has just double-tapped W on the ground for the
     *       remaining seconds would read as the mount being broken, so a launch cuts the hold short.
     *       The alternative - deferring the launch until the grace ends - was rejected for exactly that
     *       reason.</li>
     * </ul>
     */
    public void beginSpawnGrace() {
        int ticks = RasenganConfig.mountSpawnImmunityTicks();
        this.spawnImmunityTicks = ticks;
        this.perchTicks = ticks;
        this.entityData.set(DATA_IMMUNE, ticks > 0);
    }

    /** Ticks of spawn immunity remaining. Exposed for the verification harness. */
    public int spawnImmunityTicks() {
        return spawnImmunityTicks;
    }

    /** Ticks of grounded perch remaining. */
    public int perchTicks() {
        return perchTicks;
    }

    public boolean isSpawnImmune() {
        return spawnImmunityTicks > 0;
    }

    /**
     * The real damage gate.
     *
     * <p>Overriding {@code isInvulnerableTo} puts the check on the path {@code hurtServer} consults
     * before anything else, so it blocks every source without exception - players, mobs, fire, fall,
     * lava, {@code /damage}, the void. A large temporary health pool would not: that is not immunity,
     * it is a bigger number, and it still shows damage numbers, still triggers hurt animations, still
     * lets a big enough hit through.
     *
     * <p>{@code isInvulnerableToBase} is still consulted via {@code super}, so the vanilla rules
     * (creative-mode damage, {@code DamageTypeTags.BYPASSES_INVULNERABILITY}) are unchanged outside the
     * grace window.
     */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        if (spawnImmunityTicks > 0) {
            return true;
        }
        // A dragon that has not been revealed yet is also untouchable; see beginHiddenEntrance.
        if (isHiddenForSummon()) {
            return true;
        }
        return super.isInvulnerableTo(level, source);
    }

    // ------------------------------------------------------------------
    // Mounting
    // ------------------------------------------------------------------

    /**
     * Whether {@code player} is allowed to mount.
     *
     * <p>Ownership only - this says nothing about where they are looking or how far away they are, which
     * {@link DragonMountManager} checks separately. Kept apart so the refusal message can be specific.
     */
    public boolean mayMount(Player player) {
        if (!RasenganConfig.SERVER.mountEnabled.get()) {
            return false;
        }
        if (!RasenganConfig.SERVER.mountSummonerOnly.get()) {
            return true;
        }
        return summonerUuid != null && summonerUuid.equals(player.getUUID());
    }

    /** Only ever one rider, and only a player. */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && passenger instanceof Player;
    }

    /** The rider, or null. */
    @Nullable
    public Player rider() {
        return getFirstPassenger() instanceof Player player ? player : null;
    }

    public byte ridePhase() {
        return this.entityData.get(DATA_RIDE_PHASE);
    }

    private void setRidePhase(byte phase) {
        if (ridePhase() != phase) {
            this.entityData.set(DATA_RIDE_PHASE, phase);
        }
    }

    /** Bank angle in degrees, for the renderer. */
    public float bankAngle() {
        return this.entityData.get(DATA_BANK);
    }

    /**
     * Where the rider's feet go.
     *
     * <p>Delegates to {@link dev.rasengan.DragonAnchor}, the COMMON class both sides share, so the
     * server's authoritative placement and the client's rendering cannot disagree. Called every tick
     * from {@code positionRider}, and the normal entity render path interpolates between ticks - which
     * is what makes the rider track the head smoothly rather than in 20 Hz steps.
     *
     * <p>The animation phase comes from {@code tickCount}, matching the phase forced onto the GeckoLib
     * controller in {@link #registerControllers}. Without that forcing the rendered head and this
     * calculation would drift apart per client.
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions,
                                               float scale) {
        boolean airborne = !onGround();
        Vec3 anchor = dev.rasengan.DragonAnchor.riderOffset(
                getYRot(), getXRot(), (float) tickCount, airborne);

        // ---- the step-up blend ----
        //
        // Without this, mounting teleports the player from the ground to 4.6 blocks up in a single tick.
        // The blend eases them from the base of the head up onto the plate on a smoothstep, so it reads
        // as climbing rather than as a snap.
        //
        // The start point is derived, not remembered: the head's horizontal position at the dragon's own
        // foot height. That is deterministic on both sides from replicated state alone, where the
        // player's actual pre-mount position would have had to be captured and synced.
        int mountTick = this.entityData.get(DATA_MOUNT_TICK);
        if (mountTick < 0) {
            return anchor;
        }
        int elapsed = tickCount - mountTick;
        if (elapsed < 0 || elapsed >= MOUNT_BLEND_TICKS) {
            return anchor;
        }
        float k = (elapsed + 1.0F) / MOUNT_BLEND_TICKS;
        float eased = k * k * (3.0F - 2.0F * k);
        Vec3 from = new Vec3(anchor.x, 0.0D, anchor.z);
        return from.add(anchor.subtract(from).scale(eased));
    }

    /**
     * Mid-air dismount: the rider becomes an ordinary falling entity.
     *
     * <p>Fall damage applies by default - stepping off a flying mount at altitude is exactly as
     * dangerous as stepping off anything else that high, and making it free would turn the dragon into
     * a no-cost elevator. {@code [mount] dismount_fall_damage = false} waives it by resetting the fall
     * distance, which is the honest way to do it: no damage is "absorbed", the fall simply is not
     * counted.
     */
    @Override
    protected void removePassenger(Entity passenger) {
        boolean wasFlying = ridePhase() == DragonRideControl.PHASE_FLYING
                || ridePhase() == DragonRideControl.PHASE_LAUNCHING;
        super.removePassenger(passenger);

        rideInput.reset();
        launchTicks = 0;
        setRidePhase(DragonRideControl.PHASE_NONE);
        this.entityData.set(DATA_BANK, 0.0F);
        this.entityData.set(DATA_MOUNT_TICK, -1);

        if (wasFlying && !RasenganConfig.SERVER.mountDismountFallDamage.get()) {
            passenger.resetFallDistance();
        }
        // Hand the dragon back to its own AI. The goals were never removed, only denied their control
        // flags, so this is a single flip rather than a rebuild - and it reverts to the standalone
        // flight AI that already exists, with no special case for "was ridden".
        if (!level().isClientSide()) {
            suspendAi(perchTicks > 0);
            setNoGravity(RasenganConfig.SERVER.dragonCanFly.get());
        }
    }

    /**
     * Suspends or resumes the dragon's own AI without touching {@code NoAi}.
     *
     * <p>Idempotent and cheap: the flag setters are no-ops when already in the requested state, so this
     * can be called every tick.
     */
    private void suspendAi(boolean suspend) {
        this.goalSelector.setControlFlag(Goal.Flag.MOVE, !suspend);
        this.goalSelector.setControlFlag(Goal.Flag.LOOK, !suspend);
        this.targetSelector.setControlFlag(Goal.Flag.TARGET, !suspend);
        if (getMoveControl() instanceof DragonFlightMoveControl flight) {
            flight.setSuspended(suspend);
        }
    }

    /** Called by the mount manager once a mount is authorised and the rider is aboard. */
    public void onMounted() {
        rideInput.reset();
        launchTicks = 0;
        this.entityData.set(DATA_MOUNT_TICK, tickCount);
        // Perched is decided by the grace clock first and only then by contact with the ground. Using
        // onGround() alone meant a rider who mounted in the first few ticks of the grace - before the
        // dragon had finished descending the 2 blocks it arrives above the seal - was classified as
        // airborne, which put the ride straight into PHASE_FLYING and left the double-tap launch
        // unreachable. The grace clock is the authoritative statement that it is grounded.
        setRidePhase(perchTicks > 0 || onGround()
                ? DragonRideControl.PHASE_PERCHED
                : DragonRideControl.PHASE_FLYING);
    }

    /** Ticks since the current rider mounted, or -1 if nobody is aboard. */
    public int ticksSinceMount() {
        int mountTick = this.entityData.get(DATA_MOUNT_TICK);
        return mountTick < 0 ? -1 : tickCount - mountTick;
    }

    /**
     * Clears ride state when the rider vanished without dismounting.
     *
     * <p>Belt and braces for a disconnect: {@code stopRiding} normally routes through
     * {@link #removePassenger}, but if the passenger list was emptied some other way the dragon would be
     * left in {@code PHASE_FLYING} with its AI suspended and no one at the controls.
     */
    public void onRiderLost() {
        rideInput.reset();
        launchTicks = 0;
        setRidePhase(DragonRideControl.PHASE_NONE);
        this.entityData.set(DATA_BANK, 0.0F);
        this.entityData.set(DATA_MOUNT_TICK, -1);
        suspendAi(perchTicks > 0);
        setNoGravity(RasenganConfig.SERVER.dragonCanFly.get());
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
        // While perched, fall and stay down. The flying branch below applies no gravity at all - it is
        // pure velocity integration - so a dragon left on it simply hovers at whatever height it arrived
        // at. The spawn grace is specified as the dragon LANDING and sitting in place, and it arrives 2
        // blocks above the seal, so it has to be handed to the normal walking path to cover that gap.
        // Without this it hovered, onGround() stayed false, and a rider mounting during the grace was
        // classified as already airborne - which silently disabled the launch.
        // A launch sets perchTicks to 0, so this reverts to the flying path on the same tick the climb
        // begins - no special case needed for "perched but launching".
        if (perchTicks > 0) {
            super.travel(input);
            return;
        }
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
        if (entranceTicks > 0) {
            entranceTicks--;
            if (isHiddenForSummon()) {
                // Pre-reveal: completely still. Any drift here would move the dragon away from the
                // point the smoke column is centred on, and it would then be revealed off-centre.
                setDeltaMovement(Vec3.ZERO);
            } else {
                // Post-reveal: gentle lift, no horizontal drive, so it reads as rising out of the
                // seal rather than immediately flying off.
                setDeltaMovement(getDeltaMovement().multiply(0.6D, 1.0D, 0.6D).add(0.0D, 0.06D, 0.0D));
            }
            // Deliberately silent: ServerSummonManager already plays the reveal sound at the spawn
            // point on the reveal tick, at the configured volume. A second play of the same sample
            // from the entity would overlap itself and ignore sound_volume.
        }
        tickSpawnGrace();
        tickRide((ServerLevel) level());
        updateFlightState();
        tickBreath((ServerLevel) level());
    }

    /**
     * Counts down the spawn grace.
     *
     * <p>Immunity and the perch are decremented independently so that a launch can end the perch
     * without touching the immunity. The replicated mirror is only updated on the transition, not every
     * tick, so this costs one datawatcher write per summon rather than 200.
     */
    private void tickSpawnGrace() {
        if (spawnImmunityTicks > 0) {
            spawnImmunityTicks--;
            if (spawnImmunityTicks == 0) {
                this.entityData.set(DATA_IMMUNE, false);
            }
        }
        if (perchTicks > 0) {
            perchTicks--;
            if (perchTicks == 0) {
                onPerchEnded();
            }
        }
    }

    /**
     * Takes off at the end of the grounded grace.
     *
     * <p>Restoring flight is not just a matter of clearing a flag. The perch deliberately turns gravity
     * back on so the dragon lands, and {@link #travel} routes a grounded dragon through the normal walking
     * path - so on the tick the grace ends it is sitting on the ground with {@code onGround()} true, and
     * the flying branch is unreachable because that branch requires {@code !onGround()}. Nothing would
     * ever lift it: the harness measured 9.53 blocks of travel in 99 ticks against a 0.51 b/t cruise,
     * because it was walking.
     *
     * <p>So the grace ends with an actual takeoff - gravity off per config, plus one upward impulse large
     * enough to break ground contact and hand the dragon to its flight control.
     */
    private void onPerchEnded() {
        if (rider() != null) {
            return; // a rider owns it; they launch when they choose to
        }
        setNoAi(false);
        boolean canFly = RasenganConfig.SERVER.dragonCanFly.get();
        setNoGravity(canFly);
        if (canFly) {
            setDeltaMovement(getDeltaMovement().add(0.0D, 0.45D, 0.0D));
            this.entityData.set(DATA_FLIGHT_STATE, STATE_FLAP);
        }
    }

    /**
     * Rider-driven movement, or the grounded perch.
     *
     * <p>Everything here runs on the server and nowhere else. See {@link DragonRideControl} for why that
     * is sufficient rather than merely intended: vanilla structurally refuses to let the riding client
     * drive a vehicle whose {@code getControllingPassenger()} is not a {@code Mob}, which a player never
     * is, so no {@code ServerboundMoveVehiclePacket} is ever sent or accepted for this entity.
     */
    private void tickRide(ServerLevel level) {
        Player rider = rider();

        // ---- perched: hold still at the summon point ----
        if (perchTicks > 0 && ridePhase() != DragonRideControl.PHASE_LAUNCHING
                && ridePhase() != DragonRideControl.PHASE_FLYING) {
            setNoGravity(false);
            setDeltaMovement(getDeltaMovement().multiply(0.0D, 1.0D, 0.0D));
            if (rider != null) {
                setRidePhase(DragonRideControl.PHASE_PERCHED);
            }
        }

        if (rider == null) {
            if (ridePhase() != DragonRideControl.PHASE_NONE) {
                setRidePhase(DragonRideControl.PHASE_NONE);
                this.entityData.set(DATA_BANK, 0.0F);
            }
            suspendAi(perchTicks > 0);
            return;
        }

        // The rider owns the velocity while aboard, so the AI has to stop competing for it.
        //
        // Done with control flags and a suspended move control, NOT with setNoAi(true). NoAi makes
        // Mob.isEffectiveAi() false, and LivingEntity.aiStep only calls travel() when isEffectiveAi() -
        // so a NoAi dragon would have the rider's velocity written and then never integrated, and would
        // hang motionless in the air. This was worth checking rather than assuming; it is the same trap
        // recorded for the flight harness.
        suspendAi(true);

        if (!(rider instanceof ServerPlayer serverRider)) {
            return;
        }

        // ---- launch detection, server-side ----
        rideInput.tick(serverRider.getLastClientInput(),
                RasenganConfig.SERVER.mountDoubleTapWindowTicks.get());
        boolean launchRequested = rideInput.consumeLaunch();

        byte phase = ridePhase();
        if (launchRequested && phase == DragonRideControl.PHASE_PERCHED) {
            phase = DragonRideControl.PHASE_LAUNCHING;
            launchTicks = 0;
            // A launch cuts the grounded hold short. Immunity is untouched - see beginSpawnGrace.
            perchTicks = 0;
            setNoGravity(RasenganConfig.SERVER.dragonCanFly.get());
            setRidePhase(phase);
            level.playSound(null, getX(), getY(), getZ(),
                    net.minecraft.sounds.SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE,
                    1.4F, 0.7F);
        }
        // A second double-tap while already airborne is IGNORED. The alternative - an extra vertical
        // boost - was rejected because the launch is a scripted eased climb that overrides steering, so
        // re-entering it mid-flight would wrench the camera away from a player who was mid-turn. Holding
        // jump gives a controllable climb instead, which is the same capability without the hijack.

        switch (phase) {
            case DragonRideControl.PHASE_LAUNCHING -> tickLaunch();
            case DragonRideControl.PHASE_FLYING -> tickPlayerFlight(serverRider);
            default -> { }
        }
    }

    /** The eased launch climb. Hands over to player steering when it completes. */
    private void tickLaunch() {
        int total = RasenganConfig.SERVER.mountLaunchTicks.get();
        double height = RasenganConfig.SERVER.mountLaunchHeight.get();

        double vy = DragonRideControl.launchVerticalSpeed(launchTicks, total, height);
        Vec3 velocity = getDeltaMovement();
        // Blend rather than assign, so the climb starts from whatever the dragon was already doing.
        setDeltaMovement(velocity.x * 0.86D, vy, velocity.z * 0.86D);
        this.entityData.set(DATA_FLIGHT_STATE, STATE_FLAP);

        launchTicks++;
        if (launchTicks >= total) {
            launchTicks = 0;
            setRidePhase(DragonRideControl.PHASE_FLYING);
        }
    }

    /** Elytra-style steering under the rider's look direction. */
    private void tickPlayerFlight(ServerPlayer rider) {
        setNoGravity(true);
        float yawDelta = DragonRideControl.steer(this, rider, rider.getLastClientInput());

        float bank = DragonRideControl.bankFor(yawDelta,
                (float) (double) RasenganConfig.SERVER.mountTurnRateDegrees.get(),
                (float) (double) RasenganConfig.SERVER.mountBankLimitDegrees.get());
        // Ease the bank toward its target rather than snapping to it, so it rolls in and out.
        float current = bankAngle();
        this.entityData.set(DATA_BANK, Mth.lerp(0.12F, current, bank));
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
        // Not while hidden: a boss bar over an invisible entity gives the reveal away before the
        // smoke clears, which is the one thing the pre-spawn must not cost. revealFromSummon() adds
        // the bar for everyone in range at the moment it becomes visible.
        if (RasenganConfig.SERVER.dragonBossBar.get() && !isHiddenForSummon()) {
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
        // ---- Summoner identity, persisted ----
        //
        // Previously NOT written, which had two consequences. The one-dragon-per-player limit lived
        // only in ServerSummonManager's in-memory map, so a restart let a player summon a second
        // dragon while their first was still alive in the world - and nothing could rebuild the link,
        // because the entity had not kept it either. Now that mounting is restricted to the summoner,
        // losing it would also hand their dragon to nobody: after a restart it would be unrideable.
        if (summonerUuid != null) {
            output.store("Summoner", net.minecraft.core.UUIDUtil.CODEC, summonerUuid);
        }
        output.putInt("SpawnImmunity", spawnImmunityTicks);
        output.putInt("PerchTicks", perchTicks);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.breathCooldown = input.getIntOr("BreathCooldown", 0);
        this.summonerUuid = input.read("Summoner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        this.spawnImmunityTicks = input.getIntOr("SpawnImmunity", 0);
        this.perchTicks = input.getIntOr("PerchTicks", 0);
        // Replicate the restored immunity so a client that loads a freshly-immune dragon agrees.
        this.entityData.set(DATA_IMMUNE, spawnImmunityTicks > 0);
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
            DragonEntity dragon = test.animatable();
            RawAnimation animation = switch (dragon.flightState()) {
                case STATE_GROUND_WALK -> ANIM_WALK;
                case STATE_FLAP -> ANIM_FLY;
                case STATE_GLIDE -> ANIM_GLIDE;
                case STATE_HOVER -> ANIM_FLY;
                default -> ANIM_IDLE;
            };
            PlayState result = test.setAndContinue(animation);

            // ---- Entrance: force the wing phase instead of letting it free-run ----
            //
            // During the arrival the flap phase is pinned to a pure function of the dragon's own age,
            // so that the downbeat lands on the tick the smoke dispersal fires. Left to itself the
            // phase is anchored to whenever GeckoLib first initialised the controller, which is the
            // first frame this client actually *rendered* the dragon: read out of
            // AnimationController.checkControllerState, initializeNewAnimation sets timelineTime = 0
            // on that first call regardless of entity age. A player who was looking elsewhere at the
            // reveal and turned around later would therefore get a different phase from everyone
            // else, and their wings would beat out of step with their smoke.
            //
            // Forcing it also removes the transition offset: AnimationTimeline.create prepends the
            // controller's transition as a stage, so the animation's own t=0 sits transitionTicks
            // later - setAnimationTime(t) maps through the current stage and cancels that out.
            int total = dragon.entranceTotalTicks();
            float age = (float) test.renderState().getAnimatableAge();
            if (total > 0 && animation == ANIM_FLY && age < total) {
                float phase = dev.rasengan.SummonTimeline.entranceWingPhase(age);
                test.controller().setAnimationTime(phase / 20.0D);
            } else if (animation == ANIM_FLY || animation == ANIM_IDLE) {
                // ---- outside the entrance, the phase is STILL forced ----
                //
                // Originally this only applied during the arrival. It now applies whenever the fly or
                // idle cycle is playing, because dev.rasengan.DragonAnchor computes the rider's standing
                // point from a table indexed by this same phase. If GeckoLib were left to free-run, the
                // phase would be anchored to the first frame each client happened to render the dragon,
                // and the server - which has no GeckoLib state at all - could not know it. The rider
                // would then be drawn floating above or sunk into the head by up to the full 1.23-block
                // bob, differently for every observer.
                //
                // Forcing it costs nothing: a phase derived from entity age IS a free run, just with an
                // origin everyone agrees on.
                boolean airborne = animation == ANIM_FLY;
                float phase = dev.rasengan.DragonAnchor.phase(age, airborne);
                test.controller().setAnimationTime(phase / 20.0D);
            }
            return result;
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
