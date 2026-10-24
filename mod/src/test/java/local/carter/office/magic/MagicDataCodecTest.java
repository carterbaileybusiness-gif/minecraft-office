package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

class MagicDataCodecTest {
	private static MagicData full() {
		GlobalPos home = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether")), new BlockPos(12, 64, -300));
		return new MagicData(Optional.of(Element.LIGHTNING), 7, 33, Optional.of(SpellIds.CHAIN_LIGHTNING), Optional.of(home),
				Map.of(SpellIds.SPARK, 1234L, SpellIds.RECALL, 99_000L));
	}

	@Test void emptyIsLevelOne() {
		assertEquals(1, MagicData.EMPTY.level());
		assertEquals(0, MagicData.EMPTY.xp());
		assertTrue(MagicData.EMPTY.element().isEmpty());
		assertTrue(MagicData.EMPTY.home().isEmpty());
		assertTrue(MagicData.EMPTY.readyAt().isEmpty());
	}

	@Test void roundTripsThroughJson() {
		MagicData d = full();
		JsonElement json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
		assertEquals(d, MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		JsonElement empty = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, MagicData.EMPTY).getOrThrow();
		assertEquals(MagicData.EMPTY, MagicData.CODEC.parse(JsonOps.INSTANCE, empty).getOrThrow());
	}

	@Test void badFieldsFallBackOneByOne() {
		JsonObject json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, full()).getOrThrow().getAsJsonObject();
		json.addProperty("element", "plasma");
		json.addProperty("level", "high");
		json.addProperty("selected", "Not An Id!");
		JsonObject ready = new JsonObject();
		ready.addProperty("office:spark", 5L);
		ready.addProperty("BAD ID", 3L);
		json.add("ready_at", ready);
		MagicData d = MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.empty(), d.element());
		assertEquals(1, d.level());
		assertEquals(Optional.empty(), d.selected());
		assertEquals(33, d.xp(), "good fields survive");
		assertEquals(full().home(), d.home(), "good fields survive");
		assertEquals(Map.of(SpellIds.SPARK, 5L), d.readyAt(), "a bad cooldown key drops only that entry");
	}

	@Test void badElementOrdinalFailsToDecode() {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(Element.values().length + 1);
		assertThrows(io.netty.handler.codec.DecoderException.class, () -> MagicData.STREAM_CODEC.decode(buf));
	}

	@Test void roundTripsThroughStreamCodec() {
		for (MagicData d : new MagicData[] {full(), MagicData.EMPTY}) {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			MagicData.STREAM_CODEC.encode(buf, d);
			assertEquals(d, MagicData.STREAM_CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		}
	}

	@Test void cooldownLeft() {
		MagicData d = full();
		assertEquals(234L, d.cooldownLeft(SpellIds.SPARK, 1000L));
		assertEquals(0L, d.cooldownLeft(SpellIds.SPARK, 2000L));
		assertEquals(0L, d.cooldownLeft(SpellIds.GUST, 0L));
		assertEquals(40L, d.withReady(SpellIds.GUST, 50L).cooldownLeft(SpellIds.GUST, 10L));
	}

	@Test void copies() {
		MagicData d = MagicData.EMPTY.withElement(Optional.of(Element.FIRE)).withLevel(5).withXp(3).withSelected(Optional.of(SpellIds.FIRE_WALL));
		assertEquals(Optional.of(Element.FIRE), d.element());
		assertEquals(5, d.level());
		assertEquals(3, d.xp());
		assertEquals(Optional.of(SpellIds.FIRE_WALL), d.selected());
		assertEquals(Optional.empty(), d.withElement(Optional.empty()).element());
		assertEquals(5, d.withElement(Optional.empty()).level());
	}

	@Test void aBadRankEntryDropsOnlyItself() {
		JsonObject json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, full()).getOrThrow().getAsJsonObject();
		JsonObject ranks = new JsonObject();
		ranks.addProperty("office:fire_bolt", 1);
		ranks.addProperty("office:fire_wall", "nonsense");
		ranks.addProperty("office:spark", 999);
		ranks.addProperty("BAD ID", 2);
		json.add("ranks", ranks);
		MagicData d = MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Map.of(SpellIds.FIRE_BOLT, 1), d.ranks(), "the good entry survives its bad neighbours");
		assertEquals(7, d.level(), "other fields survive");
	}

	@Test void repickWindowPersistsAndSyncs() {
		MagicData open = full().withRepickUntil(5_000L);
		assertEquals(5_000L, open.repickUntil());
		assertTrue(open.repickOpen(4_999L));
		assertFalse(open.repickOpen(5_000L));
		assertEquals(5_000L, open.withLevel(2).withRank(new local.carter.office.magic.tree.SpellRank(SpellIds.SPARK, 1, 0, 0)).repickUntil(),
				"copies keep it");
		JsonElement json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, open).getOrThrow();
		assertEquals(open, MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		MagicData.STREAM_CODEC.encode(buf, open);
		assertEquals(open, MagicData.STREAM_CODEC.decode(buf), "the owner's client gets the window's end");
		JsonObject old = json.getAsJsonObject().deepCopy();
		old.remove("repick_until");
		assertEquals(0L, MagicData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().repickUntil(), "an older save has no window");
	}

	@Test void travelFlagDefaultsOnPersistsAndSyncs() {
		assertTrue(MagicData.EMPTY.travel(), "requests accepted by default");
		MagicData off = full().withTravel(false);
		assertFalse(off.travel());
		assertFalse(off.withLevel(3).withHome(Optional.empty()).withReady(SpellIds.GUST, 5L).travel(), "copies keep it");
		JsonElement json = MagicData.CODEC.encodeStart(JsonOps.INSTANCE, off).getOrThrow();
		assertEquals(off, MagicData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		MagicData.STREAM_CODEC.encode(buf, off);
		assertEquals(off, MagicData.STREAM_CODEC.decode(buf));
		// Old saves have no "travel" field, and a bad value falls back: both mean on.
		JsonObject old = json.getAsJsonObject().deepCopy();
		old.remove("travel");
		assertTrue(MagicData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow().travel());
		old.addProperty("travel", "maybe");
		MagicData lenient = MagicData.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
		assertTrue(lenient.travel());
		assertEquals(7, lenient.level(), "other fields survive");
	}
}
