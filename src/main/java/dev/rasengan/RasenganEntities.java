package dev.rasengan;

import dev.rasengan.server.DragonEntity;
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

    public static final ResourceKey<EntityType<?>> DRAGON_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, Rasengan.id("dragon"));

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

    /**
     * The boss dragon.
     *
     * <h2>Hitbox</h2>
     * Sized from the actual asset rather than a default mob box. The converted model measures
     * 29.5 blocks across with its wings spread, 19.8 nose-to-tail and 4.8 tall, so the collision
     * box is deliberately the <em>body</em>: 6.0 wide by 5.0 tall. The wings reach well outside it,
     * exactly as the Ender Dragon's do - putting a 29-block-wide box on a flying mob would make it
     * unable to path anywhere and let players hit it from absurd distances.
     *
     * <p>{@code MobCategory.MONSTER} with no spawn rules registered anywhere, which is what makes
     * it summon-only. {@code fireImmune} is set on the class, not here, so it stays with the
     * behaviour it describes.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DragonEntity>> DRAGON =
            REGISTRY.register("dragon", () -> EntityType.Builder
                    .<DragonEntity>of(DragonEntity::new, MobCategory.MONSTER)
                    .sized(6.0F, 5.0F)
                    .eyeHeight(3.6F)
                    // A boss needs to stay tracked from far enough away to be seen approaching.
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(DRAGON_KEY));

    private RasenganEntities() {}
}
