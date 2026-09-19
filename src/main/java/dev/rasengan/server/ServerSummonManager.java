package dev.rasengan.server;

import dev.rasengan.Palette;
import dev.rasengan.PowerState;
import dev.rasengan.RasenganConfig;
import dev.rasengan.RasenganEntities;
import dev.rasengan.RasenganSounds;
import dev.rasengan.network.RasenganSummonPayloads;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The Summoning Jutsu: validation, the cinematic timeline, and the dragon's arrival.
 *
 * <h2>Gating - stated explicitly</h2>
 * <ul>
 *   <li><b>Its own bar.</b> POWER BAR - SUMMONING is a second, fully independent charge bar
 *       ({@link RasenganAttachments#SUMMON_POWER}), default 300 s. Charging it does nothing to the
 *       cast bar and vice versa.</li>
 *   <li><b>One dragon per player, enforced by refusal.</b> While a player's summoned dragon is
 *       alive, a new summon is <em>rejected</em> with a message. The alternative - silently
 *       despawning the old one - would delete a boss the player may be mid-fight with, so refusing
 *       is the safer of the two. The dragon's life, not the bar, is the real limiter.</li>
 *   <li><b>No permission gate.</b> This is a player ability, not an admin tool; {@code /spawn
 *       dragon} remains the gamemaster path.</li>
 * </ul>
 *
 * <h2>Timeline, server-driven</h2>
 * The server owns the clock. It broadcasts one {@code SummonStart} and then counts ticks itself, so
 * the dragon's arrival and the chat line happen at a server-decided moment rather than whenever a
 * client's animation finishes. Defaults, all configurable:
 * <pre>
 *   tick  0        hand seal begins, buildup sound, chakra gather
 *   tick  0..45    seal circle expands, smoke builds
 *   tick 45..95    cinematic camera window (2.5 s)
 *   tick 70        REVEAL: dragon spawns, roar, shake, awakening line (fires exactly once)
 *   tick 70..100   smoke disperses, camera released, dragon enters normal flight AI
 *   tick 100       sequence complete (5.0 s total)
 * </pre>
 *
 * <h2>Despawn rule</h2>
 * The dragon <b>persists until killed</b> - identical to the standalone boss. It is not on a timer,
 * not removed on summoner death or disconnect. Ownership here is bookkeeping for the one-per-player
 * limit only: it grants no control, no taming and no riding, which remain out of scope.
 */
public final class ServerSummonManager {

    /** An in-progress cinematic. */
    private static final class PendingSummon {
        final UUID playerId;
        final Vec3 origin;
        final long seed;
        int ticks;
        boolean revealed;

        PendingSummon(UUID playerId, Vec3 origin, long seed) {
            this.playerId = playerId;
            this.origin = origin;
            this.seed = seed;
        }
    }

    private static final Map<UUID, PendingSummon> PENDING = new HashMap<>();

    /** Summoner -> their live dragon. Bookkeeping for the one-per-player rule only. */
    private static final Map<UUID, UUID> ACTIVE_DRAGONS = new HashMap<>();

    private static final int HEARTBEAT_TICKS = 20;

    private ServerSummonManager() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerSummonManager::onPlayerTick);
        gameBus.addListener(ServerSummonManager::onServerTick);
        gameBus.addListener(ServerSummonManager::onLogin);
    }

    public static PowerData data(ServerPlayer player) {
        return player.getData(RasenganAttachments.SUMMON_POWER);
    }

    // ------------------------------------------------------------------
    // Charge bar
    // ------------------------------------------------------------------

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PowerData data = data(player);
        int duration = RasenganConfig.summonChargeDurationTicks();
        boolean canProgress = player.isAlive() || RasenganConfig.SERVER.chargeWhileDead.get();

        switch (data.state()) {
            case CHARGING -> {
                if (canProgress) {
                    data.advanceCharge(duration);
                    if (data.chargeTicks() >= duration) {
                        data.setState(PowerState.READY);
                    }
                }
            }
            case READY -> {
                if (data.chargeTicks() > duration) {
                    data.setChargeTicks(duration);
                }
            }
            case CASTING -> {
                // Held while the cinematic runs; released when it completes.
            }
            case COOLDOWN -> {
                data.decrementCooldown();
                if (data.cooldownTicks() <= 0) {
                    data.setState(PowerState.CHARGING);
                }
            }
        }

        if (data.isDirty() || data.tickSyncCountdown(HEARTBEAT_TICKS)) {
            sync(player, data);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player, data(player));
        }
    }

    public static void sync(ServerPlayer player, PowerData data) {
        PacketDistributor.sendToPlayer(player, new RasenganSummonPayloads.SummonPowerSync(
                data.chargeTicks(),
                RasenganConfig.summonChargeDurationTicks(),
                data.cooldownTicks(),
                data.state().id()));
        data.markClean();
    }

    // ------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------

    public static void onActivateRequest(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                trySummon(player);
            }
        });
    }

    /**
     * Validates and begins a summon. Returns true only if a sequence actually started.
     *
     * <p>Every rejection is checked server-side against server state. The client sends a fieldless
     * packet, so there is nothing in the request to forge.
     */
    public static boolean trySummon(ServerPlayer player) {
        PowerData data = data(player);

        if (PENDING.containsKey(player.getUUID())) {
            return false; // already mid-cinematic
        }
        if (data.state() != PowerState.READY) {
            reject(player, "Your summoning power is still gathering.");
            return false;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (hasLiveDragon(player)) {
            reject(player, "Your dragon is already here. It will not come twice.");
            return false;
        }

        ServerLevel level = (ServerLevel) player.level();
        long seed = level.getRandom().nextLong();
        // Pin the seal to where the player stood, not to the player: the circle is drawn on the
        // ground and must not slide around if they walk during the sequence.
        Vec3 origin = player.position();

        PENDING.put(player.getUUID(), new PendingSummon(player.getUUID(), origin, seed));

        data.resetCharge();
        data.setCooldownTicks(0);
        data.setState(PowerState.CASTING);
        sync(player, data);

        int total = RasenganConfig.SERVER.summonCinematicTicks.get();
        int reveal = RasenganConfig.summonRevealTick();
        int camera = RasenganConfig.SERVER.summonCameraEffect.get()
                ? RasenganSummonPayloads.SummonStart.packCamera(
                        RasenganConfig.SERVER.summonCameraStartTick.get(),
                        RasenganConfig.SERVER.summonCameraEndTick.get())
                : 0;

        broadcastNear(level, origin, new RasenganSummonPayloads.SummonStart(
                player.getId(), seed, origin.x, origin.y, origin.z, total, reveal, camera));

        // Buildup, at the seal rather than on the player, so observers hear it from the circle.
        level.playSound(null, origin.x, origin.y, origin.z,
                RasenganSounds.SUMMON_BUILDUP.get(), SoundSource.PLAYERS,
                (float) (double) RasenganConfig.SERVER.summonSoundVolume.get(), 1.0F);
        return true;
    }

    private static void reject(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message)
                .withStyle(style -> style.withColor(Palette.DEEP_CYAN)));
    }

    // ------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------

    private static void onServerTick(ServerTickEvent.Post event) {
        pruneDeadDragons(event.getServer());
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int total = RasenganConfig.SERVER.summonCinematicTicks.get();
        int revealTick = RasenganConfig.summonRevealTick();

        for (Iterator<Map.Entry<UUID, PendingSummon>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            PendingSummon pending = it.next().getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);

            // Summoner gone mid-sequence: abandon it rather than spawning a boss for nobody.
            if (player == null || !player.isAlive() || player.isRemoved()) {
                it.remove();
                continue;
            }

            pending.ticks++;

            if (!pending.revealed && pending.ticks >= revealTick) {
                pending.revealed = true;
                reveal(player, pending);
            }

            if (pending.ticks >= total) {
                it.remove();
                // Bar resumes charging only once the whole sequence is done, so the readout cannot
                // show a refilling bar while the dragon is still arriving.
                PowerData data = data(player);
                data.setState(PowerState.CHARGING);
                sync(player, data);
            }
        }
    }

    /** The reveal beat: the dragon arrives, roars, and speaks - exactly once per summon. */
    private static void reveal(ServerPlayer player, PendingSummon pending) {
        ServerLevel level = (ServerLevel) player.level();

        Entity entity = RasenganEntities.DRAGON.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (!(entity instanceof DragonEntity dragon)) {
            return;
        }
        // Arrive a little in front of the seal and above it, so it rises out rather than clipping
        // through the summoner.
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 at = pending.origin.add(look.x * 8.0D, 2.0D, look.z * 8.0D);
        dragon.snapTo(at.x, at.y, at.z, player.getYRot(), 0.0F);
        dragon.setSummoner(player.getUUID());
        dragon.beginEntrance();

        if (!level.addFreshEntity(dragon)) {
            return;
        }
        ACTIVE_DRAGONS.put(player.getUUID(), dragon.getUUID());

        level.playSound(null, at.x, at.y, at.z,
                RasenganSounds.SUMMON_REVEAL.get(), SoundSource.HOSTILE,
                (float) (double) RasenganConfig.SERVER.summonSoundVolume.get(), 1.0F);

        announce(player, level);
    }

    /**
     * Sends the awakening line.
     *
     * <p>Chosen here, on the server, and sent as finished text - so every player who sees this summon
     * reads the same line. Styled through text components, never legacy section codes, matching the
     * cast announcement.
     */
    private static void announce(ServerPlayer player, ServerLevel level) {
        RandomSource random = level.getRandom();
        String line = SummonLines.get().pick(random,
                SummonLines.configuredResource(RasenganConfig.SERVER.summonLinesResource.get()));

        Component message = Component.empty()
                .append(Component.literal("[Demonic Wingwalker] ")
                        .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)))
                .append(Component.literal(line)
                        .withStyle(style -> style.withColor(DRAGON_COLOR).withBold(true)));

        if (RasenganConfig.SERVER.summonAnnounceGlobally.get()) {
            level.getServer().getPlayerList().broadcastSystemMessage(message, false);
        } else {
            double radius = RasenganConfig.maxEffectDistance();
            for (ServerPlayer nearby : level.players()) {
                if (nearby.distanceToSqr(player) <= radius * radius) {
                    nearby.sendSystemMessage(message);
                }
            }
        }
    }

    /** Ember orange, distinct from the cyan ability palette so dragon speech reads as its own voice. */
    private static final int DRAGON_COLOR = 0xFF7A29;

    // ------------------------------------------------------------------
    // One-dragon-per-player bookkeeping
    // ------------------------------------------------------------------

    public static boolean hasLiveDragon(ServerPlayer player) {
        UUID dragonId = ACTIVE_DRAGONS.get(player.getUUID());
        if (dragonId == null) {
            return false;
        }
        Entity entity = ((ServerLevel) player.level()).getEntity(dragonId);
        if (entity instanceof DragonEntity dragon && dragon.isAlive()) {
            return true;
        }
        // Not in this dimension, or gone: check every level before declaring it dead.
        for (ServerLevel level : player.level().getServer().getAllLevels()) {
            Entity found = level.getEntity(dragonId);
            if (found instanceof DragonEntity dragon && dragon.isAlive()) {
                return true;
            }
        }
        ACTIVE_DRAGONS.remove(player.getUUID());
        return false;
    }

    /** Drops entries whose dragon has died, so the bar's gate opens again. */
    private static void pruneDeadDragons(MinecraftServer server) {
        if (ACTIVE_DRAGONS.isEmpty()) {
            return;
        }
        ACTIVE_DRAGONS.entrySet().removeIf(entry -> {
            for (ServerLevel level : server.getAllLevels()) {
                Entity found = level.getEntity(entry.getValue());
                if (found instanceof DragonEntity dragon && dragon.isAlive()) {
                    return false;
                }
            }
            return true;
        });
    }

    public static void clearAll() {
        PENDING.clear();
        ACTIVE_DRAGONS.clear();
    }

    /** Exposed for the verification harness. */
    public static int activeDragonCount() {
        return ACTIVE_DRAGONS.size();
    }

    /** Number of cinematics currently running. Exposed for the verification harness. */
    public static int pendingCount() {
        return PENDING.size();
    }

    private static void broadcastNear(ServerLevel level, Vec3 at,
                                      net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z,
                RasenganConfig.maxEffectDistance(), payload);
    }
}
