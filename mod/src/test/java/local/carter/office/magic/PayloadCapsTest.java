package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import local.carter.office.magic.core.Dir;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.net.MagicPayloads;
import local.carter.office.magic.net.MagicPayloads.CastFx;
import local.carter.office.magic.net.MagicPayloads.SignAnswer;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

class PayloadCapsTest {
	private static FxEvent base() {
		return new FxEvent(SpellIds.FIRE_BOLT, 42, new Vec3(1, 2, 3), new Vec3(0, 0, 1), new Vec3(1, 2, 11), 0.5f, 1.5f, 77L, 0,
				new int[0], new float[0]);
	}

	private static FriendlyByteBuf buf() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	/** Encodes {@link #base()} (empty arrays, so the last two bytes are the two zero counts) without those last {@code drop} bytes. */
	private static FriendlyByteBuf prefix(int drop) {
		FriendlyByteBuf b = buf();
		FxEvent.STREAM_CODEC.encode(b, base());
		b.writerIndex(b.writerIndex() - drop);
		return b;
	}

	@Test void validFxRoundTrips() {
		int[] ids = new int[FxEvent.MAX_ENTITIES];
		for (int i = 0; i < ids.length; i++) ids[i] = i * 3;
		float[] data = new float[FxEvent.MAX_DATA];
		for (int i = 0; i < data.length; i++) data[i] = i / 4f;
		FxEvent e = base().withTarget(new Vec3(5, 6, 7)).withDuration(60).withEntities(ids).withData(data);
		FriendlyByteBuf b = buf();
		FxEvent.STREAM_CODEC.encode(b, e);
		FxEvent back = FxEvent.STREAM_CODEC.decode(b);
		assertEquals(e, back);
		assertEquals(60, back.duration());
		assertEquals(new Vec3(5, 6, 7), back.target());
		assertEquals(0, b.readableBytes());

		FriendlyByteBuf p = buf();
		CastFx.CODEC.encode(p, new CastFx(e));
		assertEquals(e, CastFx.CODEC.decode(p).event());
	}

	@Test void buildersClampToCaps() {
		assertEquals(FxEvent.MAX_ENTITIES, base().withEntities(new int[40]).entities().length);
		assertEquals(FxEvent.MAX_DATA, base().withData(new float[20]).data().length);
	}

	@Test void tooManyEntitiesFailsToDecode() {
		FriendlyByteBuf b = prefix(2);
		b.writeVarInt(FxEvent.MAX_ENTITIES + 1);
		for (int i = 0; i <= FxEvent.MAX_ENTITIES; i++) b.writeVarInt(i);
		b.writeVarInt(0);
		assertThrows(DecoderException.class, () -> FxEvent.STREAM_CODEC.decode(b));
	}

	@Test void tooManyDataFloatsFailsToDecode() {
		FriendlyByteBuf b = prefix(1);
		b.writeVarInt(FxEvent.MAX_DATA + 1);
		for (int i = 0; i <= FxEvent.MAX_DATA; i++) b.writeFloat(i);
		assertThrows(DecoderException.class, () -> FxEvent.STREAM_CODEC.decode(b));
	}

	@Test void signAnswerCaps() {
		SignAnswer ok = new SignAnswer(9, new byte[] {0, 1, 2, 3, 0, 1});
		FriendlyByteBuf b = buf();
		SignAnswer.CODEC.encode(b, ok);
		SignAnswer back = SignAnswer.CODEC.decode(b);
		assertEquals(9, back.id());
		assertArrayEquals(ok.dirs(), back.dirs());
		assertArrayEquals(new Dir[] {Dir.UP, Dir.DOWN, Dir.LEFT, Dir.RIGHT, Dir.UP, Dir.DOWN}, back.toDirs());
		assertNull(new SignAnswer(1, new byte[] {0, 9}).toDirs(), "an unknown arrow makes the answer invalid");

		FriendlyByteBuf bad = buf();
		bad.writeVarInt(9);
		bad.writeByteArray(new byte[MagicPayloads.MAX_SIGNS + 1]);
		assertThrows(DecoderException.class, () -> SignAnswer.CODEC.decode(bad));
		assertThrows(IllegalArgumentException.class, () -> new SignAnswer(1, new byte[MagicPayloads.MAX_SIGNS + 1]));
	}

	@Test void otherPayloadsRoundTrip() {
		FriendlyByteBuf b = buf();
		MagicPayloads.ChooseElement.CODEC.encode(b, new MagicPayloads.ChooseElement(Element.SHADOW));
		assertEquals(Element.SHADOW, MagicPayloads.ChooseElement.CODEC.decode(b).element());
		MagicPayloads.SignChallenge c = new MagicPayloads.SignChallenge(3, SpellIds.METEOR, 123L, 6, 92);
		MagicPayloads.SignChallenge.CODEC.encode(b, c);
		assertEquals(c, MagicPayloads.SignChallenge.CODEC.decode(b));
		MagicPayloads.OpenGrimoire.CODEC.encode(b, new MagicPayloads.OpenGrimoire(MagicPayloads.OpenGrimoire.Mode.INFO));
		assertEquals(MagicPayloads.OpenGrimoire.Mode.INFO, MagicPayloads.OpenGrimoire.CODEC.decode(b).mode());
		assertEquals(0, b.readableBytes());
	}
}
