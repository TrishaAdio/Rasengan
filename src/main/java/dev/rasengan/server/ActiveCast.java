package dev.rasengan.server;

import dev.rasengan.AbilityType;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Transient server-side record of one in-flight cast. Never persisted. */
final class ActiveCast {
    final UUID playerId;
    final long seed;
    final int totalTicks;
    final boolean mainHand;
    /** Which technique this cast is. Chosen by the player, validated by the server. */
    final AbilityType ability;
    /** Aim direction captured and validated at activation time. */
    final Vec3 direction;

    int elapsedTicks;
    boolean resolved;

    ActiveCast(UUID playerId, long seed, int totalTicks, boolean mainHand, Vec3 direction,
               AbilityType ability) {
        this.ability = ability;
        this.playerId = playerId;
        this.seed = seed;
        this.totalTicks = totalTicks;
        this.mainHand = mainHand;
        this.direction = direction;
    }

    boolean isComplete() {
        return elapsedTicks >= totalTicks;
    }
}
