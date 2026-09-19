package dev.rasengan.network;

import dev.rasengan.Rasengan;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packets for the Summoning Jutsu.
 *
 * <p>Kept in its own class rather than added to {@link RasenganPayloads} so the existing wire format
 * is untouched and {@code NETWORK_VERSION} does not need bumping - an older client and a newer server
 * disagree only about packets neither will send.
 *
 * <p>Same design rule as the rest of the mod: <b>no per-particle traffic.</b> One packet carries the
 * facts a client needs - who is summoning, from where, when it started, and a seed - and every client
 * derives the seal, smoke and camera motion locally from those. Two clients given the same seed and
 * start tick draw the same cinematic, so observers and summoner stay in step.
 */
public final class RasenganSummonPayloads {
    private RasenganSummonPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Rasengan.MOD_ID, path));
    }

    // ------------------------------------------------------------------
    // Client -> Server: intent only, no fields.
    // ------------------------------------------------------------------

    /**
     * "I pressed the summon key."
     *
     * <p>Carries nothing. Charge state, cooldown, dimension rules and the one-dragon-per-player
     * limit are all read from server state, so a modified client can do no more than ask.
     */
    public record SummonActivate() implements CustomPacketPayload {
        public static final SummonActivate INSTANCE = new SummonActivate();
        public static final CustomPacketPayload.Type<SummonActivate> TYPE = payloadType("summon_activate");
        public static final StreamCodec<RegistryFriendlyByteBuf, SummonActivate> CODEC =
                StreamCodec.unit(INSTANCE);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: the summoning POWER BAR, for its owner only.
    // ------------------------------------------------------------------

    /** Mirrors the shape of {@code PowerSync}, but for the independent summoning bar. */
    public record SummonPowerSync(int chargeTicks, int chargeDuration, int cooldownTicks, int state)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SummonPowerSync> TYPE =
                payloadType("summon_power_sync");

        public static final StreamCodec<RegistryFriendlyByteBuf, SummonPowerSync> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SummonPowerSync::chargeTicks,
                        ByteBufCodecs.VAR_INT, SummonPowerSync::chargeDuration,
                        ByteBufCodecs.VAR_INT, SummonPowerSync::cooldownTicks,
                        ByteBufCodecs.VAR_INT, SummonPowerSync::state,
                        SummonPowerSync::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: a summoning has begun. Sent to everyone in range.
    // ------------------------------------------------------------------

    /**
     * Start of the cinematic.
     *
     * @param summonerId    entity id of the summoning player, so observers can anchor the seal
     * @param seed          drives every procedural detail; identical on every client
     * @param x,y,z         the seal's centre, pinned server-side so it cannot drift with the player
     * @param totalTicks    full sequence length
     * @param revealTick    tick within the sequence at which the dragon appears
     * @param cameraTicks   packed camera window: {@code (start << 16) | end}, 0 when disabled
     */
    public record SummonStart(int summonerId, long seed, double x, double y, double z,
                              int totalTicks, int revealTick, int cameraTicks)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SummonStart> TYPE = payloadType("summon_start");

        public static final StreamCodec<RegistryFriendlyByteBuf, SummonStart> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SummonStart::summonerId,
                        ByteBufCodecs.VAR_LONG, SummonStart::seed,
                        ByteBufCodecs.DOUBLE, SummonStart::x,
                        ByteBufCodecs.DOUBLE, SummonStart::y,
                        ByteBufCodecs.DOUBLE, SummonStart::z,
                        ByteBufCodecs.VAR_INT, SummonStart::totalTicks,
                        ByteBufCodecs.VAR_INT, SummonStart::revealTick,
                        ByteBufCodecs.VAR_INT, SummonStart::cameraTicks,
                        SummonStart::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public int cameraStart() {
            return (cameraTicks >>> 16) & 0xFFFF;
        }

        public int cameraEnd() {
            return cameraTicks & 0xFFFF;
        }

        public boolean cameraEnabled() {
            return cameraTicks != 0;
        }

        public static int packCamera(int start, int end) {
            return ((start & 0xFFFF) << 16) | (end & 0xFFFF);
        }
    }
}
