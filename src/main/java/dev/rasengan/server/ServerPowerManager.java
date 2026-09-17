package dev.rasengan.server;

import dev.rasengan.PowerState;
import dev.rasengan.RasenganConfig;
import dev.rasengan.network.RasenganPayloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Owns the POWER BAR. Runs only on the logical server.
 *
 * <h2>State flow</h2>
 * <pre>
 *   CHARGING: chargeTicks += 1 each tick, capped at chargeDuration.
 *             When chargeTicks == chargeDuration  ->  READY (packet pushed immediately)
 *   READY:    holds at 100%, waiting for an activation request.
 *             On a validated activation  ->  CASTING (charge is zeroed in the same tick)
 *   CASTING:  ServerCastManager drives the cast. Charge stays 0.
 *             On resolution  ->  COOLDOWN if cooldown_ticks > 0, else straight to CHARGING
 *   COOLDOWN: cooldownTicks -= 1 each tick. At 0  ->  CHARGING
 * </pre>
 *
 * <h2>Sync strategy</h2>
 * The bar advances by exactly one tick per tick, which is perfectly predictable, so the server
 * does <em>not</em> send a packet every tick. It pushes a packet on every state transition and
 * otherwise once per {@link #HEARTBEAT_TICKS}. The client advances its own copy locally and
 * re-anchors on each packet, which keeps the percentage moving smoothly in real time while
 * costing about one packet per second per player.
 */
public final class ServerPowerManager {

    /** Heartbeat interval. One second: cheap, and bounds any visible drift to nothing. */
    private static final int HEARTBEAT_TICKS = 20;

    private ServerPowerManager() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(ServerPowerManager::onPlayerTick);
        gameBus.addListener(ServerPowerManager::onLogin);
        gameBus.addListener(ServerPowerManager::onRespawn);
        gameBus.addListener(ServerPowerManager::onChangedDimension);
        gameBus.addListener(ServerPowerManager::onClone);
    }

    public static PowerData data(ServerPlayer player) {
        return player.getData(RasenganAttachments.POWER);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return; // client-side copy of the event: ignore, the server is authoritative
        }

        PowerData data = data(player);
        int duration = RasenganConfig.chargeDurationTicks();

        // A player who died mid-charge should not silently keep accumulating unless the
        // server owner opted in.
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
                // Config may have been lowered at runtime; keep the stored value sane.
                if (data.chargeTicks() > duration) {
                    data.setChargeTicks(duration);
                }
            }
            case CASTING -> {
                // ServerCastManager owns the transition out of this state.
            }
            case COOLDOWN -> {
                data.decrementCooldown();
                if (data.cooldownTicks() <= 0) {
                    data.setState(PowerState.CHARGING);
                }
            }
        }

        // If the configured duration shrank below the stored charge while CHARGING, promote.
        if (data.state() == PowerState.CHARGING && data.chargeTicks() >= duration) {
            data.setState(PowerState.READY);
        }

        boolean heartbeat = data.tickSyncCountdown(HEARTBEAT_TICKS);
        if (data.isDirty() || heartbeat) {
            sync(player, data);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle - never leave a client with a stale bar or a stuck effect
    // ------------------------------------------------------------------

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Fresh connection: push the truth immediately so the HUD is correct on frame one.
            sync(player, data(player));
        }
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PowerData data = data(player);
            if (RasenganConfig.SERVER.resetChargeOnDeath.get()) {
                data.resetCharge();
                data.setCooldownTicks(0);
                data.setState(PowerState.CHARGING);
            }
            // A cast cannot survive the player entity being replaced.
            ServerCastManager.cancel(player);
            sync(player, data);
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Nearby-player sets differ per dimension, so tear the effect down and resync.
            ServerCastManager.cancel(player);
            sync(player, data(player));
        }
    }

    private static void onClone(PlayerEvent.Clone event) {
        // Attachment copying is handled by copyOnDeath, but the transient sync bookkeeping
        // lives on the new instance, so force a push on the next tick.
        if (event.getEntity() instanceof ServerPlayer player) {
            data(player).markDirty();
        }
    }

    // ------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------

    /** Pushes the authoritative bar state to its owner and clears the dirty flag. */
    public static void sync(ServerPlayer player, PowerData data) {
        PacketDistributor.sendToPlayer(player, new RasenganPayloads.PowerSync(
                data.chargeTicks(),
                RasenganConfig.chargeDurationTicks(),
                data.cooldownTicks(),
                data.state().id()));
        data.markClean();
    }

    public static void sync(ServerPlayer player) {
        sync(player, data(player));
    }
}
