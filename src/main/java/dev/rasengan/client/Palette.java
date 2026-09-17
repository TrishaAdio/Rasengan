package dev.rasengan.client;

/**
 * The single locked energy palette. Every player's aura and sphere uses these colours.
 *
 * <p>There is intentionally no API here that takes a player, a team, a seed or any other
 * per-player input. Randomness in the animation is applied to <em>motion and timing</em> only -
 * position, speed, phase, lifetime - never to hue. That is what keeps every cast on-palette
 * while still looking alive.
 *
 * <p>Colours are packed 0xRRGGBB. Alpha is supplied per-vertex by the renderer because the
 * energy layers fade independently.
 */
public final class Palette {
    private Palette() {}

    /** Innermost core: near-white with a blue bias. */
    public static final int CORE = 0xE8F6FF;

    /** Bright white-blue highlight, used for streaks and the hottest edges. */
    public static final int HIGHLIGHT = 0xBFE9FF;

    /** Primary cyan. The dominant colour of the sphere shell and bands. */
    public static final int CYAN = 0x4FD6FF;

    /** Deeper cyan-blue used for outer shell and wisp tails. */
    public static final int DEEP_CYAN = 0x1FA8E8;

    /** Saturated blue for the outermost falloff and shockwave rings. */
    public static final int BLUE = 0x1560D8;

    /** HUD accent, matching the sphere's cyan so the bar reads as the same energy. */
    public static final int HUD_FILL = CYAN;
    public static final int HUD_FILL_HOT = HIGHLIGHT;

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /** Packs an ARGB int from an RGB constant plus an alpha in 0..1. */
    public static int argb(int rgb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Linear blend between two palette colours. Used to shade from the white-hot core out to
     * cyan and then blue, which is what gives the sphere its sense of depth.
     */
    public static int lerp(int fromRgb, int toRgb, float t) {
        float f = Math.clamp(t, 0.0F, 1.0F);
        int r = Math.round(red(fromRgb) + (red(toRgb) - red(fromRgb)) * f);
        int g = Math.round(green(fromRgb) + (green(toRgb) - green(fromRgb)) * f);
        int b = Math.round(blue(fromRgb) + (blue(toRgb) - blue(fromRgb)) * f);
        return (r << 16) | (g << 8) | b;
    }
}
