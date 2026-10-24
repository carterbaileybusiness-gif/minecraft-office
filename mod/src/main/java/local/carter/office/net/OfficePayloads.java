package local.carter.office.net;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

/**
 * Hologram and screen-stream payloads. Client-to-server hologram edits are applied in {@link HologramActions}
 * (owner-only); the stream payloads are handled by {@code stream.StreamService}.
 */
public final class OfficePayloads {
	public static final int MAX_WINDOW_KEY = 512;
	public static final int MAX_ALLOWED = 64;
	/** Max characters of a monitor id (CGDisplay UUID string on a Mac, a device name such as {@code \\.\DISPLAY2} on Windows). */
	public static final int MAX_MONITOR_ID = 64;
	/** Max bytes of a stream datagram carried in a fallback payload (either direction). */
	public static final int MAX_FALLBACK_DATAGRAM = 30 * 1024;

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

	/**
	 * Owner-only: which real monitor a Mirror copies ({@code display} = its opaque id; null = the main display). Its own
	 * payload rather than an {@link UpdateHologram} field, so an older server never sees a longer packet it can't decode;
	 * clients send it only when the server registered it. Monitor names never travel, only the id.
	 */
	public record SetMirrorDisplay(BlockPos pos, @Nullable String display) implements CustomPacketPayload {
		public static final Type<SetMirrorDisplay> TYPE = new Type<>(OfficeMod.id("set_mirror_display"));
		public static final StreamCodec<FriendlyByteBuf, SetMirrorDisplay> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBlockPos(p.pos);
					buf.writeNullable(p.display, (b, s) -> b.writeUtf(s, MAX_MONITOR_ID));
				},
				buf -> new SetMirrorDisplay(buf.readBlockPos(), buf.readNullable(b -> b.readUtf(MAX_MONITOR_ID))));

		@Override
		public Type<SetMirrorDisplay> type() {
			return TYPE;
		}
	}

	/** Max players listed in one {@link WorkingPlayers}. */
	public static final int MAX_WORKING = 1024;

	/**
	 * Server to every client: the players currently zoomed in and working (sent on every change and on join). Clients
	 * show "⌨ working" after those names on name tags and in the tab list; nothing on the server (teams, scoreboard)
	 * changes.
	 */
	public record WorkingPlayers(List<UUID> players) implements CustomPacketPayload {
		public static final Type<WorkingPlayers> TYPE = new Type<>(OfficeMod.id("working_players"));
		public static final StreamCodec<FriendlyByteBuf, WorkingPlayers> CODEC = StreamCodec.of(
				(buf, p) -> {
					List<UUID> list = p.players.size() > MAX_WORKING ? p.players.subList(0, MAX_WORKING) : p.players;
					buf.writeVarInt(list.size());
					for (UUID id : list) {
						buf.writeUUID(id);
					}
				},
				buf -> {
					int n = buf.readVarInt();
					if (n < 0 || n > MAX_WORKING) {
						throw new DecoderException("too many working players: " + n);
					}
					List<UUID> list = new ArrayList<>(n);
					for (int i = 0; i < n; i++) {
						list.add(buf.readUUID());
					}
					return new WorkingPlayers(List.copyOf(list));
				});

		public WorkingPlayers {
			players = List.copyOf(players);
		}

		@Override
		public Type<WorkingPlayers> type() {
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

	// ---- Screen streams (Task 13). Datagram format: core.StreamHeader + body; see stream.StreamRelayServer. ----

	/**
	 * S2C on join: this session's relay token (append to every UDP datagram; HELLO = header(HELLO) + token) and the
	 * relay's UDP port on the game server's host. {@code udpPort == 0}: no UDP, use the fallback payloads.
	 */
	public record StreamToken(long token, int udpPort) implements CustomPacketPayload {
		public static final Type<StreamToken> TYPE = new Type<>(OfficeMod.id("stream_token"));
		public static final StreamCodec<FriendlyByteBuf, StreamToken> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeLong(p.token);
					buf.writeVarInt(p.udpPort);
				},
				buf -> new StreamToken(buf.readLong(), buf.readVarInt()));

		@Override
		public Type<StreamToken> type() {
			return TYPE;
		}
	}

	/** S2C to an owner: start ({@code wanted}) or stop sending hologram {@code hologramId} as stream {@code streamId}. */
	public record StreamDemand(UUID hologramId, int streamId, boolean wanted) implements CustomPacketPayload {
		public static final Type<StreamDemand> TYPE = new Type<>(OfficeMod.id("stream_demand"));
		public static final StreamCodec<FriendlyByteBuf, StreamDemand> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUUID(p.hologramId);
					buf.writeVarInt(p.streamId);
					buf.writeBoolean(p.wanted);
				},
				buf -> new StreamDemand(buf.readUUID(), buf.readVarInt(), buf.readBoolean()));

		@Override
		public Type<StreamDemand> type() {
			return TYPE;
		}
	}

	/** S2C to a viewer: datagrams with {@code streamId} carry hologram {@code hologramId}'s screen. */
	public record StreamStart(int streamId, UUID hologramId) implements CustomPacketPayload {
		public static final Type<StreamStart> TYPE = new Type<>(OfficeMod.id("stream_start"));
		public static final StreamCodec<FriendlyByteBuf, StreamStart> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.streamId);
					buf.writeUUID(p.hologramId);
				},
				buf -> new StreamStart(buf.readVarInt(), buf.readUUID()));

		@Override
		public Type<StreamStart> type() {
			return TYPE;
		}
	}

	/** S2C to a viewer: stream {@code streamId} stopped (private, out of range, owner offline, removed). Clear it. */
	public record StreamEnded(int streamId) implements CustomPacketPayload {
		public static final Type<StreamEnded> TYPE = new Type<>(OfficeMod.id("stream_ended"));
		public static final StreamCodec<FriendlyByteBuf, StreamEnded> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeVarInt(p.streamId),
				buf -> new StreamEnded(buf.readVarInt()));

		@Override
		public Type<StreamEnded> type() {
			return TYPE;
		}
	}

	/** S2C to a viewer without UDP: one relayed datagram (header + body, no token), exactly as UDP would carry it. */
	public record StreamFallbackTile(int streamId, byte[] datagram) implements CustomPacketPayload {
		public static final Type<StreamFallbackTile> TYPE = new Type<>(OfficeMod.id("stream_fallback_tile"));
		public static final StreamCodec<FriendlyByteBuf, StreamFallbackTile> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeVarInt(p.streamId);
					buf.writeByteArray(p.datagram);
				},
				buf -> new StreamFallbackTile(buf.readVarInt(), buf.readByteArray(MAX_FALLBACK_DATAGRAM)));

		@Override
		public Type<StreamFallbackTile> type() {
			return TYPE;
		}
	}

	/** C2S from an owner without UDP: one datagram (header + body, no token, at most 30 KiB). */
	public record FallbackTile(byte[] datagram) implements CustomPacketPayload {
		public static final Type<FallbackTile> TYPE = new Type<>(OfficeMod.id("fallback_tile"));
		public static final StreamCodec<FriendlyByteBuf, FallbackTile> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeByteArray(p.datagram),
				buf -> new FallbackTile(buf.readByteArray(MAX_FALLBACK_DATAGRAM)));

		@Override
		public Type<FallbackTile> type() {
			return TYPE;
		}
	}

	/** C2S: whether this client got a HELLO_ACK (false = none within 5 s: send it viewer datagrams as payloads). */
	public record UdpStatus(boolean ok) implements CustomPacketPayload {
		public static final Type<UdpStatus> TYPE = new Type<>(OfficeMod.id("udp_status"));
		public static final StreamCodec<FriendlyByteBuf, UdpStatus> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeBoolean(p.ok),
				buf -> new UdpStatus(buf.readBoolean()));

		@Override
		public Type<UdpStatus> type() {
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

	/** Registers the payload types (both sides). */
	public static void register() {
		var s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(StreamToken.TYPE, StreamToken.CODEC);
		s2c.register(StreamDemand.TYPE, StreamDemand.CODEC);
		s2c.register(StreamStart.TYPE, StreamStart.CODEC);
		s2c.register(StreamEnded.TYPE, StreamEnded.CODEC);
		s2c.register(StreamFallbackTile.TYPE, StreamFallbackTile.CODEC);
		s2c.register(WorkingPlayers.TYPE, WorkingPlayers.CODEC);

		var c2s = PayloadTypeRegistry.serverboundPlay();
		c2s.register(FallbackTile.TYPE, FallbackTile.CODEC);
		c2s.register(UdpStatus.TYPE, UdpStatus.CODEC);
		c2s.register(ResizeHologram.TYPE, ResizeHologram.CODEC);
		c2s.register(UpdateHologram.TYPE, UpdateHologram.CODEC);
		c2s.register(MoveHologram.TYPE, MoveHologram.CODEC);
		c2s.register(SetWorking.TYPE, SetWorking.CODEC);
		c2s.register(RemoveHologram.TYPE, RemoveHologram.CODEC);
		c2s.register(SetMirrorDisplay.TYPE, SetMirrorDisplay.CODEC);
	}
}
