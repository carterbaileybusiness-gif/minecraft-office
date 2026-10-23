package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import local.carter.office.client.stream.StbJpeg;
import local.carter.office.core.TileCodec;

/** The real LWJGL stb JPEG path (skipped if the stb natives can't load in this JVM). */
class StbJpegTest {
	private static StbJpeg open() {
		try {
			return new StbJpeg();
		} catch (LinkageError | IllegalStateException e) {
			Assumptions.abort("stb natives unavailable: " + e);
			return null;
		}
	}

	@Test
	void roundTripIsCloseAndSmall() {
		try (StbJpeg jpeg = open()) {
			int w = 64;
			int h = 64;
			ByteBuffer px = ByteBuffer.allocate(w * h * 4);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					px.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) 128).put((byte) 255);
				}
			}
			px.flip();
			byte[] enc = jpeg.encode(new TileCodec.Tile(0, 0, w, h, px), 70);
			assertTrue(enc.length > 100 && enc.length < 4000, "q70 gradient tile size " + enc.length);
			ByteBuffer out = ByteBuffer.allocateDirect(w * h * 4);
			jpeg.decodeInto(enc, out, w, h);
			int worst = 0;
			for (int i = 0; i < w * h * 4; i++) {
				if (i % 4 != 3) {
					worst = Math.max(worst, Math.abs((px.get(i) & 0xFF) - (out.get(i) & 0xFF)));
				}
			}
			assertTrue(worst < 24, "decoded close to the source (worst channel error " + worst + ")");
			assertThrows(IllegalArgumentException.class, () -> jpeg.decodeInto(enc, out, 32, 64), "size mismatch refused");
			assertThrows(IllegalArgumentException.class, () -> jpeg.decodeInto(new byte[] { 1, 2, 3 }, out, w, h));

			// edge tile (non-square) and a "busy" tile, for the bandwidth estimate in the report
			Random r = new Random(1);
			ByteBuffer text = ByteBuffer.allocate(64 * 64 * 4);
			for (int i = 0; i < 64 * 64; i++) {
				int v = (i / 64) % 8 < 6 && r.nextInt(3) == 0 ? 20 : 240; // text-like: dark glyph pixels on white rows
				text.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
			}
			text.flip();
			int textSize = jpeg.encode(new TileCodec.Tile(0, 0, 64, 64, text), 70).length;
			ByteBuffer flat = ByteBuffer.allocate(64 * 64 * 4);
			for (int i = 0; i < 64 * 64; i++) {
				flat.put((byte) 30).put((byte) 30).put((byte) 36).put((byte) 255);
			}
			flat.flip();
			int flatSize = jpeg.encode(new TileCodec.Tile(0, 0, 64, 64, flat), 70).length;
			ByteBuffer edge = ByteBuffer.allocate(16 * 64 * 4);
			for (int i = 0; i < 16 * 64; i++) {
				edge.put((byte) i).put((byte) 0).put((byte) 0).put((byte) 255);
			}
			edge.flip();
			byte[] edgeEnc = jpeg.encode(new TileCodec.Tile(0, 0, 16, 64, edge), 70);
			ByteBuffer edgeOut = ByteBuffer.allocateDirect(16 * 64 * 4);
			jpeg.decodeInto(edgeEnc, edgeOut, 16, 64);
			System.out.println("[stb-jpeg q70 64x64] gradient=" + enc.length + " B, text-like=" + textSize + " B, flat="
					+ flatSize + " B, edge 16x64=" + edgeEnc.length + " B");
			assertEquals(255, edgeOut.get(3) & 0xFF, "decoded opaque");
		}
	}

	@Test
	void aTileDeclaringAHugeSizeIsRefusedBeforeDecoding() {
		try (StbJpeg jpeg = open()) {
			ByteBuffer px = ByteBuffer.allocate(64 * 64 * 4);
			byte[] enc = jpeg.encode(new TileCodec.Tile(0, 0, 64, 64, px), 70);
			// rewrite the SOF0 frame header (FF C0, len u16, precision u8, height u16, width u16) to 30000 x 30000
			int sof = -1;
			for (int i = 0; i + 1 < enc.length; i++) {
				if ((enc[i] & 0xFF) == 0xFF && (enc[i + 1] & 0xFF) == 0xC0) {
					sof = i;
					break;
				}
			}
			assertTrue(sof > 0, "SOF0 found");
			byte[] huge = enc.clone();
			huge[sof + 5] = (byte) (30000 >> 8);
			huge[sof + 6] = (byte) 30000;
			huge[sof + 7] = (byte) (30000 >> 8);
			huge[sof + 8] = (byte) 30000;
			ByteBuffer out = ByteBuffer.allocateDirect(64 * 64 * 4);
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> jpeg.decodeInto(huge, out, 64, 64));
			assertTrue(e.getMessage().contains("declares 30000x30000"), e.getMessage());
			assertThrows(IllegalArgumentException.class, () -> jpeg.decodeInto(enc, out, 128, 128), "tiles over 64 px refused");
			jpeg.decodeInto(enc, out, 64, 64); // the untouched tile still decodes
		}
	}
}
