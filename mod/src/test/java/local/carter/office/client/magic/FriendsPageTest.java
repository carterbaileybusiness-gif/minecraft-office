package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.screen.FriendsPage;
import local.carter.office.client.magic.screen.FriendsPage.Status;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.net.MagicPayloads.TravelRoster.Friend;
import local.carter.office.magic.net.MagicPayloads.TravelRoster.State;

/** Why a friend row is greyed (the brief's "travel off", "on cooldown" for you, "busy") and the paging. */
class FriendsPageTest {
	private static final Friend AVA = new Friend(UUID.randomUUID(), "Ava", true);
	private static final Friend BEN = new Friend(UUID.randomUUID(), "Ben", false);

	private static TravelRoster roster(State s, Friend waitingFor) {
		return new TravelRoster(List.of(AVA, BEN), s, Optional.ofNullable(waitingFor).map(Friend::id), 300);
	}

	@Test
	void readyRowsShowTheFriendsOwnSetting() {
		TravelRoster r = roster(State.READY, null);
		assertEquals(Status.AVAILABLE, FriendsPage.status(r, AVA, 0));
		assertEquals(Status.OFF, FriendsPage.status(r, BEN, 0));
	}

	@Test
	void yourCooldownGreysEveryone() {
		TravelRoster r = roster(State.READY, null);
		assertEquals(Status.COOLDOWN, FriendsPage.status(r, AVA, 40));
		assertEquals(Status.OFF, FriendsPage.status(r, BEN, 40), "your cooldown never hides their travel off");
	}

	@Test
	void anOpenRequestMarksItsFriendAndBusiesTheRest() {
		TravelRoster w = roster(State.WAITING, BEN);
		assertEquals(Status.WAITING, FriendsPage.status(w, BEN, 0));
		assertEquals(Status.ONE_AT_A_TIME, FriendsPage.status(w, AVA, 0));
		TravelRoster t = roster(State.TRAVELLING, AVA);
		assertEquals(Status.TRAVELLING, FriendsPage.status(t, AVA, 0));
		assertEquals(Status.ONE_AT_A_TIME, FriendsPage.status(t, BEN, 0));
		assertEquals(Status.BUSY, FriendsPage.status(roster(State.BUSY, null), AVA, 0));
		for (Status s : Status.values()) assertEquals(s == Status.AVAILABLE, s.available());
	}

	@Test
	void pagesOfEight() {
		assertEquals(1, FriendsPage.pages(0));
		assertEquals(1, FriendsPage.pages(8));
		assertEquals(2, FriendsPage.pages(9));
		assertEquals(3, FriendsPage.pages(17));
	}
}
