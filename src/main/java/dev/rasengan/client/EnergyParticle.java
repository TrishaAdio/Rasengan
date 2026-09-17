package dev.rasengan.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;

/**
 * The one particle class behind all five of the mod's particle types.
 *
 * <p>Behaviour is selected by a {@link Style}, so there is a single place where lifetime, drag,
 * gravity, size curve and fade live. The texture is white and the colour comes from
 * {@link Palette} at spawn time - a particle can never end up off-palette.
 *
 * <p>Rendered on the translucent particle layer with emissive light, which is what lets it glow in
 * a dark cave instead of going flat grey.
 */
public class EnergyParticle extends SingleQuadParticle {

    /** Per-type behaviour. */
    public enum Style {
        /** Motes pulled toward the palm: accelerate inward, small and short-lived. */
        INTAKE(0.94F, 0.0F, 0.09F, 10, 16, 0.55F),
        /** Fast outward glints that decelerate hard. */
        SPARK(0.82F, 0.02F, 0.11F, 5, 10, 0.85F),
        /** Slow flame-like wisps rising off the body. */
        AURA_WISP(0.90F, -0.006F, 0.13F, 12, 20, 0.45F),
        /** Outer wisps that stretch, curve and dissolve. */
        WISP(0.93F, -0.003F, 0.17F, 14, 24, 0.40F),
        /** Dense impact fragments. */
        BURST(0.86F, 0.03F, 0.16F, 8, 16, 0.90F);

        final float friction;
        final float gravity;
        final float size;
        final int minLifetime;
        final int maxLifetime;
        final float startAlpha;

        Style(float friction, float gravity, float size, int minLifetime, int maxLifetime, float startAlpha) {
            this.friction = friction;
            this.gravity = gravity;
            this.size = size;
            this.minLifetime = minLifetime;
            this.maxLifetime = maxLifetime;
            this.startAlpha = startAlpha;
        }
    }

    private final SpriteSet sprites;
    private final Style style;
    private final float baseSize;
    private final float baseAlpha;

    protected EnergyParticle(ClientLevel level, double x, double y, double z,
                             double xa, double ya, double za,
                             SpriteSet sprites, Style style, RandomSource random) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D, sprites.first());
        this.sprites = sprites;
        this.style = style;

        this.hasPhysics = false; // energy passes through geometry; avoids sliding along floors
        this.friction = style.friction;
        this.gravity = style.gravity;

        // Size and lifetime jitter so particles never look like a synchronised batch.
        this.baseSize = style.size * (0.7F + random.nextFloat() * 0.6F);
        this.quadSize = baseSize;
        this.baseAlpha = style.startAlpha * (0.8F + random.nextFloat() * 0.4F);
        this.alpha = baseAlpha;

        setLifetime(style.minLifetime + random.nextInt(Math.max(1, style.maxLifetime - style.minLifetime)));
        setParticleSpeed(xa, ya, za);
        applyPaletteColour(random);
        setSpriteFromAge(sprites);
    }

    /**
     * Picks a colour from the locked palette.
     *
     * <p>The random draw only chooses <em>which</em> palette entry and how far to blend between
     * two of them - it can never produce a hue outside the cyan/blue/white set. That is how the
     * effect stays visually varied while remaining colour-locked for every player.
     */
    private void applyPaletteColour(RandomSource random) {
        int rgb = switch (style) {
            // Hottest near the hand and at impact.
            case INTAKE, SPARK, BURST -> Palette.lerp(Palette.CORE, Palette.CYAN, random.nextFloat() * 0.85F);
            // Aura and outer wisps sit further down the palette toward blue.
            case AURA_WISP -> Palette.lerp(Palette.CYAN, Palette.DEEP_CYAN, random.nextFloat());
            case WISP -> Palette.lerp(Palette.DEEP_CYAN, Palette.BLUE, random.nextFloat() * 0.8F);
        };
        setColor(Palette.red(rgb) / 255.0F, Palette.green(rgb) / 255.0F, Palette.blue(rgb) / 255.0F);
    }

    @Override
    protected SingleQuadParticle.Layer getLayer() {
        return SingleQuadParticle.Layer.TRANSLUCENT;
    }

    @Override
    public int getLightCoords(float partialTick) {
        // Emissive: the particle lights itself so it glows in shadow.
        return LightCoordsUtil.addSmoothBlockEmission(
                super.getLightCoords(partialTick), 0.85F);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);

        float lifeFraction = lifetime <= 0 ? 1.0F : (float) age / lifetime;

        // Staggered, individual fades: each particle shrinks and fades on its own curve, so the
        // cloud continuously dissolves and reforms rather than blinking out together.
        this.alpha = baseAlpha * (1.0F - lifeFraction * lifeFraction);

        this.quadSize = switch (style) {
            // Intake motes shrink as they are swallowed by the sphere.
            case INTAKE -> baseSize * (1.0F - 0.65F * lifeFraction);
            // Wisps stretch outward before dissolving.
            case WISP, AURA_WISP -> baseSize * (1.0F + 0.85F * lifeFraction);
            // Sparks and fragments taper away.
            case SPARK, BURST -> baseSize * (1.0F - 0.45F * lifeFraction);
        };
    }

    /** Provider bound to one {@link Style}, registered per particle type. */
    public record Provider(SpriteSet sprites, Style style) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(SimpleParticleType options, ClientLevel level,
                                       double x, double y, double z,
                                       double xa, double ya, double za,
                                       RandomSource random) {
            return new EnergyParticle(level, x, y, z, xa, ya, za, sprites, style, random);
        }
    }
}
