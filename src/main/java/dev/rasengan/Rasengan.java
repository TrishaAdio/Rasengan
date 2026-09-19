package dev.rasengan;

import dev.rasengan.network.RasenganPayloads;
import dev.rasengan.server.ChargeCommand;
import dev.rasengan.server.RasenganAttachments;
import dev.rasengan.server.ServerCastManager;
import dev.rasengan.server.ServerPowerManager;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Common mod entrypoint.
 *
 * <p><b>Dedicated-server safety.</b> Nothing in this class, and nothing it references on the
 * unconditional path, touches a {@code net.minecraft.client.*} type. All rendering lives under
 * {@code dev.rasengan.client} and is reached only through the {@link Dist#CLIENT} branch below.
 * Because the JVM resolves a class lazily at first active use, {@code RasenganClient} is never
 * loaded on a dedicated server.
 */
@Mod(Rasengan.MOD_ID)
public final class Rasengan {
    public static final String MOD_ID = "rasengan";

    /** Bumped when the payload wire format changes incompatibly. */
    public static final String NETWORK_VERSION = "1";

    public Rasengan(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, RasenganConfig.SPEC);

        RasenganAttachments.REGISTRY.register(modBus);
        RasenganParticles.REGISTRY.register(modBus);
        RasenganEntities.REGISTRY.register(modBus);
        RasenganSounds.REGISTRY.register(modBus);

        modBus.addListener(Rasengan::registerPayloads);
        modBus.addListener(Rasengan::registerEntityAttributes);

        // Server-authoritative gameplay listeners (game bus, both physical sides - they
        // self-guard on level.isClientSide so a LAN host's integrated server behaves like a
        // dedicated one).
        IEventBus gameBus = NeoForge.EVENT_BUS;
        ServerPowerManager.register(gameBus);
        ServerCastManager.register(gameBus);
        dev.rasengan.server.ServerSummonManager.register(gameBus);
        dev.rasengan.server.DragonFearManager.register(gameBus);
        gameBus.addListener(Rasengan::registerCommands);
        gameBus.addListener(Rasengan::registerReloadListeners);
        gameBus.addListener(Rasengan::onServerStopping);

        if (FMLEnvironment.getDist().isClient()) {
            dev.rasengan.client.RasenganClient.init(modBus, gameBus);
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Registers {@code /chargeit}. Commands are parsed and executed entirely on the server, which
     * is what makes the Creative-mode check untamperable.
     */
    private static void registerCommands(RegisterCommandsEvent event) {
        ChargeCommand.register(event.getDispatcher());
        dev.rasengan.server.SpawnCommand.register(event.getDispatcher());
    }

    /**
     * Supplies base attributes for the mod's living entities.
     *
     * <p>Required: a {@code LivingEntity} type with no registered attribute supplier crashes the
     * moment it is spawned.
     */
    private static void registerEntityAttributes(
            net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(RasenganEntities.DRAGON.get(),
                dev.rasengan.server.DragonEntity.createAttributes().build());
    }

    /**
     * Registers every payload.
     *
     * <p>Note the strict directionality: the client may only send
     * {@link RasenganPayloads.Activate}, and that payload carries no fields whatsoever.
     */
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(NETWORK_VERSION);

        // ---- Server -> Client (cosmetic + HUD state) ----
        registrar.playToClient(
                RasenganPayloads.PowerSync.TYPE,
                RasenganPayloads.PowerSync.CODEC,
                Rasengan::toClient);
        registrar.playToClient(
                RasenganPayloads.CastStart.TYPE,
                RasenganPayloads.CastStart.CODEC,
                Rasengan::toClient);
        registrar.playToClient(
                RasenganPayloads.CastImpact.TYPE,
                RasenganPayloads.CastImpact.CODEC,
                Rasengan::toClient);
        registrar.playToClient(
                RasenganPayloads.CastEnd.TYPE,
                RasenganPayloads.CastEnd.CODEC,
                Rasengan::toClient);

        // ---- Client -> Server (intent only) ----
        registrar.playToServer(
                RasenganPayloads.Activate.TYPE,
                RasenganPayloads.Activate.CODEC,
                (payload, context) -> ServerCastManager.onActivateRequest(context, payload.ability()));

        // ---- Summoning Jutsu ----
        registrar.playToClient(
                dev.rasengan.network.RasenganSummonPayloads.SummonPowerSync.TYPE,
                dev.rasengan.network.RasenganSummonPayloads.SummonPowerSync.CODEC,
                Rasengan::toClient);
        registrar.playToClient(
                dev.rasengan.network.RasenganSummonPayloads.SummonStart.TYPE,
                dev.rasengan.network.RasenganSummonPayloads.SummonStart.CODEC,
                Rasengan::toClient);
        registrar.playToClient(
                dev.rasengan.network.RasenganSummonPayloads.SummonEnd.TYPE,
                dev.rasengan.network.RasenganSummonPayloads.SummonEnd.CODEC,
                Rasengan::toClient);
        registrar.playToServer(
                dev.rasengan.network.RasenganSummonPayloads.SummonActivate.TYPE,
                dev.rasengan.network.RasenganSummonPayloads.SummonActivate.CODEC,
                (payload, context) ->
                        dev.rasengan.server.ServerSummonManager.onActivateRequest(context));
    }

    /**
     * Registers the awakening-lines data loader.
     *
     * <p>Hooking the reload event is what makes {@code /reload} pick up edits to the lines file with
     * no restart, and lets a datapack override or extend the shipped set.
     */
    private static void registerReloadListeners(
            net.neoforged.neoforge.event.AddServerReloadListenersEvent event) {
        event.addRetainedListener(
                net.neoforged.neoforge.resource.ListenerKey.create(id("summon_lines")),
                dev.rasengan.server.SummonLines.get());
    }

    /** Drops summon bookkeeping so nothing survives a restart. */
    private static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        dev.rasengan.server.ServerSummonManager.clearAll();
        ServerCastManager.clearAll();
    }

    /**
     * Forwards a clientbound payload to the client dispatcher through
     * {@link ClientPayloadBridge}, so this common class keeps zero compile-time references to
     * client-only code.
     */
    private static void toClient(Object payload, IPayloadContext context) {
        ClientPayloadBridge.dispatch(payload, context);
    }

    /**
     * Indirection between common payload registration and the client-only handlers.
     *
     * <p>The client installs its handler during client bootstrap. On a dedicated server the
     * field stays null and these payloads are never received anyway.
     */
    public static final class ClientPayloadBridge {
        private ClientPayloadBridge() {}

        public interface Handler {
            void accept(Object payload, IPayloadContext context);
        }

        private static volatile Handler handler;

        public static void install(Handler h) {
            handler = h;
        }

        static void dispatch(Object payload, IPayloadContext context) {
            Handler h = handler;
            if (h != null) {
                h.accept(payload, context);
            }
        }
    }
}
