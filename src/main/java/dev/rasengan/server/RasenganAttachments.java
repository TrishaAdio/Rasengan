package dev.rasengan.server;

import dev.rasengan.Rasengan;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Data attachment registration.
 *
 * <p>{@code copyOnDeath} keeps the charge across the death/respawn entity swap. Whether dying
 * <em>should</em> wipe the bar is a separate server policy, handled explicitly in
 * {@link ServerPowerManager} via {@code reset_charge_on_death} - copying here first means the
 * server, not the entity lifecycle, decides.
 */
public final class RasenganAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTRY =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Rasengan.MOD_ID);

    public static final Supplier<AttachmentType<PowerData>> POWER = REGISTRY.register(
            "power",
            id -> AttachmentType.builder(PowerData::new)
                    .serialize(PowerData.CODEC)
                    .copyOnDeath()
                    .build());

    private RasenganAttachments() {}
}
