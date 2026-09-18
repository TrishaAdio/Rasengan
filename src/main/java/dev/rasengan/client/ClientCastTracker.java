package dev.rasengan.client;

import dev.rasengan.network.RasenganPayloads;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
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
                aura));
    }

    public static void onCastImpact(RasenganPayloads.CastImpact payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ClientCast cast = ACTIVE.get(payload.casterId());
        Vec3 pos = new Vec3(payload.x(), payload.y(), payload.z());

        if (cast != null) {
            cast.applyImpact(pos, payload.hitKind(), level.getGameTime());
        }
        // The burst is spawned even if the cast record was dropped (out of range on arrival,
        // or over the effect cap), so an impact is never silently invisible.
        ClientEffects.spawnImpactBurst(level, pos, payload.hitKind(),
                cast != null ? cast.seed : payload.casterId(),
                cast != null ? cast.particleDensity : ClientTuning.particleScale());
        ClientEffects.playImpactSound(level, pos);
    }

    public static void onCastEnd(RasenganPayloads.CastEnd payload) {
        ClientLevel level = Minecraft.getInstance().level;
        ClientCast cast = ACTIVE.get(payload.casterId());
        if (cast == null || level == null) {
            return;
        }
        if (payload.reason() == RasenganPayloads.CastEnd.REASON_CANCELLED) {
            cast.applyCancel(level.getGameTime());
        } else {
            cast.markReleased();
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Expires finished casts and emits the per-tick stochastic particles. */
    public static void clientTick(ClientLevel level) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
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
                // floating at a stale position.
                if (!cast.hasImpact()) {
                    it.remove();
                }
                continue;
            }

            ClientEffects.tickCast(level, player, cast, gameTime);
        }
        toRemove.forEach(ACTIVE::remove);
    }

    /** Full teardown on world change or disconnect. */
    public static void clear() {
        ACTIVE.clear();
    }
}
