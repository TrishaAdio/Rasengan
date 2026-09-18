package dev.rasengan.client;

import dev.rasengan.network.RasenganPayloads;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Impact blasts currently playing, tracked independently of any cast or projectile.
 *
 * <h2>Why impacts are their own thing</h2>
 * An impact happens at the projectile's contact point, which can be dozens of blocks from the
 * caster and can occur after the caster's cast record has already expired. Attaching impacts to the
 * cast record - as this mod used to - meant the blast was positioned relative to the caster's hand,
 * and it dragged the held sphere toward the impact point to compensate. That is what produced a
 * second sphere gliding through the air.
 *
 * <p>An impact is a fire-and-forget world-space event: a position, a moment, a seed, and a kind.
 * It needs no owner, so it has none.
 */
public final class ClientImpactTracker {

    /** Ticks an impact animation runs for. */
    public static final float IMPACT_TICKS = 18.0F;

    /** Hard cap, so a burst of simultaneous impacts cannot grow this list without bound. */
    private static final int MAX_IMPACTS = 32;

    /** One playing blast. */
    public record Impact(Vec3 pos, long startGameTime, long seed, int hitKind) {

        /** Ticks since the blast began, including the frame's partial tick. */
        public float age(long gameTime, float partialTick) {
            return (gameTime - startGameTime) + partialTick;
        }

        public boolean isExpired(long gameTime) {
            return (gameTime - startGameTime) > IMPACT_TICKS;
        }
    }

    private static final List<Impact> ACTIVE = new ArrayList<>();

    private ClientImpactTracker() {}

    public static List<Impact> active() {
        return Collections.unmodifiableList(ACTIVE);
    }

    public static boolean isEmpty() {
        return ACTIVE.isEmpty();
    }

    public static void add(RasenganPayloads.CastImpact payload, long gameTime) {
        if (ACTIVE.size() >= MAX_IMPACTS) {
            ACTIVE.remove(0); // drop the oldest rather than refuse the newest
        }
        // Seeded off the caster id and the tick so the fragment scatter varies per blast while
        // still being identical on every client that receives the same packet.
        long seed = payload.casterId() * 31L + gameTime;
        ACTIVE.add(new Impact(
                new Vec3(payload.x(), payload.y(), payload.z()),
                gameTime,
                seed,
                payload.hitKind()));
    }

    /** Drops finished blasts. */
    public static void clientTick(long gameTime) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        ACTIVE.removeIf(impact -> impact.isExpired(gameTime));
    }

    public static void clear() {
        ACTIVE.clear();
    }
}
