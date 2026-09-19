package dev.rasengan.verify;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.rasengan.PowerState;
import dev.rasengan.RasenganConfig;
import dev.rasengan.network.RasenganSummonPayloads;
import dev.rasengan.server.DragonEntity;
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
                7, -1234567890123L, 12.5D, -48.0D, -7.25D, 100, 70,
                RasenganSummonPayloads.SummonStart.packCamera(45, 95));
        RasenganSummonPayloads.SummonStart.CODEC.encode(buf, start);
        int startBytes = buf.readableBytes();
        RasenganSummonPayloads.SummonStart back =
                RasenganSummonPayloads.SummonStart.CODEC.decode(buf);
        check(start.equals(back), "SummonStart survives encode/decode (" + startBytes + " bytes)");
        check(buf.readableBytes() == 0, "SummonStart decode consumed exactly what encode wrote");
        check(back.cameraStart() == 45 && back.cameraEnd() == 95,
                "packed camera window unpacks to 45..95, got " + back.cameraStart() + ".." + back.cameraEnd());
        check(back.cameraEnabled(), "camera window reports enabled when non-zero");
        check(!new RasenganSummonPayloads.SummonStart(0, 0L, 0, 0, 0, 100, 70, 0).cameraEnabled(),
                "camera window reports disabled when zero");

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
                log("config: charge=" + RasenganConfig.summonChargeDurationTicks() + "t total="
                        + total + "t reveal=" + configuredReveal + "t camera="
                        + RasenganConfig.SERVER.summonCameraStartTick.get() + ".."
                        + RasenganConfig.SERVER.summonCameraEndTick.get()
                        + " camera_enabled=" + RasenganConfig.SERVER.summonCameraEffect.get());
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
                    log("SummonStart: summonerId=" + a.summonerId() + " origin=(" + a.x() + "," + a.y()
                            + "," + a.z() + ") total=" + a.totalTicks() + " reveal=" + a.revealTick()
                            + " camera=" + a.cameraStart() + ".." + a.cameraEnd());
                    check(a.totalTicks() == total && a.revealTick() == configuredReveal,
                            "SummonStart carries the configured timings");
                    check(a.summonerId() == s.summoner.getId(),
                            "SummonStart identifies the summoner by entity id");
                    check(Math.abs(a.x() - 0.5D) < 1.0E-9 && Math.abs(a.z() - 0.5D) < 1.0E-9,
                            "the seal is pinned to where the summoner stood");
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

        if (s.revealTick < 0 && dragons > 0) {
            s.revealTick = s.t;
            int delta = s.t - s.summonTick;
            log("dragon appeared at scenario tick " + s.t + " = summon+" + delta);
            check(delta == configuredReveal,
                    "reveal landed on the configured tick (" + delta + " vs " + configuredReveal + ")");
            check(dragons == 1, "exactly one dragon appeared, got " + dragons);
            DragonEntity dragon = firstDragon(s.level);
            if (dragon != null) {
                check(dragon.isEntering(), "the dragon starts its arrival flourish");
                check(dragon.summoner() != null
                                && dragon.summoner().equals(s.summoner.getUUID()),
                        "the dragon records its summoner for the one-per-player limit");
                check(ServerSummonManager.activeDragonCount() == 1,
                        "the manager is tracking one live dragon");
                s.firstDragonPos = dragon.position();
                log("dragon spawned at " + fmt(dragon.position()) + ", summoner at "
                        + fmt(s.summoner.position()) + ", distance "
                        + String.format("%.2f", dragon.position().distanceTo(s.summoner.position())));
            }
        }

        // Count awakening announcements for the whole run, on both players.
        int announces = 0;
        for (Component line : s.summoner.chat) {
            if (line.getString().contains(TAG)) {
                announces++;
            }
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

        if (s.endTick < 0 && s.t >= s.summonTick + total) {
            s.endTick = s.t;
            PowerData data = ServerSummonManager.data(s.summoner);
            log("sequence end at scenario tick " + s.t + " = summon+" + (s.t - s.summonTick)
                    + "; bar state=" + data.state() + " charge=" + data.chargeTicks());
            check(ServerSummonManager.pendingCount() == 0,
                    "the cinematic is finished and cleaned up");
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
        if (s.endTick > 0 && s.t > s.endTick + 3 && s.t <= s.endTick + 103) {
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

        if (s.endTick > 0 && s.t == s.endTick + 104) {
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

        if (s.endTick > 0 && s.t == s.endTick + 108) {
            log("killing the dragon to test that the gate reopens");
            killDragons(s.level);
        }
        if (s.endTick > 0 && s.t == s.endTick + 112) {
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
        }
        if (s.endTick > 0 && s.t == s.endTick + 118) {
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
