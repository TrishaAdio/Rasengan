package dev.rasengan.client;

import dev.rasengan.Rasengan;
import dev.rasengan.RasenganParticles;
import dev.rasengan.network.RasenganPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client bootstrap. This class and everything it touches is loaded only on {@code Dist.CLIENT};
 * {@link Rasengan} reaches it through a dist check so a dedicated server never resolves it.
 *
 * <p>Its jobs: register the HUD layer, the keybind and the particle providers, install the
 * clientbound payload handler, and drive the per-tick client state.
 */
public final class RasenganClient {

    private RasenganClient() {}

    public static void init(IEventBus modBus, IEventBus gameBus) {
        // ---- Mod bus: registration ----
        modBus.addListener(RasenganClient::registerGuiLayers);
        modBus.addListener(RasenganKeys::register);
        modBus.addListener(RasenganClient::registerParticleProviders);

        // ---- Game bus: per-frame and per-tick work ----
        gameBus.addListener(RasenganRenderer::onSubmitGeometry);
        gameBus.addListener(RasenganClient::onClientTick);
        gameBus.addListener(RasenganClient::onLoggingOut);
        gameBus.addListener(RasenganClient::onLevelUnload);

        // ---- Payload handling ----
        // The common side has no compile-time reference to this lambda's body, which is what
        // keeps client rendering classes off the dedicated server's classpath usage.
        Rasengan.ClientPayloadBridge.install((payload, context) -> {
            switch (payload) {
                case RasenganPayloads.PowerSync sync -> ClientPowerState.accept(sync);
                case RasenganPayloads.CastStart start -> {
                    ClientCastTracker.onCastStart(start);
                    ClientLevel level = Minecraft.getInstance().level;
                    if (level != null) {
                        var entity = level.getEntity(start.casterId());
                        if (entity != null) {
                            ClientEffects.playCastStartSound(level, entity.position());
                        }
                    }
                }
                case RasenganPayloads.CastImpact impact -> ClientCastTracker.onCastImpact(impact);
                case RasenganPayloads.CastEnd end -> ClientCastTracker.onCastEnd(end);
                default -> {
                    // Unknown payload: ignore rather than throw, so a version mismatch cannot
                    // hard-crash the client.
                }
            }
        });
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        // Above the hotbar so the bar is never drawn underneath vanilla HUD elements.
        event.registerAbove(VanillaGuiLayers.HOTBAR, Rasengan.id("power_bar"), PowerBarHud.INSTANCE);
    }

    private static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(RasenganParticles.INTAKE.get(),
                sprites -> new EnergyParticle.Provider(sprites, EnergyParticle.Style.INTAKE));
        event.registerSpriteSet(RasenganParticles.SPARK.get(),
                sprites -> new EnergyParticle.Provider(sprites, EnergyParticle.Style.SPARK));
        event.registerSpriteSet(RasenganParticles.AURA_WISP.get(),
                sprites -> new EnergyParticle.Provider(sprites, EnergyParticle.Style.AURA_WISP));
        event.registerSpriteSet(RasenganParticles.WISP.get(),
                sprites -> new EnergyParticle.Provider(sprites, EnergyParticle.Style.WISP));
        event.registerSpriteSet(RasenganParticles.BURST.get(),
                sprites -> new EnergyParticle.Provider(sprites, EnergyParticle.Style.BURST));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        RasenganKeys.tick(minecraft);

        // Advance the HUD prediction and the cosmetic effects only while actually in a world and
        // not paused, so a paused single-player game does not keep spawning particles.
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.isPaused()) {
            return;
        }
        ClientPowerState.clientTick();
        ClientCastTracker.clientTick(level);
    }

    // ------------------------------------------------------------------
    // Teardown - no state may survive a world or connection change
    // ------------------------------------------------------------------

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCastTracker.clear();
        ClientPowerState.reset();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientCastTracker.clear();
        }
    }
}
