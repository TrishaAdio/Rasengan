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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import org.jspecify.annotations.Nullable;
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
 * The server owns the clock. It broadcasts one {@code SummonStart}, counts ticks itself, and sends
 * exactly one {@code SummonEnd}. Stage boundaries come from {@link dev.rasengan.SummonTimeline},
 * which both sides share, so the client's smoke cannot drift out of step with the reveal. At the
 * shipped defaults (total 70, reveal 32):
 * <pre>
 *   tick  0        SPAWN: the dragon is created here, hidden, frozen and invulnerable.
 *                  Buildup sound. Stage A - seal spreads, chakra drawn inward.
 *   tick 12..32    Stage B - smoke eruption. The column must fully obscure the arrival point.
 *   tick 32        REVEAL: the dragon becomes visible, hittable and audible. Roar, awakening
 *                  line, fear reaction. Fires exactly once.
 *   tick 49.17     Stage D - the wing downbeat throws the smoke outward. Snapped to a real
 *                  downbeat of the fly animation; see SummonTimeline.
 *   tick 61..70    Stage E - residual wisps fade, camera eases back, flight AI takes over.
 * </pre>
 *
 * <h2>Why the dragon is spawned on tick 0 rather than at the reveal</h2>
 * Creating a 300 HP GeckoLib boss is not free: attribute setup, goal construction, a chunk touch on
 * the server, and on each client the first-ever load of a 183-cube model and a 1024x1024 texture.
 * Doing that on the reveal frame put all of it on the one frame the player is most likely to be
 * watching closely, and a frame-time spike there is indistinguishable from a camera problem. Spawning
 * it hidden on tick 0 moves the whole cost under the smoke.
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
        final int summonerEntityId;
        final Vec3 origin;
        final long seed;
        /** Dimension the sequence began in. Leaving it aborts, rather than playing to an empty room. */
        final ResourceKey<Level> dimension;
        /** The pre-spawned, still-hidden dragon. Null only if creation failed outright. */
        @Nullable
        DragonEntity dragon;
        int ticks;
        boolean revealed;

        PendingSummon(ServerPlayer player, Vec3 origin, long seed) {
            this.playerId = player.getUUID();
            this.summonerEntityId = player.getId();
            this.origin = origin;
            this.seed = seed;
            this.dimension = player.level().dimension();
        }
    }

    private static final Map<UUID, PendingSummon> PENDING = new HashMap<>();

    /** Summoner -> their live dragon. Bookkeeping for the one-per-player rule only. */
    private static final Map<UUID, UUID> ACTIVE_DRAGONS = new HashMap<>();

    private static final int HEARTBEAT_TICKS = 20;

    /**
     * Blocks in front of the summoner where the seal, the smoke and the dragon all land.
     *
     * <p>Far enough that a 29.5-block wingspan does not materialise on top of the player, close
     * enough to stay in frame. The dragon is not pushed further out than the seal, deliberately - see
     * {@link #trySummon}.
     */
    private static final double ARRIVAL_DISTANCE = 9.0D;

    /** How far above the seal the dragon sits, so it clears the ground rather than intersecting it. */
    private static final double ARRIVAL_LIFT = 2.0D;

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

        // ---- Where the seal goes ----
        //
        // In front of the summoner, not under their feet, and the dragon arrives at this same point.
        // The seal, the smoke column and the dragon must be concentric or the reveal does not work:
        // the previous version centred the seal on the player and spawned the dragon 8 blocks away,
        // which put the dragon outside its own smoke cloud. Whatever the cloud did, the dragon would
        // have been plainly visible beside it.
        //
        // Pinned once, here, rather than followed from the player: the circle is drawn on the ground
        // and must not slide if they walk during the sequence.
        Vec3 look = player.getViewVector(1.0F).normalize();
        double forward = ARRIVAL_DISTANCE;
        Vec3 origin = new Vec3(
                player.getX() + look.x * forward,
                player.getY(),
                player.getZ() + look.z * forward);

        PendingSummon pending = new PendingSummon(player, origin, seed);
        int total = RasenganConfig.SERVER.summonCinematicTicks.get();

        // ---- Pre-spawn, hidden. See the class javadoc for why this is not at the reveal. ----
        DragonEntity dragon = createHiddenDragon(level, player, origin, total);
        if (dragon == null) {
            // Nothing has been charged away yet and no packet has gone out, so a failure here is a
            // clean refusal rather than a half-played sequence.
            reject(player, "The summoning fails. There is no room for what you called.");
            return false;
        }
        pending.dragon = dragon;
        PENDING.put(player.getUUID(), pending);

        data.resetCharge();
        data.setCooldownTicks(0);
        data.setState(PowerState.CASTING);
        sync(player, data);

        int release = RasenganConfig.SERVER.summonCameraEffect.get()
                ? RasenganConfig.SERVER.summonCameraReleaseTicks.get()
                : 0; // 0 disables the camera move entirely; every visual still plays

        broadcastNear(level, origin, new RasenganSummonPayloads.SummonStart(
                player.getId(), dragon.getId(), seed,
                origin.x, origin.y, origin.z,
                total, RasenganConfig.summonRevealTick(),
                release,
                (float) (double) RasenganConfig.SERVER.summonCameraRadius.get(),
                (float) (double) RasenganConfig.SERVER.summonSmokeDensity.get(),
                RasenganConfig.SERVER.fearPlayerVignette.get()));

        // Buildup, at the seal rather than on the player, so observers hear it from the circle.
        level.playSound(null, origin.x, origin.y, origin.z,
                RasenganSounds.SUMMON_BUILDUP.get(), SoundSource.PLAYERS,
                (float) (double) RasenganConfig.SERVER.summonSoundVolume.get(), 1.0F);
        return true;
    }

    /**
     * Creates the dragon at its arrival spot and holds it hidden.
     *
     * @return the dragon, or null if it could not be created or added
     */
    @Nullable
    private static DragonEntity createHiddenDragon(ServerLevel level, ServerPlayer player,
                                                   Vec3 origin, int cinematicTicks) {
        Entity entity = RasenganEntities.DRAGON.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (!(entity instanceof DragonEntity dragon)) {
            return null;
        }
        // At the seal's centre, lifted just clear of the ground so it reads as rising out of the
        // circle rather than standing in it. Facing the summoner, which is why the yaw is the
        // player's own plus 180.
        Vec3 at = origin.add(0.0D, ARRIVAL_LIFT, 0.0D);
        dragon.snapTo(at.x, at.y, at.z, player.getYRot() + 180.0F, 0.0F);
        dragon.setSummoner(player.getUUID());
        dragon.beginHiddenEntrance(cinematicTicks);

        if (!level.addFreshEntity(dragon)) {
            return null;
        }
        return dragon;
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

            // ---- Interruption: abandon rather than play on ----
            //
            // Every one of these used to just drop the map entry, which cleaned up the server and
            // left every nearby client running the timeline to its end - camera still rolling for a
            // summon that no longer existed. They now go through interrupt(), which tells the clients.
            String abort = interruptionReason(player, pending);
            if (abort != null) {
                it.remove();
                interrupt(server, pending, player);
                continue;
            }

            pending.ticks++;

            if (!pending.revealed && pending.ticks >= revealTick) {
                pending.revealed = true;
                reveal(player, pending);
            }

            if (pending.ticks >= total) {
                it.remove();
                broadcastEnd(server, pending,
                        RasenganSummonPayloads.SummonEnd.Reason.COMPLETED);
                // Bar resumes charging only once the whole sequence is done, so the readout cannot
                // show a refilling bar while the dragon is still arriving.
                PowerData data = data(player);
                data.setState(PowerState.CHARGING);
                sync(player, data);
            }
        }
    }

    /**
     * Why this sequence must be abandoned, or null to continue.
     *
     * <p>Returned as text rather than a boolean so the reason appears in one place and the set of
     * conditions is readable as a list. All four are real: a player can die, log out, walk through a
     * portal or enter spectator mid-cinematic, and the pre-spawned dragon must not be left hidden and
     * frozen in the world if any of them happens.
     */
    @Nullable
    private static String interruptionReason(@Nullable ServerPlayer player, PendingSummon pending) {
        if (player == null || player.isRemoved()) {
            return "summoner left";
        }
        if (!player.isAlive()) {
            return "summoner died";
        }
        if (player.isSpectator()) {
            return "summoner became a spectator";
        }
        if (!player.level().dimension().equals(pending.dimension)) {
            return "summoner changed dimension";
        }
        if (pending.dragon == null || pending.dragon.isRemoved()) {
            return "dragon was removed";
        }
        return null;
    }

    /**
     * Abandons a sequence: removes the never-revealed dragon and tells every client to restore.
     *
     * <p>The dragon is discarded only if it is still hidden. Past the reveal it is a real boss that
     * the player may already be fighting, and deleting it because its summoner disconnected would
     * destroy live gameplay - the despawn rule in the class javadoc says it persists until killed.
     */
    private static void interrupt(MinecraftServer server, PendingSummon pending,
                                  @Nullable ServerPlayer player) {
        DragonEntity dragon = pending.dragon;
        if (dragon != null && !dragon.isRemoved() && dragon.isHiddenForSummon()) {
            dragon.cancelHiddenEntrance();
        }
        broadcastEnd(server, pending, RasenganSummonPayloads.SummonEnd.Reason.INTERRUPTED);

        // Release the bar if the player is still around to see it. A dead or disconnected player's
        // attachment is restored by the existing charge logic on respawn / login.
        if (player != null && player.isAlive() && !player.isRemoved()) {
            PowerData data = data(player);
            if (data.state() == PowerState.CASTING) {
                data.setState(PowerState.CHARGING);
                sync(player, data);
            }
        }
    }

    /**
     * Sends {@code SummonEnd} to everyone who could have received the start.
     *
     * <p>Broadcast to the level rather than to a radius around the origin: a player who was in range
     * at the start and has since walked out still has an active cinematic record, and the whole point
     * of this packet is that they are told to stop. The payload is two varints.
     */
    private static void broadcastEnd(MinecraftServer server, PendingSummon pending,
                                     RasenganSummonPayloads.SummonEnd.Reason reason) {
        ServerLevel level = server.getLevel(pending.dimension);
        if (level == null) {
            return;
        }
        RasenganSummonPayloads.SummonEnd payload =
                RasenganSummonPayloads.SummonEnd.of(pending.summonerEntityId, reason);
        for (ServerPlayer nearby : level.players()) {
            PacketDistributor.sendToPlayer(nearby, payload);
        }
    }

    /**
     * The reveal beat: the dragon becomes visible, roars, speaks and frightens - exactly once.
     *
     * <p>No spawning happens here any more; the dragon has existed since tick 0. This method only
     * lifts the hidden flag and fires the things that must land on this exact tick, which is what
     * keeps the reveal frame cheap.
     */
    private static void reveal(ServerPlayer player, PendingSummon pending) {
        ServerLevel level = (ServerLevel) player.level();
        DragonEntity dragon = pending.dragon;
        if (dragon == null || dragon.isRemoved()) {
            return; // interruptionReason() will abandon the sequence on the next tick
        }

        dragon.revealFromSummon();
        // Grounded and untouchable for the configured grace. Started here, on the reveal, so the ten
        // seconds are ten seconds of the player being able to see and reach it - not ten seconds that
        // began while it was still hidden under the smoke.
        dragon.beginSpawnGrace();
        ACTIVE_DRAGONS.put(player.getUUID(), dragon.getUUID());

        Vec3 at = dragon.position();
        float volume = (float) (double) RasenganConfig.SERVER.summonSoundVolume.get();
        // Both positional and at the dragon, not at the seal or on the player, so each listener hears
        // them attenuate from where the creature actually is.
        level.playSound(null, at.x, at.y, at.z,
                RasenganSounds.SUMMON_REVEAL.get(), SoundSource.HOSTILE, volume, 1.0F);
        // The optional roar. A no-op for anyone who has not installed the local-only file; see
        // RasenganSounds.SUMMON_ROAR. Played on the reveal tick so it lands while the dragon is still
        // partly hidden - hearing it before seeing it whole is what makes the entrance land.
        level.playSound(null, at.x, at.y, at.z,
                RasenganSounds.SUMMON_ROAR.get(), SoundSource.HOSTILE, volume, 1.0F);

        // Nearby hostiles react to a boss appearing next to them.
        DragonFearManager.frighten(level, dragon);

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

    /**
     * Whether this player already has a live dragon.
     *
     * <p>The in-memory map is a fast path, not the source of truth. It is cleared on
     * {@code ServerStoppingEvent}, so before {@code summonerUuid} was persisted a restart let a player
     * summon a second dragon while their first was still alive in the world - and nothing could rebuild
     * the link, because the entity had not kept it either. Now the entity persists its summoner and this
     * falls back to scanning for one whose {@code summoner()} matches, so the limit survives a restart.
     */
    public static boolean hasLiveDragon(ServerPlayer player) {
        UUID dragonId = ACTIVE_DRAGONS.get(player.getUUID());
        if (dragonId == null) {
            return scanForOwnedDragon(player);
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
        return scanForOwnedDragon(player);
    }

    /**
     * Scans loaded levels for a dragon this player summoned, rebuilding the map entry if found.
     *
     * <p>Only reached when the fast path has no answer, which after startup is once per summon attempt
     * per player. It walks loaded entities only - a dragon in an unloaded chunk cannot be found, which is
     * the honest limit of this approach and is recorded in {@code SUMMONING.md}.
     */
    private static boolean scanForOwnedDragon(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (DragonEntity dragon : level.getEntities(
                    net.minecraft.world.level.entity.EntityTypeTest.forClass(DragonEntity.class),
                    candidate -> candidate.isAlive()
                            && player.getUUID().equals(candidate.summoner()))) {
                ACTIVE_DRAGONS.put(player.getUUID(), dragon.getUUID());
                return true;
            }
        }
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

    /**
     * Drops all bookkeeping on shutdown.
     *
     * <p>Any still-hidden dragon is discarded first. Without that, stopping the server mid-cinematic
     * would <em>save</em> an invisible, frozen, invulnerable boss into the world, and nothing would
     * ever reveal it - the pending map that owned it is in memory only. A visible dragon is left
     * alone: past the reveal it is a normal boss and is meant to persist.
     */
    public static void clearAll() {
        for (PendingSummon pending : PENDING.values()) {
            DragonEntity dragon = pending.dragon;
            if (dragon != null && !dragon.isRemoved() && dragon.isHiddenForSummon()) {
                dragon.cancelHiddenEntrance();
            }
        }
        PENDING.clear();
        ACTIVE_DRAGONS.clear();
        DragonFearManager.clearAll();
    }

    /** Exposed for the verification harness. */
    public static int activeDragonCount() {
        return ACTIVE_DRAGONS.size();
    }

    /**
     * Empties the in-memory dragon map without touching the world.
     *
     * <p>Exists so the harness can reproduce the restart case without restarting: this is precisely what
     * {@code ServerStoppingEvent} does to the map, so if the one-per-player limit still holds afterwards
     * it is holding on the persisted {@code summonerUuid} rather than on the map.
     */
    public static void clearActiveDragonsForTest() {
        ACTIVE_DRAGONS.clear();
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
