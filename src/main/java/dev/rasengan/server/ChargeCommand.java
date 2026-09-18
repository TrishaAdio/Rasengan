package dev.rasengan.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.rasengan.PowerState;
import dev.rasengan.RasenganConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /chargeit} - instantly fills the executing player's POWER BAR.
 *
 * <h2>Server-authoritative by construction</h2>
 * There is no packet and no client involvement at all here. Brigadier commands are parsed and
 * executed on the server, and the game mode is read from {@link ServerPlayer#isCreative()} on the
 * server's own copy of the player. A modified client cannot claim to be in Creative, because it is
 * never asked - the only thing it sends is the command text.
 *
 * <h2>Forms</h2>
 * <ul>
 *   <li>{@code /chargeit} - charges the caller. Requires the caller to be in Creative mode.
 *       Available to everyone, no permission level needed.</li>
 *   <li>{@code /chargeit <player>} - charges someone else. Requires permission level 2
 *       (operator), and the <em>target</em> must be in Creative mode.</li>
 * </ul>
 */
public final class ChargeCommand {

    /**
     * Permission needed to charge a <em>different</em> player.
     *
     * <p>26.1 replaced integer permission levels with named {@link PermissionCheck}s.
     * {@code LEVEL_GAMEMASTERS} is the tier vanilla uses for {@code /gamemode}, which is the
     * closest analogue to the old "operator, level 2".
     */
    private static final PermissionCheck OP_CHECK = Commands.LEVEL_GAMEMASTERS;

    private ChargeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chargeit")
                // Self form: no permission requirement, gated on Creative instead.
                .executes(context -> chargeSelf(context.getSource()))
                // Target form: operator only.
                .then(Commands.argument("target", EntityArgument.player())
                        .requires(Commands.hasPermission(OP_CHECK))
                        .executes(context -> chargeOther(
                                context.getSource(),
                                EntityArgument.getPlayer(context, "target"))));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------

    private static int chargeSelf(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        if (!player.isCreative()) {
            source.sendFailure(Component.literal("/chargeit is Creative mode only.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        fill(player);
        source.sendSuccess(() -> Component.literal("POWER BAR charged to 100%.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int chargeOther(CommandSourceStack source, ServerPlayer target) {
        // The Creative requirement follows the player being charged, not the operator running
        // the command, so this cannot be used to hand a survival player a free cast.
        if (!target.isCreative()) {
            source.sendFailure(Component.literal(
                            target.getGameProfile().name() + " is not in Creative mode.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        fill(target);
        source.sendSuccess(() -> Component.literal(
                        "Charged " + target.getGameProfile().name() + "'s POWER BAR to 100%.")
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /**
     * Drives the bar to a full, castable state through the same fields the normal charge tick
     * uses, so the result is indistinguishable from having waited out the timer.
     *
     * <p>Cooldown is cleared too: otherwise charging during a COOLDOWN window would leave the
     * state machine in COOLDOWN with a full bar, which {@code tryActivate} would reject.
     */
    private static void fill(ServerPlayer player) {
        PowerData data = ServerPowerManager.data(player);

        // Any in-flight cast is cancelled first; otherwise the player would sit in CASTING with
        // a full bar and the next activation would be refused.
        ServerCastManager.cancel(player);

        data.setChargeTicks(RasenganConfig.chargeDurationTicks());
        data.setCooldownTicks(0);
        data.setState(PowerState.READY);
        data.markDirty();

        // Push immediately so the HUD jumps to 100% on the next frame instead of waiting for
        // the one-second heartbeat.
        ServerPowerManager.sync(player, data);
    }
}
