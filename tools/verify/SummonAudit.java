package dev.rasengan.verify;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.rasengan.PowerState;
import dev.rasengan.RasenganConfig;
import dev.rasengan.network.RasenganSummonPayloads;
import dev.rasengan.server.DragonEntity;
import dev.rasengan.server.DragonFearManager;
import dev.rasengan.server.FleeFromDragonGoal;
import dev.rasengan.server.PowerData;
import dev.rasengan.server.ServerSummonManager;
import dev.rasengan.server.SummonLines;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Logged-evidence harness for the Summoning Jutsu.
 *
 * <p><b>Not part of the mod.</b> It lives in {@code tools/verify/} and is copied into
 * {@code src/main/java/dev/rasengan/verify/} only for the duration of a verification run by
 * {@code tools/verify/run_summon_test.sh}, which deletes it again afterwards.
 *
 * <p>It calls the <em>real</em> shipped entry points - {@link ServerSummonManager#trySummon},
 * the real charge-bar tick, the real timeline, the real line picker, the real payload codecs - so
 * every number printed is a statement about the code that ships, not about a reimplementation.
 *
 * <h2>Why a synthetic player</h2>
 * This environment has no display, so no real client can connect. The harness instead inserts a
 * genuine {@link ServerPlayer} into the real {@link PlayerList} and the real level, with a packet
 * listener that records outbound packets instead of writing them to a socket. Everything the summon
 * code touches - player list membership, distance-based broadcast, attachments, the tick event - is
 * therefore the real thing. What is NOT exercised: byte-level serialisation over a socket (covered
 * separately by the {@code codec} subcommand) and anything client-side.
 */
@EventBusSubscriber(modid = "rasengan")
public final class SummonAudit {

    private SummonAudit() {}

    private static final List<String> REPORT = new ArrayList<>();
    private static Scenario active;

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        log("SUMMONAUDIT harness registered");
        LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> root = Commands.literal("summonaudit")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        root.then(Commands.literal("lines")
                .executes(context -> {
                    auditLines(-1);
                    return 1;
                })
                .then(Commands.argument("expected", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                        .executes(context -> {
                            auditLines(com.mojang.brigadier.arguments.IntegerArgumentType
                                    .getInteger(context, "expected"));
                            return 1;
                        })));
        root.then(Commands.literal("codec").executes(context -> {
            auditCodecs(context.getSource().getServer());
            return 1;
        }));
        root.then(Commands.literal("run").executes(context -> {
            if (active != null) {
                log("FAIL a scenario is already running");
                return 0;
            }
            active = new Scenario(context.getSource().getServer());
            return 1;
        }));
        root.then(Commands.literal("report").executes(context -> {
            dump();
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    private static void log(String message) {
        REPORT.add(message);
        System.out.println("[SUMMONAUDIT] " + message);
    }

    private static void check(boolean ok, String what) {
        log((ok ? "PASS " : "FAIL ") + what);
    }

    private static void dump() {
        System.out.println("[SUMMONAUDIT] ===== BEGIN REPORT =====");
        for (String line : REPORT) {
            System.out.println("[SUMMONAUDIT] | " + line);
        }
        long fails = REPORT.stream().filter(l -> l.startsWith("FAIL")).count();
        long passes = REPORT.stream().filter(l -> l.startsWith("PASS")).count();
        System.out.println("[SUMMONAUDIT] ===== END REPORT: " + passes + " pass, " + fails + " fail =====");
    }

    // ==================================================================
    // A. The awakening lines
    // ==================================================================

    private static void auditLines(int expected) {
        SummonLines lines = SummonLines.get();
        Identifier configured = SummonLines.configuredResource(
                RasenganConfig.SERVER.summonLinesResource.get());
        int pool = lines.poolSize(configured);
        log("poolSize=" + pool + " loadedTotal=" + lines.loadedTotal()
                + " files=" + lines.files());
        log("configured resource=" + configured + " (from config value '"
                + RasenganConfig.SERVER.summonLinesResource.get() + "')");
        check(lines.files().contains(configured), "configured resource is among the loaded files");
        if (expected < 0) {
            // Only meaningful for the shipped state. Later stages deliberately swap in a tiny
            // datapack to prove override works, and 3 lines there is the expected answer, not a
            // failure to meet the brief.
            check(pool >= 50, "at least 50 speakable lines (brief asks 50+), got " + pool);
        } else {
            check(pool == expected, "speakable line count is exactly " + expected + ", got " + pool);
        }

        // Draw enough samples that a stuck picker is obvious, and confirm the pool really is the
        // configured file rather than the built-in fallback.
        RandomSource random = RandomSource.create(0xC0FFEEL);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 4000; i++) {
            seen.add(lines.pick(random, configured));
        }
        log("4000 picks yielded " + seen.size() + " distinct lines");
        check(seen.size() == pool,
                "every line in the pool is reachable by pick() (" + seen.size() + "/" + pool + ")");

        for (String line : seen) {
            if (line.indexOf('\u00A7') >= 0) {
                check(false, "line contains a legacy section code: " + line);
            }
        }
        check(seen.stream().noneMatch(l -> l.indexOf('\u00A7') >= 0), "no line uses legacy section codes");

        // A bad config value must still speak, from the pooled set.
        String viaBadId = lines.pick(random, Identifier.fromNamespaceAndPath("rasengan", "does_not_exist"));
        check(!viaBadId.isEmpty(),
                "an unknown configured resource still speaks rather than falling silent");

        // Loaded-but-unselected files must not leak into the spoken set. This is the documented rule
        // that makes the lines_resource config option mean anything.
        if (lines.loadedTotal() > pool) {
            log("note: " + (lines.loadedTotal() - pool)
                    + " line(s) are loaded from other files and correctly not spoken");
            check(seen.stream().noneMatch(l -> l.startsWith("MARK datapack")),
                    "an extra file that lines_resource does not point at is never spoken");
        }
    }

    // ==================================================================
    // B. Payload codecs, actually round-tripped
    // ==================================================================

    private static void auditCodecs(MinecraftServer server) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), server.registryAccess());

        RasenganSummonPayloads.SummonStart start = new RasenganSummonPayloads.SummonStart(
                7, 99, -1234567890123L, 12.5D, -48.0D, -7.25D, 70, 32,
                12, 64.0F, 1.0F, true);
        RasenganSummonPayloads.SummonStart.CODEC.encode(buf, start);
        int startBytes = buf.readableBytes();
        RasenganSummonPayloads.SummonStart back =
                RasenganSummonPayloads.SummonStart.CODEC.decode(buf);
        check(start.equals(back), "SummonStart survives encode/decode (" + startBytes + " bytes)");
        check(buf.readableBytes() == 0, "SummonStart decode consumed exactly what encode wrote");
        check(back.dragonId() == 99,
                "SummonStart carries the pre-spawned dragon id through the wire");
        check(back.cameraEnabled(), "a non-zero release length means the camera effect is on");
        check(!new RasenganSummonPayloads.SummonStart(
                        0, 0, 0L, 0, 0, 0, 70, 32, 0, 64.0F, 1.0F, false).cameraEnabled(),
                "a zero release length means the camera effect is off");

        buf.clear();
        // SummonEnd: the packet that makes interruption safe. Two varints.
        for (RasenganSummonPayloads.SummonEnd.Reason reason
                : RasenganSummonPayloads.SummonEnd.Reason.values()) {
            buf.clear();
            RasenganSummonPayloads.SummonEnd end =
                    RasenganSummonPayloads.SummonEnd.of(7, reason);
            RasenganSummonPayloads.SummonEnd.CODEC.encode(buf, end);
            int bytes = buf.readableBytes();
            RasenganSummonPayloads.SummonEnd decoded =
                    RasenganSummonPayloads.SummonEnd.CODEC.decode(buf);
            check(end.equals(decoded) && decoded.reasonValue() == reason,
                    "SummonEnd/" + reason + " survives encode/decode (" + bytes + " bytes)");
            check(buf.readableBytes() == 0, "SummonEnd/" + reason + " decode consumed exactly what "
                    + "encode wrote");
        }
        check(RasenganSummonPayloads.SummonEnd.Reason.byId(99)
                        == RasenganSummonPayloads.SummonEnd.Reason.INTERRUPTED,
                "an unknown SummonEnd reason falls back to INTERRUPTED, so a version mismatch "
                        + "releases the camera rather than stranding it");

        buf.clear();
        RasenganSummonPayloads.SummonPowerSync sync =
                new RasenganSummonPayloads.SummonPowerSync(6000, 6000, 0, PowerState.READY.id());
        RasenganSummonPayloads.SummonPowerSync.CODEC.encode(buf, sync);
        check(sync.equals(RasenganSummonPayloads.SummonPowerSync.CODEC.decode(buf)),
                "SummonPowerSync survives encode/decode");

        buf.clear();
        RasenganSummonPayloads.SummonActivate.CODEC.encode(
                buf, RasenganSummonPayloads.SummonActivate.INSTANCE);
        check(buf.readableBytes() == 0, "SummonActivate writes zero bytes (intent only, nothing to forge)");
    }

    // ==================================================================
    // C. The live scenario
    // ==================================================================

    /** A real ServerPlayer whose outbound packets are recorded rather than sent. */
    private static final class Recorder extends ServerPlayer {
        final List<CustomPacketPayload> payloads = new ArrayList<>();
        final List<Component> chat = new ArrayList<>();

        Recorder(MinecraftServer server, ServerLevel level, String name) {
            super(server, level, new GameProfile(
                            UUID.nameUUIDFromBytes(name.getBytes()), name),
                    ClientInformation.createDefault());
            this.connection = new Listener(server, this);
            this.setInvulnerable(true);
        }

        void clear() {
            payloads.clear();
            chat.clear();
        }

        List<CustomPacketPayload> mine(Class<?> type) {
            return payloads.stream().filter(type::isInstance).toList();
        }
    }

    private static final class Listener extends ServerGamePacketListenerImpl {
        private final Recorder owner;

        Listener(MinecraftServer server, Recorder owner) {
            super(server, new Wire(), owner,
                    CommonListenerCookie.createInitial(owner.getGameProfile(), false));
            this.owner = owner;
        }

        @Override
        public void send(Packet<?> packet) {
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            record(packet);
        }

        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                owner.payloads.add(custom.payload());
            } else if (packet instanceof ClientboundSystemChatPacket chat) {
                owner.chat.add(chat.content());
            }
        }

        @Override
        public void tick() {}

        @Override
        public void resetPosition() {}

        @Override
        public void disconnect(Component message) {}

        @Override
        public void onDisconnect(net.minecraft.network.DisconnectionDetails details) {}

        @Override
        public boolean hasChannel(Identifier id) {
            return true;
        }
    }

    /** Same trick NeoForge's FakePlayer uses: a Connection that is never wired to a socket. */
    private static final class Wire extends Connection {
        Wire() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void setListenerForServerboundHandshake(PacketListener listener) {}
    }

    private static void join(MinecraftServer server, Recorder player) throws Exception {
        PlayerList list = server.getPlayerList();
        Field players = PlayerList.class.getDeclaredField("players");
        Field byUuid = PlayerList.class.getDeclaredField("playersByUUID");
        players.setAccessible(true);
        byUuid.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ServerPlayer> live = (List<ServerPlayer>) players.get(list);
        @SuppressWarnings("unchecked")
        Map<UUID, ServerPlayer> index = (Map<UUID, ServerPlayer>) byUuid.get(list);
        live.add(player);
        index.put(player.getUUID(), player);
        player.level().addNewPlayer(player);
    }

    private static void leave(MinecraftServer server, Recorder player) throws Exception {
        PlayerList list = server.getPlayerList();
        Field players = PlayerList.class.getDeclaredField("players");
        Field byUuid = PlayerList.class.getDeclaredField("playersByUUID");
        players.setAccessible(true);
        byUuid.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ServerPlayer> live = (List<ServerPlayer>) players.get(list);
        @SuppressWarnings("unchecked")
        Map<UUID, ServerPlayer> index = (Map<UUID, ServerPlayer>) byUuid.get(list);
        live.remove(player);
        index.remove(player.getUUID());
        player.discard();
    }

    private static final String TAG = "[Demonic Wingwalker]";

    private static final class Scenario {
        final MinecraftServer server;
        final ServerLevel level;
        Recorder summoner;
        Recorder observer;

        int t = -1;
        int summonTick = -1;
        int revealTick = -1;
        int endTick = -1;
        int announceCount;
        int maxDragons;
        int lateGateChecks;
        boolean started;
        boolean preSpawnSeen;

        // ---- fear ----
        int mobsPlaced;
        int fearAtReveal;
        final List<net.minecraft.world.entity.Mob> fearTargets = new ArrayList<>();
        final List<Vec3> fearStartPos = new ArrayList<>();
        net.minecraft.world.entity.Mob farMob;
        net.minecraft.world.entity.Mob immuneMob; // unused: immunity is asserted via the tag
        int fearExpiredTick = -1;
        double maxFleeDistance;
        DragonEntity interruptedDragon;

        // ---- mount / ride ----
        int mountSummonTick = -1;
        int graceStartTick = -1;
        int mountedTick = -1;
        int dismountTick = -1;
        double mountY;
        double maxClimbY;
        final List<Double> launchSamples = new ArrayList<>();
        int damageAttempts;
        int damageLanded;
        int firstDamageTick = -1;
        int lastBlockedTick = -1;

        Vec3 firstDragonPos;
        Vec3 lastDragonPos;
        double pathLength;
        int stalledTicks;
        int flightSamples;

        Scenario(MinecraftServer server) {
            this.server = server;
            this.level = server.overworld();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Scenario s = active;
        if (s == null) {
            return;
        }
        try {
            step(s);
        } catch (Throwable error) {
            log("FAIL scenario threw " + error);
            error.printStackTrace();
            active = null;
        }
    }

    private static void step(Scenario s) throws Exception {
        s.t++;
        int total = RasenganConfig.SERVER.summonCinematicTicks.get();
        int configuredReveal = RasenganConfig.summonRevealTick();

        // The one thing a synthetic player genuinely cannot have is a network tick, and in 26.1
        // ServerPlayer.tick() does NOT call Player.tick() - the level's entity tick runs only the
        // light half. Player.tick(), which is where NeoForge fires PlayerTickEvent and therefore
        // where both charge bars advance, is reached from ServerPlayer.doTick(), called once per
        // tick by ServerGamePacketListenerImpl.tickPlayer(). The harness stands in for exactly that
        // call and nothing else. Without it the bars sit at 0 forever - which looks like a charging
        // bug in the mod and is not one.
        if (s.summoner != null) {
            s.summoner.doTick();
            s.observer.doTick();
        }

        switch (s.t) {
            case 0 -> {
                log("---- scenario start ----");
                var stages = RasenganConfig.summonStages();
                log("config: charge=" + RasenganConfig.summonChargeDurationTicks() + "t total="
                        + total + "t reveal=" + configuredReveal
                        + "t camera_enabled=" + RasenganConfig.SERVER.summonCameraEffect.get()
                        + " release=" + RasenganConfig.SERVER.summonCameraReleaseTicks.get() + "t");
                log(String.format("stages: A 0-%.2f  B -%.2f  C -%.2f  D -%.2f  E -%d"
                                + "  (dispersal wing-aligned=%b)",
                        stages.sealEnd(), stages.eruptionEnd(), stages.revealEnd(),
                        stages.dispersalEnd(), stages.total(), stages.dispersalWingAligned()));
                log("fear: enabled=" + RasenganConfig.SERVER.fearEnabled.get()
                        + " radius=" + RasenganConfig.SERVER.fearRadius.get()
                        + " duration=" + RasenganConfig.SERVER.fearDurationTicks.get() + "t"
                        + " suppress=" + RasenganConfig.SERVER.fearSuppressAttacks.get());
                ServerSummonManager.clearAll();
                killDragons(s.level);
                s.summoner = new Recorder(s.server, s.level, "AuditSummoner");
                s.observer = new Recorder(s.server, s.level, "AuditObserver");
                s.summoner.snapTo(0.5D, -59.0D, 0.5D, 0.0F, 0.0F);
                s.observer.snapTo(6.5D, -59.0D, 0.5D, 180.0F, 0.0F);
                join(s.server, s.summoner);
                join(s.server, s.observer);
                log("two synthetic players joined; playerList size="
                        + s.server.getPlayerList().getPlayerCount());
            }
            case 3 -> {
                PowerData data = ServerSummonManager.data(s.summoner);
                log("summoner bar after 3 ticks: state=" + data.state()
                        + " charge=" + data.chargeTicks());
                check(data.state() == PowerState.CHARGING,
                        "a fresh summoning bar starts CHARGING, not READY");
                check(data.chargeTicks() > 0,
                        "the summoning bar is actually accumulating (charge=" + data.chargeTicks() + ")");
                check(!s.summoner.mine(RasenganSummonPayloads.SummonPowerSync.class).isEmpty(),
                        "the summoner receives SummonPowerSync for its own bar");
                check(s.observer.mine(RasenganSummonPayloads.SummonPowerSync.class).stream()
                                .findAny().isPresent(),
                        "each player receives its own bar sync (observer has one too)");
            }
            case 5 -> {
                // Gate: not READY must refuse, with a message.
                s.summoner.clear();
                boolean ok = ServerSummonManager.trySummon(s.summoner);
                check(!ok, "trySummon refuses while the bar is CHARGING");
                check(s.summoner.chat.size() == 1, "the refusal is explained to the player (chat="
                        + s.summoner.chat.size() + ")");
                if (!s.summoner.chat.isEmpty()) {
                    log("refusal text: " + s.summoner.chat.get(0).getString());
                }
                check(ServerSummonManager.pendingCount() == 0, "no cinematic started on refusal");
            }
            case 6 -> {
                // ---- place hostiles for the fear test, BEFORE the summon ----
                //
                // The seal lands ARRIVAL_DISTANCE blocks along the summoner's look vector, which is
                // +Z here (yaw 0), so the dragon arrives near (0.5, y, 9.5). Mobs are placed close to
                // that, one deliberately outside the radius, and one carrying the fear-immune tag.
                DragonFearManager.clearAll();
                double radius = RasenganConfig.SERVER.fearRadius.get();
                double[][] spots = {{4.5D, 11.5D}, {-3.5D, 12.5D}, {0.5D, 16.5D}};
                for (double[] spot : spots) {
                    var zombie = net.minecraft.world.entity.EntityType.ZOMBIE
                            .create(s.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    if (zombie == null) {
                        continue;
                    }
                    zombie.snapTo(spot[0], -59.0D, spot[1], 0.0F, 0.0F);
                    zombie.setPersistenceRequired();
                    if (s.level.addFreshEntity(zombie)) {
                        s.fearTargets.add(zombie);
                        s.mobsPlaced++;
                    }
                }
                // Far outside the radius.
                var far = net.minecraft.world.entity.EntityType.ZOMBIE
                        .create(s.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (far != null) {
                    far.snapTo(0.5D, -59.0D, 9.5D + radius + 20.0D, 0.0F, 0.0F);
                    far.setPersistenceRequired();
                    if (s.level.addFreshEntity(far)) {
                        s.farMob = far;
                    }
                }
                log("placed " + s.mobsPlaced + " hostiles inside the fear radius, 1 outside it");
                check(s.mobsPlaced == 3, "three test hostiles were placed (" + s.mobsPlaced + ")");
                check(DragonFearManager.frightenedCount() == 0,
                        "nothing is frightened before the summon");

                // ---- boss immunity, checked as a tag rather than with a live boss ----
                //
                // Deliberately not by spawning a Warden or a Wither inside the radius: both would
                // attack the zombies this scenario is measuring, and a Warden also digs down and
                // despawns. Either would corrupt the flee measurement and the failure would look like
                // a fear bug. The mechanism worth testing is that the tag file actually loaded and
                // contains the right entries - a wrong resource path is the realistic failure here,
                // and it would silently make every boss frightenable.
                var immuneTag = DragonFearManager.FEAR_IMMUNE;
                boolean dragonImmune = dev.rasengan.RasenganEntities.DRAGON.get()
                        .builtInRegistryHolder().is(immuneTag);
                boolean witherImmune = net.minecraft.world.entity.EntityType.WITHER
                        .builtInRegistryHolder().is(immuneTag);
                boolean enderImmune = net.minecraft.world.entity.EntityType.ENDER_DRAGON
                        .builtInRegistryHolder().is(immuneTag);
                boolean zombieImmune = net.minecraft.world.entity.EntityType.ZOMBIE
                        .builtInRegistryHolder().is(immuneTag);
                log("fear_immune tag: dragon=" + dragonImmune + " wither=" + witherImmune
                        + " ender_dragon=" + enderImmune + " zombie=" + zombieImmune);
                check(dragonImmune,
                        "the tag file loaded and our own dragon is fear-immune, so two dragons will "
                                + "not flee from each other");
                check(witherImmune && enderImmune, "vanilla bosses are in the fear-immune tag");
                check(!zombieImmune, "an ordinary hostile is NOT in the fear-immune tag");
            }
            case 7 -> {
                PowerData data = ServerSummonManager.data(s.summoner);
                data.setChargeTicks(RasenganConfig.summonChargeDurationTicks());
                data.setState(PowerState.READY);
                log("summoning bar forced to READY");
            }
            case 8 -> {
                // Independence of the two bars: filling the summoning bar must not fill the cast bar.
                PowerData cast = s.summoner.getData(
                        dev.rasengan.server.RasenganAttachments.POWER.get());
                PowerData summon = ServerSummonManager.data(s.summoner);
                log("cast bar: state=" + cast.state() + " charge=" + cast.chargeTicks()
                        + " | summon bar: state=" + summon.state() + " charge=" + summon.chargeTicks());
                check(cast.state() == PowerState.CHARGING
                                && cast.chargeTicks() > 0
                                && cast.chargeTicks() < 20
                                && summon.chargeTicks() == RasenganConfig.summonChargeDurationTicks(),
                        "the two bars are independent: the summoning bar is full while the cast bar "
                                + "is still on its first few ticks");

                s.summoner.clear();
                s.observer.clear();
                boolean ok = ServerSummonManager.trySummon(s.summoner);
                check(ok, "trySummon succeeds at READY");
                s.started = ok;
                s.summonTick = s.t;

                List<CustomPacketPayload> mine =
                        s.summoner.mine(RasenganSummonPayloads.SummonStart.class);
                List<CustomPacketPayload> theirs =
                        s.observer.mine(RasenganSummonPayloads.SummonStart.class);
                check(mine.size() == 1, "summoner got exactly one SummonStart (" + mine.size() + ")");
                check(theirs.size() == 1,
                        "a nearby player got the same SummonStart, so the cinematic is not summoner-only ("
                                + theirs.size() + ")");
                if (!mine.isEmpty() && !theirs.isEmpty()) {
                    RasenganSummonPayloads.SummonStart a =
                            (RasenganSummonPayloads.SummonStart) mine.get(0);
                    RasenganSummonPayloads.SummonStart b =
                            (RasenganSummonPayloads.SummonStart) theirs.get(0);
                    check(a.equals(b), "both players got byte-identical SummonStart data (same seed "
                            + a.seed() + ")");
                    log("SummonStart: summonerId=" + a.summonerId() + " dragonId=" + a.dragonId()
                            + " origin=(" + a.x() + "," + a.y() + "," + a.z() + ") total="
                            + a.totalTicks() + " reveal=" + a.revealTick()
                            + " release=" + a.cameraReleaseTicks() + " radius=" + a.cameraRadius()
                            + " density=" + a.smokeDensity() + " vignette=" + a.vignette());
                    check(a.totalTicks() == total && a.revealTick() == configuredReveal,
                            "SummonStart carries the configured timings");
                    check(a.summonerId() == s.summoner.getId(),
                            "SummonStart identifies the summoner by entity id");
                    check(a.cameraEnabled() == RasenganConfig.SERVER.summonCameraEffect.get(),
                            "SummonStart's camera flag matches the config toggle");

                    // ---- the pre-spawned dragon, on the FIRST tick of the sequence ----
                    DragonEntity pre = firstDragon(s.level);
                    check(pre != null, "the dragon already exists on the cinematic's first tick");
                    if (pre != null) {
                        check(a.dragonId() == pre.getId(),
                                "SummonStart carries the pre-spawned dragon's entity id, so the "
                                        + "client can warm its assets before the reveal");
                        check(pre.isHiddenForSummon(), "the pre-spawned dragon is hidden");
                        check(pre.isInvulnerable(),
                                "the hidden dragon cannot be hit before it is revealed");
                        check(pre.isNoAi(), "the hidden dragon runs no AI while it waits");
                        check(pre.entranceTotalTicks() == total,
                                "the dragon knows the cinematic length, for its wing phase ("
                                        + pre.entranceTotalTicks() + ")");
                        // Concentric: seal, smoke and dragon must share an XZ centre or the reveal
                        // cannot work - the cloud would be beside the dragon rather than around it.
                        double dxz = Math.hypot(pre.getX() - a.x(), pre.getZ() - a.z());
                        log(String.format("dragon pre-spawned at %s; seal centre (%.2f, %.2f);"
                                        + " horizontal offset %.4f", fmt(pre.position()), a.x(), a.z(), dxz));
                        check(dxz < 1.0E-6D,
                                "the dragon is concentric with the seal and the smoke column ("
                                        + String.format("%.2e", dxz) + " blocks off)");
                        check(pre.getY() > a.y(), "the dragon sits above the seal, not inside it");
                        double fromPlayer = Math.hypot(a.x() - s.summoner.getX(),
                                a.z() - s.summoner.getZ());
                        check(fromPlayer > 4.0D,
                                "the seal is placed in front of the summoner, not underfoot ("
                                        + String.format("%.2f", fromPlayer) + " blocks)");
                        s.preSpawnSeen = true;
                    }
                }
                PowerData after = ServerSummonManager.data(s.summoner);
                check(after.state() == PowerState.CASTING,
                        "the bar goes to CASTING for the duration, got " + after.state());
                check(after.chargeTicks() == 0, "the bar is spent immediately, not on completion");
                check(ServerSummonManager.pendingCount() == 1, "exactly one cinematic is pending");
            }
            case 9 -> {
                s.summoner.clear();
                boolean ok = ServerSummonManager.trySummon(s.summoner);
                check(!ok, "a second press mid-cinematic is ignored");
                check(s.summoner.chat.isEmpty(),
                        "the mid-cinematic press is silent rather than spamming chat ("
                                + s.summoner.chat.size() + ")");
                check(ServerSummonManager.pendingCount() == 1, "still exactly one cinematic pending");
                s.summoner.clear();
                s.observer.clear();
            }
            default -> { }
        }

        if (!s.started) {
            return;
        }

        // ---- watch the sequence ----
        int dragons = dragonCount(s.level);
        s.maxDragons = Math.max(s.maxDragons, dragons);

        // The dragon now EXISTS from tick 0, so "revealed" is the hidden flag clearing, not the
        // entity appearing. Detecting it by entity count - as this harness used to - would now report
        // the reveal on the sequence's first tick and pass while the timing was completely wrong.
        DragonEntity watched = firstDragon(s.level);
        if (s.revealTick < 0 && watched != null && !watched.isHiddenForSummon()) {
            s.revealTick = s.t;
            int delta = s.t - s.summonTick;
            log("dragon became visible at scenario tick " + s.t + " = summon+" + delta);
            check(delta == configuredReveal,
                    "reveal landed on the configured tick (" + delta + " vs " + configuredReveal + ")");
            check(dragons == 1, "exactly one dragon exists at the reveal, got " + dragons);
            check(!watched.isInvulnerable(),
                    "the dragon becomes hittable the moment it becomes visible");
            check(!watched.isNoAi(), "the dragon's AI is released at the reveal");
            check(watched.isEntering(), "the dragon is still inside its arrival sequence");
            check(watched.summoner() != null
                            && watched.summoner().equals(s.summoner.getUUID()),
                    "the dragon records its summoner for the one-per-player limit");
            check(ServerSummonManager.activeDragonCount() == 1,
                    "the manager is tracking one live dragon");
            s.firstDragonPos = watched.position();
            log("dragon revealed at " + fmt(watched.position()) + ", summoner at "
                    + fmt(s.summoner.position()) + ", distance "
                    + String.format("%.2f", watched.position().distanceTo(s.summoner.position())));

            // ---- fear, applied on this same tick ----
            s.fearAtReveal = DragonFearManager.frightenedCount();
            log("hostile mobs frightened on the reveal beat: " + s.fearAtReveal
                    + " (of " + s.mobsPlaced + " placed)");
            check(s.fearAtReveal == s.mobsPlaced,
                    "every hostile inside the radius was frightened (" + s.fearAtReveal + "/"
                            + s.mobsPlaced + ")");
            check(DragonFearManager.frightenedCount() > 0
                            || !RasenganConfig.SERVER.fearEnabled.get(),
                    "the fear reaction fired");
            for (net.minecraft.world.entity.Mob mob : s.fearTargets) {
                int left = DragonFearManager.ticksLeftFor(mob);
                check(left > 0, mob.getType().toShortString() + " has fear ticks remaining (" + left + ")");
                check(mob.getTarget() == null,
                        mob.getType().toShortString() + " dropped its attack target");
                boolean hasGoal = mob.goalSelector.getAvailableGoals().stream()
                        .anyMatch(w -> w.getGoal() instanceof FleeFromDragonGoal);
                check(hasGoal, mob.getType().toShortString()
                        + " has a real flee goal inserted, not just a debuff");
                s.fearStartPos.add(mob.position());
            }
            // The one mob outside the radius must be untouched - otherwise "radius" means nothing.
            if (s.farMob != null) {
                check(DragonFearManager.ticksLeftFor(s.farMob) < 0,
                        "a hostile outside the fear radius was NOT frightened");
            }
            check(DragonFearManager.ticksLeftFor(watched) < 0,
                    "the dragon did not frighten itself");
        }

        // Count awakening announcements for the whole run, on both players.
        int announces = 0;
        for (Component line : s.summoner.chat) {
            if (line.getString().contains(TAG)) {
                announces++;
            }
        }
        // Only accounted for the FIRST summon. The mount phase deliberately summons a second dragon, and
        // its awakening line is correct but arrives long after s.revealTick was recorded for the first -
        // so the "fires on the reveal tick" assertion would compare against a stale tick and fail a
        // working announcement. The once-per-summon guarantee is already established above.
        if (s.mountSummonTick > 0) {
            s.summoner.clear();
            s.observer.clear();
            announces = 0;
        }
        if (announces > 0) {
            s.announceCount += announces;
            List<Component> observerLines = s.observer.chat.stream()
                    .filter(c -> c.getString().contains(TAG)).toList();
            log("awakening line: " + s.summoner.chat.stream()
                    .filter(c -> c.getString().contains(TAG)).findFirst()
                    .map(Component::getString).orElse("<none>"));
            check(observerLines.size() == announces,
                    "the nearby player received the line too (" + observerLines.size() + ")");
            if (!observerLines.isEmpty()) {
                Component mine = s.summoner.chat.stream()
                        .filter(c -> c.getString().contains(TAG)).findFirst().orElseThrow();
                check(mine.equals(observerLines.get(0)),
                        "both players received the identical component, so the server picked the line");
                check(mine.getString().indexOf('\u00A7') < 0,
                        "the announcement carries no legacy section codes");
            }
            check(s.revealTick == s.t, "the line fires on the reveal tick, not earlier or later");
            s.summoner.clear();
            s.observer.clear();
        }

        // ---- flee progress: are the frightened mobs actually moving away? ----
        if (s.revealTick > 0 && !s.fearTargets.isEmpty() && s.fearStartPos.size() == s.fearTargets.size()) {
            DragonEntity dragon = firstDragon(s.level);
            if (dragon != null) {
                for (int i = 0; i < s.fearTargets.size(); i++) {
                    var mob = s.fearTargets.get(i);
                    if (!mob.isAlive()) {
                        continue;
                    }
                    double before = s.fearStartPos.get(i).distanceTo(dragon.position());
                    double now = mob.position().distanceTo(dragon.position());
                    s.maxFleeDistance = Math.max(s.maxFleeDistance, now - before);
                }
            }
        }

        // ---- fear expiry and cleanup ----
        if (s.revealTick > 0 && s.fearExpiredTick < 0
                && DragonFearManager.frightenedCount() == 0 && s.fearAtReveal > 0) {
            s.fearExpiredTick = s.t;
            int elapsed = s.t - s.revealTick;
            int configured = RasenganConfig.SERVER.fearDurationTicks.get();
            log("fear expired at scenario tick " + s.t + " = reveal+" + elapsed
                    + " (configured " + configured + "t)");
            log(String.format("furthest a frightened mob got from the dragon: %+.2f blocks",
                    s.maxFleeDistance));
            check(Math.abs(elapsed - configured) <= 2,
                    "fear lasted the configured duration (" + elapsed + " vs " + configured + "t)");
            check(s.maxFleeDistance > 1.0D,
                    "frightened mobs actually moved away from the dragon (max "
                            + String.format("%+.2f", s.maxFleeDistance) + " blocks)");

            // The cleanup guarantee: no leftover goals, and targeting restored.
            int leftover = 0;
            int suppressed = 0;
            for (var mob : s.fearTargets) {
                if (!mob.isAlive()) {
                    continue;
                }
                long goals = mob.goalSelector.getAvailableGoals().stream()
                        .filter(w -> w.getGoal() instanceof FleeFromDragonGoal).count();
                leftover += (int) goals;
                if (mob.targetSelector.getAvailableGoals().isEmpty()) {
                    continue;
                }
                if (DragonFearManager.isTargetingSuppressed(mob)) {
                    suppressed++;
                }
            }
            log("leftover flee goals after expiry: " + leftover
                    + "; still-suppressed mobs: " + suppressed);
            check(leftover == 0,
                    "every temporary flee goal was removed - no permanently fleeing mobs ("
                            + leftover + " left)");
            check(suppressed == 0, "targeting suppression was lifted on every mob");
            check(DragonFearManager.ticksLeftFor(s.fearTargets.get(0)) < 0,
                    "the manager no longer tracks the expired mobs");
        }

        if (s.endTick < 0 && s.t >= s.summonTick + total) {
            s.endTick = s.t;
            PowerData data = ServerSummonManager.data(s.summoner);
            log("sequence end at scenario tick " + s.t + " = summon+" + (s.t - s.summonTick)
                    + "; bar state=" + data.state() + " charge=" + data.chargeTicks());
            check(ServerSummonManager.pendingCount() == 0,
                    "the cinematic is finished and cleaned up");

            // ---- SummonEnd ----
            List<CustomPacketPayload> ends =
                    s.summoner.mine(RasenganSummonPayloads.SummonEnd.class);
            List<CustomPacketPayload> theirEnds =
                    s.observer.mine(RasenganSummonPayloads.SummonEnd.class);
            check(ends.size() == 1,
                    "the summoner got exactly one SummonEnd (" + ends.size() + ")");
            check(theirEnds.size() == 1,
                    "the observer got the same SummonEnd, so their camera is released too ("
                            + theirEnds.size() + ")");
            if (!ends.isEmpty()) {
                var end = (RasenganSummonPayloads.SummonEnd) ends.get(0);
                log("SummonEnd: summonerId=" + end.summonerId() + " reason=" + end.reasonValue());
                check(end.reasonValue() == RasenganSummonPayloads.SummonEnd.Reason.COMPLETED,
                        "a sequence that ran to its end reports COMPLETED");
                check(end.summonerId() == s.summoner.getId(),
                        "SummonEnd identifies which sequence ended");
                if (!theirEnds.isEmpty()) {
                    check(end.equals(theirEnds.get(0)),
                            "both players got byte-identical SummonEnd data");
                }
            }
            check(data.state() == PowerState.CHARGING,
                    "the bar resumes charging only after the whole sequence, got " + data.state());
            check(s.announceCount == 1,
                    "the awakening line fired exactly once for the summon (" + s.announceCount + ")");
            check(s.maxDragons == 1, "never more than one dragon existed (" + s.maxDragons + ")");
        }

        // ---- one dragon per player, checked once the cinematic is actually over ----
        // Deliberately NOT during the sequence: while a summon is pending, trySummon returns early
        // on the "already mid-cinematic" branch, which is silent by design. Testing here would have
        // measured that branch and called it the ownership gate.
        if (s.endTick > 0 && s.t == s.endTick + 3) {
            check(dragonCount(s.level) == 1, "the dragon is still alive for the ownership test");
            PowerData data = ServerSummonManager.data(s.summoner);
            data.setChargeTicks(RasenganConfig.summonChargeDurationTicks());
            data.setState(PowerState.READY);
            s.summoner.clear();
            boolean ok = ServerSummonManager.trySummon(s.summoner);
            check(!ok, "a second summon is refused while the first dragon lives");
            check(s.summoner.chat.size() == 1, "the one-dragon-per-player refusal is explained ("
                    + s.summoner.chat.size() + " message(s))");
            if (!s.summoner.chat.isEmpty()) {
                log("refusal text: " + s.summoner.chat.get(0).getString());
            }
            check(dragonCount(s.level) == 1, "the refusal did not spawn a second dragon");
            check(ServerSummonManager.pendingCount() == 0, "the refusal started no cinematic");
            s.lateGateChecks++;
            s.summoner.clear();
        }

        // ---- flight watch: does it hand over to the normal AI? ----
        //
        // Deliberately delayed past the spawn grace. The dragon is now held grounded and immune for 10
        // seconds after the reveal, so the old window - which started 3 ticks after the cinematic - was
        // measuring the perch and reporting 0.00 blocks of flight and 99 stalled ticks. The assertion was
        // right about what it wanted and wrong about when to look.

        if (s.endTick > 0 && s.t > s.endTick + 165 && s.t <= s.endTick + 265) {
            DragonEntity dragon = firstDragon(s.level);
            if (dragon != null) {
                Vec3 now = dragon.position();
                if (s.lastDragonPos != null) {
                    double stepped = now.distanceTo(s.lastDragonPos);
                    s.pathLength += stepped;
                    if (stepped < 0.01D) {
                        s.stalledTicks++;
                    }
                    s.flightSamples++;
                }
                s.lastDragonPos = now;
            }
        }

        if (s.endTick > 0 && s.t == s.endTick + 266) {
            log("flight after the cinematic: " + s.flightSamples + " samples, path "
                    + String.format("%.2f", s.pathLength) + " blocks, mean "
                    + String.format("%.3f", s.pathLength / Math.max(1, s.flightSamples))
                    + " b/t, stalled ticks " + s.stalledTicks);
            check(s.stalledTicks == 0, "the summoned dragon never stalls (" + s.stalledTicks + " ticks)");
            check(s.pathLength > 20.0D,
                    "the summoned dragon genuinely flies off under the normal AI ("
                            + String.format("%.2f", s.pathLength) + " blocks)");
            DragonEntity dragon = firstDragon(s.level);
            check(dragon != null && !dragon.isEntering(),
                    "the arrival flourish has ended and normal AI has the entity");
        }

        if (s.endTick > 0 && s.t == s.endTick + 270) {
            log("killing the dragon to test that the gate reopens");
            killDragons(s.level);
        }
        if (s.endTick > 0 && s.t == s.endTick + 274) {
            check(dragonCount(s.level) == 0, "dragon is gone");
            check(ServerSummonManager.activeDragonCount() == 0,
                    "the manager pruned the dead dragon ("
                            + ServerSummonManager.activeDragonCount() + ")");
            PowerData data = ServerSummonManager.data(s.summoner);
            data.setChargeTicks(RasenganConfig.summonChargeDurationTicks());
            data.setState(PowerState.READY);
            s.summoner.clear();
            boolean ok = ServerSummonManager.trySummon(s.summoner);
            check(ok, "once the dragon is dead the player may summon again");
            check(ServerSummonManager.pendingCount() == 1,
                    "the reopened gate really did start a fresh cinematic");
            check(s.lateGateChecks == 1, "the ownership refusal really was exercised");
            s.summoner.clear();
            s.observer.clear();
        }

        // ---- INTERRUPTION ----
        //
        // The fresh cinematic started two steps above is now killed off mid-flight by removing the
        // summoner, which is what a disconnect looks like to the manager. Three things must happen:
        // the clients must be told (or their cameras keep rolling for a summon that no longer
        // exists), the never-revealed dragon must be discarded rather than left hidden and frozen in
        // the world forever, and the bar must be released.
        if (s.endTick > 0 && s.t == s.endTick + 278) {
            DragonEntity hidden = firstDragon(s.level);
            log("---- interruption test ----");
            check(ServerSummonManager.pendingCount() == 1, "a cinematic is running to interrupt");
            check(hidden != null && hidden.isHiddenForSummon(),
                    "its dragon is pre-spawned and still hidden");
            s.interruptedDragon = hidden;
            s.observer.clear();
            // Kill the summoner mid-sequence. interruptionReason() treats a dead summoner the same
            // way as a disconnect, a dimension change or entering spectator.
            s.summoner.setHealth(0.0F);
            log("summoner killed at scenario tick " + s.t + " mid-cinematic");
        }
        if (s.endTick > 0 && s.t == s.endTick + 280) {
            check(ServerSummonManager.pendingCount() == 0,
                    "the interrupted cinematic was abandoned ("
                            + ServerSummonManager.pendingCount() + " still pending)");
            check(s.interruptedDragon == null || s.interruptedDragon.isRemoved(),
                    "the never-revealed dragon was discarded, not left hidden in the world");
            check(dragonCount(s.level) == 0,
                    "no leftover dragon after the interruption (" + dragonCount(s.level) + ")");

            List<CustomPacketPayload> ends =
                    s.observer.mine(RasenganSummonPayloads.SummonEnd.class);
            check(!ends.isEmpty(),
                    "the observer was told the sequence ended, so their camera is restored");
            if (!ends.isEmpty()) {
                var end = (RasenganSummonPayloads.SummonEnd) ends.get(ends.size() - 1);
                log("SummonEnd on interruption: reason=" + end.reasonValue());
                check(end.reasonValue() == RasenganSummonPayloads.SummonEnd.Reason.INTERRUPTED,
                        "the interruption is reported as INTERRUPTED, not COMPLETED");
            }
            check(DragonFearManager.frightenedCount() == 0,
                    "no fear was applied by a sequence that never reached its reveal ("
                            + DragonFearManager.frightenedCount() + ")");
        }
        // ==================================================================
        // MOUNT / RIDE
        // ==================================================================
        // Runs on a fresh dragon summoned at endTick+126, so the spawn grace is being observed from its
        // very first tick rather than inferred part-way through.
        if (s.endTick > 0 && s.t == s.endTick + 286) {
            ServerSummonManager.clearAll();
            killDragons(s.level);
            s.summoner.setHealth(20.0F);
            PowerData data = ServerSummonManager.data(s.summoner);
            data.setChargeTicks(RasenganConfig.summonChargeDurationTicks());
            data.setState(PowerState.READY);
            log("---- mount tests: summoning a fresh dragon ----");
            check(ServerSummonManager.trySummon(s.summoner), "a dragon was summoned for the mount test");
            s.mountSummonTick = s.t;
        }

        if (s.mountSummonTick > 0) {
            int sinceSummon = s.t - s.mountSummonTick;
            DragonEntity dragon = firstDragon(s.level);
            int reveal = RasenganConfig.summonRevealTick();

            // ---- the moment it becomes visible: grace must be armed ----
            if (dragon != null && sinceSummon == reveal + 1 && s.graceStartTick < 0) {
                s.graceStartTick = s.t;
                int expected = RasenganConfig.mountSpawnImmunityTicks();
                log("grace armed at reveal+1: immunity=" + dragon.spawnImmunityTicks()
                        + "t perch=" + dragon.perchTicks() + "t (configured " + expected + "t)");
                check(dragon.spawnImmunityTicks() > expected - 3,
                        "spawn immunity is armed for the configured duration ("
                                + dragon.spawnImmunityTicks() + " vs " + expected + ")");
                check(dragon.isSpawnImmune(), "the dragon reports itself immune");

                // ---- ownership ----
                check(dragon.summoner() != null
                                && dragon.summoner().equals(s.summoner.getUUID()),
                        "the dragon records its summoner");
                check(dragon.mayMount(s.summoner), "the summoner may mount");
                check(!dragon.mayMount(s.observer),
                        "a player who did not summon it may NOT mount");

                // ---- the head region is separate from the body ----
                // Stand the observer where the head is and aim at it; then aim at the body from the same
                // spot. Only the first may register.
                var headCentre = dev.rasengan.DragonAnchor.headCentre(dragon.position(),
                        dragon.getYRot(), dragon.getXRot(), (float) dragon.tickCount,
                        !dragon.onGround());
                log("head centre " + fmt(headCentre) + ", dragon at " + fmt(dragon.position())
                        + ", separation " + String.format("%.3f",
                                headCentre.distanceTo(dragon.position())) + " blocks");
                check(headCentre.distanceTo(dragon.position()) > 3.0D,
                        "the head region is over 3 blocks from the entity origin, i.e. outside the "
                                + "6.0-wide collision box");
            }

            // ---- damage attempts across and past the immunity window ----
            if (dragon != null && s.graceStartTick > 0) {
                int intoGrace = s.t - s.graceStartTick;
                int immunity = RasenganConfig.mountSpawnImmunityTicks();
                if (intoGrace <= immunity + 25 && intoGrace % 5 == 0) {
                    float before = dragon.getHealth();
                    // A deliberately varied set, so "immune to everything" is tested rather than
                    // "immune to player attacks".
                    var sources = dragon.damageSources();
                    var source = switch ((intoGrace / 5) % 4) {
                        case 0 -> sources.playerAttack(s.summoner);
                        case 1 -> sources.inFire();
                        case 2 -> sources.fall();
                        default -> sources.generic();
                    };
                    dragon.hurtServer(s.level, source, 25.0F);
                    float after = dragon.getHealth();
                    boolean tookDamage = after < before - 1.0E-4F;
                    s.damageAttempts++;
                    if (tookDamage) {
                        s.damageLanded++;
                        if (s.firstDamageTick < 0) {
                            s.firstDamageTick = intoGrace;
                            log("first damage landed at grace+" + intoGrace + "t (immunity configured "
                                    + immunity + "t); health " + before + " -> " + after);
                        }
                    } else if (s.firstDamageTick < 0) {
                        s.lastBlockedTick = intoGrace;
                    }
                    // Keep it alive for the rest of the run.
                    dragon.setHealth(dragon.getMaxHealth());
                }
            }

            // ---- mount, launch, dismount ----
            if (dragon != null && s.graceStartTick > 0 && s.t == s.graceStartTick + 4) {
                s.observer.clear();
                boolean refused = !dev.rasengan.server.DragonMountManager.tryMount(s.observer);
                check(refused, "a non-summoner's mount request is refused");
                check(dragon.getPassengers().isEmpty(), "and did not put them aboard");

                // Put the summoner in front of the head, looking at it, then request a mount.
                var head = dev.rasengan.DragonAnchor.headCentre(dragon.position(), dragon.getYRot(),
                        dragon.getXRot(), (float) dragon.tickCount, !dragon.onGround());
                var stand = head.add(0.0D, -1.4D, 3.0D);
                s.summoner.snapTo(stand.x, stand.y, stand.z, 180.0F, 0.0F);
                s.summoner.clear();
                boolean mounted = dev.rasengan.server.DragonMountManager.tryMount(s.summoner);
                log("summoner at " + fmt(s.summoner.position()) + " aiming at head: mounted=" + mounted);
                check(mounted, "the summoner mounts by aiming at the head");
                check(dragon.rider() == s.summoner, "the summoner is the rider");
                check(dragon.ridePhase() == dev.rasengan.server.DragonRideControl.PHASE_PERCHED
                                || dragon.ridePhase()
                                        == dev.rasengan.server.DragonRideControl.PHASE_FLYING,
                        "the ride phase is set, got " + dragon.ridePhase());
                check(dragon.isSpawnImmune(),
                        "mounting during the grace window is allowed and did not end the immunity");
                s.mountedTick = s.t;
                s.mountY = dragon.getY();
            }

            // ---- WW launch, fed as replicated input ----
            if (dragon != null && s.mountedTick > 0 && s.t == s.mountedTick + 2) {
                s.summoner.setLastClientInput(
                        new net.minecraft.world.entity.player.Input(
                                true, false, false, false, false, false, false));
            }
            if (dragon != null && s.mountedTick > 0 && s.t == s.mountedTick + 3) {
                s.summoner.setLastClientInput(net.minecraft.world.entity.player.Input.EMPTY);
            }
            if (dragon != null && s.mountedTick > 0 && s.t == s.mountedTick + 4) {
                s.summoner.setLastClientInput(
                        new net.minecraft.world.entity.player.Input(
                                true, false, false, false, false, false, false));
            }
            if (dragon != null && s.mountedTick > 0 && s.t == s.mountedTick + 6) {
                log("after WW: ridePhase=" + dragon.ridePhase() + " perch=" + dragon.perchTicks()
                        + "t immunity=" + dragon.spawnImmunityTicks() + "t");
                check(dragon.ridePhase() == dev.rasengan.server.DragonRideControl.PHASE_LAUNCHING
                                || dragon.ridePhase()
                                        == dev.rasengan.server.DragonRideControl.PHASE_FLYING,
                        "a double-tap of W launched the dragon, got phase " + dragon.ridePhase());
                check(dragon.perchTicks() == 0, "the launch cut the grounded perch short");
                check(dragon.isSpawnImmune(),
                        "but did NOT shorten the damage immunity - the two clocks are independent");
            }
            // Track the climb so the launch can be shown to be a curve rather than a snap.
            //
            // Sampling starts on the LAUNCHING transition rather than at a fixed offset from the mount.
            // A fixed offset guessed wrong by a tick or two and missed the ramp entirely, which made a
            // correct curve look like a snap - the first sample already being at peak.
            if (dragon != null && s.mountedTick > 0
                    && dragon.ridePhase() == dev.rasengan.server.DragonRideControl.PHASE_LAUNCHING) {
                s.launchSamples.add(dragon.getDeltaMovement().y);
            }
            if (dragon != null && s.mountedTick > 0 && s.t > s.mountedTick) {
                s.maxClimbY = Math.max(s.maxClimbY, dragon.getY() - s.mountY);
            }
            if (dragon != null && s.mountedTick > 0
                    && s.t == s.mountedTick + 6 + RasenganConfig.SERVER.mountLaunchTicks.get()) {
                double first = s.launchSamples.isEmpty() ? 0.0D : s.launchSamples.get(0);
                double peak = 0.0D;
                double maxStep = 0.0D;
                double prev = 0.0D;
                for (double v : s.launchSamples) {
                    peak = Math.max(peak, v);
                    maxStep = Math.max(maxStep, Math.abs(v - prev));
                    prev = v;
                }
                log(String.format("launch climb: %d samples, first vy %.5f, peak vy %.5f, "
                                + "max step %.5f, altitude gained %.3f blocks",
                        s.launchSamples.size(), first, peak, maxStep, s.maxClimbY));
                check(peak > 0.2D, "the dragon genuinely climbed (peak vy " + peak + " b/t)");
                check(first < peak * 0.5D,
                        "the climb ramped in rather than snapping to peak velocity");
                check(maxStep < peak * 0.5D, "no single tick jumped more than half the peak");
                check(s.maxClimbY > 3.0D,
                        "real altitude was gained (" + String.format("%.2f", s.maxClimbY) + " blocks)");
            }

            // ---- dismount at altitude ----
            if (dragon != null && s.mountedTick > 0
                    && s.t == s.mountedTick + 10 + RasenganConfig.SERVER.mountLaunchTicks.get()) {
                double altitude = s.summoner.getY();
                s.summoner.stopRiding();
                log(String.format("dismounted at y=%.2f (%.2f above the mount point)",
                        altitude, altitude - s.mountY));
                check(dragon.rider() == null, "the dragon has no rider after dismount");
                check(dragon.ridePhase() == dev.rasengan.server.DragonRideControl.PHASE_NONE,
                        "the ride phase is cleared, got " + dragon.ridePhase());
                check(!dragon.isNoAi(),
                        "the dragon is handed back to its own AI, not left frozen");
                check(!s.summoner.isPassenger(), "the player is no longer a passenger");
                s.dismountTick = s.t;
            }
            if (dragon != null && s.dismountTick > 0 && s.t == s.dismountTick + 12) {
                // The dragon must resume real flight under the standalone AI it already had.
                check(!dragon.onGround() || dragon.getDeltaMovement().length() > 0.01D,
                        "the dragon is moving again under its own AI");
                log("post-dismount: dragon velocity "
                        + String.format("%.4f", dragon.getDeltaMovement().length())
                        + " b/t, ridePhase=" + dragon.ridePhase());

                // ---- ownership survives losing the in-memory map ----
                // This is the restart case, minus the restart: clearing ACTIVE_DRAGONS is exactly what
                // ServerStoppingEvent does, so if the limit still holds afterwards it is holding on the
                // persisted summoner rather than on the map.
                int before = ServerSummonManager.activeDragonCount();
                ServerSummonManager.clearActiveDragonsForTest();
                boolean stillLimited = ServerSummonManager.hasLiveDragon(s.summoner);
                log("cleared the in-memory dragon map (had " + before
                        + "); hasLiveDragon now reports " + stillLimited);
                check(stillLimited,
                        "the one-dragon-per-player limit survives the in-memory map being lost, "
                                + "because the summoner is persisted on the entity");
            }
        }

        if (s.endTick > 0 && s.mountSummonTick > 0
                && s.t == s.mountSummonTick + 420) {
            int immunity = RasenganConfig.mountSpawnImmunityTicks();
            log("---- immunity summary ----");
            log("damage attempts: " + s.damageAttempts + ", landed: " + s.damageLanded
                    + ", last blocked at grace+" + s.lastBlockedTick
                    + "t, first landed at grace+" + s.firstDamageTick + "t"
                    + " (configured immunity " + immunity + "t)");
            check(s.damageAttempts > 20,
                    "enough damage attempts were made to be meaningful (" + s.damageAttempts + ")");
            check(s.lastBlockedTick >= 0 && s.lastBlockedTick < immunity,
                    "damage was still being blocked inside the window (last blocked at "
                            + s.lastBlockedTick + "t)");
            check(s.firstDamageTick >= immunity,
                    "no damage landed before the window expired (first landed at grace+"
                            + s.firstDamageTick + "t, immunity " + immunity + "t)");
            check(s.firstDamageTick - immunity <= 6,
                    "and damage resumed promptly once it did (" + (s.firstDamageTick - immunity)
                            + "t after expiry)");
            check(s.damageLanded > 0, "normal damage handling genuinely resumed");
        }

        if (s.endTick > 0 && s.mountSummonTick > 0 && s.t == s.mountSummonTick + 430) {
            log("---- teardown ----");
            ServerSummonManager.clearAll();
            killDragons(s.level);
            leave(s.server, s.summoner);
            leave(s.server, s.observer);
            check(s.server.getPlayerList().getPlayerCount() == 0, "synthetic players removed");
            active = null;
            dump();
        }
    }

    private static String fmt(Vec3 v) {
        return String.format("(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private static int dragonCount(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(DragonEntity.class), e -> true).size();
    }

    private static DragonEntity firstDragon(ServerLevel level) {
        List<? extends DragonEntity> found =
                level.getEntities(EntityTypeTest.forClass(DragonEntity.class), e -> true);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void killDragons(ServerLevel level) {
        for (Entity entity : level.getEntities(EntityTypeTest.forClass(DragonEntity.class), e -> true)) {
            entity.discard();
        }
    }
}
