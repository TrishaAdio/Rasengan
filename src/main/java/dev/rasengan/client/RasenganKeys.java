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

    /** Minimum client ticks between outgoing activation packets. */
    private static final int SEND_INTERVAL_TICKS = 5;

    private static int cooldown;

    private RasenganKeys() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ACTIVATE);
        event.register(ACTIVATE_SHURIKEN);
    }

    /** Called once per client tick. */
    public static void tick(Minecraft minecraft) {
        if (cooldown > 0) {
            cooldown--;
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
