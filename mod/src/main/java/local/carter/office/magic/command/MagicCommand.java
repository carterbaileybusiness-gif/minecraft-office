package local.carter.office.magic.command;

import java.util.Collection;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import local.carter.office.magic.cast.Travel;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.terrain.EditLog;
import local.carter.office.magic.terrain.EditLog.UndoReport;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

/** {@code /magic level|mana|reset|undo} (op 2) and {@code /magic travel …} (any player). */
public final class MagicCommand {
	private MagicCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("magic")
				.then(travel())
				.then(Commands.literal("level").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("level", IntegerArgumentType.integer(1, LevelCurve.MAX_LEVEL)).executes(ctx -> {
							ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
							int level = IntegerArgumentType.getInteger(ctx, "level");
							setLevel(p, level);
							ctx.getSource().sendSuccess(() -> Component.literal("Grimoire level of " + p.getName().getString() + " set to " + level), true);
							return 1;
						}))))
				.then(Commands.literal("mana").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("mana", IntegerArgumentType.integer(0)).executes(ctx -> {
							ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
							MagicAttachments.setMana(p, IntegerArgumentType.getInteger(ctx, "mana"));
							int now = MagicAttachments.mana(p);
							ctx.getSource().sendSuccess(() -> Component.literal("Mana of " + p.getName().getString() + " set to " + now), true);
							return 1;
						}))))
				.then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
					ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
					reset(p);
					ctx.getSource().sendSuccess(() -> Component.literal("Magic of " + p.getName().getString() + " reset"), true);
					return 1;
				})))
				.then(Commands.literal("undo").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.translatable("magic.office.undo_help"), false);
							return 1;
						})
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(ctx -> undo(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player"), 1))
								.then(Commands.argument("n", IntegerArgumentType.integer(1, EditLog.KEEP))
										.executes(ctx -> undo(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player"),
												IntegerArgumentType.getInteger(ctx, "n")))))));
	}

	/**
	 * {@code /magic travel on|off} (any player: whether friends may ask to travel to you) and {@code /magic travel accept|deny <id>}
	 * (the buttons of a travel request line).
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> travel() {
		return Commands.literal("travel")
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					ctx.getSource().sendSuccess(() -> Component.translatable(MagicAttachments.get(p).travel()
							? "magic.office.travel_state_on" : "magic.office.travel_state_off"), false);
					return 1;
				})
				.then(Commands.literal("on").executes(ctx -> {
					Travel.setAccepting(ctx.getSource().getPlayerOrException(), true);
					return 1;
				}))
				.then(Commands.literal("off").executes(ctx -> {
					Travel.setAccepting(ctx.getSource().getPlayerOrException(), false);
					return 1;
				}))
				.then(Commands.literal("accept").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx ->
						Travel.accept(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "id")) == Travel.Answer.STARTED ? 1 : 0)))
				.then(Commands.literal("deny").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx ->
						Travel.deny(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "id")) == Travel.Answer.DENIED ? 1 : 0)));
	}

	private static int undo(CommandSourceStack source, Collection<NameAndId> players, int n) {
		UndoReport total = new UndoReport(0, 0, 0);
		for (NameAndId p : players) total = total.plus(undo(source.getServer(), p.id(), n));
		UndoReport r = total;
		source.sendSuccess(() -> Component.translatable("magic.office.undo_done", r.restored(), r.casts(), r.skipped()), true);
		return r.casts();
	}

	/**
	 * Undoes {@code player}'s last {@code n} terrain casts (newest first; blocks changed since are left alone). Through the
	 * world kit when installed (its queued changes for the player are dropped and pending logs written first), else from disk.
	 */
	public static UndoReport undo(MinecraftServer server, UUID player, int n) {
		if (MagicServer.kit().terrain() instanceof WorldTerrainEdits edits) return edits.undo(player, n);
		return EditLog.forServer(server).undo(server, player, n);
	}

	/** Sets the grimoire level (xp toward the next level cleared). */
	public static void setLevel(ServerPlayer p, int level) {
		MagicData d = MagicAttachments.get(p);
		MagicAttachments.set(p, d.withLevel(level).withXp(0));
	}

	/** Back to a fresh, unbound level-1 grimoire with full mana (home and cooldowns cleared too). */
	public static void reset(ServerPlayer p) {
		MagicAttachments.set(p, MagicData.EMPTY);
		MagicAttachments.setMana(p, ManaMath.max(1));
	}
}
