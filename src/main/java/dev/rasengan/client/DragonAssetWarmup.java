package dev.rasengan.client;

import dev.rasengan.Rasengan;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Forces the dragon's texture to be resident before the reveal.
 *
 * <h2>What is and is not lazy - checked, not assumed</h2>
 * The obvious worry is that a 183-cube GeckoLib model and its twelve animations are parsed on the
 * first frame the dragon is drawn, putting a large stall on the reveal frame. <b>That turns out not to
 * be the case.</b> {@code GeckoLibResources} is a {@code PreparableReloadListener} that bakes every
 * model and animation during resource reload, and {@code BakedModelCache.getModel} only ever reads
 * from the map it populated - it logs "Unable to find model" and returns a placeholder rather than
 * baking on demand. So geometry and animation are already warm long before any summon.
 *
 * <p>The texture is different. {@code dragon.png} is 1024x1024 and is registered with the vanilla
 * {@link net.minecraft.client.renderer.texture.TextureManager}, which loads and uploads on first
 * request. For a dragon that has never been seen this session, that upload lands on the frame it first
 * renders - which, without the hidden pre-spawn, is exactly the reveal frame.
 *
 * <p>So the warm-up is deliberately narrow: touch the texture, nothing else. Calling
 * {@code getTexture} is enough - it registers and loads if absent, and is a cheap map lookup if not.
 * Combined with the server spawning the dragon hidden on tick 0, the first real render has nothing
 * left to load.
 *
 * <p><b>Not verified:</b> the size of the frame-time spike this avoids, or that it is gone. Measuring
 * it needs a display; this environment has none. What <em>is</em> verified is the mechanism - that the
 * texture is loaded on demand by {@code TextureManager} and that GeckoLib's caches are not.
 */
public final class DragonAssetWarmup {

    private static final Identifier TEXTURE = Rasengan.id("textures/entity/dragon.png");

    /** Set once the texture has been requested, so repeat summons do nothing at all. */
    private static boolean warmed;

    private DragonAssetWarmup() {}

    /** Requests the dragon texture. Safe to call on every summon; only the first does work. */
    public static void prewarm() {
        if (warmed) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getTextureManager() == null) {
            return;
        }
        minecraft.getTextureManager().getTexture(TEXTURE);
        warmed = true;
    }

    /** Clears the flag on disconnect, since a resource reload can drop the texture. */
    public static void reset() {
        warmed = false;
    }
}
