package dev.rasengan.client;

import dev.rasengan.PowerState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * The POWER BAR: a persistent bottom-centre HUD element.
 *
 * <p>Layout, from the bottom of the screen upward, is chosen to sit clear of the vanilla hotbar,
 * experience bar and health/armour rows.
 *
 * <h2>Smoothness</h2>
 * The percentage comes from {@link ClientPowerState#fraction(float)}, which folds in the frame's
 * partial tick, so the label and the bar both advance every frame rather than 20 times a second.
 * The bar additionally renders a fractional leading pixel: full pixels are drawn at full alpha
 * and the remainder is drawn as one partially transparent column, so the fill edge glides
 * instead of snapping a whole pixel at a time. At the default 150-second charge that is the
 * difference between a visibly stepping bar and a continuous one.
 */
public final class PowerBarHud implements GuiLayer {

    public static final PowerBarHud INSTANCE = new PowerBarHud();

    private static final int BAR_WIDTH = 150;
    private static final int BAR_HEIGHT = 8;
    /** Distance from the bottom of the screen to the top of the bar. */
    private static final int BOTTOM_OFFSET = 60;

    private static final int COLOR_BORDER = 0xFF0A1A24;
    private static final int COLOR_TRACK = 0xB0031017;
    private static final int COLOR_LABEL = 0xFFDFF4FF;

    private PowerBarHud() {}

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();

        // NeoForge does not gate modded layers behind F1, so guard explicitly.
        if (minecraft.options.hideGui) {
            return;
        }
        if (minecraft.player == null || minecraft.player.isSpectator()) {
            return;
        }
        if (!ClientPowerState.isInitialised()) {
            return; // no authoritative value yet; draw nothing rather than a wrong 0%
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float fraction = ClientPowerState.fraction(partialTick);
        int percent = ClientPowerState.percent(partialTick);
        PowerState state = ClientPowerState.state();

        Font font = minecraft.font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();

        int barX = (screenWidth - BAR_WIDTH) / 2;
        int barY = screenHeight - BOTTOM_OFFSET;
        int centreX = screenWidth / 2;

        // ---- Title ----
        graphics.centeredText(font, Component.literal("POWER BAR"), centreX, barY - 21, COLOR_LABEL);

        // ---- Frame and track ----
        graphics.fill(barX - 1, barY - 1, barX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, COLOR_BORDER);
        graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT, COLOR_TRACK);

        // ---- Fill ----
        float exact = fraction * BAR_WIDTH;
        int wholePixels = (int) exact;
        float remainder = exact - wholePixels;

        if (wholePixels > 0) {
            // Gradient along the bar: cyan at the base, white-blue at the hot end.
            // fillGradient interpolates vertically, so the horizontal ramp is approximated by
            // two stacked bands, which is enough to give the fill depth without extra draws.
            int mid = barY + BAR_HEIGHT / 2;
            graphics.fillGradient(barX, barY, barX + wholePixels, mid,
                    Palette.argb(Palette.HUD_FILL_HOT, 0.95F), Palette.argb(Palette.HUD_FILL, 0.95F));
            graphics.fillGradient(barX, mid, barX + wholePixels, barY + BAR_HEIGHT,
                    Palette.argb(Palette.HUD_FILL, 0.95F), Palette.argb(Palette.DEEP_CYAN, 0.95F));
        }

        // Fractional leading column: this is what makes the edge glide.
        if (remainder > 0.01F && wholePixels < BAR_WIDTH) {
            int edgeX = barX + wholePixels;
            graphics.fill(edgeX, barY, edgeX + 1, barY + BAR_HEIGHT,
                    Palette.argb(Palette.HUD_FILL, remainder * 0.95F));
        }

        // ---- Leading-edge highlight ----
        if (state == PowerState.CHARGING && wholePixels > 1) {
            int edgeX = barX + wholePixels;
            graphics.fill(edgeX - 1, barY, edgeX, barY + BAR_HEIGHT,
                    Palette.argb(Palette.HIGHLIGHT, 0.75F));
        }

        // ---- Ready pulse ----
        // A slow breathing overlay so a full bar is obvious without being noisy.
        if (state == PowerState.READY) {
            float phase = (float) ((minecraft.level != null ? minecraft.level.getGameTime() : 0L) + partialTick);
            float pulse = 0.30F + 0.30F * (float) Math.sin(phase * 0.20F);
            graphics.fill(barX, barY, barX + BAR_WIDTH, barY + BAR_HEIGHT,
                    Palette.argb(Palette.HIGHLIGHT, pulse));
        }

        // ---- Readout ----
        Component readout = switch (state) {
            case READY -> Component.literal("100%  READY");
            case CASTING -> Component.literal("0%  CASTING");
            case COOLDOWN -> Component.literal(percent + "%  RESET");
            case CHARGING -> Component.literal(percent + "%");
        };
        graphics.centeredText(font, readout, centreX, barY - 11, COLOR_LABEL);
    }
}
