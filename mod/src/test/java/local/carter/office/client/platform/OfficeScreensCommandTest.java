package local.carter.office.client.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import local.carter.office.client.platform.OfficeScreensCommand.Action;

import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.Bootstrap;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * The Mac setup chat links: what a click sends (vanilla's {@code Screen.clickCommandAction} trims the slash and calls
 * {@code sendUnattendedCommand}, where Fabric runs client commands) must parse and run in the tree MacPlatform
 * registers, and typing the bare command must not be "Unknown or incomplete command".
 */
class OfficeScreensCommandTest {
	private final List<Action> ran = new ArrayList<>();
	private final CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap(); // Commands (trimOptionalPrefix, as the click uses it) touches the registries
	}

	OfficeScreensCommandTest() {
		dispatcher.register(OfficeScreensCommand.tree(ran::add));
	}

	/** Exactly what clicking the link hands to the command dispatcher. */
	private static String clicked(final Action action) {
		ClickEvent.RunCommand run = assertInstanceOf(ClickEvent.RunCommand.class, OfficeScreensCommand.click(action));
		return Commands.trimOptionalPrefix(run.command());
	}

	@Test
	void eachLinkRunsItsAction() throws CommandSyntaxException {
		for (Action action : Action.values()) {
			ran.clear();
			assertEquals(1, dispatcher.execute(clicked(action), new Object()));
			assertEquals(List.of(action), ran);
		}
	}

	@Test
	void linksUseTheRegisteredCommandName() {
		assertEquals("officescreens setup", clicked(Action.SETUP));
		assertEquals("officescreens retry", clicked(Action.RETRY));
		assertTrue(dispatcher.getRoot().getChild(OfficeScreensCommand.NAME) != null);
	}

	/** MacPlatform registers the tree through Fabric's client command callback (fired on every world join). */
	@Test
	void macPlatformRegistersTheLinksAsClientCommands() {
		new MacPlatform().register();
		CommandDispatcher<FabricClientCommandSource> client = new CommandDispatcher<>();
		ClientCommandRegistrationCallback.EVENT.invoker().register(client, null);
		for (Action action : Action.values()) {
			ParseResults<FabricClientCommandSource> parse = client.parse(clicked(action), null);
			assertTrue(parse.getExceptions().isEmpty() && !parse.getReader().canRead() && parse.getContext().getCommand() != null,
					clicked(action) + " parses to a runnable client command");
		}
		assertTrue(client.parse("officescreens", null).getContext().getCommand() != null, "bare /officescreens runs");
	}

	@Test
	void bareCommandRunsSetup() throws CommandSyntaxException {
		assertEquals(1, dispatcher.execute("officescreens", new Object()));
		assertEquals(List.of(Action.SETUP), ran);
	}
}
