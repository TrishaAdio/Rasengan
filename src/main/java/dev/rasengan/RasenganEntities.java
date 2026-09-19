package dev.rasengan;

import dev.rasengan.server.RasenganProjectile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity type registration.
 *
 * <p>Registered on both sides because an {@link EntityType} is a registry object and the server
 * is the side that spawns and simulates it. The class it points at, {@link RasenganProjectile},
 * contains no client code.
 */
public final class RasenganEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY =
            DeferredRegister.create(Registries.ENTITY_TYPE, Rasengan.MOD_ID);

    public static final ResourceKey<EntityType<?>> PROJECTILE_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Rasengan.id("rasengan_projectile"));

    /**
     * The thrown energy sphere.
     *
     * <p>{@code clientTrackingRange(6)} chunks comfortably exceeds the default 64-block effect
     * distance, and {@code updateInterval(1)} means the server pushes a position update every
     * tick. A fast projectile with a slow update interval is exactly what produces the "it
     * teleported" look, so this one is synced every tick - it is short-lived, so the cost is
     * bounded and small.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RasenganProjectile>> PROJECTILE =
            REGISTRY.register("rasengan_projectile", () -> EntityType.Builder
                    .<RasenganProjectile>of(RasenganProjectile::new, MobCategory.MISC)
                    .noLootTable()
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(6)
                    .updateInterval(1)
                    .build(PROJECTILE_KEY));

    private RasenganEntities() {}
}
