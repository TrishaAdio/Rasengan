package dev.rasengan.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.rasengan.Palette;
import dev.rasengan.Rasengan;
import dev.rasengan.RasenganEntities;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /spawn <mob>} - spawns one of this mod's custom mobs at the caller's position.
 *
 * <h2>Permission</h2>
 * Gated at {@link Commands#LEVEL_GAMEMASTERS}, the same tier the existing
 * {@code /chargeit <player>} form uses and the tier vanilla puts {@code /summon} behind. This is an
 * admin convenience, not a player-facing ability, and the dragon is a boss.
 *
 * <h2>Relationship to /summon</h2>
 * This is purely a shorthand. {@code /summon rasengan:dragon} keeps working on its own because the
 * entity type is registered normally; nothing here is required for that path. Registering a
 * shorthand does not replace or wrap the vanilla command.
 */
public final class SpawnCommand {

    /** Permission tier required. Matches {@code /chargeit <player>} and vanilla {@code /summon}. */
    private static final PermissionCheck OP_CHECK = Commands.LEVEL_GAMEMASTERS;

    /**
     * The mod's spawnable custom mobs, by short name.
     *
     * <p>Ordered and declared in one place so tab-completion and the spawn lookup cannot disagree:
     * adding a future mob here is all that is needed for it to appear in completions.
     */
    private static final Map<String, EntityType<?>> MOBS = new LinkedHashMap<>();

    static {
        MOBS.put("dragon", RasenganEntities.DRAGON.get());
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MOBS =
            (context, builder) -> SharedSuggestionProvider.suggest(MOBS.keySet(), builder);

    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("spawn")
                .requires(Commands.hasPermission(OP_CHECK))
                .then(Commands.argument("mob", StringArgumentType.word())
                        .suggests(SUGGEST_MOBS)
                        .executes(context -> spawn(
                                context.getSource(),
                                StringArgumentType.getString(context, "mob"))));
        dispatcher.register(root);
    }

    private static int spawn(CommandSourceStack source, String name) {
        EntityType<?> type = MOBS.get(name.toLowerCase(java.util.Locale.ROOT));
        if (type == null) {
            source.sendFailure(Component.literal("Unknown mob '" + name + "'. Available: "
                    + String.join(", ", MOBS.keySet())));
            return 0;
        }
        if (!(source.getLevel() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal("This command must be run in a world."));
            return 0;
        }

        Vec3 at = source.getPosition();
        Entity spawned = type.create(level, EntitySpawnReason.COMMAND);
        if (spawned == null) {
            source.sendFailure(Component.literal("Failed to create entity " + name + "."));
            return 0;
        }
        spawned.snapTo(at.x, at.y, at.z, source.getRotation().y, 0.0F);

        // ---- a command-spawned dragon belongs to whoever spawned it ----
        //
        // Without this it had NO summoner, and since mounting is summoner-only that made every dragon
        // spawned this way permanently unrideable - the refusal being "this dragon answers only to the one
        // who called it", about a dragon nobody had called. /spawn dragon is the obvious way to try the
        // mount, so this was the first thing a player would hit and the least informative way to fail.
        //
        // The grace is started too, so a command-spawned dragon behaves exactly like a summoned one from
        // the moment it appears rather than being a subtly different object.
        if (spawned instanceof DragonEntity dragon
                && source.getEntity() instanceof ServerPlayer player) {
            dragon.setSummoner(player.getUUID());
            dragon.beginSpawnGrace();
        }

        if (!level.addFreshEntity(spawned)) {
            source.sendFailure(Component.literal("The world refused to accept the entity."));
            return 0;
        }

        source.sendSuccess(() -> Component.empty()
                .append(Component.literal("Spawned ")
                        .withStyle(style -> style.withColor(Palette.CORE)))
                .append(Component.literal(name)
                        .withStyle(style -> style.withColor(Palette.CYAN).withBold(true))), true);
        return 1;
    }

    /** Exposed so other code can enumerate the mod's spawnable mobs without duplicating the list. */
    public static java.util.Set<String> mobNames() {
        return java.util.Collections.unmodifiableSet(MOBS.keySet());
    }
}
