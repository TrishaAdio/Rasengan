package dev.rasengan.server;

import dev.rasengan.PowerState;
import dev.rasengan.Rasengan;
import dev.rasengan.RasenganConfig;
import dev.rasengan.network.RasenganPayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-authoritative cast lifecycle: validation, timing, hit detection, damage, announcement
 * and teardown.
 *
 * <h2>Chosen release behaviour: close-range hand strike (thrust)</h2>
 * The sphere is held in the caster's hand for the whole cast. On release the caster thrusts it
 * forward along the aim direction captured at activation. The server then sweeps a sphere of
 * radius {@code hitbox_size} along that direction out to {@code range} blocks and takes the
 * first entity or block it meets. There is no projectile entity: the strike resolves in a
 * single tick, which is what makes "one immediate hit, never repeated" easy to guarantee.
 *
 * <h2>Why the client cannot cheat</h2>
 * The client sends only {@link RasenganPayloads.Activate}, which has no fields. Charge level,
 * aim direction, reach, target selection and damage are all read from server state and server
 * config. A client that spams the packet gets rejected by the state check.
 */
public final class ServerCastManager {

    public static final ResourceKey<DamageType> DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE, Rasengan.id("rasengan"));

    /** Active casts keyed by player UUID. Cleared aggressively on every exit path. */
    private static final Map<UUID, ActiveCast> ACTIVE = new HashMap<>();

    private ServerCastManager() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerCastManager::onServerTick);
        gameBus.addListener(ServerCastManager::onLogout);
    }

    // ------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------

    /**
     * Handles a client activation request. Runs on the network thread, so the actual work is
     * pushed onto the server thread via {@link IPayloadContext#enqueueWork(Runnable)}.
     */
    public static void onActivateRequest(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                tryActivate(player);
            }
        });
    }

    /**
     * Validates and starts a cast.
     *
     * @return true if a cast actually started. Only a true result announces or resets the bar.
     */
    public static boolean tryActivate(ServerPlayer player) {
        PowerData data = ServerPowerManager.data(player);

        // ---- Validation gate. Every one of these is a silent rejection: no chat, no reset. ----
        if (data.state() != PowerState.READY) {
            return false;
        }
        if (data.chargeTicks() < RasenganConfig.chargeDurationTicks()) {
            return false; // defensive: READY should imply a full bar
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (ACTIVE.containsKey(player.getUUID())) {
            return false; // already casting
        }

        // ---- Commit ----
        int duration = RasenganConfig.castDurationTicks();
        long seed = player.level().getRandom().nextLong();
        boolean mainHand = true;
        Vec3 direction = player.getLookAngle().normalize();

        ACTIVE.put(player.getUUID(), new ActiveCast(player.getUUID(), seed, duration, mainHand, direction));

        // The bar resets the instant the cast is accepted, exactly as specified.
        data.resetCharge();
        data.setState(PowerState.CASTING);
        data.setCooldownTicks(0);
        ServerPowerManager.sync(player, data);

        // Tell nearby clients to begin rendering. Sent once.
        int cosmetics = RasenganPayloads.CosmeticFlags.pack(
                RasenganConfig.SERVER.particleDensity.get(),
                RasenganConfig.SERVER.auraIntensity.get(),
                RasenganConfig.SERVER.maxSimultaneousEffects.get());

        broadcastNear(player, new RasenganPayloads.CastStart(
                player.getId(),
                seed,
                duration,
                mainHand,
                (float) direction.x,
                (float) direction.y,
                (float) direction.z,
                cosmetics));

        // Global announcement - server side, once per successful cast, to everyone online
        // including the caster.
        if (RasenganConfig.announceCast()) {
            MinecraftServer server = ((ServerLevel) player.level()).getServer();
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal(player.getGameProfile().name() + " casted Rasengan"),
                    false);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<UUID> finished = new ArrayList<>();

        for (Iterator<Map.Entry<UUID, ActiveCast>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, ActiveCast> entry = it.next();
            ActiveCast cast = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());

            // Caster vanished (disconnect, death mid-cast, dimension swap): drop it cleanly.
            if (player == null || !player.isAlive() || player.isRemoved()) {
                it.remove();
                if (player != null) {
                    endCast(player, RasenganPayloads.CastEnd.REASON_CANCELLED);
                } else {
                    // No entity to broadcast from; nearby clients time the effect out on their
                    // own using castDuration, so nothing can stick.
                    finished.add(entry.getKey());
                }
                continue;
            }

            cast.elapsedTicks++;
            if (cast.isComplete() && !cast.resolved) {
                cast.resolved = true;
                resolveStrike(player, cast);
                it.remove();
                endCast(player, RasenganPayloads.CastEnd.REASON_COMPLETED);
            }
        }
        finished.forEach(ACTIVE::remove);
    }

    // ------------------------------------------------------------------
    // Strike resolution
    // ------------------------------------------------------------------

    /**
     * Sweeps the strike volume and applies damage exactly once.
     *
     * <p>The sweep walks the aim ray in small steps and, at each step, queries entities inside a
     * sphere of {@code hitbox_size}. The first valid entity wins; the loop then stops. Because
     * this runs in one tick and the cast is removed immediately afterwards, there is no path
     * that can apply the damage twice.
     */
    private static void resolveStrike(ServerPlayer player, ActiveCast cast) {
        ServerLevel level = (ServerLevel) player.level();
        double range = RasenganConfig.range();
        double radius = RasenganConfig.hitboxSize();

        Vec3 origin = player.getEyePosition();
        Vec3 dir = cast.direction;

        // Stop the sweep at a wall so the strike cannot reach through terrain.
        Vec3 rayEnd = origin.add(dir.scale(range));
        var clip = level.clip(new net.minecraft.world.level.ClipContext(
                origin, rayEnd,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player));
        double maxDistance = clip.getType() == HitResult.Type.BLOCK
                ? origin.distanceTo(clip.getLocation())
                : range;

        LivingEntity target = null;
        Vec3 impact = origin.add(dir.scale(maxDistance));

        final double step = Math.max(0.25D, radius * 0.5D);
        for (double travelled = 0.0D; travelled <= maxDistance; travelled += step) {
            Vec3 probe = origin.add(dir.scale(travelled));
            AABB box = new AABB(
                    probe.x - radius, probe.y - radius, probe.z - radius,
                    probe.x + radius, probe.y + radius, probe.z + radius);

            List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != player && e.isAlive() && !e.isSpectator() && e.isPickable());

            if (!candidates.isEmpty()) {
                LivingEntity closest = null;
                double best = Double.MAX_VALUE;
                for (LivingEntity candidate : candidates) {
                    double d = candidate.position().distanceToSqr(probe);
                    if (d < best) {
                        best = d;
                        closest = candidate;
                    }
                }
                target = closest;
                impact = target != null ? target.getBoundingBox().getCenter() : probe;
                break;
            }
        }

        int hitKind = RasenganPayloads.CastImpact.KIND_WHIFF;

        if (target != null) {
            hitKind = RasenganPayloads.CastImpact.KIND_ENTITY;
            applyHit(level, player, target);
        } else if (clip.getType() == HitResult.Type.BLOCK) {
            hitKind = RasenganPayloads.CastImpact.KIND_TERRAIN;
            impact = clip.getLocation();
        }

        // Impact packet goes out regardless of whether the target survived, so the full
        // impact animation always plays.
        broadcastNearPos(level, impact, new RasenganPayloads.CastImpact(
                player.getId(), impact.x, impact.y, impact.z, hitKind));

        if (RasenganConfig.SERVER.blockDamageEnabled.get()
                && hitKind != RasenganPayloads.CastImpact.KIND_WHIFF) {
            applyEnvironmentDamage(level, player, impact);
        }
    }

    /** One immediate hit for the full configured amount. */
    private static void applyHit(ServerLevel level, ServerPlayer caster, LivingEntity target) {
        Holder<DamageType> holder = level.registryAccess()
                .lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(DAMAGE_TYPE);

        DamageSource source = new DamageSource(holder, caster, caster);

        // Clear the immunity window so the full amount always lands even if the target was
        // struck moments earlier by something else.
        if (RasenganConfig.SERVER.bypassInvulnerabilityFrames.get()) {
            target.invulnerableTime = 0;
        }

        target.hurtServer(level, source, (float) RasenganConfig.damage());
    }

    /**
     * Optional terrain damage. Off by default and tuned independently of entity damage.
     *
     * <p>Deliberately <em>not</em> implemented with {@code Level#explode}: a vanilla explosion
     * would also damage entities, which would double up on the single authoritative hit already
     * applied above. Instead this walks the block sphere directly and removes only blocks whose
     * explosion resistance is at or below the configured strength, which is exactly what the
     * config comment promises.
     */
    private static void applyEnvironmentDamage(ServerLevel level, ServerPlayer caster, Vec3 at) {
        double radius = RasenganConfig.SERVER.environmentDamageRadius.get();
        double strength = RasenganConfig.SERVER.environmentDamageStrength.get();
        if (radius <= 0.0D) {
            return;
        }
        boolean drops = RasenganConfig.SERVER.environmentDropsItems.get();

        int r = (int) Math.ceil(radius);
        BlockPos centre = BlockPos.containing(at);
        double radiusSq = radius * radius;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    BlockPos pos = centre.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.getBlock().getExplosionResistance() > strength) {
                        continue; // obsidian, bedrock, claimed blocks with high resistance, etc.
                    }
                    level.destroyBlock(pos, drops, caster, 512);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /** Moves the bar out of CASTING and tells clients to drop the effect. */
    private static void endCast(ServerPlayer player, int reason) {
        PowerData data = ServerPowerManager.data(player);
        int cooldown = RasenganConfig.cooldownTicks();
        data.resetCharge();
        if (cooldown > 0) {
            data.setCooldownTicks(cooldown);
            data.setState(PowerState.COOLDOWN);
        } else {
            data.setCooldownTicks(0);
            data.setState(PowerState.CHARGING);
        }
        ServerPowerManager.sync(player, data);

        broadcastNear(player, new RasenganPayloads.CastEnd(player.getId(), reason));
    }

    /** Cancels any cast for this player without applying damage. Safe to call unconditionally. */
    public static void cancel(ServerPlayer player) {
        if (ACTIVE.remove(player.getUUID()) != null) {
            endCast(player, RasenganPayloads.CastEnd.REASON_CANCELLED);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Remove state without trying to sync to a closing connection.
            ACTIVE.remove(player.getUUID());
        }
    }

    /** Clears all state, e.g. on server shutdown, so nothing survives a restart. */
    public static void clearAll() {
        ACTIVE.clear();
    }

    // ------------------------------------------------------------------
    // Broadcast helpers
    // ------------------------------------------------------------------

    private static void broadcastNear(ServerPlayer player, RasenganPayloads.CastStart payload) {
        broadcastNearPos((ServerLevel) player.level(), player.position(), payload);
    }

    private static void broadcastNear(ServerPlayer player, RasenganPayloads.CastEnd payload) {
        broadcastNearPos((ServerLevel) player.level(), player.position(), payload);
    }

    /**
     * Sends to every player within {@code max_effect_distance}, caster included.
     * Passing {@code null} as the excluded player is what includes the caster.
     */
    private static void broadcastNearPos(ServerLevel level, Vec3 pos,
                                         net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersNear(
                level, null, pos.x, pos.y, pos.z, RasenganConfig.maxEffectDistance(), payload);
    }

    /** Exposed for the aura/effect layer: is this player mid-cast right now? */
    public static boolean isCasting(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }
}
