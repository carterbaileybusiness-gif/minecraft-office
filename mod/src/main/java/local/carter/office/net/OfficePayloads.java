package local.carter.office.net;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import io.netty.handler.codec.DecoderException;
import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.core.Mode;
import local.carter.office.core.Privacy;
import local.carter.office.core.Shape;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Client-to-server hologram payloads. The server applies them in {@link HologramActions} (owner-only). */
public final class OfficePayloads {
	public static final int MAX_WINDOW_KEY = 512;
	public static final int MAX_ALLOWED = 64;

	private OfficePayloads() {}

	public record ResizeHologram(BlockPos pos, double width) implements CustomPacketPayload {
		public static final Type<ResizeHologram> TYPE = new Type<>(OfficeMod.id("resize_hologram"));
		public static final StreamCodec<FriendlyByteBuf, ResizeHologram> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBlockPos(p.pos);
					buf.writeDouble(p.width);
				},
				buf -> new ResizeHologram(buf.readBlockPos(), buf.readDouble()));

		@Override
		public Type<ResizeHologram> type() {
			return TYPE;
		}
	}

	/** {@code windowKey} may be null (Extend mode). */
	public record UpdateHologram(BlockPos pos, Shape shape, Mode mode, Privacy privacy, int displaySlot,
			@Nullable String windowKey) implements CustomPacketPayload {
		public static final Type<UpdateHologram> TYPE = new Type<>(OfficeMod.id("update_hologram"));
		public static final StreamCodec<FriendlyByteBuf, UpdateHologram> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBlockPos(p.pos);
					buf.writeEnum(p.shape);
					buf.writeEnum(p.mode);
					writePrivacy(buf, p.privacy);
					buf.writeVarInt(p.displaySlot);
					buf.writeNullable(p.windowKey, (b, s) -> b.writeUtf(s, MAX_WINDOW_KEY));
				},
				buf -> new UpdateHologram(buf.readBlockPos(), buf.readEnum(Shape.class), buf.readEnum(Mode.class),
						readPrivacy(buf), buf.readVarInt(), buf.readNullable(b -> b.readUtf(MAX_WINDOW_KEY))));

		@Override
		public Type<UpdateHologram> type() {
			return TYPE;
		}
	}

	public record MoveHologram(BlockPos pos, double offX, double offY, double offZ, float yaw) implements CustomPacketPayload {
		public static final Type<MoveHologram> TYPE = new Type<>(OfficeMod.id("move_hologram"));
		public static final StreamCodec<FriendlyByteBuf, MoveHologram> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBlockPos(p.pos);
					buf.writeDouble(p.offX);
					buf.writeDouble(p.offY);
					buf.writeDouble(p.offZ);
					buf.writeFloat(p.yaw);
				},
				buf -> new MoveHologram(buf.readBlockPos(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

		@Override
		public Type<MoveHologram> type() {
			return TYPE;
		}
	}

	/** Sent by the zoom controller: true while the player is zoomed in and working on their computer. */
	public record SetWorking(boolean working) implements CustomPacketPayload {
		public static final Type<SetWorking> TYPE = new Type<>(OfficeMod.id("set_working"));
		public static final StreamCodec<FriendlyByteBuf, SetWorking> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBoolean(p.working),
				buf -> new SetWorking(buf.readBoolean()));

		@Override
		public Type<SetWorking> type() {
			return TYPE;
		}
	}

	/** Settings-screen "Remove": owner-only; breaks the emitter and returns it to the owner's inventory. */
	public record RemoveHologram(BlockPos pos) implements CustomPacketPayload {
		public static final Type<RemoveHologram> TYPE = new Type<>(OfficeMod.id("remove_hologram"));
		public static final StreamCodec<FriendlyByteBuf, RemoveHologram> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBlockPos(p.pos),
				buf -> new RemoveHologram(buf.readBlockPos()));

		@Override
		public Type<RemoveHologram> type() {
			return TYPE;
		}
	}

	static void writePrivacy(final FriendlyByteBuf buf, final Privacy privacy) {
		buf.writeEnum(privacy.level());
		buf.writeVarInt(privacy.allowed().size());
		for (UUID u : privacy.allowed()) {
			buf.writeUUID(u);
		}
	}

	static Privacy readPrivacy(final FriendlyByteBuf buf) {
		Privacy.Level level = buf.readEnum(Privacy.Level.class);
		int n = buf.readVarInt();
		if (n < 0 || n > MAX_ALLOWED) {
			throw new DecoderException("too many allowed viewers: " + n);
		}
		Set<UUID> allowed = new HashSet<>();
		for (int i = 0; i < n; i++) {
			allowed.add(buf.readUUID());
		}
		return new Privacy(level, allowed);
	}

	/** Registers the C2S payload types (both sides). */
	public static void register() {
		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(ResizeHologram.TYPE, ResizeHologram.CODEC);
		c2s.register(UpdateHologram.TYPE, UpdateHologram.CODEC);
		c2s.register(MoveHologram.TYPE, MoveHologram.CODEC);
		c2s.register(SetWorking.TYPE, SetWorking.CODEC);
		c2s.register(RemoveHologram.TYPE, RemoveHologram.CODEC);
	}
}
