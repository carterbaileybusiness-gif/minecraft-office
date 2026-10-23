package local.carter.office.client.platform;

import java.util.function.Consumer;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.network.chat.ClickEvent;

/**
 * {@code /officescreens [setup|retry]}: the client command behind the Mac setup chat links. It is a Fabric client
 * command, so a click runs it on this computer (Fabric intercepts {@code sendUnattendedCommand} before anything goes
 * to the server) — the same in singleplayer and on a dedicated server, which never knows the command. The bare
 * {@code /officescreens} runs setup too, so typing it is never "Unknown or incomplete command".
 */
public final class OfficeScreensCommand {
	public static final String NAME = "officescreens";

	public enum Action {
		/** Set up screens: install / start the helper, ask macOS for missing permissions, open the right pane. */
		SETUP("setup"),
		/** Check again: restart the helper so permissions granted meanwhile apply. */
		RETRY("retry");

		public final String literal;

		Action(final String literal) {
			this.literal = literal;
		}
	}

	private OfficeScreensCommand() {}

	/** The chat line's click: runs {@code /officescreens <action>} as a client command. */
	public static ClickEvent click(final Action action) {
		return new ClickEvent.RunCommand("/" + NAME + " " + action.literal);
	}

	/** The command tree, for {@code ClientCommandRegistrationCallback} (any source type, so tests can run it). */
	public static <S> LiteralArgumentBuilder<S> tree(final Consumer<Action> handler) {
		LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal(NAME).executes(ctx -> {
			handler.accept(Action.SETUP);
			return 1;
		});
		for (Action action : Action.values()) {
			root.then(LiteralArgumentBuilder.<S>literal(action.literal).executes(ctx -> {
				handler.accept(action);
				return 1;
			}));
		}
		return root;
	}
}
