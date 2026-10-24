package local.carter.office.magic.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.LevelCurve;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A player's magic: bound element (empty = unbound), grimoire level (1–10) and xp toward the next level, the selected spell,
 * the Recall home (the only home store), per-spell cooldowns as absolute game times ({@code readyAt}) and whether friends may
 * ask to travel to this player ({@code travel}, default on; {@code /magic travel off|on}) and each spell's skill-tree rank
 * ({@code ranks}: spell id → {@code SpellRank.packed()}; keyed by spell, so every element keeps its own and a rebind and back
 * restores them). Immutable; change it with the {@code with…} copies and {@code MagicAttachments.set}.
 */
public record MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home,
		Map<Identifier, Long> readyAt, boolean travel, Map<Identifier, Integer> ranks) {
	/** Cap on cooldown entries carried over the network (there are 31 spells). */
	public static final int MAX_COOLDOWNS = 64;
	/** Cap on rank entries carried over the network (30 element spells have a tree). */
	public static final int MAX_RANKS = 64;

	public static final MagicData EMPTY = new MagicData(Optional.empty(), 1, 0, Optional.empty(), Optional.empty(), Map.of(), true,
			Map.of());

	private static final Codec<Element> ELEMENT_CODEC = Codec.STRING.comapFlatMap(
			k -> Element.byKey(k).map(DataResult::success).orElseGet(() -> DataResult.error(() -> "unknown element " + k)),
			Element::key);

	/** Cooldowns keyed by id strings; entries whose key is not a valid id are dropped instead of failing the whole map. */
	private static final Codec<Map<Identifier, Long>> READY_AT_CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(
			m -> {
				Map<Identifier, Long> out = new HashMap<>();
				m.forEach((k, v) -> {
					Identifier id = Identifier.tryParse(k);
					if (id != null) out.put(id, v);
				});
				return out;
			},
			m -> {
				Map<String, Long> out = new HashMap<>();
				m.forEach((k, v) -> out.put(k.toString(), v));
				return out;
			});

	/** Ranks keyed by id strings; entries whose key is not a valid id, or whose rank is out of range, are dropped. */
	private static final Codec<Map<Identifier, Integer>> RANKS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(
			m -> {
				Map<Identifier, Integer> out = new HashMap<>();
				m.forEach((k, v) -> {
					Identifier id = Identifier.tryParse(k);
					if (id != null && validRank(v)) out.put(id, v);
				});
				return out;
			},
			m -> {
				Map<String, Integer> out = new HashMap<>();
				m.forEach((k, v) -> out.put(k.toString(), v));
				return out;
			});

	/** Every field is lenient: a bad value falls back to that field's default and the other fields still load. */
	public static final Codec<MagicData> CODEC = RecordCodecBuilder.create(i -> i.group(
			ELEMENT_CODEC.lenientOptionalFieldOf("element").forGetter(MagicData::element),
			Codec.INT.lenientOptionalFieldOf("level", 1).forGetter(MagicData::level),
			Codec.INT.lenientOptionalFieldOf("xp", 0).forGetter(MagicData::xp),
			Identifier.CODEC.lenientOptionalFieldOf("selected").forGetter(MagicData::selected),
			GlobalPos.CODEC.lenientOptionalFieldOf("home").forGetter(MagicData::home),
			READY_AT_CODEC.lenientOptionalFieldOf("ready_at", Map.of()).forGetter(MagicData::readyAt),
			Codec.BOOL.lenientOptionalFieldOf("travel", true).forGetter(MagicData::travel),
			RANKS_CODEC.lenientOptionalFieldOf("ranks", Map.of()).forGetter(MagicData::ranks)
	).apply(i, MagicData::new));

	public static final StreamCodec<FriendlyByteBuf, MagicData> STREAM_CODEC = StreamCodec.of(MagicData::write, MagicData::read);

	public MagicData {
		level = Math.clamp(level, 1, LevelCurve.MAX_LEVEL);
		xp = Math.max(0, xp);
		readyAt = Map.copyOf(readyAt);
		ranks = Map.copyOf(ranks);
	}

	/** Travel requests accepted (the default), no ranks. */
	public MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home,
			Map<Identifier, Long> readyAt) {
		this(element, level, xp, selected, home, readyAt, true, Map.of());
	}

	/** No ranks. */
	public MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home,
			Map<Identifier, Long> readyAt, boolean travel) {
		this(element, level, xp, selected, home, readyAt, travel, Map.of());
	}

	public MagicData withElement(Optional<Element> e) { return new MagicData(e, level, xp, selected, home, readyAt, travel, ranks); }

	public MagicData withLevel(int l) { return new MagicData(element, l, xp, selected, home, readyAt, travel, ranks); }

	public MagicData withXp(int x) { return new MagicData(element, level, x, selected, home, readyAt, travel, ranks); }

	public MagicData withSelected(Optional<Identifier> s) { return new MagicData(element, level, xp, s, home, readyAt, travel, ranks); }

	public MagicData withHome(Optional<GlobalPos> h) { return new MagicData(element, level, xp, selected, h, readyAt, travel, ranks); }

	public MagicData withReadyAt(Map<Identifier, Long> r) { return new MagicData(element, level, xp, selected, home, r, travel, ranks); }

	public MagicData withTravel(boolean t) { return new MagicData(element, level, xp, selected, home, readyAt, t, ranks); }

	public MagicData withRanks(Map<Identifier, Integer> r) { return new MagicData(element, level, xp, selected, home, readyAt, travel, r); }

	/** {@code spell}'s skill-tree rank (rank 0 if it has none). */
	public SpellRank rank(Identifier spell) {
		Integer packed = ranks.get(spell);
		return packed == null ? SpellRank.none(spell) : SpellRank.unpack(spell, packed);
	}

	/** A copy with {@code r} stored for its spell (rank 0 removes the entry). */
	public MagicData withRank(SpellRank r) {
		Map<Identifier, Integer> m = new HashMap<>(ranks);
		if (r.rank() == 0) m.remove(r.spell());
		else m.put(r.spell(), r.packed());
		return withRanks(m);
	}

	/** A packed rank that unpacks to itself (rank 0–5, a fork picked exactly where one is due). */
	static boolean validRank(int packed) {
		if (packed < 0 || packed > 0x7F) return false;
		int rank = packed & 7, f3 = packed >> 3 & 3, f5 = packed >> 5 & 3;
		if (rank > SpellRank.MAX) return false;
		return (rank >= 3 ? f3 >= 1 && f3 <= 2 : f3 == 0) && (rank >= 5 ? f5 >= 1 && f5 <= 2 : f5 == 0);
	}

	/** A copy with {@code spell} ready again at game time {@code gameTime}. */
	public MagicData withReady(Identifier spell, long gameTime) {
		Map<Identifier, Long> m = new HashMap<>(readyAt);
		m.put(spell, gameTime);
		return withReadyAt(m);
	}

	/** Ticks until {@code spell} is ready at {@code gameTime} (0 = ready). */
	public long cooldownLeft(Identifier spell, long gameTime) {
		Long at = readyAt.get(spell);
		return at == null ? 0 : Math.max(0, at - gameTime);
	}

	private static void write(FriendlyByteBuf buf, MagicData d) {
		buf.writeVarInt(d.element.map(e -> e.ordinal() + 1).orElse(0));
		buf.writeVarInt(d.level);
		buf.writeVarInt(d.xp);
		buf.writeOptional(d.selected, (b, id) -> b.writeIdentifier(id));
		buf.writeOptional(d.home, (b, h) -> GlobalPos.STREAM_CODEC.encode(b, h));
		int n = Math.min(d.readyAt.size(), MAX_COOLDOWNS);
		buf.writeVarInt(n);
		int i = 0;
		for (Map.Entry<Identifier, Long> e : d.readyAt.entrySet()) {
			if (i++ == n) break;
			buf.writeIdentifier(e.getKey());
			buf.writeVarLong(e.getValue());
		}
		buf.writeBoolean(d.travel);
		int r = Math.min(d.ranks.size(), MAX_RANKS);
		buf.writeVarInt(r);
		int j = 0;
		for (Map.Entry<Identifier, Integer> e : d.ranks.entrySet()) {
			if (j++ == r) break;
			buf.writeIdentifier(e.getKey());
			buf.writeByte(e.getValue());
		}
	}

	private static MagicData read(FriendlyByteBuf buf) {
		int e = buf.readVarInt();
		Element[] all = Element.values();
		if (e < 0 || e > all.length) throw new DecoderException("bad element " + e);
		Optional<Element> element = e == 0 ? Optional.empty() : Optional.of(all[e - 1]);
		int level = buf.readVarInt();
		int xp = buf.readVarInt();
		Optional<Identifier> selected = buf.readOptional(FriendlyByteBuf::readIdentifier);
		Optional<GlobalPos> home = buf.readOptional(b -> GlobalPos.STREAM_CODEC.decode(b));
		int n = buf.readVarInt();
		if (n < 0 || n > MAX_COOLDOWNS) throw new DecoderException("too many cooldowns: " + n);
		Map<Identifier, Long> ready = new HashMap<>();
		for (int i = 0; i < n; i++) ready.put(buf.readIdentifier(), buf.readVarLong());
		boolean travel = buf.readBoolean();
		int r = buf.readVarInt();
		if (r < 0 || r > MAX_RANKS) throw new DecoderException("too many ranks: " + r);
		Map<Identifier, Integer> ranks = new HashMap<>();
		for (int i = 0; i < r; i++) {
			Identifier id = buf.readIdentifier();
			int packed = buf.readUnsignedByte();
			if (!validRank(packed)) throw new DecoderException("bad rank " + packed + " for " + id);
			ranks.put(id, packed);
		}
		return new MagicData(element, level, xp, selected, home, ready, travel, ranks);
	}
}
