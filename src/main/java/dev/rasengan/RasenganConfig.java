package dev.rasengan;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-authoritative configuration.
 *
 * <p>Everything that affects gameplay lives in the {@code SERVER} config type. NeoForge
 * automatically syncs {@code SERVER} configs to connected clients, which means the client
 * HUD and the client renderer can read the very same values the server is enforcing. The
 * client never gets a vote on whether the ability may be used - it only mirrors state.
 *
 * <p>The cosmetic knobs (particle density, aura intensity, max effect distance) are also
 * defined here so a server owner can cap what clients are allowed to draw. Clients may
 * additionally lower these values locally for performance, but never raise them above the
 * server cap - see {@code ClientTuning}.
 */
public final class RasenganConfig {
    public static final ModConfigSpec SPEC;
    public static final Server SERVER;

    static {
        Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = pair.getLeft();
        SPEC = pair.getRight();
    }

    private RasenganConfig() {}

    public static final class Server {
        // ---- POWER BAR ----
        public final ModConfigSpec.IntValue chargeDurationSeconds;
        public final ModConfigSpec.BooleanValue chargeWhileDead;
        public final ModConfigSpec.BooleanValue resetChargeOnDeath;

        // ---- Ability / damage ----
        public final ModConfigSpec.DoubleValue rasenganDamage;
        public final ModConfigSpec.DoubleValue abilityRange;
        public final ModConfigSpec.DoubleValue hitboxSize;
        public final ModConfigSpec.IntValue castDurationTicks;
        public final ModConfigSpec.IntValue cooldownTicks;
        public final ModConfigSpec.BooleanValue bypassInvulnerabilityFrames;
        public final ModConfigSpec.BooleanValue announceCast;

        // ---- Environment ----
        public final ModConfigSpec.BooleanValue blockDamageEnabled;
        public final ModConfigSpec.DoubleValue environmentDamageRadius;
        public final ModConfigSpec.DoubleValue environmentDamageStrength;
        public final ModConfigSpec.BooleanValue environmentDropsItems;

        // ---- Cosmetic caps (synced to clients) ----
        public final ModConfigSpec.DoubleValue particleDensity;
        public final ModConfigSpec.DoubleValue auraIntensity;
        public final ModConfigSpec.DoubleValue maxEffectDistance;
        public final ModConfigSpec.IntValue maxSimultaneousEffects;

        private Server(ModConfigSpec.Builder builder) {
            builder.comment(
                    "Rasengan - server-authoritative configuration.",
                    "All gameplay values below are enforced by the server. Clients cannot override them.")
                    .push("power_bar");

            chargeDurationSeconds = builder
                    .comment("Real seconds for the POWER BAR to charge from 0% to 100%.",
                            "Default 150 seconds = 2 minutes 30 seconds.")
                    .defineInRange("charge_duration_seconds", 150, 1, 86_400);

            chargeWhileDead = builder
                    .comment("If true the POWER BAR keeps charging while the player is dead / respawning.")
                    .define("charge_while_dead", false);

            resetChargeOnDeath = builder
                    .comment("If true, dying resets the POWER BAR back to 0%.",
                            "If false the charge is preserved across death and respawn.")
                    .define("reset_charge_on_death", false);

            builder.pop().push("ability");

            rasenganDamage = builder
                    .comment("Damage of one direct Rasengan hit, in health points (2 points = 1 heart).",
                            "Default 40.0 = 20 full hearts. Applied once, as a single immediate hit.")
                    .defineInRange("damage", 40.0D, 0.0D, 10_000.0D);

            abilityRange = builder
                    .comment("Maximum reach of the release strike, in blocks, measured from the caster's eyes.")
                    .defineInRange("range", 4.0D, 0.5D, 64.0D);

            hitboxSize = builder
                    .comment("Radius in blocks of the spherical hit volume swept along the strike path.")
                    .defineInRange("hitbox_size", 1.0D, 0.1D, 8.0D);

            castDurationTicks = builder
                    .comment("Ticks between activation and the release/impact resolution (20 ticks = 1 second).",
                            "This covers cast preparation plus sphere formation.")
                    .defineInRange("cast_duration_ticks", 30, 2, 200);

            cooldownTicks = builder
                    .comment("Extra ticks after a cast completes before the POWER BAR resumes charging.",
                            "0 means charging restarts immediately from 0% on the next tick.")
                    .defineInRange("cooldown_ticks", 0, 0, 72_000);

            bypassInvulnerabilityFrames = builder
                    .comment("Clear the target's damage-immunity timer so the full configured damage always lands",
                            "even if the target was hit moments earlier. Recommended true.")
                    .define("bypass_invulnerability_frames", true);

            announceCast = builder
                    .comment("Broadcast '<player name> casted Rasengan' to every online player on a successful cast.")
                    .define("announce_cast", true);

            builder.pop().push("environment");

            blockDamageEnabled = builder
                    .comment("Allow the impact to damage terrain. Disabled by default: it is destructive,",
                            "irreversible on most servers, and is tuned independently of entity damage.")
                    .define("block_damage_enabled", false);

            environmentDamageRadius = builder
                    .comment("Radius in blocks of terrain damage at the impact point. Only used when",
                            "block_damage_enabled is true.")
                    .defineInRange("damage_radius", 2.0D, 0.0D, 16.0D);

            environmentDamageStrength = builder
                    .comment("Terrain damage strength. Blocks whose explosion resistance exceeds this value survive.",
                            "Only used when block_damage_enabled is true.")
                    .defineInRange("damage_strength", 3.0D, 0.0D, 100.0D);

            environmentDropsItems = builder
                    .comment("Whether terrain destroyed by the impact drops items.")
                    .define("drops_items", true);

            builder.pop().push("effects");

            particleDensity = builder
                    .comment("Multiplier on the number of cosmetic particles clients may spawn.",
                            "1.0 is the tuned default. Lower it to reduce client and network cost.")
                    .defineInRange("particle_density", 1.0D, 0.0D, 3.0D);

            auraIntensity = builder
                    .comment("Multiplier on the caster body aura density. 0.0 disables the body aura.")
                    .defineInRange("aura_intensity", 1.0D, 0.0D, 3.0D);

            maxEffectDistance = builder
                    .comment("Maximum distance in blocks at which a client is told about, and renders, a cast.",
                            "Also the radius used to pick which players receive the cast packets.")
                    .defineInRange("max_effect_distance", 64.0D, 8.0D, 256.0D);

            maxSimultaneousEffects = builder
                    .comment("Hard cap on concurrently rendered Rasengan effects per client.",
                            "Casts beyond this cap are skipped visually; gameplay is unaffected.")
                    .defineInRange("max_simultaneous_effects", 8, 1, 64);

            builder.pop();
        }
    }

    // ---- Convenience accessors ----

    /** POWER BAR charge duration expressed in ticks. */
    public static int chargeDurationTicks() {
        return SERVER.chargeDurationSeconds.get() * 20;
    }

    public static double damage() {
        return SERVER.rasenganDamage.get();
    }

    public static double range() {
        return SERVER.abilityRange.get();
    }

    public static double hitboxSize() {
        return SERVER.hitboxSize.get();
    }

    public static int castDurationTicks() {
        return SERVER.castDurationTicks.get();
    }

    public static int cooldownTicks() {
        return SERVER.cooldownTicks.get();
    }

    public static boolean announceCast() {
        return SERVER.announceCast.get();
    }

    public static double maxEffectDistance() {
        return SERVER.maxEffectDistance.get();
    }
}
