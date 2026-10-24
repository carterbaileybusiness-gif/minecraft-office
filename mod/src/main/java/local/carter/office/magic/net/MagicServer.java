package local.carter.office.magic.net;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.cast.ElementChoice;
import local.carter.office.magic.cast.Travel;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.item.GrimoireItem;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicPayloads.CancelCharge;
import local.carter.office.magic.net.MagicPayloads.CastFx;
import local.carter.office.magic.net.MagicPayloads.ChooseElement;
import local.carter.office.magic.net.MagicPayloads.FxCancel;
import local.carter.office.magic.net.MagicPayloads.OpenGrimoire;
import local.carter.office.magic.net.MagicPayloads.SelectSpell;
import local.carter.office.magic.net.MagicPayloads.SignChallenge;
import local.carter.office.magic.net.MagicPayloads.TravelRequest;
import local.carter.office.magic.net.MagicPayloads.TravelRoster;
import local.carter.office.magic.net.MagicPayloads.TravelRosterQuery;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellKit;
import local.carter.office.magic.spell.SpellRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Server-side magic glue: the active {@link MagicSender}, the world {@link SpellKit}, and the client-to-server receivers. */
public final class MagicServer {
	/** Cancels reach this far around the caster (the longest {@code CastFx} range). */
	public static final double CANCEL_RANGE = 128;
	/** {@code SelectSpell} packets handled per player per second (20 ticks); the rest are ignored. */
	public static final int SELECTS_PER_SECOND = 20;

	private static final MagicSender NETWORK = new NetworkSender();
	private static @Nullable MagicSender testSender;
	private static SpellKit kit = SpellKit.NONE;
	/** Player → {second (server tick / 20), SelectSpell packets handled in it}. Server thread only. */
	private static final Map<UUID, long[]> SELECTS = new HashMap<>();

	private MagicServer() {}

	public static MagicSender sender() {
		MagicSender s = testSender;
		return s != null ? s : NETWORK;
	}

	/** The real network sender, whatever is installed (test recorders forward events they don't own to it). */
	public static MagicSender network() {
		return NETWORK;
	}

	/** GameTests only: route every send through {@code s} (null restores the network sender). */
	public static void setSenderForTesting(@Nullable MagicSender s) {
		testSender = s;
	}

	public static SpellKit kit() {
		return kit;
	}

	/** Task 5 installs the world kit on server start; {@link SpellKit#NONE} refuses every edit. */
	public static void setKit(SpellKit k) {
		kit = k;
	}

	public static void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(ChooseElement.TYPE, (p, ctx) -> chooseElement(ctx.player(), p.element()));
		ServerPlayNetworking.registerGlobalReceiver(SelectSpell.TYPE, (p, ctx) -> receiveSelect(ctx.player(), p.spell()));
		ServerPlayNetworking.registerGlobalReceiver(CancelCharge.TYPE, (p, ctx) -> CastService.cancelCharge(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(TravelRequest.TYPE, (p, ctx) -> {
			Travel.request(ctx.player(), p.target());
			Travel.query(ctx.player()); // the page shows the outcome (throttled like any roster query)
		});
		ServerPlayNetworking.registerGlobalReceiver(TravelRosterQuery.TYPE, (p, ctx) -> Travel.query(ctx.player()));
	}

	/**
	 * The {@code ChooseElement} receiver. A rebind that a Rebinding Seal opened (a seal still in either hand) spends one seal and
	 * rebinds; otherwise a first binding needs a grimoire in either hand (the choice screen is opened from one) and no element.
	 */
	public static boolean chooseElement(ServerPlayer p, Element e) {
		if (ElementChoice.rebind(p, e)) return true;
		boolean holding = GrimoireItem.isGrimoire(p.getMainHandItem()) || GrimoireItem.isGrimoire(p.getOffhandItem());
		return holding && ElementChoice.choose(p, e);
	}

	/**
	 * The {@code SelectSpell} receiver: at most {@link #SELECTS_PER_SECOND} per player each second are handled ({@link #select});
	 * the rest are ignored, so a flood cannot make the server re-sync the player's data and re-stamp the grimoire every packet.
	 */
	public static boolean receiveSelect(ServerPlayer p, Identifier spell) {
		long second = p.level().getServer().getTickCount() / 20;
		long[] window = SELECTS.computeIfAbsent(p.getUUID(), id -> new long[] {second, 0});
		if (window[0] != second) {
			window[0] = second;
			window[1] = 0;
		}
		if (++window[1] > SELECTS_PER_SECOND) return false;
		return select(p, spell);
	}

	/** A player left (or the server stops, with {@code null}): forget their select throttle. */
	public static void forget(@Nullable ServerPlayer p) {
		if (p == null) SELECTS.clear();
		else SELECTS.remove(p.getUUID());
	}

	/** Selects {@code spell} if it is one of the player's unlocked spells; anything else is ignored. */
	public static boolean select(ServerPlayer p, Identifier spell) {
		MagicData d = MagicAttachments.get(p);
		for (Spell s : SpellRegistry.unlocked(p.getUUID(), d)) {
			if (s.id().equals(spell)) {
				if (!d.selected().equals(Optional.of(spell))) MagicAttachments.set(p, d.withSelected(Optional.of(spell)));
				return true;
			}
		}
		return false;
	}

	/** Sends payloads to players whose client understands them; mock and vanilla players are skipped. */
	private static final class NetworkSender implements MagicSender {
		private static void send(ServerPlayer p, CustomPacketPayload payload) {
			if (p.connection != null && ServerPlayNetworking.canSend(p, payload.type())) ServerPlayNetworking.send(p, payload);
		}

		private static Set<ServerPlayer> around(ServerLevel level, ServerPlayer caster, double range) {
			Set<ServerPlayer> to = new LinkedHashSet<>(PlayerLookup.around(level, caster.position(), range));
			to.add(caster);
			return to;
		}

		@Override
		public void fx(ServerLevel level, FxEvent e, double range) {
			Set<ServerPlayer> to = new LinkedHashSet<>(PlayerLookup.around(level, e.origin(), range));
			if (level.getEntity(e.casterId()) instanceof ServerPlayer caster) to.add(caster);
			CastFx payload = new CastFx(e);
			for (ServerPlayer p : to) send(p, payload);
		}

		@Override
		public void cancelFx(ServerPlayer caster) {
			FxCancel payload = new FxCancel(caster.getId());
			for (ServerPlayer p : around(caster.level(), caster, CANCEL_RANGE)) send(p, payload);
		}

		@Override
		public void challenge(ServerPlayer p, SignChallenge c) {
			send(p, c);
		}

		@Override
		public void open(ServerPlayer p, OpenGrimoire.Mode m) {
			send(p, new OpenGrimoire(m));
		}

		@Override
		public void actionBar(ServerPlayer p, Component c) {
			if (p.connection != null) p.sendSystemMessage(c, true);
		}

		@Override
		public void title(ServerPlayer p, Component title, Component subtitle) {
			if (p.connection == null) return;
			p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
			p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			p.connection.send(new ClientboundSetTitleTextPacket(title));
		}

		@Override
		public void chat(ServerPlayer p, Component c) {
			if (p.connection != null) p.sendSystemMessage(c);
		}

		@Override
		public void roster(ServerPlayer p, TravelRoster r) {
			send(p, r);
		}
	}
}
