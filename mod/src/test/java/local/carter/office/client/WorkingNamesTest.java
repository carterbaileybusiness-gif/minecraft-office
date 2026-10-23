package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.zoom.WorkingNames;
import local.carter.office.net.OfficePayloads.WorkingPlayers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

class WorkingNamesTest {
	@Test
	void workingNameGetsAGraySuffixAndTheOriginalIsUntouched() {
		MutableComponent name = Component.literal("Carter").withStyle(ChatFormatting.AQUA);
		Component shown = WorkingNames.decorate(name, true);
		assertEquals("Carter ⌨ working", shown.getString());
		assertEquals("Carter", name.getString(), "the entity's own name (used by chat) is not modified");
		Component suffix = shown.getSiblings().get(shown.getSiblings().size() - 1);
		assertEquals(" ⌨ working", suffix.getString());
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), suffix.getStyle().getColor());
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA), shown.getStyle().getColor(), "name keeps its colour");
	}

	@Test
	void notWorkingIsTheSameComponent() {
		Component name = Component.literal("Friend");
		assertSame(name, WorkingNames.decorate(name, false));
	}

	@Test
	void theBroadcastReplacesTheSet() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		WorkingNames.set(new WorkingPlayers(List.of(a, b)));
		assertTrue(WorkingNames.isWorking(a));
		assertTrue(WorkingNames.isWorking(b));
		WorkingNames.set(new WorkingPlayers(List.of(b)));
		assertFalse(WorkingNames.isWorking(a), "a stopped working");
		WorkingNames.set(new WorkingPlayers(List.of()));
		assertFalse(WorkingNames.isWorking(b));
	}
}
