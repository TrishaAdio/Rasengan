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
 *   <li>{@code /chargeit} - charges the caller's cast bar. Requires the caller to be in Creative
 *       mode. Available to everyone, no permission level needed.</li>
 *   <li>{@code /chargeit <player>} - charges someone else's cast bar. Requires permission level 2
 *       (operator), and the <em>target</em> must be in Creative mode.</li>
 *   <li>{@code /chargeit summon} - the same, for POWER BAR - SUMMONING.</li>
 *   <li>{@code /chargeit summon <player>} - the same, for someone else.</li>
 * </ul>
 *
 * <p>The two bars are independent, so the two forms are too: {@code /chargeit} never touches the
 * summoning bar and {@code /chargeit summon} never touches the cast bar. Mirroring the gating rather
 * than inventing a second command keeps one rule to remember - Creative for yourself, gamemaster plus
 * a Creative target for anyone else.
 *
 * <h2>One grammar wrinkle, recorded deliberately</h2>
 * {@code summon} is a literal node sitting beside the {@code <player>} argument node, and Brigadier
 * matches literals first. A player actually named "summon" therefore cannot be targeted as
 * {@code /chargeit summon}; {@code /chargeit summon summon} still reaches them. Accepted as the
 * cheaper trade against a clumsier grammar like {@code /chargeit bar <cast|summon>}.
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
                .executes(context -> chargeSelf(context.getSource(), Bar.CAST))
                // Summoning bar, same two forms and the same gating.
                .then(Commands.literal("summon")
                        .executes(context -> chargeSelf(context.getSource(), Bar.SUMMON))
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(Commands.hasPermission(OP_CHECK))
                                .executes(context -> chargeOther(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "target"),
                                        Bar.SUMMON))))
                // Target form: operator only.
                .then(Commands.argument("target", EntityArgument.player())
                        .requires(Commands.hasPermission(OP_CHECK))
                        .executes(context -> chargeOther(
                                context.getSource(),
                                EntityArgument.getPlayer(context, "target"),
                                Bar.CAST)));

        dispatcher.register(root);
    }

    /** Which of the two independent bars a given invocation targets. */
    private enum Bar {
        CAST("POWER BAR"),
        SUMMON("POWER BAR \u2014 SUMMONING");

        private final String label;

        Bar(String label) {
            this.label = label;
        }
    }

    // ------------------------------------------------------------------

    private static int chargeSelf(CommandSourceStack source, Bar bar) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        if (!player.isCreative()) {
            source.sendFailure(Component.literal("/chargeit is Creative mode only.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!fill(source, player, bar)) {
            return 0;
        }

        source.sendSuccess(() -> Component.literal(bar.label + " charged to 100%.")
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int chargeOther(CommandSourceStack source, ServerPlayer target, Bar bar) {
        // The Creative requirement follows the player being charged, not the operator running
        // the command, so this cannot be used to hand a survival player a free cast.
        if (!target.isCreative()) {
            source.sendFailure(Component.literal(
                            target.getGameProfile().name() + " is not in Creative mode.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!fill(source, target, bar)) {
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Charged " + target.getGameProfile().name()
                        + "'s " + bar.label + " to 100%.")
                .withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    /** Routes to the right bar, reporting failure through {@code source} rather than silently. */
    private static boolean fill(CommandSourceStack source, ServerPlayer player, Bar bar) {
        if (bar == Bar.CAST) {
            fillCast(player);
            return true;
        }
        return fillSummon(source, player);
    }

    /**
     * Drives the bar to a full, castable state through the same fields the normal charge tick
     * uses, so the result is indistinguishable from having waited out the timer.
     *
     * <p>Cooldown is cleared too: otherwise charging during a COOLDOWN window would leave the
     * state machine in COOLDOWN with a full bar, which {@code tryActivate} would reject.
     */
    private static void fillCast(ServerPlayer player) {
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

    /**
     * The same, for POWER BAR - SUMMONING.
     *
     * <p>Deliberately <b>refuses</b> mid-cinematic rather than cancelling the sequence, which is the
     * one place this differs from {@link #fillCast}. Cancelling would leave every nearby client
     * drawing a summoning circle that never produces a dragon, and the timeline would drive the bar
     * back to CHARGING at the end of the sequence anyway - so the granted charge would silently
     * evaporate a few ticks later. The sequence is 5 seconds; waiting it out is the honest answer.
     *
     * <p>It also does not bypass the one-dragon-per-player rule, because that rule is about the
     * dragon's life rather than the bar. Charging while a dragon is alive is allowed - it just leaves
     * the player holding a full bar that the summon will still refuse, so that is said out loud
     * instead of leaving them to wonder why the key does nothing.
     */
    private static boolean fillSummon(CommandSourceStack source, ServerPlayer player) {
        if (ServerSummonManager.isPending(player)) {
            source.sendFailure(Component.literal(
                            "A summoning is already in progress for "
                                    + player.getGameProfile().name() + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        PowerData data = ServerSummonManager.data(player);
        data.setChargeTicks(RasenganConfig.summonChargeDurationTicks());
        data.setCooldownTicks(0);
        data.setState(PowerState.READY);
        data.markDirty();
        ServerSummonManager.sync(player, data);

        if (ServerSummonManager.hasLiveDragon(player)) {
            source.sendSuccess(() -> Component.literal(
                            player.getGameProfile().name()
                                    + " already has a dragon, so the summon will still be refused.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return true;
    }
}
