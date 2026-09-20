package dev.rasengan.client;

import dev.rasengan.DragonAnchor;
import dev.rasengan.network.RasenganMountPayloads;
import dev.rasengan.server.DragonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Turns a left-click on a dragon's head into a mount request.
 *
 * <h2>Why the attack key and not a new keybind</h2>
 * The brief asks for left-click, and left-click already means "attack". The dragon is a hostile boss, so
 * both readings are legitimate and the ambiguity has to be resolved rather than ignored. It is resolved
 * by position: a click whose look ray hits the <b>head region</b> mounts, any other click attacks. The
 * head is a small target 4.2 blocks out in front of a 29.5-block creature, so the two almost never
 * compete in practice.
 *
 * <h2>What left-click does while already riding: nothing</h2>
 * Explicitly nothing - it is not forwarded as a mount request, and it is not reinterpreted as any kind of
 * command to the mount. It is left to behave as a completely ordinary attack swing, which is what lets a
 * rider still fight things from the dragon's head. What it will never do is hit the dragon underneath
 * them: vanilla cannot target your own vehicle, and the head is outside the collision box in any case.
 *
 * <h2>The click is not trusted</h2>
 * The local ray-trace here decides only whether to <em>send</em> the fieldless
 * {@link RasenganMountPayloads.MountRequest}, so an unrelated swing does not generate traffic. The server
 * repeats the whole test against its own view of the player and the dragon and can refuse. Both sides run
 * the same {@link DragonAnchor} code, so they agree about where the head is.
 *
 * <p>Runs on {@code ClientTickEvent.Pre}, ahead of {@code Minecraft.handleKeybinds()}, so that a mounting
 * click can be consumed before vanilla turns it into a swing. A click that is not a mount is left
 * untouched for vanilla to handle normally.
 */
public final class RasenganMountInput {

    /** Must match {@code DragonMountManager.MOUNT_REACH}; the server is the one that enforces it. */
    private static final double MOUNT_REACH = 6.0D;

    private static final double SEARCH_RADIUS = 16.0D;

    /** Minimum client ticks between mount requests, so a held button cannot flood. */
    private static final int SEND_INTERVAL_TICKS = 5;

    private static int cooldown;

    /**
     * The dragon whose head is currently under the crosshair, or null.
     *
     * <p>Held rather than recomputed per frame so {@link DragonHeadHighlight} draws exactly the region the
     * input code would act on - if the outline is visible, a click will be sent.
     */
    private static DragonEntity targeted;

    /** Whether a click would actually mount, as opposed to merely being aimed at a head. */
    private static boolean mountable;

    private RasenganMountInput() {}

    public static DragonEntity targetedDragon() {
        return targeted;
    }

    public static boolean isMountable() {
        return mountable;
    }

    /** Called from {@code ClientTickEvent.Pre}. */
    public static void tick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        targeted = null;
        mountable = false;

        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null) {
            return;
        }
        // Riding already: both buttons stay ordinary. See the class note.
        if (player.isPassenger()) {
            return;
        }

        DragonEntity head = findHead(minecraft, player);
        targeted = head;
        if (head == null) {
            return;
        }
        // Ownership is re-checked server-side; this only decides how the outline is drawn, and whether
        // it is worth sending anything at all.
        mountable = head.mayMount(player);

        // BOTH buttons mount.
        //
        // Left-click is what the feature was specified around, but right-click is what every vanilla
        // mount uses - horses, boats, striders, camels - so it is what a player's hand does first. The
        // original build accepted only left-click and the first thing that happened was someone trying
        // right-click, getting nothing, then trying left-click and also getting nothing because of
        // separate bugs. Accepting both removes a whole class of "is it broken or am I?" confusion.
        //
        // The click is consumed only when a head is targeted, so ordinary attacking and item use are
        // untouched everywhere else.
        boolean clicked = false;
        while (minecraft.options.keyAttack.consumeClick()) {
            clicked = true;
        }
        while (minecraft.options.keyUse.consumeClick()) {
            clicked = true;
        }
        if (!clicked || cooldown > 0) {
            return;
        }
        cooldown = SEND_INTERVAL_TICKS;
        ClientPacketDistributor.sendToServer(RasenganMountPayloads.MountRequest.INSTANCE);
    }

    /**
     * The dragon head under the crosshair, if any.
     *
     * <p>Mirrors {@code DragonMountManager.findHeadUnderCrosshair} and shares the same
     * {@link DragonAnchor} maths, so a click the client thinks will mount is one the server agrees with.
     */
    private static DragonEntity findHead(Minecraft minecraft, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        AABB search = player.getBoundingBox().inflate(SEARCH_RADIUS);

        DragonEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (var entity : minecraft.level.getEntities(player, search,
                candidate -> candidate instanceof DragonEntity)) {
            DragonEntity dragon = (DragonEntity) entity;
            if (!dragon.isAlive() || dragon.isHiddenForSummon()) {
                continue;
            }
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
}
