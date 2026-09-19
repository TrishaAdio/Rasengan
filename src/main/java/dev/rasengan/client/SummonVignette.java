package dev.rasengan.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * A brief dark pulse around the screen edges when the dragon is revealed.
 *
 * <h2>Why this exists at all</h2>
 * Nearby hostile mobs are given a real flee goal when the dragon arrives. Players deliberately get
 * <b>nothing</b> that affects their movement, input or control - taking the controls away from a
 * player to represent an emotion is a bad trade, and this mod's rule is that the player always steers.
 * So the player's share of the fear is entirely cosmetic: this vignette and the distance-scaled camera
 * rumble, both behind {@code [fear] player_vignette} and {@code [summoning] camera_effect}
 * respectively.
 *
 * <h2>Drawn as edge gradients rather than a texture</h2>
 * Four {@code fillGradient} bands, opaque black at the screen edge fading to transparent inward. That
 * needs no asset, scales to any resolution or GUI scale, and keeps the centre of the screen completely
 * untouched - which matters, because the whole point of the reveal is that the player can see the
 * dragon clearly.
 *
 * <p>Strength comes from {@link SummonCinematic#vignetteStrength(float)} and is a pure function of
 * elapsed ticks including the partial tick, so it fades smoothly at any frame rate rather than in 20
 * steps a second.
 */
public final class SummonVignette implements GuiLayer {

    public static final SummonVignette INSTANCE = new SummonVignette();

    /** Peak darkness at the very edge. Deliberately short of opaque. */
    private static final float MAX_ALPHA = 0.55F;

    /** How far in the gradient reaches, as a fraction of the smaller screen dimension. */
    private static final float BAND_FRACTION = 0.22F;

    private SummonVignette() {}

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();

        // NeoForge does not hide modded layers on F1, so guard explicitly - same as PowerBarHud.
        if (minecraft.options.hideGui) {
            return;
        }
        if (minecraft.player == null || minecraft.player.isSpectator()) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float strength = SummonCinematic.vignetteStrength(partialTick);
        if (strength <= 0.004F) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int band = Math.max(4, Math.round(Math.min(width, height) * BAND_FRACTION));

        int edge = argbBlack(strength * MAX_ALPHA);
        int clear = argbBlack(0.0F);

        // Top and bottom, then left and right. Overlapping corners darken twice, which is the
        // correct behaviour for a vignette - corners should be the darkest part.
        graphics.fillGradient(0, 0, width, band, edge, clear);
        graphics.fillGradient(0, height - band, width, height, clear, edge);

        // fillGradient interpolates vertically only, so the side bands are built from columns.
        int columns = Math.min(band, 48);
        int step = Math.max(1, band / columns);
        for (int offset = 0; offset < band; offset += step) {
            float k = 1.0F - (offset / (float) band);
            int col = argbBlack(strength * MAX_ALPHA * k * k);
            graphics.fill(offset, 0, offset + step, height, col);
            graphics.fill(width - offset - step, 0, width - offset, height, col);
        }
    }

    private static int argbBlack(float alpha) {
        int a = Math.round(net.minecraft.util.Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        return a << 24;
    }
}
