package local.carter.office.magic.net;

import io.netty.handler.codec.DecoderException;
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
 * client-to-server receivers live in {@link MagicServer#registerReceivers()} (SignAnswer's arrives with Task 6).
 */
public final class MagicPayloads {
	/** Most arrows a hand-sign answer may carry. */
	public static final int MAX_SIGNS = 6;

	private MagicPayloads() {}

	// ---- Server to client ----

	/** Opens the element choice (unbound) or the grimoire info screen. */
	public record OpenGrimoire(Mode mode) implements CustomPacketPayload {
		public enum Mode { CHOOSE, INFO }

		public static final Type<OpenGrimoire> TYPE = new Type<>(OfficeMod.id("open_grimoire"));
		public static final StreamCodec<FriendlyByteBuf, OpenGrimoire> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeEnum(p.mode),
				buf -> new OpenGrimoire(buf.readEnum(Mode.class)));

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

		@Override
		public Type<SignChallenge> type() {
			return TYPE;
		}
	}

	// ---- Client to server ----

	public record ChooseElement(Element element) implements CustomPacketPayload {
		public static final Type<ChooseElement> TYPE = new Type<>(OfficeMod.id("choose_element"));
		public static final StreamCodec<FriendlyByteBuf, ChooseElement> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeEnum(p.element),
				buf -> new ChooseElement(buf.readEnum(Element.class)));

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

		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(ChooseElement.TYPE, ChooseElement.CODEC);
		c2s.register(SelectSpell.TYPE, SelectSpell.CODEC);
		c2s.register(SignAnswer.TYPE, SignAnswer.CODEC);
	}
}
