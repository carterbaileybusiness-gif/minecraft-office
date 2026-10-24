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

import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A player's magic: bound element (empty = unbound), grimoire level (1–10) and xp toward the next level, the selected spell,
 * the Recall home (the only home store) and per-spell cooldowns as absolute game times ({@code readyAt}).
 * Immutable; change it with the {@code with…} copies and {@code MagicAttachments.set}.
 */
public record MagicData(Optional<Element> element, int level, int xp, Optional<Identifier> selected, Optional<GlobalPos> home,
		Map<Identifier, Long> readyAt) {
	/** Cap on cooldown entries carried over the network (there are 31 spells). */
	public static final int MAX_COOLDOWNS = 64;

	public static final MagicData EMPTY = new MagicData(Optional.empty(), 1, 0, Optional.empty(), Optional.empty(), Map.of());

	private static final Codec<Element> ELEMENT_CODEC = Codec.STRING.comapFlatMap(
			k -> Element.byKey(k).map(DataResult::success).orElseGet(() -> DataResult.error(() -> "unknown element " + k)),
			Element::key);

	public static final Codec<MagicData> CODEC = RecordCodecBuilder.create(i -> i.group(
			ELEMENT_CODEC.lenientOptionalFieldOf("element").forGetter(MagicData::element),
			Codec.INT.optionalFieldOf("level", 1).forGetter(MagicData::level),
			Codec.INT.optionalFieldOf("xp", 0).forGetter(MagicData::xp),
			Identifier.CODEC.lenientOptionalFieldOf("selected").forGetter(MagicData::selected),
			GlobalPos.CODEC.lenientOptionalFieldOf("home").forGetter(MagicData::home),
			Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("ready_at", Map.of()).forGetter(MagicData::readyAt)
	).apply(i, MagicData::new));

	public static final StreamCodec<FriendlyByteBuf, MagicData> STREAM_CODEC = StreamCodec.of(MagicData::write, MagicData::read);

	public MagicData {
		level = Math.clamp(level, 1, LevelCurve.MAX_LEVEL);
		xp = Math.max(0, xp);
		readyAt = Map.copyOf(readyAt);
	}

	public MagicData withElement(Optional<Element> e) { return new MagicData(e, level, xp, selected, home, readyAt); }

	public MagicData withLevel(int l) { return new MagicData(element, l, xp, selected, home, readyAt); }

	public MagicData withXp(int x) { return new MagicData(element, level, x, selected, home, readyAt); }

	public MagicData withSelected(Optional<Identifier> s) { return new MagicData(element, level, xp, s, home, readyAt); }

	public MagicData withHome(Optional<GlobalPos> h) { return new MagicData(element, level, xp, selected, h, readyAt); }

	public MagicData withReadyAt(Map<Identifier, Long> r) { return new MagicData(element, level, xp, selected, home, r); }

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
		return new MagicData(element, level, xp, selected, home, ready);
	}
}
