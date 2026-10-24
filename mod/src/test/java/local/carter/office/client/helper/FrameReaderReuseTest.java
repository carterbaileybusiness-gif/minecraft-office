package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import local.carter.office.client.helper.HelperProtocol.Frame;
import local.carter.office.client.helper.HelperProtocol.FrameReader;

/** The frame path allocates no new pixel array per frame once the caller releases each picture (GC / swap stalls). */
class FrameReaderReuseTest {
	private static byte[] frame(final int w, final int h, final int fill) throws Exception {
		byte[] px = new byte[w * h * 4];
		Arrays.fill(px, (byte) fill);
		return HelperProtocol.encode(new Frame(1, 1L, w, h, ByteBuffer.wrap(px)));
	}

	private static int firstByte(final HelperProtocol.Message m) {
		ByteBuffer b = ((Frame) m).rgba();
		return b.get(b.position()) & 0xFF;
	}

	@Test
	void releasedPayloadsAreReused() throws Exception {
		FrameReader r = FrameReader.forHelperMessages();
		byte[] f1 = frame(64, 32, 1);
		r.push(f1, 0, f1.length);
		HelperProtocol.Message m1 = r.next();
		assertInstanceOf(Frame.class, m1);
		r.release(m1);
		assertEquals(1, r.spares(), "a released picture's array is kept");
		byte[] f2 = frame(64, 32, 2);
		r.push(f2, 0, f2.length);
		HelperProtocol.Message m2 = r.next();
		assertEquals(0, r.spares(), "the next picture of that size reuses it");
		assertEquals(2, firstByte(m2), "with the new pixels");
		assertEquals(64 * 32 * 4, ((Frame) m2).rgba().remaining());
	}

	@Test
	void unreleasedPicturesAreNeverOverwritten() throws Exception {
		FrameReader r = FrameReader.forHelperMessages();
		byte[] both = concat(frame(8, 8, 5), frame(8, 8, 6));
		r.push(both, 0, both.length); // two pictures complete in one read
		HelperProtocol.Message a = r.next();
		HelperProtocol.Message b = r.next();
		assertEquals(5, firstByte(a));
		assertEquals(6, firstByte(b));
		r.release(a);
		byte[] f3 = frame(8, 8, 7);
		r.push(f3, 0, f3.length);
		assertEquals(7, firstByte(r.next()));
		assertEquals(6, firstByte(b), "b was still held: its pixels stay");
	}

	@Test
	void aMuchBiggerSpareIsNotUsedForASmallPicture() throws Exception {
		FrameReader r = FrameReader.forHelperMessages();
		byte[] big = frame(256, 256, 1);
		r.push(big, 0, big.length);
		r.release(r.next());
		byte[] small = frame(8, 8, 2);
		r.push(small, 0, small.length);
		HelperProtocol.Message m = r.next();
		assertEquals(1, r.spares(), "the big array waits for a big picture");
		assertEquals(8 * 8 * 4, ((Frame) m).rgba().remaining());
	}

	@Test
	void aCallerThatNeverReleasesLeaksNothing() throws Exception {
		FrameReader r = FrameReader.forHelperMessages();
		byte[] f = frame(16, 16, 3);
		for (int i = 0; i < 100; i++) {
			r.push(f, 0, f.length);
			assertEquals(3, firstByte(r.next()));
		}
		assertEquals(4, r.lent(), "only the newest few pictures are tracked; older ones are left to the GC");
		assertEquals(0, r.spares());
	}

	private static byte[] concat(final byte[] a, final byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
