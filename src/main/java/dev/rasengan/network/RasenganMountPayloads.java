package dev.rasengan.network;

import dev.rasengan.Rasengan;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packets for mounting the dragon.
 *
 * <p>Its own class, for the same reason {@link RasenganSummonPayloads} is: the ability wire format stays
 * untouched, so {@code NETWORK_VERSION} does not move and an older client and a newer server disagree
 * only about packets neither will send.
 *
 * <p>There is exactly one, it goes client to server, and it carries <b>nothing</b>.
 */
public final class RasenganMountPayloads {
    private RasenganMountPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Rasengan.MOD_ID, path));
    }

    /**
     * "I left-clicked, and I think I was aiming at a dragon's head."
     *
     * <p>Fieldless on purpose. It does not name a dragon, carry a hit position, or assert that the head
     * was hit - so there is nothing in it to forge. The server finds the candidate itself and
     * re-traces the player's own server-side eye and look vector against the head region. The client's
     * local check exists only to avoid sending this on every unrelated swing.
     *
     * <p>Note what is <em>not</em> here: no dismount packet. Dismounting uses vanilla's own shift
     * handling, which already routes through {@code Entity.removePassenger} - the hook the fall-damage
     * policy and the AI handover live on. Adding a second path to the same place would be two things to
     * keep in step.
     */
    public record MountRequest() implements CustomPacketPayload {
        public static final MountRequest INSTANCE = new MountRequest();
        public static final CustomPacketPayload.Type<MountRequest> TYPE = payloadType("mount_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, MountRequest> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
