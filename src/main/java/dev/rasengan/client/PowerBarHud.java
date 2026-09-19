package dev.rasengan.client;

import dev.rasengan.Palette;
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
 * <h2>Smoothness</h2>
 * The percentage comes from {@link ClientPowerState#fraction(float)}, which folds in the frame's
 * partial tick, so the label and the bar both advance every frame rather than 20 times a second.
 * The bar additionally renders a fractional leading pixel: full pixels are drawn at full alpha and
 * the remainder is drawn as one partially transparent column, so the fill edge glides instead of
 * snapping a whole pixel at a time.
 *
 * <h2>Glow</h2>
 * The bar has its own glow, entirely independent of the Rasengan sphere and aura - it runs even
 * when no cast is happening, because its job is to telegraph readiness. It is composed of two
 * continuous effects:
 * <ul>
 *   <li>a <b>breathing</b> brightness that rises and falls across the whole filled portion;</li>
 *   <li>a <b>travelling shimmer</b>, a soft highlight that sweeps repeatedly along the fill.</li>
 * </ul>
 * Both quicken and brighten as the bar fills, so it is nearly calm when empty and unmistakably alive
 * as it approaches 100%. Because their <em>rates</em> change with the fill, their phases are
 * integrated tick by tick in {@link ClientPowerState} rather than computed as {@code rate * time} -
 * the latter is not the integral of a varying rate and made the glow strobe in proportion to the
 * world's age. The amplitudes are still plain functions of the fill fraction and are computed here.
 * This is client-side cosmetic only and is never synced.
 */
public final class PowerBarHud implements GuiLayer {

    public static final PowerBarHud INSTANCE = new PowerBarHud();

    private static final int BAR_WIDTH = 150;
    private static final int BAR_HEIGHT = 8;
    /** Distance from the bottom of the screen to the top of the bar. */
    private static final int BOTTOM_OFFSET = 60;

    /** Horizontal slices used to paint the glow gradient. Enough to look continuous. */
    private static final int GLOW_SEGMENTS = 32;

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

        // ---- The bar's own glow ----
        renderGlow(graphics, barX, barY, wholePixels, fraction, partialTick, state);

        // ---- Leading-edge highlight ----
        if (state == PowerState.CHARGING && wholePixels > 1) {
            int edgeX = barX + wholePixels;
            graphics.fill(edgeX - 1, barY, edgeX, barY + BAR_HEIGHT,
                    Palette.argb(Palette.HIGHLIGHT, 0.75F));
        }

        // ---- Second, independent bar: POWER BAR - SUMMONING ----
        renderSummonBar(graphics, font, centreX, barX, barY, partialTick);

        // ---- Readout ----
        Component readout = switch (state) {
            case READY -> Component.literal("100%  READY");
            case CASTING -> Component.literal("0%  CASTING");
            case COOLDOWN -> Component.literal(percent + "%  RESET");
            case CHARGING -> Component.literal(percent + "%");
        };
        graphics.centeredText(font, readout, centreX, barY - 11, COLOR_LABEL);
    }

    /**
     * Paints the breathing glow and the travelling shimmer over the filled portion.
     *
     * <p>Drawn as {@link #GLOW_SEGMENTS} vertical slices whose alpha varies per slice. Varying
     * alpha across slices is what produces a visible gradient sweeping along the bar; a single
     * rectangle could only ever pulse uniformly.
     */
    private void renderGlow(GuiGraphicsExtractor graphics, int barX, int barY, int filledWidth,
                            float fraction, float partialTick, PowerState state) {
        if (filledWidth <= 0) {
            return;
        }

        // Readiness curve: squared so the effect stays subtle for most of the charge and then
        // ramps hard over the last stretch. Used for AMPLITUDES only - the rates it also governs
        // are integrated in ClientPowerState, because multiplying a ramping rate by elapsed time
        // is not the integral of that rate.
        float readiness = Math.clamp(fraction, 0.0F, 1.0F);
        float ramp = readiness * readiness;

        // Breathing brightness. Period is roughly 35 ticks at the base rate, quickening as the bar
        // fills so a nearly-ready bar feels more urgent. The phase is accumulated, not recomputed
        // from the world clock - see ClientPowerState.breathePhase.
        float breathe = 0.5F + 0.5F * (float) Math.sin(ClientPowerState.breathePhase(partialTick));

        float baseAlpha = (0.05F + 0.26F * ramp) * (0.55F + 0.45F * breathe);

        // Travelling shimmer head, looping along the filled portion.
        float head = positiveFraction(ClientPowerState.shimmerPhase(partialTick));

        // A tighter, brighter shimmer as the bar fills.
        float shimmerWidth = 4.0F + 2.5F * ramp;
        float shimmerPeak = 0.14F + 0.40F * ramp;

        boolean ready = state == PowerState.READY;
        if (ready) {
            // At 100% the shimmer becomes a steady, obvious pulse rather than ramping further.
            baseAlpha = 0.20F + 0.18F * breathe;
            shimmerPeak = 0.55F;
        }

        for (int i = 0; i < GLOW_SEGMENTS; i++) {
            float t0 = i / (float) GLOW_SEGMENTS;
            float t1 = (i + 1) / (float) GLOW_SEGMENTS;

            int x0 = barX + Math.round(t0 * filledWidth);
            int x1 = barX + Math.round(t1 * filledWidth);
            if (x1 <= x0) {
                continue; // slice narrower than a pixel at this fill width
            }

            float centre = (t0 + t1) * 0.5F;

            // Wrapped distance to the shimmer head, so the sweep loops seamlessly.
            float distance = Math.abs(centre - head);
            distance = Math.min(distance, 1.0F - distance);

            float falloff = Math.max(0.0F, 1.0F - distance * shimmerWidth);
            float shimmer = falloff * falloff * shimmerPeak;

            float alpha = Math.min(0.85F, baseAlpha + shimmer);
            if (alpha <= 0.004F) {
                continue;
            }

            // Shimmer crest leans toward the white-blue highlight; the body stays cyan.
            int colour = Palette.lerp(Palette.HUD_FILL, Palette.HUD_FILL_HOT,
                    Math.clamp(shimmer / Math.max(0.001F, shimmerPeak), 0.0F, 1.0F));

            graphics.fill(x0, barY, x1, barY + BAR_HEIGHT, Palette.argb(colour, alpha));
        }

        // ---- Outer halo, only once the bar is genuinely close to ready ----
        float haloStrength = OrbitMath.smoothstep(0.75F, 1.0F, readiness);
        if (haloStrength > 0.01F) {
            float haloAlpha = (0.10F + 0.22F * breathe) * haloStrength;
            int halo = Palette.argb(Palette.HIGHLIGHT, haloAlpha);
            int right = barX + filledWidth;
            // Thin bands hugging the bar, top and bottom, plus the leading edge.
            graphics.fill(barX - 1, barY - 2, right + 1, barY - 1, halo);
            graphics.fill(barX - 1, barY + BAR_HEIGHT + 1, right + 1, barY + BAR_HEIGHT + 2, halo);
            graphics.fill(right, barY - 1, right + 2, barY + BAR_HEIGHT + 1, halo);
        }
    }

    /**
     * The summoning bar, drawn directly beneath the cast bar.
     *
     * <p>A separate bar rather than a second fill on the same one, because the two are independent:
     * showing them as one bar would imply spending from a shared pool. Deliberately thinner and in
     * the dragon's ember colour so a glance distinguishes it from the cyan cast bar.
     */
    private void renderSummonBar(GuiGraphicsExtractor graphics, Font font, int centreX,
                                 int barX, int castBarY, float partialTick) {
        if (!ClientSummonPowerState.isInitialised()) {
            return;
        }
        final int height = 5;
        final int gap = 13;
        int y = castBarY + BAR_HEIGHT + gap;

        float fraction = ClientSummonPowerState.fraction(partialTick);
        PowerState state = ClientSummonPowerState.state();

        graphics.fill(barX - 1, y - 1, barX + BAR_WIDTH + 1, y + height + 1, COLOR_BORDER);
        graphics.fill(barX, y, barX + BAR_WIDTH, y + height, COLOR_TRACK);

        float exact = fraction * BAR_WIDTH;
        int whole = (int) exact;
        float remainder = exact - whole;
        if (whole > 0) {
            graphics.fillGradient(barX, y, barX + whole, y + height,
                    Palette.argb(SUMMON_HOT, 0.95F), Palette.argb(SUMMON_FILL, 0.95F));
        }
        // Same fractional leading column as the cast bar, so the edge glides rather than stepping.
        if (remainder > 0.01F && whole < BAR_WIDTH) {
            graphics.fill(barX + whole, y, barX + whole + 1, y + height,
                    Palette.argb(SUMMON_FILL, remainder * 0.95F));
        }

        Component label = switch (state) {
            case READY -> Component.literal("SUMMONING  READY");
            case CASTING -> Component.literal("SUMMONING  \u2014  ARRIVING");
            case COOLDOWN -> Component.literal("SUMMONING  " + ClientSummonPowerState.percent(partialTick) + "%");
            case CHARGING -> Component.literal("SUMMONING  " + ClientSummonPowerState.percent(partialTick) + "%");
        };
        graphics.centeredText(font, label, centreX, y + height + 2, COLOR_SUMMON_LABEL);
    }

    /** Ember tones, matching the dragon's chat colour rather than the cyan ability palette. */
    private static final int SUMMON_FILL = 0xC4521A;
    private static final int SUMMON_HOT = 0xFF7A29;
    private static final int COLOR_SUMMON_LABEL = 0xFFE8C9A8;

    /** Fractional part, always in 0..1 even for negative inputs. */
    private static float positiveFraction(float value) {
        float f = value - (float) Math.floor(value);
        return f < 0.0F ? f + 1.0F : f;
    }
}
