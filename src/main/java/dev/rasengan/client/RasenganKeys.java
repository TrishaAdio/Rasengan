package dev.rasengan.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.rasengan.AbilityType;
import dev.rasengan.network.RasenganPayloads;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The activation keybind, default {@code R}.
 *
 * <p>The key does exactly one thing: send an empty {@link RasenganPayloads.Activate} to the
 * server. It performs no local charge check that could be bypassed and starts no local animation -
 * the client only begins rendering when the server's {@code CastStart} comes back. That round trip
 * is what guarantees a player never sees an effect the server did not authorise.
 *
 * <p>There is a cheap local rate limit purely to avoid spamming the server with packets when a
 * player holds or mashes the key; it is a courtesy to the network, not a security measure.
 */
public final class RasenganKeys {

    public static final KeyMapping ACTIVATE = new KeyMapping(
            "key.rasengan.activate",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KeyMapping.Category.GAMEPLAY);

    /** Rasen Shuriken, default G. */
    public static final KeyMapping ACTIVATE_SHURIKEN = new KeyMapping(
            "key.rasengan.activate_shuriken",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.GAMEPLAY);

    /**
     * Summoning Jutsu, default {@code L}.
     *
     * <p>Same contract as the cast keys: it sends a fieldless request and starts nothing locally. The
     * cinematic only begins when the server's {@code SummonStart} arrives, so a client cannot show
     * itself a summon the server refused.
     */
    public static final KeyMapping SUMMON = new KeyMapping(
            "key.rasengan.summon",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            KeyMapping.Category.GAMEPLAY);

    /** Minimum client ticks between outgoing activation packets. */
    private static final int SEND_INTERVAL_TICKS = 5;

    private static int cooldown;

    /**
     * Separate anti-spam counter for the summon key.
     *
     * <p>Deliberately not shared with {@link #cooldown}. Sharing one counter would mean a press of L
     * swallowed the next quarter-second of R and G presses, and vice versa - two unrelated abilities
     * silently eating each other's input. The counter exists to stop packet flooding on one key, not
     * to serialise the whole ability set.
     */
    private static int summonCooldown;

    private RasenganKeys() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ACTIVATE);
        event.register(ACTIVATE_SHURIKEN);
        event.register(SUMMON);
    }

    /** Called once per client tick. */
    public static void tick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
        }
        if (summonCooldown > 0) {
            summonCooldown--;
        }

        boolean summonRequested = false;
        while (SUMMON.consumeClick()) {
            summonRequested = true;
        }
        // Not an early return: a player who taps L and R in the same tick meant both, and the summon
        // key must not consume the cast press.
        if (summonRequested && minecraft.player != null && minecraft.level != null
                && summonCooldown <= 0) {
            summonCooldown = SEND_INTERVAL_TICKS;
            ClientPacketDistributor.sendToServer(
                    dev.rasengan.network.RasenganSummonPayloads.SummonActivate.INSTANCE);
        }

        AbilityType requested = null;
        while (ACTIVATE.consumeClick()) {
            requested = AbilityType.RASENGAN;
        }
        while (ACTIVATE_SHURIKEN.consumeClick()) {
            requested = AbilityType.RASEN_SHURIKEN;
        }
        if (requested == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (cooldown > 0) {
            return;
        }
        cooldown = SEND_INTERVAL_TICKS;

        ClientPacketDistributor.sendToServer(new RasenganPayloads.Activate(requested.id()));
    }
}
