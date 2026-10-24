package local.carter.office.magic.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.fire.FireBolt;
import local.carter.office.magic.spell.fire.BarrageGeometry;
import local.carter.office.magic.spell.fire.FireballBarrage;
import local.carter.office.magic.spell.wind.BladeWindGeometry;

import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;

/** The per-cast rank view: packing, the bump maths, fork lookup, and ranks in {@code MagicData} (save and sync). */
class SpellRankTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap(); // the trees name status effects
	}

	@Test
	void packsAndUnpacksEveryRank() {
		for (int rank = 0; rank <= SpellRank.MAX; rank++) {
			for (int f3 = 1; f3 <= 2; f3++) {
				for (int f5 = 1; f5 <= 2; f5++) {
					SpellRank r = new SpellRank(SpellIds.FIRE_BOLT, rank, f3, f5);
					assertEquals(r, SpellRank.unpack(SpellIds.FIRE_BOLT, r.packed()));
					assertTrue(r.packed() >= 0 && r.packed() <= 0x7F);
					assertEquals(rank >= 3 ? f3 : 0, r.fork(3), "a fork is only picked once reached");
					assertEquals(rank >= 5 ? f5 : 0, r.fork(5));
				}
			}
		}
		assertEquals(0, SpellRank.none(SpellIds.GUST).packed());
	}

	@Test
	void bumpsAreRanksOneTwoAndFour() {
		int[] bumps = {0, 1, 2, 2, 3, 3};
		for (int rank = 0; rank <= 5; rank++) {
			SpellRank r = new SpellRank(SpellIds.FIRE_BOLT, rank, 1, 1);
			assertEquals(bumps[rank], r.bumps(), "rank " + rank);
		}
		SpellRank max = new SpellRank(SpellIds.FIRE_BOLT, 5, 2, 2); // Searing, Flare: no knob changes
		assertEquals(1.45F, max.mul(Knob.DAMAGE), 1.0E-5);
		assertEquals(1.45F, max.mul(Knob.SIZE), 1.0E-5);
		assertEquals(0.70F, max.mul(Knob.MANA), 1.0E-5);
		assertEquals(1F, max.mul(Knob.COOLDOWN), 1.0E-5);
		assertEquals(0F, max.add(Knob.COUNT), 1.0E-5);
	}

	@Test
	void forksAreLookedUpByName() {
		SpellRank scatter = new SpellRank(SpellIds.FIRE_BOLT, 3, SpellRank.A, 0);
		assertTrue(scatter.is(FireBolt.SCATTER));
		assertFalse(scatter.is(FireBolt.SEARING));
		assertFalse(scatter.is(FireBolt.LANCE), "rank V not reached");
		SpellRank flare = scatter.next(0).next(SpellRank.B);
		assertEquals(5, flare.rank());
		assertTrue(flare.is(FireBolt.SCATTER) && flare.is(FireBolt.FLARE));
		assertTrue(flare.repick(3, SpellRank.B).is(FireBolt.SEARING));
		assertEquals(5, flare.repick(3, SpellRank.B).rank(), "a re-pick keeps the rank");
		// A fork's knobs: Carpet adds two fireballs, Heavy Shells takes one.
		SpellRank carpet = new SpellRank(SpellIds.FIREBALL_BARRAGE, 3, SpellRank.A, 0);
		assertTrue(carpet.is(FireballBarrage.CARPET));
		assertEquals(2F, carpet.add(Knob.COUNT), 1.0E-5);
		assertEquals(1F, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.A, SpellRank.B).add(Knob.COUNT), 1.0E-5);
	}

	/** The size knob's count is capped before a fork's extra is added, so each fork's trade holds at full charge, rank V. */
	@Test
	void forkCountsHoldAtFullChargeRankFive() {
		// Blade Wind: 7 × 1.45 rounds to 11, capped at 9; Cleave's −2 then gives 7, fewer than Tempest's 9.
		int cleave = BladeWindGeometry.blades(1f, new SpellRank(SpellIds.BLADE_WIND, 5, SpellRank.A, SpellRank.A));
		int tempest = BladeWindGeometry.blades(1f, new SpellRank(SpellIds.BLADE_WIND, 5, SpellRank.A, SpellRank.B));
		assertEquals(BladeWindGeometry.MAX_BLADES, tempest);
		assertEquals(BladeWindGeometry.MAX_BLADES - 2, cleave, "Cleave throws two fewer blades at full charge too");
		// Fireball Barrage: 6 × 1.45 rounds to 9 (the cap); Carpet's +2 then lands, Heavy Shells' −1 too.
		int carpetGuided = BarrageGeometry.count(1f, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.A, SpellRank.A));
		int napalmGuided = BarrageGeometry.count(1f, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.B, SpellRank.A));
		int carpetHeavy = BarrageGeometry.count(1f, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.A, SpellRank.B));
		int napalmHeavy = BarrageGeometry.count(1f, new SpellRank(SpellIds.FIREBALL_BARRAGE, 5, SpellRank.B, SpellRank.B));
		assertEquals(BarrageGeometry.MAX_COUNT, napalmGuided);
		assertEquals(BarrageGeometry.MAX_COUNT + 2, carpetGuided, "Carpet's two extra fireballs at full charge");
		assertEquals(BarrageGeometry.MAX_COUNT + 1, carpetHeavy);
		assertEquals(BarrageGeometry.MAX_COUNT - 1, napalmHeavy, "Heavy Shells' one fewer at full charge");
		assertTrue(carpetGuided <= BarrageGeometry.MAX_FORKED);
	}

	@Test
	void romanNumerals() {
		assertEquals("", SpellRank.roman(0));
		assertEquals("III", SpellRank.roman(3));
		assertEquals("V", SpellRank.roman(5));
	}

	@Test
	void ranksSaveAndSyncWithMagicData() {
		MagicData d = MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(9)
				.withRank(new SpellRank(SpellIds.FIRE_BOLT, 5, 1, 2)).withRank(new SpellRank(SpellIds.WATER_BULLET, 2, 0, 0));
		assertEquals(5, d.rank(SpellIds.FIRE_BOLT).rank());
		assertEquals(2, d.rank(SpellIds.WATER_BULLET).rank(), "another element's ranks are kept");
		assertEquals(0, d.rank(SpellIds.METEOR).rank());
		JsonElement json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
		assertEquals(d, MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(), "save and load (relog)");
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		MagicData.STREAM_CODEC.encode(buf, d);
		assertEquals(d, MagicData.STREAM_CODEC.decode(buf), "sync");
		assertEquals(0, buf.readableBytes());
		// The copies keep the ranks; a rebind (element change) keeps them too.
		assertEquals(d.ranks(), d.withElement(Optional.of(Element.WATER)).withLevel(3).withReady(SpellIds.GUST, 9L).ranks());
		assertEquals(0, d.withRank(SpellRank.none(SpellIds.FIRE_BOLT)).rank(SpellIds.FIRE_BOLT).rank(), "rank 0 removes the entry");
	}

	@Test
	void badRanksAreDroppedOnLoad() {
		JsonObject json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE,
				MagicData.EMPTY.withRank(new SpellRank(SpellIds.GUST, 4, 1, 0))).getOrThrow().getAsJsonObject();
		JsonObject ranks = new JsonObject();
		ranks.addProperty("office:gust", new SpellRank(SpellIds.GUST, 4, 1, 0).packed());
		ranks.addProperty("office:spark", 7); // rank 7
		ranks.addProperty("office:blink", 3); // rank III with no fork
		ranks.addProperty("NOT AN ID", 1);
		json.add("ranks", ranks);
		MagicData d = MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Map.of(SpellIds.GUST, new SpellRank(SpellIds.GUST, 4, 1, 0).packed()), d.ranks());
		// Old saves have no ranks at all.
		json.remove("ranks");
		assertTrue(MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().ranks().isEmpty());
	}
}
