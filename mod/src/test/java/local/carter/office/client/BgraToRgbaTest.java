package local.carter.office.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import local.carter.office.client.capture.BgraToRgba;
import org.junit.jupiter.api.Test;

/** Windows capture delivers BGRA (libyuv "ARGB"); the sink wants tightly packed RGBA. */
class BgraToRgbaTest {
	private static byte[] bytes(final ByteBuffer b, final int from, final int n) {
		byte[] out = new byte[n];
		for (int i = 0; i < n; i++) {
			out[i] = b.get(from + i);
		}
		return out;
	}

	@Test
	void swapsRedAndBlueAndForcesOpaque() {
		ByteBuffer src = ByteBuffer.wrap(new byte[] {
				10, 20, 30, 0, // B G R A(0: GDI often leaves alpha empty)
				(byte) 200, (byte) 150, (byte) 100, (byte) 255});
		ByteBuffer dst = ByteBuffer.allocate(8);
		BgraToRgba.convert(src, 8, 2, 1, dst);
		assertArrayEquals(new byte[] {30, 20, 10, (byte) 255, (byte) 100, (byte) 150, (byte) 200, (byte) 255}, dst.array());
	}

	@Test
	void skipsRowPaddingAndPacksTheOutput() {
		// 1 x 2 image, source stride 8 (4 bytes padding per row)
		ByteBuffer src = ByteBuffer.wrap(new byte[] {
				1, 2, 3, 4, 9, 9, 9, 9,
				5, 6, 7, 8, 9, 9, 9, 9});
		ByteBuffer dst = ByteBuffer.allocate(8);
		BgraToRgba.convert(src, 8, 1, 2, dst);
		assertArrayEquals(new byte[] {3, 2, 1, (byte) 255, 7, 6, 5, (byte) 255}, dst.array());
	}

	@Test
	void worksInPlaceOnDirectBuffersAndKeepsPositions() {
		ByteBuffer buf = ByteBuffer.allocateDirect(4 + 8).order(ByteOrder.BIG_ENDIAN);
		buf.position(4);
		buf.put(4, (byte) 11).put(5, (byte) 22).put(6, (byte) 33).put(7, (byte) 44);
		buf.put(8, (byte) 55).put(9, (byte) 66).put(10, (byte) 77).put(11, (byte) 88);
		BgraToRgba.convert(buf, 8, 2, 1, buf);
		assertEquals(4, buf.position());
		assertEquals(ByteOrder.BIG_ENDIAN, buf.order());
		assertArrayEquals(new byte[] {33, 22, 11, (byte) 255, 77, 66, 55, (byte) 255}, bytes(buf, 4, 8));
	}

	@Test
	void largeFrameMatchesBytewiseReference() {
		int w = 37;
		int h = 11;
		int stride = w * 4 + 12;
		ByteBuffer src = ByteBuffer.allocate(stride * h);
		for (int i = 0; i < src.capacity(); i++) {
			src.put(i, (byte) (i * 31 + 7));
		}
		ByteBuffer dst = ByteBuffer.allocate(w * h * 4);
		BgraToRgba.convert(src, stride, w, h, dst);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int s = y * stride + x * 4;
				int d = (y * w + x) * 4;
				assertEquals(src.get(s + 2), dst.get(d));
				assertEquals(src.get(s + 1), dst.get(d + 1));
				assertEquals(src.get(s), dst.get(d + 2));
				assertEquals((byte) 255, dst.get(d + 3));
			}
		}
	}
}
