package dev.rasengan.server;

import dev.rasengan.AbilityType;
import dev.rasengan.PowerState;
import dev.rasengan.Rasengan;
import dev.rasengan.PalmAnchor;
import dev.rasengan.Palette;
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
import org.jspecify.annotations.Nullable;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-authoritative cast lifecycle: validation, timing, hit detection, damage, announcement
 * and teardown.
 *
 * <h2>Release behaviour: thrown projectile</h2>
 * The sphere forms and is held in the caster's hand for the duration of the cast. On release it is
 * launched as a {@link RasenganProjectile} entity along the caster's aim vector. That entity owns
 * its own flight and collision and applies the single authoritative hit on first contact, so this
 * class is responsible for the cast timeline and the launch, not for damage.
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
    public static void onActivateRequest(IPayloadContext context, int abilityId) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // byId() clamps to a valid enum value, so a malformed ordinal cannot select
                // anything the server does not recognise.
                tryActivate(player, AbilityType.byId(abilityId));
            }
        });
    }

    /**
     * Validates and starts a cast.
     *
     * @return true if a cast actually started. Only a true result announces or resets the bar.
     */
    public static boolean tryActivate(ServerPlayer player, AbilityType ability) {
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

        ACTIVE.put(player.getUUID(),
                new ActiveCast(player.getUUID(), seed, duration, mainHand, direction, ability));

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
                cosmetics,
                ability.id()));

        // Global announcement - server side, once per successful cast, to everyone online
        // including the caster.
        if (RasenganConfig.announceCast()) {
            MinecraftServer server = ((ServerLevel) player.level()).getServer();
            server.getPlayerList().broadcastSystemMessage(castAnnouncement(player, ability), false);
        }
        return true;
    }

    /**
     * Builds the styled global announcement: {@code <player name> casted Rasengan}.
     *
     * <p>Assembled from three styled {@link Component}s rather than a string with legacy
     * {@code §} codes. Section codes are a rendering-layer hack: they cannot be translated,
     * inspected or restyled by downstream mods, and some chat plugins strip or escape them. Real
     * components carry their style as data all the way to the client.
     *
     * <p>Colours come from the same locked {@link Palette} the sphere uses, via
     * {@code Style.withColor(int)} for exact RGB rather than the 16 legacy chat colours. The player
     * name is the pale white-blue highlight and the ability name is the palette cyan, which keeps
     * the two visually distinct and both legible against light and dark chat backgrounds. The whole
     * message is bold.
     */
    public static Component castAnnouncement(ServerPlayer player, AbilityType ability) {
        return Component.empty()
                .append(Component.literal(player.getGameProfile().name())
                        .withStyle(style -> style.withColor(Palette.HIGHLIGHT).withBold(true)))
                .append(Component.literal(" casted ")
                        .withStyle(style -> style.withColor(Palette.CORE).withBold(true)))
                .append(Component.literal(ability.displayName())
                        .withStyle(style -> style.withColor(Palette.CYAN).withBold(true)));
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
     * Releases the sphere as a thrown projectile.
     *
     * <p>This method no longer resolves damage itself. It hands ownership of hit detection to a
     * {@link RasenganProjectile} entity, which sweeps its own flight path each tick and applies
     * the single authoritative hit on first contact. The launch direction is re-read from the
     * player at release time so the throw follows where they are actually looking, while the
     * original activation direction remains what the cast animation was built from.
     */
    private static void resolveStrike(ServerPlayer player, ActiveCast cast) {
        ServerLevel level = (ServerLevel) player.level();

        Vec3 direction = player.getLookAngle().normalize();
        if (direction.lengthSqr() < 1.0E-6D) {
            direction = cast.direction;
        }

        // Launch from the exact palm position the client has been drawing the held sphere at.
        // PalmAnchor is shared common code precisely so both sides agree here - launching from
        // somewhere else, such as "eyes plus a bit forward", makes the sphere visibly jump at the
        // moment of release.
        Vec3 origin = PalmAnchor.palmPosition(player, cast.mainHand, 1.0F);

        RasenganProjectile.launch(level, player, origin, direction, cast.seed, cast.ability);
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
    public static void applyEnvironmentDamage(ServerLevel level, @Nullable ServerPlayer caster, Vec3 at) {
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
     * Broadcasts an impact at {@code point} to every nearby client.
     *
     * <p>Called by {@link RasenganProjectile} on contact and on expiry. Kept here so both the
     * cast lifecycle and the projectile use one identical broadcast path.
     */
    public static void broadcastImpact(ServerLevel level, Vec3 point, int casterId, int hitKind,
                                       AbilityType ability, float spinTicks) {
        broadcastNearPos(level, point, new RasenganPayloads.CastImpact(
                casterId, point.x, point.y, point.z, hitKind, ability.id(), spinTicks));
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
