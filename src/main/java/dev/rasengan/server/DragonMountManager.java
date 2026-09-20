package dev.rasengan.server;

import dev.rasengan.DragonAnchor;
import dev.rasengan.Palette;
import dev.rasengan.RasenganConfig;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/**
 * Validates mount requests and keeps ride state from outliving the rider.
 *
 * <h2>Zero-trust mounting</h2>
 * The client sends a <b>fieldless</b> {@code MountRequest} - the same contract as
 * {@code SummonActivate}. It does not say which dragon, where it clicked, or that it hit the head. The
 * server finds the candidate dragon itself, re-derives the head region from {@link DragonAnchor}, and
 * ray-traces the player's own server-side eye position and look vector against it. Everything a client
 * could lie about is recomputed here, so the packet carries nothing worth forging.
 *
 * <h2>Why mounting cannot use vanilla attack targeting</h2>
 * The head sits <em>outside</em> the dragon's collision box. The box is 6.0 x 5.0, so it reaches 3.0
 * blocks forward; the head centre is 4.22 blocks forward. Vanilla picks entities for attack by their
 * collision box, so a left-click on the head never produces an {@code AttackEntityEvent} for the dragon
 * at all - there is nothing to intercept. Hence a dedicated request packet plus this server-side check.
 *
 * <p>The alternative considered and rejected: making the head a real multipart hitbox, the way the Ender
 * Dragon does. That would give vanilla something to pick, but it also re-routes <em>damage</em> through
 * the parts and changes the hurtbox of the whole boss - a combat change smuggled in behind a mount
 * feature. Out of scope, and it would invalidate the existing damage evidence.
 */
public final class DragonMountManager {

    /** How far past their normal reach a player may mount from, in blocks. */
    private static final double MOUNT_REACH = 6.0D;

    /** Radius searched for a candidate dragon. Generous: the head is 4.2 blocks off-centre already. */
    private static final double SEARCH_RADIUS = 16.0D;

    private DragonMountManager() {}

    public static void register(IEventBus gameBus) {
        gameBus.addListener(DragonMountManager::onLoggingOut);
    }

    /**
     * Handles a mount request.
     *
     * @return true if the player was mounted
     */
    public static boolean tryMount(ServerPlayer player) {
        if (!RasenganConfig.SERVER.mountEnabled.get()) {
            return false;
        }
        if (player.isPassenger()) {
            // Already riding something. Left-click while mounted deliberately does nothing here - see
            // the note in RasenganMountInput on the client side.
            return false;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }

        ServerLevel level = (ServerLevel) player.level();
        DragonEntity target = findHeadUnderCrosshair(level, player);
        if (target == null) {
            return false; // not looking at a head: a normal left-click, nothing to do
        }

        if (!target.mayMount(player)) {
            // A clear refusal, not silence and not damage. Rate-limited by the client's own send
            // interval, so a held mouse button cannot spam it.
            reject(player, "This dragon answers only to the one who called it.");
            return false;
        }
        if (!target.getPassengers().isEmpty()) {
            reject(player, "Someone is already standing on its head.");
            return false;
        }

        // force = false: canAddPassenger and canRide still get to refuse. sendEventAndTriggers = true so
        // NeoForge's mount event and the vanilla advancement triggers fire normally, rather than this
        // being an invisible side-door onto a vehicle.
        if (!player.startRiding(target, false, true)) {
            return false;
        }
        target.onMounted();
        return true;
    }

    /**
     * Finds a dragon whose head region the player is looking at.
     *
     * <p>Nearest-first, so two overlapping dragons resolve to the one actually under the crosshair
     * rather than whichever the entity list happened to return first.
     */
    @Nullable
    public static DragonEntity findHeadUnderCrosshair(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        AABB search = player.getBoundingBox().inflate(SEARCH_RADIUS);

        List<? extends DragonEntity> nearby = level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(DragonEntity.class),
                search, dragon -> dragon.isAlive() && !dragon.isHiddenForSummon());

        DragonEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (DragonEntity dragon : nearby) {
            boolean airborne = !dragon.onGround();
            if (!DragonAnchor.rayHitsHead(dragon.position(), dragon.getYRot(), dragon.getXRot(),
                    (float) dragon.tickCount, airborne, eye, look, MOUNT_REACH)) {
                continue;
            }
            double distance = DragonAnchor.headCentre(dragon.position(), dragon.getYRot(),
                    dragon.getXRot(), (float) dragon.tickCount, airborne).distanceToSqr(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dragon;
            }
        }
        return best;
    }

    private static void reject(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message)
                .withStyle(style -> style.withColor(Palette.DEEP_CYAN)));
    }

    /**
     * Disconnecting while riding must not leave a rider aboard.
     *
     * <p>A logged-out player's entity is removed from the level, but the vehicle's passenger list is
     * what drives {@code DragonEntity.tickRide} - and a stale entry there would leave the dragon in
     * {@code PHASE_FLYING} forever, steering from a player who is no longer there: velocity frozen at
     * whatever the last input produced, AI still suspended, and unmountable because it already has a
     * "rider". Dismounting explicitly on logout is what prevents that.
     *
     * <p>The rider is set down rather than dropped: {@code stopRiding} puts them at the dismount
     * position and their fall distance is cleared, because a player who lost their connection did not
     * choose to step off at altitude. This is the one case where the fall-damage config is deliberately
     * ignored - it governs a <em>choice</em> to dismount, and this was not one.
     */
    private static void onLoggingOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof DragonEntity dragon)) {
            return;
        }
        player.resetFallDistance();
        player.stopRiding();
        // stopRiding routes through removePassenger, which already restores the AI and clears the ride
        // phase; this is only a guard against the passenger list having been emptied another way.
        if (dragon.ridePhase() != DragonRideControl.PHASE_NONE && dragon.rider() == null) {
            dragon.onRiderLost();
        }
    }
}
