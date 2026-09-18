package dev.rasengan.client;

import dev.rasengan.AbilityType;
import dev.rasengan.network.RasenganPayloads;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.rasengan.RasenganSounds;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Holds every cast this client is currently drawing.
 *
 * <p>Bounded, self-cleaning, and cheap to iterate. The cap from the server config is enforced
 * here: past the cap, new casts are dropped visually rather than queued, which keeps a crowd of
 * simultaneous casters from turning into a frame-rate cliff.
 */
public final class ClientCastTracker {

    /** Number of orbital layers per cast. Kept modest; each one is real geometry. */
    private static final int LAYER_COUNT = 7;

    private static final Map<Integer, ClientCast> ACTIVE = new LinkedHashMap<>();

    private static int maxEffects = 8;

    /**
     * Live shuriken screech loops, keyed by the entity they ride.
     *
     * <p>Tracked so they can be stopped explicitly. Relying only on the instance's own
     * source-is-gone check would be enough for impact and despawn, but a cancelled cast leaves the
     * caster very much alive - so that path needs an explicit stop.
     */
    private static final Map<Integer, ShurikenSoundInstance> SPIN_SOUNDS = new HashMap<>();

    private ClientCastTracker() {}

    public static Collection<ClientCast> active() {
        return ACTIVE.values();
    }

    public static boolean isEmpty() {
        return ACTIVE.isEmpty();
    }

    // ------------------------------------------------------------------
    // Packet application
    // ------------------------------------------------------------------

    public static void onCastStart(RasenganPayloads.CastStart payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        maxEffects = RasenganPayloads.CosmeticFlags.maxEffects(payload.cosmeticFlags());
        float density = (float) RasenganPayloads.CosmeticFlags.particleDensity(payload.cosmeticFlags());
        float aura = (float) RasenganPayloads.CosmeticFlags.auraIntensity(payload.cosmeticFlags());

        // Respect the server's simultaneous-effect cap. Replacing an existing entry for the
        // same caster is always allowed; only genuinely new ones count against the cap.
        if (!ACTIVE.containsKey(payload.casterId()) && ACTIVE.size() >= maxEffects) {
            return;
        }

        Vec3 direction = new Vec3(payload.dirX(), payload.dirY(), payload.dirZ());
        if (direction.lengthSqr() < 1.0E-6D) {
            direction = new Vec3(0.0D, 0.0D, 1.0D);
        }

        ACTIVE.put(payload.casterId(), new ClientCast(
                payload.casterId(),
                payload.seed(),
                payload.castDuration(),
                payload.mainHand(),
                direction.normalize(),
                level.getGameTime(),
                LAYER_COUNT,
                density * ClientTuning.particleScale(),
                // Raw server aura intensity. The player's particle setting is applied later, and
                // only to the particle layer - the mesh aura must not vanish just because someone
                // set Particles to Minimal, or the aura would silently disappear while the
                // sphere kept rendering. That asymmetry was the original aura bug.
                aura,
                AbilityType.byId(payload.ability())));
    }

    /**
     * An impact is a standalone world-space event. It is deliberately <em>not</em> attached to the
     * cast record: the blast happens wherever the projectile made contact, which may be far from the
     * caster and may happen after the cast record has already expired.
     */
    public static void onCastImpact(RasenganPayloads.CastImpact payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long gameTime = level.getGameTime();
        Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());

        ClientImpactTracker.add(payload, gameTime);

        ClientCast cast = ACTIVE.get(payload.casterId());
        ClientEffects.spawnImpactBurst(level, pos, payload.hitKind(),
                cast != null ? cast.seed : payload.casterId(),
                cast != null ? cast.particleDensity : ClientTuning.particleScale());
        if (AbilityType.byId(payload.ability()).isShuriken()) {
            ClientEffects.playShurikenImpactSound(level, pos);
        } else {
            ClientEffects.playImpactSound(level, pos);
        }
    }

    /**
     * Release hands the single sphere over to the projectile entity.
     *
     * <p>{@code markReleased} stops this record drawing a sphere immediately. The record survives
     * only a few more ticks so the body aura can fade, then it is discarded.
     */
    public static void onCastEnd(RasenganPayloads.CastEnd payload) {
        ClientLevel level = Minecraft.getInstance().level;
        ClientCast cast = ACTIVE.get(payload.casterId());
        if (cast == null || level == null) {
            return;
        }
        // The held-phase screech ends here either way. On a normal release the projectile starts
        // its own instance on the same tick, so the audio is continuous across the handoff; on a
        // cancel it simply stops, with no tail.
        stopSpinSound(cast.casterId);

        if (payload.reason() == RasenganPayloads.CastEnd.REASON_CANCELLED) {
            cast.applyCancel(level.getGameTime());
        } else {
            cast.markReleased(level.getGameTime());
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Expires finished casts and impacts, and emits the per-tick stochastic particles. */
    public static void clientTick(ClientLevel level) {
        long gameTime = level.getGameTime();
        ClientImpactTracker.clientTick(gameTime);
        pruneSpinSounds();
        tickProjectileSounds(level);

        if (ACTIVE.isEmpty()) {
            return;
        }
        List<Integer> toRemove = new ArrayList<>(0);

        for (Iterator<Map.Entry<Integer, ClientCast>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ClientCast> entry = it.next();
            ClientCast cast = entry.getValue();

            if (cast.isExpired(gameTime)) {
                it.remove();
                continue;
            }

            Entity entity = level.getEntity(cast.casterId);
            if (!(entity instanceof Player player) || !player.isAlive() || player.isRemoved()) {
                // Caster left the client's view or died: drop the effect rather than leaving it
                // floating at a stale position. Any in-flight projectile is unaffected - it is an
                // independent entity, and any impact already queued still plays.
                it.remove();
                continue;
            }

            // Start the screech on the exact tick the blades snap out, so the audible transition
            // and the visual unfurl are the same tick rather than approximately aligned.
            if (cast.ability.isShuriken() && !cast.isReleased() && !cast.isCancelled()) {
                float age = cast.age(gameTime, 1.0F);
                if (age >= ShurikenRenderer.BLADE_SNAP_TICK
                        && !SPIN_SOUNDS.containsKey(cast.casterId)) {
                    startSpinSound(player);
                }
            }

            ClientEffects.tickCast(level, player, cast, gameTime);
        }
        toRemove.forEach(ACTIVE::remove);
    }

    /**
     * Gives every in-flight shuriken a screech, and takes it away the moment the entity is gone.
     *
     * <p>Driven from the entity list rather than from a packet, so it is correct for a projectile
     * that flies into view partway through its flight, and it cannot leak if an end packet is
     * missed - the entity disappearing is itself the stop condition.
     */
    private static void tickProjectileSounds(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof dev.rasengan.server.RasenganProjectile projectile
                    && projectile.ability().isShuriken()
                    && !SPIN_SOUNDS.containsKey(projectile.getId())) {
                startSpinSound(projectile);
            }
        }
        // Any tracked source that has been removed stops immediately - impact, despawn, max range.
        SPIN_SOUNDS.entrySet().removeIf(entry -> {
            ShurikenSoundInstance instance = entry.getValue();
            if (instance.source().isRemoved() || !instance.source().isAlive()) {
                instance.stopNow();
                Minecraft.getInstance().getSoundManager().stop(instance);
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------
    // Screech loop management
    // ------------------------------------------------------------------

    /**
     * Starts an entity-following screech loop.
     *
     * <p>Positional and attenuated by the engine's own distance model, so each listener hears the
     * falloff from their own position rather than one global fade. Every nearby client runs this off
     * the same server cast packet, so it is not caster-only.
     */
    public static void startSpinSound(Entity source) {
        if (SPIN_SOUNDS.containsKey(source.getId())) {
            return;
        }
        ShurikenSoundInstance instance = new ShurikenSoundInstance(
                RasenganSounds.SHURIKEN_SPIN.get(), source, 1.0F, 1.0F, true);
        SPIN_SOUNDS.put(source.getId(), instance);
        Minecraft.getInstance().getSoundManager().play(instance);
    }

    /** Stops and forgets the loop for an entity id. Safe to call when none exists. */
    public static void stopSpinSound(int entityId) {
        ShurikenSoundInstance instance = SPIN_SOUNDS.remove(entityId);
        if (instance != null) {
            instance.stopNow();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    /** Drops entries whose sound already ended on its own, so the map cannot grow unbounded. */
    private static void pruneSpinSounds() {
        SPIN_SOUNDS.entrySet().removeIf(entry -> entry.getValue().isStopped());
    }

    /** Full teardown on world change or disconnect. */
    public static void clear() {
        ACTIVE.clear();
        ClientImpactTracker.clear();
        for (ShurikenSoundInstance instance : SPIN_SOUNDS.values()) {
            instance.stopNow();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
        SPIN_SOUNDS.clear();
    }
}
