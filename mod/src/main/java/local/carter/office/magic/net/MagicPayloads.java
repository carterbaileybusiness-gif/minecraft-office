package local.carter.office.magic.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.Element;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Magic payloads (same style as {@code net.OfficePayloads}). Server-to-client ones go out through {@link MagicSender};
 * client-to-server receivers live in {@link MagicServer#registerReceivers()} (SignAnswer: {@code MagicMod}).
 */
public final class MagicPayloads {
	/** Most arrows a hand-sign answer may carry. */
	public static final int MAX_SIGNS = 6;
	/** Most players a {@link TravelRoster} lists. */
	public static final int MAX_FRIENDS = 128;
	/** Longest player name a {@link TravelRoster} carries (vanilla names are at most 16). */
	public static final int MAX_NAME = 16;

	private MagicPayloads() {}

	/** {@code readEnum} that rejects an out-of-range ordinal with a {@link DecoderException}. */
	static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> type) {
		int i = buf.readVarInt();
		E[] all = type.getEnumConstants();
		if (i < 0 || i >= all.length) throw new DecoderException("bad " + type.getSimpleName() + " ordinal " + i);
		return all[i];
	}

	// ---- Server to client ----

	/** Opens the element choice (unbound) or the grimoire info screen. */
	public record OpenGrimoire(Mode mode) implements CustomPacketPayload {
		public enum Mode { CHOOSE, INFO }

		public static final Type<OpenGrimoire> TYPE = new Type<>(OfficeMod.id("open_grimoire"));
		public static final StreamCodec<FriendlyByteBuf, OpenGrimoire> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeEnum(p.mode),
				buf -> new OpenGrimoire(readEnum(buf, Mode.class)));

		@Override
		public Type<OpenGrimoire> type() {
			return TYPE;
		}
	}

	/** One cast's visuals. */
	public record CastFx(FxEvent event) implements CustomPacketPayload {
		public static final Type<CastFx> TYPE = new Type<>(OfficeMod.id("cast_fx"));
		public static final StreamCodec<FriendlyByteBuf, CastFx> CODEC = FxEvent.STREAM_CODEC.map(CastFx::new, CastFx::event);

		@Override
		public Type<CastFx> type() {
			return TYPE;
		}
	}

	/** Stop the lasting visuals of {@code casterId}'s cast (an interrupted Recall channel). */
	public record FxCancel(int casterId) implements CustomPacketPayload {
		public static final Type<FxCancel> TYPE = new Type<>(OfficeMod.id("fx_cancel"));
		public static final StreamCodec<FriendlyByteBuf, FxCancel> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeVarInt(p.casterId),
				buf -> new FxCancel(buf.readVarInt()));

		@Override
		public Type<FxCancel> type() {
			return TYPE;
		}
	}

	/** Hand-sign prompt: the client rebuilds the arrows with {@code HandSigns.sequence(seed, length)}. */
	public record SignChallenge(int id, Identifier spell, long seed, int length, int limitTicks) implements CustomPacketPayload {
		public static final Type<SignChallenge> TYPE = new Type<>(OfficeMod.id("sign_challenge"));
		public static final StreamCodec<FriendlyByteBuf, SignChallenge> CODEC = StreamCodec.of(
				(buf, p) -> {
					if (p.length < 1 || p.length > MAX_SIGNS) throw new EncoderException("bad sign length " + p.length);
					buf.writeVarInt(p.id);
					buf.writeIdentifier(p.spell);
					buf.writeLong(p.seed);
					buf.writeVarInt(p.length);
					buf.writeVarInt(p.limitTicks);
				},
				buf -> {
					int id = buf.readVarInt();
					Identifier spell = buf.readIdentifier();
					long seed = buf.readLong();
					int length = buf.readVarInt();
					if (length < 1 || length > MAX_SIGNS) throw new DecoderException("bad sign length " + length);
					return new SignChallenge(id, spell, seed, length, buf.readVarInt());
				});

		/** @throws IllegalArgumentException unless {@code length} is 1..{@link #MAX_SIGNS} (caught where it is made, not in the pipeline) */
		public SignChallenge {
			if (length < 1 || length > MAX_SIGNS) throw new IllegalArgumentException("bad sign length " + length);
		}

		@Override
		public Type<SignChallenge> type() {
			return TYPE;
		}
	}

	/**
	 * The grimoire's Friends page: every other online player (name order, at most {@link #MAX_FRIENDS}) with whether they accept
	 * travel requests, and the viewer's own travel state ({@code waitingFor}/{@code ticksLeft} only while {@link State#WAITING}).
	 * The viewer's Recall cooldown is not here: the client reads it from its own synced {@code MagicData}.
	 */
	public record TravelRoster(List<Friend> friends, State state, Optional<UUID> waitingFor, int ticksLeft) implements CustomPacketPayload {
		/** READY: can ask; WAITING: a request is open; TRAVELLING: a travel channel runs; BUSY: another channel, hand signs or a charge. */
		public enum State { READY, WAITING, TRAVELLING, BUSY }

		public record Friend(UUID id, String name, boolean accepts) {
			public Friend {
				if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
			}
		}

		public static final TravelRoster EMPTY = new TravelRoster(List.of(), State.READY, Optional.empty(), 0);
		public static final Type<TravelRoster> TYPE = new Type<>(OfficeMod.id("travel_roster"));
		public static final StreamCodec<FriendlyByteBuf, TravelRoster> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.friends.size());
					for (Friend f : p.friends) {
						buf.writeUUID(f.id());
						buf.writeUtf(f.name(), MAX_NAME);
						buf.writeBoolean(f.accepts());
					}
					buf.writeEnum(p.state);
					buf.writeOptional(p.waitingFor, (b, id) -> b.writeUUID(id));
					buf.writeVarInt(p.ticksLeft);
				},
				buf -> {
					int n = buf.readVarInt();
					if (n < 0 || n > MAX_FRIENDS) throw new DecoderException("too many friends: " + n);
					List<Friend> friends = new ArrayList<>(n);
					for (int i = 0; i < n; i++) friends.add(new Friend(buf.readUUID(), buf.readUtf(MAX_NAME), buf.readBoolean()));
					State state = readEnum(buf, State.class);
					Optional<UUID> waiting = buf.readOptional(b -> b.readUUID());
					return new TravelRoster(friends, state, waiting, buf.readVarInt());
				});

		/** Keeps at most {@link #MAX_FRIENDS} friends (so every instance encodes). */
		public TravelRoster {
			friends = List.copyOf(friends.size() > MAX_FRIENDS ? friends.subList(0, MAX_FRIENDS) : friends);
			ticksLeft = Math.max(0, ticksLeft);
		}

		@Override
		public Type<TravelRoster> type() {
			return TYPE;
		}
	}

	// ---- Client to server ----

	/** Ask to travel to {@code target} (a click on the Friends page). */
	public record TravelRequest(UUID target) implements CustomPacketPayload {
		public static final Type<TravelRequest> TYPE = new Type<>(OfficeMod.id("travel_request"));
		public static final StreamCodec<FriendlyByteBuf, TravelRequest> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeUUID(p.target),
				buf -> new TravelRequest(buf.readUUID()));

		@Override
		public Type<TravelRequest> type() {
			return TYPE;
		}
	}

	/** The Friends page is showing: send me a {@link TravelRoster}. */
	public record TravelRosterQuery() implements CustomPacketPayload {
		public static final TravelRosterQuery INSTANCE = new TravelRosterQuery();
		public static final Type<TravelRosterQuery> TYPE = new Type<>(OfficeMod.id("travel_roster_query"));
		public static final StreamCodec<FriendlyByteBuf, TravelRosterQuery> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<TravelRosterQuery> type() {
			return TYPE;
		}
	}

	public record ChooseElement(Element element) implements CustomPacketPayload {
		public static final Type<ChooseElement> TYPE = new Type<>(OfficeMod.id("choose_element"));
		public static final StreamCodec<FriendlyByteBuf, ChooseElement> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeEnum(p.element),
				buf -> new ChooseElement(readEnum(buf, Element.class)));

		@Override
		public Type<ChooseElement> type() {
			return TYPE;
		}
	}

	public record SelectSpell(Identifier spell) implements CustomPacketPayload {
		public static final Type<SelectSpell> TYPE = new Type<>(OfficeMod.id("select_spell"));
		public static final StreamCodec<FriendlyByteBuf, SelectSpell> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeIdentifier(p.spell),
				buf -> new SelectSpell(buf.readIdentifier()));

		@Override
		public Type<SelectSpell> type() {
			return TYPE;
		}
	}

	/** The arrows typed for challenge {@code id} as {@link Dir} ordinals (at most {@link #MAX_SIGNS}; a partial answer is a miss). */
	public record SignAnswer(int id, byte[] dirs) implements CustomPacketPayload {
		public static final Type<SignAnswer> TYPE = new Type<>(OfficeMod.id("sign_answer"));
		public static final StreamCodec<FriendlyByteBuf, SignAnswer> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.id);
					buf.writeByteArray(p.dirs);
				},
				buf -> new SignAnswer(buf.readVarInt(), buf.readByteArray(MAX_SIGNS)));

		public SignAnswer {
			if (dirs.length > MAX_SIGNS) throw new IllegalArgumentException("too many signs: " + dirs.length);
			dirs = dirs.clone();
		}

		public static SignAnswer of(int id, Dir... dirs) {
			byte[] b = new byte[dirs.length];
			for (int i = 0; i < dirs.length; i++) b[i] = (byte) dirs[i].ordinal();
			return new SignAnswer(id, b);
		}

		@Override
		public byte[] dirs() { return dirs.clone(); }

		/** The arrows, or null if any byte is not a {@link Dir} ordinal. */
		public Dir @Nullable [] toDirs() {
			Dir[] all = Dir.values();
			Dir[] out = new Dir[dirs.length];
			for (int i = 0; i < dirs.length; i++) {
				if (dirs[i] < 0 || dirs[i] >= all.length) return null;
				out[i] = all[dirs[i]];
			}
			return out;
		}

		@Override
		public Type<SignAnswer> type() {
			return TYPE;
		}
	}

	/** Registers the payload types (both sides). */
	public static void register() {
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(OpenGrimoire.TYPE, OpenGrimoire.CODEC);
		s2c.register(CastFx.TYPE, CastFx.CODEC);
		s2c.register(FxCancel.TYPE, FxCancel.CODEC);
		s2c.register(SignChallenge.TYPE, SignChallenge.CODEC);
		s2c.register(TravelRoster.TYPE, TravelRoster.CODEC);

		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(ChooseElement.TYPE, ChooseElement.CODEC);
		c2s.register(SelectSpell.TYPE, SelectSpell.CODEC);
		c2s.register(SignAnswer.TYPE, SignAnswer.CODEC);
		c2s.register(TravelRequest.TYPE, TravelRequest.CODEC);
		c2s.register(TravelRosterQuery.TYPE, TravelRosterQuery.CODEC);
	}
}
