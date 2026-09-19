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
     * Start of the cinematic. Sent once, to everyone in range, on the sequence's first tick.
     *
     * <p>Everything the client needs to render all five stages without another packet: the anchor,
     * the seed, the stage lengths and the cosmetic policy. The stage <em>boundaries</em> are not sent
     * because both sides derive them from {@code totalTicks} and {@code revealTick} through
     * {@link dev.rasengan.SummonTimeline}, so there is one layout rather than two that must agree.
     *
     * @param summonerId         entity id of the summoning player, so observers can anchor the seal
     * @param dragonId           entity id of the already-spawned, still-hidden dragon. Sent at the
     *                           start rather than at the reveal so the client can warm its model and
     *                           texture during the smoke, instead of loading them on the reveal frame
     * @param seed               drives every procedural detail; identical on every client
     * @param x,y,z              the seal's centre, pinned server-side so it cannot drift if the
     *                           summoner walks during the sequence
     * @param totalTicks         full sequence length
     * @param revealTick         tick at which the dragon becomes visible
     * @param cameraReleaseTicks ticks over which the camera eases back, or <b>0 when the cinematic
     *                           camera is disabled entirely</b> - the visuals are unaffected either way
     * @param cameraRadius       blocks within which an observer gets the camera move, with a taper
     * @param smokeDensity       server-side multiplier on smoke particle counts
     * @param vignette           whether the reveal may pulse a screen-edge vignette
     */
    public record SummonStart(int summonerId, int dragonId, long seed,
                              double x, double y, double z,
                              int totalTicks, int revealTick,
                              int cameraReleaseTicks, float cameraRadius,
                              float smokeDensity, boolean vignette)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SummonStart> TYPE = payloadType("summon_start");

        public static final StreamCodec<RegistryFriendlyByteBuf, SummonStart> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SummonStart::summonerId,
                        ByteBufCodecs.VAR_INT, SummonStart::dragonId,
                        ByteBufCodecs.VAR_LONG, SummonStart::seed,
                        ByteBufCodecs.DOUBLE, SummonStart::x,
                        ByteBufCodecs.DOUBLE, SummonStart::y,
                        ByteBufCodecs.DOUBLE, SummonStart::z,
                        ByteBufCodecs.VAR_INT, SummonStart::totalTicks,
                        ByteBufCodecs.VAR_INT, SummonStart::revealTick,
                        ByteBufCodecs.VAR_INT, SummonStart::cameraReleaseTicks,
                        ByteBufCodecs.FLOAT, SummonStart::cameraRadius,
                        ByteBufCodecs.FLOAT, SummonStart::smokeDensity,
                        ByteBufCodecs.BOOL, SummonStart::vignette,
                        SummonStart::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public boolean cameraEnabled() {
            return cameraReleaseTicks > 0;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: the sequence is over, one way or another.
    // ------------------------------------------------------------------

    /**
     * End of a cinematic.
     *
     * <h2>Why this packet exists</h2>
     * Without it, an interrupted sequence was only ever cleaned up on the <em>server</em>: the entry
     * was dropped from the pending map and the client was never told, so every nearby client kept
     * running the timeline to its natural end - still rolling the camera, still erupting smoke for a
     * summon that had been abandoned. The client's own expiry eventually stopped it, but "eventually"
     * is the whole problem when the trigger was the summoner dying.
     *
     * <p>{@link Reason#INTERRUPTED} asks the client to abort: ease the camera back from wherever it
     * is, stop emitting, and cut the summoning sound. {@link Reason#COMPLETED} is the normal ending
     * and lets the settle stage finish as authored.
     *
     * @param summonerId entity id of the summoner whose sequence is ending
     * @param reason     ordinal of {@link Reason}
     */
    public record SummonEnd(int summonerId, int reason) implements CustomPacketPayload {

        /** Why a sequence ended. Ordinals are the wire format; append only. */
        public enum Reason {
            /** Ran to its natural end. Stage E plays out normally. */
            COMPLETED,
            /**
             * Abandoned early - the summoner died, disconnected, changed dimension or turned
             * spectator, or the dragon failed to spawn. The client aborts and restores the camera.
             */
            INTERRUPTED;

            private static final Reason[] VALUES = values();

            public static Reason byId(int id) {
                return id >= 0 && id < VALUES.length ? VALUES[id] : INTERRUPTED;
            }
        }

        public static final CustomPacketPayload.Type<SummonEnd> TYPE = payloadType("summon_end");

        public static final StreamCodec<RegistryFriendlyByteBuf, SummonEnd> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SummonEnd::summonerId,
                        ByteBufCodecs.VAR_INT, SummonEnd::reason,
                        SummonEnd::new);

        public static SummonEnd of(int summonerId, Reason reason) {
            return new SummonEnd(summonerId, reason.ordinal());
        }

        public Reason reasonValue() {
            return Reason.byId(reason);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
