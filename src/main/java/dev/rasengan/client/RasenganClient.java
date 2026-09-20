package dev.rasengan.client;

import dev.rasengan.Rasengan;
import dev.rasengan.RasenganEntities;
import dev.rasengan.RasenganParticles;
import dev.rasengan.network.RasenganPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
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
        modBus.addListener(RasenganClient::registerEntityRenderers);

        // ---- Game bus: per-frame and per-tick work ----
        gameBus.addListener(RasenganRenderer::onSubmitGeometry);
        // Cinematic camera. Registered here so a dedicated server never resolves these classes.
        gameBus.addListener(SummonCinematic::onComputeCameraAngles);
        gameBus.addListener(SummonCinematic::onComputeFov);
        gameBus.addListener(SummonCinematic::onDetachedDistance);
        gameBus.addListener(RasenganClient::onClientTick);
        // Pre, not Post: the mount click has to be consumed before Minecraft.handleKeybinds() turns it
        // into an attack swing.
        gameBus.addListener(RasenganClient::onClientTickPre);
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
                            if (dev.rasengan.AbilityType.byId(start.ability()).isShuriken()) {
                                // Formation swell, started on the very first tick of the cast so it
                                // is synchronised with the core beginning to form. Entity-bound so
                                // it tracks the caster if they move while charging.
                                Minecraft.getInstance().getSoundManager().play(
                                        new ShurikenSoundInstance(
                                                dev.rasengan.RasenganSounds.SHURIKEN_FORM.get(),
                                                entity, 1.0F, 1.0F, false));
                            } else {
                                // Rasengan gets the same treatment: entity-bound so it tracks the
                                // caster, and started on the cast's first tick.
                                Minecraft.getInstance().getSoundManager().play(
                                        new ShurikenSoundInstance(
                                                dev.rasengan.RasenganSounds.RASENGAN_FORM.get(),
                                                entity, 0.85F, 1.0F, false));
                            }
                        }
                    }
                }
                case RasenganPayloads.CastImpact impact -> ClientCastTracker.onCastImpact(impact);
                case RasenganPayloads.CastEnd end -> ClientCastTracker.onCastEnd(end);
                case dev.rasengan.network.RasenganSummonPayloads.SummonPowerSync sync ->
                        ClientSummonPowerState.accept(sync);
                case dev.rasengan.network.RasenganSummonPayloads.SummonStart start -> {
                    SummonCinematic.onSummonStart(start);
                    // Warm the dragon's model and texture now, while the smoke is still building.
                    // Left to itself, GeckoLib loads a 183-cube geometry and a 1024x1024 texture on
                    // the first frame the dragon is drawn - which is the reveal frame.
                    DragonAssetWarmup.prewarm();
                }
                case dev.rasengan.network.RasenganSummonPayloads.SummonEnd end ->
                        SummonCinematic.onSummonEnd(end);
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
        // The reveal vignette. Above the hotbar as well, so it darkens the screen edges over the
        // whole HUD rather than being painted under it.
        event.registerAbove(VanillaGuiLayers.HOTBAR, Rasengan.id("summon_vignette"),
                SummonVignette.INSTANCE);
    }

    /**
     * Every entity type must have a renderer registered or the client errors on spawn.
     *
     * <p>A {@link NoopRenderer} is registered deliberately: the projectile's entire appearance is
     * drawn by {@link RasenganRenderer} through {@code SubmitCustomGeometryEvent}, which is the
     * same code path - and the same additive render type - already used for the held sphere. That
     * keeps one implementation of the energy sphere rather than a second one inside an
     * EntityRenderer, and means the in-flight visual is identical to the in-hand visual.
     */
    private static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RasenganEntities.PROJECTILE.get(), NoopRenderer::new);
        // The dragon, unlike the projectile, is a real model: GeckoLib draws it from the converted
        // Bedrock geometry and animation files.
        event.registerEntityRenderer(RasenganEntities.DRAGON.get(), DragonRenderer::new);
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

        // Summoning smoke. Alpha-blended, unlike everything above.
        event.registerSpriteSet(RasenganParticles.SMOKE_BILLOW.get(),
                sprites -> new SmokeParticle.Provider(sprites, SmokeParticle.Style.BILLOW));
        event.registerSpriteSet(RasenganParticles.SMOKE_WISP.get(),
                sprites -> new SmokeParticle.Provider(sprites, SmokeParticle.Style.WISP));
        event.registerSpriteSet(RasenganParticles.GROUND_FOG.get(),
                sprites -> new SmokeParticle.Provider(sprites, SmokeParticle.Style.GROUND_FOG));
        event.registerSpriteSet(RasenganParticles.DUST_MOTE.get(),
                sprites -> new SmokeParticle.Provider(sprites, SmokeParticle.Style.MOTE));
        event.registerSpriteSet(RasenganParticles.FEAR.get(),
                sprites -> new SmokeParticle.Provider(sprites, SmokeParticle.Style.FEAR));
    }

    private static void onClientTickPre(ClientTickEvent.Pre event) {
        RasenganMountInput.tick(Minecraft.getInstance());
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
        ClientSummonPowerState.clientTick();
        ClientCastTracker.clientTick(level);
        SummonCinematic.clientTick(level);
    }

    // ------------------------------------------------------------------
    // Teardown - no state may survive a world or connection change
    // ------------------------------------------------------------------

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCastTracker.clear();
        ClientPowerState.reset();
        ClientSummonPowerState.reset();
        // Belt and braces on the camera: it already expires on its own, but disconnecting mid-summon
        // must not leave a tilt behind on the next world join.
        SummonCinematic.clear();
        RasenganRenderer.clearCaches();
        DragonAssetWarmup.reset();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientCastTracker.clear();
            RasenganRenderer.clearCaches();
            // Drops the cinematic records and the smoke registry. A dimension change routes through
            // here, and without it the old level's sequence would keep running against the new
            // level's game time - which jumps arbitrarily and could put the camera anywhere in its
            // window. That is a genuine stuck-camera path, not a theoretical one.
            SummonCinematic.clear();
        }
    }
}
