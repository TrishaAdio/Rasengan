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

        // ---- Thrown projectile ----
        public final ModConfigSpec.DoubleValue projectileSpeed;
        public final ModConfigSpec.IntValue projectileLifetimeTicks;
        public final ModConfigSpec.DoubleValue projectileMaxRange;

        // ---- Environment ----
        public final ModConfigSpec.BooleanValue blockDamageEnabled;
        public final ModConfigSpec.DoubleValue environmentDamageRadius;
        public final ModConfigSpec.DoubleValue environmentDamageStrength;
        public final ModConfigSpec.BooleanValue environmentDropsItems;

        // ---- Rasen Shuriken ----
        public final ModConfigSpec.DoubleValue shurikenDamage;
        public final ModConfigSpec.DoubleValue shurikenSpeed;
        public final ModConfigSpec.DoubleValue shurikenHitboxSize;
        public final ModConfigSpec.DoubleValue shurikenMaxRange;
        public final ModConfigSpec.DoubleValue shurikenLaunchSpeed;
        public final ModConfigSpec.DoubleValue shurikenLaunchLift;
        public final ModConfigSpec.IntValue shurikenCastDurationTicks;

        // ---- Cosmetic caps (synced to clients) ----
        public final ModConfigSpec.DoubleValue particleDensity;
        public final ModConfigSpec.DoubleValue auraIntensity;
        public final ModConfigSpec.DoubleValue maxEffectDistance;
        public final ModConfigSpec.IntValue maxSimultaneousEffects;

        // ---- Summoning Jutsu ----
        public final ModConfigSpec.IntValue summonChargeDurationSeconds;
        public final ModConfigSpec.IntValue summonCinematicTicks;
        public final ModConfigSpec.IntValue summonRevealTick;
        public final ModConfigSpec.BooleanValue summonCameraEffect;
        public final ModConfigSpec.IntValue summonCameraReleaseTicks;
        public final ModConfigSpec.DoubleValue summonCameraRadius;
        public final ModConfigSpec.DoubleValue summonSmokeDensity;
        public final ModConfigSpec.DoubleValue summonSoundVolume;
        public final ModConfigSpec.BooleanValue summonAnnounceGlobally;
        public final ModConfigSpec.ConfigValue<String> summonLinesResource;

        // ---- Mount / ride ----
        public final ModConfigSpec.BooleanValue mountEnabled;
        public final ModConfigSpec.BooleanValue mountSummonerOnly;
        public final ModConfigSpec.IntValue mountDoubleTapWindowTicks;
        public final ModConfigSpec.IntValue mountLaunchTicks;
        public final ModConfigSpec.DoubleValue mountLaunchHeight;
        public final ModConfigSpec.DoubleValue mountMaxSpeed;
        public final ModConfigSpec.DoubleValue mountAcceleration;
        public final ModConfigSpec.DoubleValue mountTurnRateDegrees;
        public final ModConfigSpec.DoubleValue mountPitchRateDegrees;
        public final ModConfigSpec.DoubleValue mountBankLimitDegrees;
        public final ModConfigSpec.IntValue mountSpawnImmunitySeconds;
        public final ModConfigSpec.BooleanValue mountDismountFallDamage;

        // ---- Fear effect ----
        public final ModConfigSpec.BooleanValue fearEnabled;
        public final ModConfigSpec.DoubleValue fearRadius;
        public final ModConfigSpec.IntValue fearDurationTicks;
        public final ModConfigSpec.BooleanValue fearSuppressAttacks;
        public final ModConfigSpec.BooleanValue fearBossImmune;
        public final ModConfigSpec.BooleanValue fearPlayerVignette;

        // ---- Dragon mob ----
        public final ModConfigSpec.DoubleValue dragonHealth;
        public final ModConfigSpec.DoubleValue dragonArmour;
        public final ModConfigSpec.DoubleValue dragonKnockbackResistance;
        public final ModConfigSpec.DoubleValue dragonMovementSpeed;
        public final ModConfigSpec.DoubleValue dragonFlyingSpeed;
        public final ModConfigSpec.DoubleValue dragonAttackDamage;
        public final ModConfigSpec.DoubleValue dragonFollowRange;
        public final ModConfigSpec.BooleanValue dragonCanFly;
        public final ModConfigSpec.BooleanValue dragonBossBar;
        public final ModConfigSpec.BooleanValue dragonBreathEnabled;
        public final ModConfigSpec.DoubleValue dragonBreathDamage;
        public final ModConfigSpec.DoubleValue dragonBreathRange;
        public final ModConfigSpec.DoubleValue dragonBreathConeDegrees;
        public final ModConfigSpec.IntValue dragonBreathFireSeconds;
        public final ModConfigSpec.IntValue dragonBreathCooldownTicks;
        public final ModConfigSpec.IntValue dragonBreathDurationTicks;
        public final ModConfigSpec.BooleanValue dragonAggressive;

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

            builder.pop().push("projectile");

            projectileSpeed = builder
                    .comment("Launch speed of the thrown sphere, in blocks per tick.",
                            "0.85 is about 17 blocks/second - fast enough to read as a launched attack,",
                            "slow enough to watch the sphere spin in flight.",
                            "Collision is a continuous sweep, so high values cannot tunnel through targets.",
                            "",
                            "Note: the sphere always flies in a perfectly straight line. There are no",
                            "gravity or drag options, by design - nothing may bend its path.")
                    .defineInRange("speed", 0.85D, 0.1D, 10.0D);

            projectileLifetimeTicks = builder
                    .comment("Maximum ticks the sphere may stay airborne before it fizzles out (20 ticks = 1 second).")
                    .defineInRange("lifetime_ticks", 100, 5, 600);

            projectileMaxRange = builder
                    .comment("Maximum distance in blocks the sphere may travel before it fizzles out.")
                    .defineInRange("max_range", 64.0D, 4.0D, 256.0D);

            builder.pop().push("rasen_shuriken");

            shurikenDamage = builder
                    .comment("Damage of one direct Rasen Shuriken hit, in health points.",
                            "Default 60.0 = 30 hearts. Higher than Rasengan by design: it is the",
                            "more advanced technique and costs the same full POWER BAR.")
                    .defineInRange("damage", 60.0D, 0.0D, 10_000.0D);

            shurikenSpeed = builder
                    .comment("Flight speed in blocks per tick. Slightly faster than Rasengan,",
                            "but still slow enough to read the spinning blades.")
                    .defineInRange("speed", 1.05D, 0.1D, 10.0D);

            shurikenHitboxSize = builder
                    .comment("Radius in blocks of the swept hit volume. Larger than Rasengan's",
                            "because the blades visibly extend well past the core.")
                    .defineInRange("hitbox_size", 1.6D, 0.1D, 8.0D);

            shurikenMaxRange = builder
                    .comment("Maximum distance in blocks before it fizzles out.")
                    .defineInRange("max_range", 80.0D, 4.0D, 256.0D);

            shurikenLaunchSpeed = builder
                    .comment("Horizontal launch velocity applied to a struck entity, in blocks per tick.",
                            "",
                            "MEASURED, not derived: a target standing on the ground and launched at 20.5",
                            "lands about 143 blocks away (four headless trials: 142.03, 143.24, 143.24,",
                            "143.24; mean 142.94, spread 1.21). Airtime 47 ticks / 2.35 s, peak rise",
                            "20.02 blocks.",
                            "",
                            "The often-quoted figure of ~225 blocks is the AIR-FRICTION CONVERGENCE LIMIT",
                            "speed / (1 - 0.91) = 11.1x speed, which only applies to a target that never",
                            "touches the ground. A grounded target loses 45% of the launch on the very",
                            "first tick, because that tick uses BLOCK friction (0.6 * 0.91 = 0.546) rather",
                            "than air friction; measured first-tick ratio is exactly 0.5460, and every",
                            "tick after it is 0.9100. Reaching ~225 blocks for a grounded target would",
                            "need roughly 32.2 here. The default is deliberately left at 20.5 - changing",
                            "it is a gameplay decision, not a bug fix.",
                            "",
                            "Set to 0 with lift 0 to disable.")
                    .defineInRange("launch_speed", 20.5D, 0.0D, 100.0D);

            shurikenLaunchLift = builder
                    .comment("Upward launch velocity in blocks per tick. 2.0 gives roughly a 20 block",
                            "peak and about 2.3 seconds of airtime, so the target arcs up, forward, then down.")
                    .defineInRange("launch_lift", 2.0D, 0.0D, 10.0D);

            shurikenCastDurationTicks = builder
                    .comment("Ticks from activation to release for Rasen Shuriken (20 ticks = 1s).",
                            "MUST be greater than 70. The blades snap out at tick 70 (3.5 seconds) to",
                            "match the transition in the formation audio, so a shorter cast would launch",
                            "before the blades had formed. Default 100 = 5.0s: 3.5s forming, 1.5s held.")
                    .defineInRange("cast_duration_ticks", 100, 71, 400);

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

            builder.pop().push("dragon");

            builder.comment("The summon-only boss dragon. It does not spawn naturally.",
                    "This is a standalone hostile mob: it is not tamed, ridden, owned or bound to a",
                    "caster, and it takes and deals damage like any other boss.");

            dragonHealth = builder
                    .comment("Maximum health. Boss-tier: 300 = 150 hearts, roughly 1.5x the Ender Dragon.")
                    .defineInRange("health", 300.0D, 1.0D, 10_000.0D);

            dragonArmour = builder
                    .comment("Armour points. Reduces incoming damage without making it immune.")
                    .defineInRange("armour", 12.0D, 0.0D, 30.0D);

            dragonKnockbackResistance = builder
                    .comment("Knockback resistance, 0..1. High but not 1.0, so it still reacts to hits.")
                    .defineInRange("knockback_resistance", 0.8D, 0.0D, 1.0D);

            dragonMovementSpeed = builder
                    .comment("Ground movement speed attribute.")
                    .defineInRange("movement_speed", 0.25D, 0.0D, 2.0D);

            dragonFlyingSpeed = builder
                    .comment("Flight speed attribute, used while airborne.")
                    .defineInRange("flying_speed", 0.6D, 0.0D, 4.0D);

            dragonAttackDamage = builder
                    .comment("Melee bite damage in health points (2 = 1 heart).")
                    .defineInRange("attack_damage", 18.0D, 0.0D, 1_000.0D);

            dragonFollowRange = builder
                    .comment("How far away it will notice and pursue a target, in blocks.")
                    .defineInRange("follow_range", 48.0D, 1.0D, 256.0D);

            dragonCanFly = builder
                    .comment("Whether it flies. When false it still walks and fights, using ground pathing.")
                    .define("can_fly", true);

            dragonBossBar = builder
                    .comment("Show a boss bar to nearby players while it is alive.")
                    .define("boss_bar", true);

            dragonBreathEnabled = builder
                    .comment("Enable the ranged fire-breath attack in addition to the melee bite.")
                    .define("breath_enabled", true);

            dragonBreathDamage = builder
                    .comment("Damage per damaging tick of the breath, to each entity caught in the cone.")
                    .defineInRange("breath_damage", 4.0D, 0.0D, 1_000.0D);

            dragonBreathRange = builder
                    .comment("Reach of the breath cone, in blocks.")
                    .defineInRange("breath_range", 16.0D, 1.0D, 64.0D);

            dragonBreathConeDegrees = builder
                    .comment("Half-angle of the breath cone, in degrees. 25 gives a 50 degree spread.")
                    .defineInRange("breath_cone_degrees", 25.0D, 1.0D, 90.0D);

            dragonBreathFireSeconds = builder
                    .comment("Seconds of burning applied to entities caught in the breath.")
                    .defineInRange("breath_fire_seconds", 5, 0, 60);

            dragonBreathCooldownTicks = builder
                    .comment("Ticks between breath attacks (20 ticks = 1 second).")
                    .defineInRange("breath_cooldown_ticks", 140, 20, 6_000);

            dragonBreathDurationTicks = builder
                    .comment("How long one breath lasts, in ticks. Matched to the 2.0s firebreath",
                            "animation by default (40 ticks).")
                    .defineInRange("breath_duration_ticks", 40, 5, 200);

            dragonAggressive = builder
                    .comment("Whether it targets players on sight. When false it only retaliates.")
                    .define("aggressive", true);

            builder.pop().push("summoning");

            builder.comment("Summoning Jutsu: the cinematic that brings in the boss dragon.",
                    "Gated by its own independent POWER BAR - SUMMONING, separate from the cast bar,",
                    "so charging one does not charge the other.");

            summonChargeDurationSeconds = builder
                    .comment("Real seconds for the summoning bar to charge from 0% to 100%.",
                            "Default 300 seconds = 5 minutes: deliberately longer than the 150s cast",
                            "bar, because this arrives with a boss.")
                    .defineInRange("charge_duration_seconds", 300, 1, 86_400);

            summonCinematicTicks = builder
                    .comment("Total length of the summoning sequence in ticks (20 ticks = 1s).",
                            "Default 70 = 3.5s, which is the sum of the five stage durations:",
                            "  seal 0.6s + smoke eruption 1.0s + reveal 0.8s + dispersal 0.6s + settle 0.5s",
                            "Stage boundaries are derived from this and reveal_tick - see SummonTimeline.",
                            "Lengthening this stretches every stage proportionally rather than padding the end.")
                    .defineInRange("cinematic_ticks", SummonTimeline.REFERENCE_TOTAL_TICKS, 20, 400);

            summonRevealTick = builder
                    .comment("Tick at which the dragon becomes visible and speaks - the smoke-eruption",
                            "to silhouette-reveal boundary. Default 32 = 1.6s.",
                            "The dragon is SPAWNED at tick 0 and held hidden until here, so no entity or",
                            "model loading lands on the reveal frame. Must be less than cinematic_ticks.")
                    .defineInRange("reveal_tick", SummonTimeline.REFERENCE_REVEAL_TICK, 5, 399);

            summonCameraEffect = builder
                    .comment("Enable the cinematic camera move for the summoner and nearby players.",
                            "Set false to leave every camera completely alone - some players dislike any",
                            "forced camera movement. ALL smoke, seal and fog visuals still play; only the",
                            "roll, FOV, third-person pull-back and rumble are skipped.",
                            "Player look input is never captured or suppressed either way: the effect only",
                            "offsets the rendered camera, so there is nothing to flush on release.")
                    .define("camera_effect", true);

            summonCameraReleaseTicks = builder
                    .comment("Ticks over which the camera eases back to the player's own view at the end",
                            "of the sequence. Default 12 = 0.6s, on an ease-in-out curve.",
                            "Never a hard cut: a one-frame snap back to the real view reads as a lag",
                            "spike even when no frames were dropped.")
                    .defineInRange("camera_release_ticks", 12, 4, 40);

            summonCameraRadius = builder
                    .comment("Blocks within which an observer gets the cinematic camera move.",
                            "The effect fades to nothing over the last quarter of this distance rather",
                            "than switching off at the boundary, so walking across it cannot snap the view.")
                    .defineInRange("camera_radius", 64.0D, 4.0D, 256.0D);

            summonSmokeDensity = builder
                    .comment("Multiplier on summoning smoke particle counts. 1.0 is tuned to fully obscure",
                            "the summon at peak, which the reveal depends on - lowering it below about 0.6",
                            "will start to leave the dragon visible through the cloud.",
                            "The client's own particle setting scales down from here as well.")
                    .defineInRange("smoke_density", 1.0D, 0.1D, 3.0D);

            summonSoundVolume = builder
                    .comment("Volume multiplier for the summoning buildup and reveal sounds.")
                    .defineInRange("sound_volume", 1.0D, 0.0D, 2.0D);

            summonAnnounceGlobally = builder
                    .comment("If true the dragon's awakening line goes to every online player.",
                            "If false only players within max_effect_distance hear it.")
                    .define("announce_globally", true);

            summonLinesResource = builder
                    .comment("Which awakening-lines data file to read, as namespace:path under",
                            "data/<namespace>/summon_lines/<path>.json. Server owners can edit the",
                            "shipped file, override it from a datapack, or point this at their own.",
                            "/reload picks up changes without a restart.")
                    .define("lines_resource", "rasengan:awakening");

            builder.pop().push("mount");

            builder.comment("Riding the summoned dragon.",
                    "The rider STANDS on the dragon's head rather than sitting in a saddle, and steers",
                    "elytra-style with their look direction. Every value here is enforced server-side:",
                    "the server reads the rider's replicated input, computes the resulting velocity and",
                    "rotation itself, and syncs the result. A modified client cannot move the dragon.");

            mountEnabled = builder
                    .comment("Allow the summoned dragon to be ridden at all.",
                            "False leaves it as a pure standalone boss.")
                    .define("enabled", true);

            mountSummonerOnly = builder
                    .comment("Only the player who summoned the dragon may mount it.",
                            "Setting this false lets ANY player mount any summoned dragon - there is no",
                            "trusted-player list yet, so false means everyone. Leave it true unless you",
                            "specifically want a free-for-all.")
                    .define("summoner_only", true);

            mountDoubleTapWindowTicks = builder
                    .comment("Maximum ticks between the two W presses of the launch double-tap.",
                            "Default 6 ticks = 300ms. Detected server-side from the rider's replicated",
                            "input, so it cannot be spoofed by a client claiming 'I double-tapped'.")
                    .defineInRange("double_tap_window_ticks", 6, 2, 20);

            mountLaunchTicks = builder
                    .comment("Length of the launch climb in ticks (20 = 1 second).",
                            "The dragon eases upward over this window rather than snapping to a",
                            "velocity. Default 30 = 1.5 seconds.")
                    .defineInRange("launch_ticks", 30, 5, 200);

            mountLaunchHeight = builder
                    .comment("Blocks of altitude the launch climb gains before handing over to the",
                            "player's steering.")
                    .defineInRange("launch_height", 22.0D, 2.0D, 200.0D);

            mountMaxSpeed = builder
                    .comment("Top speed under player control, in blocks per tick.",
                            "0.9 b/t = 18 blocks/second, comfortably faster than the AI cruise of ~0.51.")
                    .defineInRange("max_speed", 0.9D, 0.05D, 4.0D);

            mountAcceleration = builder
                    .comment("Fraction of the gap to target speed closed each tick, 0..1.",
                            "This is a smoothing factor, not a binary throttle: 0.08 gives a noticeably",
                            "heavy spool-up and coast-down. Higher is twitchier.")
                    .defineInRange("acceleration", 0.08D, 0.005D, 1.0D);

            mountTurnRateDegrees = builder
                    .comment("Maximum yaw change per tick, in degrees. This is the turn-rate limit that",
                            "makes steering feel weighty instead of snapping to the look direction.")
                    .defineInRange("turn_rate_degrees", 4.5D, 0.25D, 90.0D);

            mountPitchRateDegrees = builder
                    .comment("Maximum pitch change per tick, in degrees.")
                    .defineInRange("pitch_rate_degrees", 3.5D, 0.25D, 90.0D);

            mountBankLimitDegrees = builder
                    .comment("Maximum bank (roll) angle during a turn, in degrees. Cosmetic: it tilts",
                            "the model, and deliberately does NOT tilt the rider, who stays upright.")
                    .defineInRange("bank_limit_degrees", 32.0D, 0.0D, 80.0D);

            mountSpawnImmunitySeconds = builder
                    .comment("Seconds after being summoned during which the dragon takes NO damage from",
                            "any source, and stays grounded at the summon point.",
                            "Implemented as a real check in the damage path, not a health buffer.",
                            "Launching cuts the grounded hold short but does NOT shorten the immunity.")
                    .defineInRange("spawn_immunity_seconds", 10, 0, 300);

            mountDismountFallDamage = builder
                    .comment("Whether a rider who dismounts in mid-air takes normal fall damage.",
                            "True (default) means dismounting at altitude is exactly as dangerous as",
                            "stepping off any other high place. False grants immunity for the fall.")
                    .define("dismount_fall_damage", true);

            builder.pop().push("fear");

            builder.comment("How nearby hostile mobs react to the dragon's arrival.",
                    "Applied server-side on the reveal beat: affected mobs get a temporary",
                    "high-priority flee goal aimed at the dragon, so they actually run away rather",
                    "than merely being slowed. Players are never force-moved by any setting here.");

            fearEnabled = builder
                    .comment("Enable the fear reaction entirely.")
                    .define("enabled", true);

            fearRadius = builder
                    .comment("Blocks from the dragon within which hostile mobs are frightened.")
                    .defineInRange("radius", 24.0D, 1.0D, 128.0D);

            fearDurationTicks = builder
                    .comment("How long a frightened mob flees, in ticks (20 ticks = 1 second).",
                            "Default 140 = 7 seconds. The temporary goal is removed when this expires,",
                            "when the dragon dies or despawns, or when the mob is unloaded.")
                    .defineInRange("duration_ticks", 140, 10, 2_400);

            fearSuppressAttacks = builder
                    .comment("Also suppress the mob's targeting while it flees, so it does not turn and",
                            "fight mid-retreat. Restored when the fear expires.")
                    .define("suppress_attacks", true);

            fearBossImmune = builder
                    .comment("Exempt bosses and other dragons. With this false, two dragons will flee",
                            "from each other.")
                    .define("boss_immune", true);

            fearPlayerVignette = builder
                    .comment("Give nearby players a brief dark screen-edge vignette pulse on the reveal.",
                            "Purely cosmetic - it never affects movement, input or control. Set false to",
                            "remove it without touching the mob behaviour.")
                    .define("player_vignette", true);

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

    public static double damage(dev.rasengan.AbilityType ability) {
        return ability.isShuriken() ? SERVER.shurikenDamage.get() : SERVER.rasenganDamage.get();
    }

    public static double hitboxSize(dev.rasengan.AbilityType ability) {
        return ability.isShuriken() ? SERVER.shurikenHitboxSize.get() : SERVER.hitboxSize.get();
    }

    public static double projectileSpeed(dev.rasengan.AbilityType ability) {
        return ability.isShuriken() ? SERVER.shurikenSpeed.get() : SERVER.projectileSpeed.get();
    }

    public static double projectileMaxRange(dev.rasengan.AbilityType ability) {
        return ability.isShuriken() ? SERVER.shurikenMaxRange.get() : SERVER.projectileMaxRange.get();
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

    /** Summoning bar charge duration in ticks. Independent of the cast bar. */
    public static int summonChargeDurationTicks() {
        return SERVER.summonChargeDurationSeconds.get() * 20;
    }

    /** Reveal tick, clamped below the total so a misconfiguration cannot skip the reveal. */
    public static int summonRevealTick() {
        return Math.min(SERVER.summonRevealTick.get(), SERVER.summonCinematicTicks.get() - 1);
    }

    /**
     * The five-stage layout for the current configuration.
     *
     * <p>Both sides call this, so the server's reveal beat and the client's smoke stages cannot
     * disagree. See {@link SummonTimeline}.
     */
    public static SummonTimeline.Stages summonStages() {
        return SummonTimeline.of(SERVER.summonCinematicTicks.get(), summonRevealTick());
    }

    /** Spawn damage-immunity window in ticks. */
    public static int mountSpawnImmunityTicks() {
        return SERVER.mountSpawnImmunitySeconds.get() * 20;
    }

    public static int castDurationTicks(dev.rasengan.AbilityType ability) {
        return ability.isShuriken()
                ? SERVER.shurikenCastDurationTicks.get()
                : SERVER.castDurationTicks.get();
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

    public static double projectileSpeed() {
        return SERVER.projectileSpeed.get();
    }

    public static int projectileLifetimeTicks() {
        return SERVER.projectileLifetimeTicks.get();
    }

    public static double projectileMaxRange() {
        return SERVER.projectileMaxRange.get();
    }
}
