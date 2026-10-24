package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
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
}
