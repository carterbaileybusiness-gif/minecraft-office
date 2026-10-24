package local.carter.office.magic.net;

import java.util.Arrays;
import java.util.Objects;

import io.netty.handler.codec.DecoderException;

import local.carter.office.magic.tree.SpellRank;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Everything a client needs to draw one cast (one {@code CastFx} payload per cast). {@code entities} are entity ids the spell
 * touched (at most {@link #MAX_ENTITIES}); {@code data} is spell-specific numbers (at most {@link #MAX_DATA}); {@code rank} is the
 * caster's skill-tree rank of the spell ({@code SpellRank.packed()}, 0–127), so every client draws the caster's forks. The
 * constructor copies both arrays and keeps only the first {@code MAX_…} entries, so every instance encodes; the decoder rejects
 * more.
 */
public record FxEvent(Identifier spell, int casterId, Vec3 origin, Vec3 dir, Vec3 target, float charge, float power, long seed,
		int duration, int[] entities, float[] data, int rank) {
	public static final int MAX_ENTITIES = 32;
	public static final int MAX_DATA = 16;

	public static final StreamCodec<FriendlyByteBuf, FxEvent> STREAM_CODEC = StreamCodec.of(FxEvent::write, FxEvent::read);

	public FxEvent {
		Objects.requireNonNull(spell, "spell");
		entities = Arrays.copyOf(entities, Math.min(entities.length, MAX_ENTITIES));
		data = Arrays.copyOf(data, Math.min(data.length, MAX_DATA));
		rank &= 0x7F;
	}

	/** Rank 0 (Recall, which has no tree, and hand-built test events). */
	public FxEvent(Identifier spell, int casterId, Vec3 origin, Vec3 dir, Vec3 target, float charge, float power, long seed, int duration,
			int[] entities, float[] data) {
		this(spell, casterId, origin, dir, target, charge, power, seed, duration, entities, data, 0);
	}

	/** The caster's rank of the spell as a {@link SpellRank}. */
	public SpellRank spellRank() {
		return SpellRank.unpack(spell, rank);
	}

	@Override
	public int[] entities() { return entities.clone(); }

	@Override
	public float[] data() { return data.clone(); }

	public FxEvent withTarget(Vec3 t) {
		return new FxEvent(spell, casterId, origin, dir, t, charge, power, seed, duration, entities, data, rank);
	}

	public FxEvent withDuration(int ticks) {
		return new FxEvent(spell, casterId, origin, dir, target, charge, power, seed, ticks, entities, data, rank);
	}

	public FxEvent withEntities(int... ids) {
		return new FxEvent(spell, casterId, origin, dir, target, charge, power, seed, duration, ids, data, rank);
	}

	public FxEvent withDir(Vec3 d) {
		return new FxEvent(spell, casterId, origin, d, target, charge, power, seed, duration, entities, data, rank);
	}

	public FxEvent withData(float... values) {
		return new FxEvent(spell, casterId, origin, dir, target, charge, power, seed, duration, entities, values, rank);
	}

	private static void write(FriendlyByteBuf buf, FxEvent e) {
		buf.writeIdentifier(e.spell);
		buf.writeVarInt(e.casterId);
		Vec3.STREAM_CODEC.encode(buf, e.origin);
		Vec3.STREAM_CODEC.encode(buf, e.dir);
		Vec3.STREAM_CODEC.encode(buf, e.target);
		buf.writeFloat(e.charge);
		buf.writeFloat(e.power);
		buf.writeLong(e.seed);
		buf.writeVarInt(e.duration);
		buf.writeVarInt(e.entities.length);
		for (int id : e.entities) buf.writeVarInt(id);
		buf.writeVarInt(e.data.length);
		for (float f : e.data) buf.writeFloat(f);
		buf.writeByte(e.rank);
	}

	private static FxEvent read(FriendlyByteBuf buf) {
		Identifier spell = buf.readIdentifier();
		int caster = buf.readVarInt();
		Vec3 origin = Vec3.STREAM_CODEC.decode(buf);
		Vec3 dir = Vec3.STREAM_CODEC.decode(buf);
		Vec3 target = Vec3.STREAM_CODEC.decode(buf);
		float charge = buf.readFloat();
		float power = buf.readFloat();
		long seed = buf.readLong();
		int duration = buf.readVarInt();
		int n = buf.readVarInt();
		if (n < 0 || n > MAX_ENTITIES) throw new DecoderException("too many fx entities: " + n);
		int[] ids = new int[n];
		for (int i = 0; i < n; i++) ids[i] = buf.readVarInt();
		int m = buf.readVarInt();
		if (m < 0 || m > MAX_DATA) throw new DecoderException("too many fx data values: " + m);
		float[] data = new float[m];
		for (int i = 0; i < m; i++) data[i] = buf.readFloat();
		int rank = buf.readUnsignedByte();
		if (rank > 0x7F) throw new DecoderException("bad fx rank " + rank);
		return new FxEvent(spell, caster, origin, dir, target, charge, power, seed, duration, ids, data, rank);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof FxEvent f && spell.equals(f.spell) && casterId == f.casterId && origin.equals(f.origin) && dir.equals(f.dir)
				&& target.equals(f.target) && Float.compare(charge, f.charge) == 0 && Float.compare(power, f.power) == 0 && seed == f.seed
				&& duration == f.duration && Arrays.equals(entities, f.entities) && Arrays.equals(data, f.data) && rank == f.rank;
	}

	@Override
	public int hashCode() {
		return Objects.hash(spell, casterId, origin, dir, target, charge, power, seed, duration, Arrays.hashCode(entities), Arrays.hashCode(data),
				rank);
	}

	@Override
	public String toString() {
		return "FxEvent[" + spell + " caster=" + casterId + " origin=" + origin + " dir=" + dir + " target=" + target + " charge=" + charge
				+ " power=" + power + " seed=" + seed + " duration=" + duration + " entities=" + Arrays.toString(entities)
				+ " data=" + Arrays.toString(data) + " rank=" + rank + "]";
	}
}
