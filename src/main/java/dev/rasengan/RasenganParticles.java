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

    private RasenganParticles() {}

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> simple(String name) {
        // overrideLimiter = false: respect the client's particle setting so "Minimal" users
        // are not forced to render the full effect.
        Supplier<SimpleParticleType> factory = () -> new SimpleParticleType(false);
        return REGISTRY.register(name, factory);
    }
}
