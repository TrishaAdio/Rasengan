package dev.rasengan.network;

import dev.rasengan.Rasengan;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Every packet this mod sends. Deliberately tiny and low-frequency.
 *
 * <p>Design rule: <b>no per-particle traffic.</b> The server sends the small set of facts a
 * client needs to reproduce the animation - who cast, when, which hand, which direction, and
 * a random seed - and each client then derives every particle and every mesh vertex locally
 * from those facts. Two clients given the same seed and the same start tick draw the same
 * animation, so nothing looks disconnected between players.
 */
public final class RasenganPayloads {
    private RasenganPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Rasengan.MOD_ID, path));
    }

    // ------------------------------------------------------------------
    // Server -> Client: POWER BAR state for the receiving player only.
    // ------------------------------------------------------------------

    /**
     * Authoritative POWER BAR snapshot for the receiving player.
     *
     * <p>Sent on every state transition and then only as a low-rate heartbeat. Between
     * packets the client advances its own copy one tick per tick, so the HUD percentage moves
     * smoothly in real time instead of stepping whenever a packet lands. Each heartbeat
     * re-anchors the client to the server value, so drift cannot accumulate.
     *
     * @param chargeTicks    ticks of charge accumulated, 0..chargeDuration
     * @param chargeDuration ticks required for a full bar (server config, echoed so the client
     *                       never has to guess the denominator)
     * @param cooldownTicks  remaining cooldown ticks, 0 when not in COOLDOWN
     * @param state          ordinal of {@link dev.rasengan.PowerState}
     */
    public record PowerSync(int chargeTicks, int chargeDuration, int cooldownTicks, int state)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<PowerSync> TYPE = payloadType("power_sync");

        public static final StreamCodec<RegistryFriendlyByteBuf, PowerSync> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, PowerSync::chargeTicks,
                        ByteBufCodecs.VAR_INT, PowerSync::chargeDuration,
                        ByteBufCodecs.VAR_INT, PowerSync::cooldownTicks,
                        ByteBufCodecs.VAR_INT, PowerSync::state,
                        PowerSync::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: a cast has begun (sent to everyone in range).
    // ------------------------------------------------------------------

    /**
     * Announces the start of a cast to nearby clients.
     *
     * @param casterId       entity id of the caster, so clients can track the real player entity
     *                       and keep the sphere glued to the hand as the player moves
     * @param seed           animation seed; drives all "random" particle variation deterministically
     * @param castDuration   total ticks from activation to release, echoed from server config
     * @param mainHand       true if the ability is bound to the main hand
     * @param dirX/dirY/dirZ the server-validated aim direction at activation time
     * @param cosmeticFlags  packed cosmetic caps from server config (see {@link CosmeticFlags})
     */
    public record CastStart(int casterId, long seed, int castDuration, boolean mainHand,
                            float dirX, float dirY, float dirZ, int cosmeticFlags, int ability)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<CastStart> TYPE = payloadType("cast_start");

        public static final StreamCodec<RegistryFriendlyByteBuf, CastStart> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, CastStart::casterId,
                        ByteBufCodecs.VAR_LONG, CastStart::seed,
                        ByteBufCodecs.VAR_INT, CastStart::castDuration,
                        ByteBufCodecs.BOOL, CastStart::mainHand,
                        ByteBufCodecs.FLOAT, CastStart::dirX,
                        ByteBufCodecs.FLOAT, CastStart::dirY,
                        ByteBufCodecs.FLOAT, CastStart::dirZ,
                        ByteBufCodecs.VAR_INT, CastStart::cosmeticFlags,
                        ByteBufCodecs.VAR_INT, CastStart::ability,
                        CastStart::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: the cast resolved at a specific point in space.
    // ------------------------------------------------------------------

    /**
     * The server-validated impact. Clients play the implosion/burst at exactly this point.
     *
     * <p>This is sent whether or not the target survived, so the full impact animation always
     * plays even when the hit was lethal.
     *
     * @param casterId entity id of the caster
     * @param x/y/z    impact position in world space
     * @param hitKind  0 = nothing hit (whiff), 1 = entity hit, 2 = terrain hit
     */
    public record CastImpact(int casterId, double x, double y, double z, int hitKind,
                             int ability, float spinTicks)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<CastImpact> TYPE = payloadType("cast_impact");

        public static final StreamCodec<RegistryFriendlyByteBuf, CastImpact> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, CastImpact::casterId,
                        ByteBufCodecs.DOUBLE, CastImpact::x,
                        ByteBufCodecs.DOUBLE, CastImpact::y,
                        ByteBufCodecs.DOUBLE, CastImpact::z,
                        ByteBufCodecs.VAR_INT, CastImpact::hitKind,
                        ByteBufCodecs.VAR_INT, CastImpact::ability,
                        ByteBufCodecs.FLOAT, CastImpact::spinTicks,
                        CastImpact::new);

        public static final int KIND_WHIFF = 0;
        public static final int KIND_ENTITY = 1;
        public static final int KIND_TERRAIN = 2;

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Server -> Client: cast finished or was cancelled; drop all effect state.
    // ------------------------------------------------------------------

    /**
     * Tells clients to tear down a caster's effect state.
     *
     * <p>Sent on normal completion and on every abnormal path too - death, disconnect,
     * dimension change, or an interrupted cast - so no client can be left with a stuck aura.
     *
     * @param casterId entity id of the caster
     * @param reason   0 = completed normally, 1 = cancelled/interrupted
     */
    public record CastEnd(int casterId, int reason) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<CastEnd> TYPE = payloadType("cast_end");

        public static final StreamCodec<RegistryFriendlyByteBuf, CastEnd> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, CastEnd::casterId,
                        ByteBufCodecs.VAR_INT, CastEnd::reason,
                        CastEnd::new);

        public static final int REASON_COMPLETED = 0;
        public static final int REASON_CANCELLED = 1;

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // Client -> Server: "I pressed the key". Intent only, carries no state.
    // ------------------------------------------------------------------

    /**
     * A bare request to activate. Carries no charge value, no damage, no target and no
     * position, precisely so a modified client cannot assert anything. The server decides
     * whether the bar is full, whether a cast is already running, and where the strike lands.
     */
    public record Activate(int ability) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Activate> TYPE = payloadType("activate");

        /**
         * Carries only which of the two abilities was requested - an enum ordinal, validated
         * server-side. It still asserts nothing about charge, aim, target or damage.
         */
        public static final StreamCodec<RegistryFriendlyByteBuf, Activate> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Activate::ability,
                        Activate::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cosmetic caps packed into one int so a single cast packet carries them without extra
     * traffic. Clients clamp their own preferences against these.
     */
    public static final class CosmeticFlags {
        private CosmeticFlags() {}

        /**
         * particleDensity and auraIntensity quantised to 0..100 each, plus the effect cap.
         *
         * <p>The cap is stored as {@code maxEffects - 1} in 6 bits. Storing it raw would overflow:
         * the configured maximum is 64, and {@code 64 & 0x3F} is 0, which previously collapsed a
         * server's 64-effect cap down to 1 and silently suppressed almost every effect.
         */
        public static int pack(double particleDensity, double auraIntensity, int maxEffects) {
            int pd = clamp((int) Math.round(particleDensity * 100.0D / 3.0D), 0, 100);
            int ai = clamp((int) Math.round(auraIntensity * 100.0D / 3.0D), 0, 100);
            int me = clamp(maxEffects, 1, 64) - 1;
            return (pd & 0x7F) | ((ai & 0x7F) << 7) | ((me & 0x3F) << 14);
        }

        public static double particleDensity(int packed) {
            return (packed & 0x7F) * 3.0D / 100.0D;
        }

        public static double auraIntensity(int packed) {
            return ((packed >>> 7) & 0x7F) * 3.0D / 100.0D;
        }

        public static int maxEffects(int packed) {
            return (((packed >>> 14) & 0x3F) + 1);
        }

        private static int clamp(int v, int min, int max) {
            return v < min ? min : Math.min(v, max);
        }
    }
}
