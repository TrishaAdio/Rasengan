package dev.rasengan;

import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Particle types used by the cosmetic layer.
 *
 * <p>These are registered on both sides because a {@link ParticleType} is a registry object,
 * but they are only ever <em>spawned</em> client-side, straight into the local particle engine.
 * The server never sends particle packets for them.
 *
 * <p>Each type is a separate registry entry rather than one type with parameters, because the
 * client-side provider attached to each type encodes its own motion and fade behaviour. All of
 * them draw from the same locked cyan/blue/white palette in {@link dev.rasengan.client.Palette}.
 */
public final class RasenganParticles {
    public static final DeferredRegister<ParticleType<?>> REGISTRY =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Rasengan.MOD_ID);

    /** Tiny bright motes that spiral into the palm during cast preparation. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> INTAKE = simple("intake");

    /** Short-lived sparks flung outward from the sphere surface. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK = simple("spark");

    /** Slow flame-like wisps that rise along the caster's body. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> AURA_WISP = simple("aura_wisp");

    /** Fading outer wisps that stretch and curve away from the sphere. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WISP = simple("wisp");

    /** Dense burst fragments emitted at the impact point. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BURST = simple("burst");

    // ------------------------------------------------------------------
    // Summoning smoke
    // ------------------------------------------------------------------
    // Alpha-blended rather than additive, unlike everything above, because the reveal depends on the
    // summon being hidden and additive blending can only brighten. Drawn by SmokeParticle.

    /** The main eruption column: large, dense, heavily dragged billboards. Never culled. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_BILLOW =
            unlimited("smoke_billow");

    /** Fraying edge wisps, so the cloud has no hard boundary. First to go under load. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_WISP =
            simple("smoke_wisp");

    /** The rolling low layer that spreads outward along the ground. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> GROUND_FOG =
            simple("ground_fog");

    /** Near-camera dust motes for depth, only for close observers. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DUST_MOTE =
            simple("dust_mote");

    /** The fear tell above a frightened mob's head. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FEAR = simple("fear");

    private RasenganParticles() {}

    /**
     * A type that ignores the client's particle-count limiter.
     *
     * <p>Used only for the eruption column. Everything else in this mod is cosmetic garnish that a
     * player is welcome to turn down, but the column is load-bearing: if the limiter drops it, the
     * summon is not hidden and the reveal does not work. Density is still reduced for "Decreased" and
     * "Minimal" through {@link dev.rasengan.client.ClientTuning}, so the setting is respected - it
     * just cannot cull the column to nothing.
     */
    private static DeferredHolder<ParticleType<?>, SimpleParticleType> unlimited(String name) {
        return REGISTRY.register(name, () -> new SimpleParticleType(true));
    }

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> simple(String name) {
        // overrideLimiter = false: respect the client's particle setting so "Minimal" users
        // are not forced to render the full effect.
        Supplier<SimpleParticleType> factory = () -> new SimpleParticleType(false);
        return REGISTRY.register(name, factory);
    }
}
