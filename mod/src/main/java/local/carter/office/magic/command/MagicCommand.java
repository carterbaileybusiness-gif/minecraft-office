package local.carter.office.magic.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.core.ManaMath;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /magic level|mana|reset} (op 2). Task 5 adds {@code /magic undo}. */
public final class MagicCommand {
	private MagicCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("magic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("level").then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("level", IntegerArgumentType.integer(1, LevelCurve.MAX_LEVEL)).executes(ctx -> {
							ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
							int level = IntegerArgumentType.getInteger(ctx, "level");
							setLevel(p, level);
							ctx.getSource().sendSuccess(() -> Component.literal("Grimoire level of " + p.getName().getString() + " set to " + level), true);
							return 1;
						}))))
				.then(Commands.literal("mana").then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("mana", IntegerArgumentType.integer(0)).executes(ctx -> {
							ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
							MagicAttachments.setMana(p, IntegerArgumentType.getInteger(ctx, "mana"));
							int now = MagicAttachments.mana(p);
							ctx.getSource().sendSuccess(() -> Component.literal("Mana of " + p.getName().getString() + " set to " + now), true);
							return 1;
						}))))
				.then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
					ServerPlayer p = EntityArgument.getPlayer(ctx, "player");
					reset(p);
					ctx.getSource().sendSuccess(() -> Component.literal("Magic of " + p.getName().getString() + " reset"), true);
					return 1;
				}))));
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
