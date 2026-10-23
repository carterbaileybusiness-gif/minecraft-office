package local.carter.office.client.stream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImage;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import local.carter.office.core.TileCodec;

/**
 * JPEG via LWJGL's stb (bundled with Minecraft): {@code stbi_write_jpg_to_func} to encode, {@code stbi_load_from_memory}
 * to decode. Not thread-safe: use one instance per thread (the sender and the receiver each own one). {@link #close()}
 * frees the native callback and buffers.
 */
public final class StbJpeg implements TileCodec.Jpeg, AutoCloseable {
	private final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
	private final STBIWriteCallback callback = STBIWriteCallback.create((ctx, data, size) -> {
		ByteBuffer chunk = STBIWriteCallback.getData(data, size);
		byte[] b = new byte[size];
		chunk.get(b);
		out.write(b, 0, size);
	});
	private ByteBuffer pixels = MemoryUtil.memAlloc(64 * 64 * 4);
	private ByteBuffer input = MemoryUtil.memAlloc(16 * 1024);

	@Override
	public byte[] encode(final TileCodec.Tile tile, final int quality) {
		int n = tile.w() * tile.h() * 4;
		if (pixels.capacity() < n) {
			pixels = MemoryUtil.memRealloc(pixels, n);
		}
		pixels.clear();
		pixels.put(0, tile.rgba(), tile.rgba().position(), n);
		pixels.limit(n);
		out.reset();
		int ok = STBImageWrite.stbi_write_jpg_to_func(callback, 0L, tile.w(), tile.h(), 4, pixels, quality);
		if (ok == 0) {
			throw new IllegalStateException("stbi_write_jpg failed for a " + tile.w() + "x" + tile.h() + " tile");
		}
		return out.toByteArray();
	}

	/** Largest tile side decoded (the stream's tile size). */
	public static final int MAX_SIDE = StreamSender.TILE;
	/** Largest compressed tile accepted. */
	public static final int MAX_BYTES = 64 * 1024;

	/**
	 * Decodes a tile of exactly {@code w x h} (each at most {@link #MAX_SIDE}). The header is checked with
	 * {@code stbi_info_from_memory} first, so a JPEG that declares any other size is refused before stb allocates.
	 */
	@Override
	public void decodeInto(final byte[] jpeg, final ByteBuffer dst, final int w, final int h) {
		if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) {
			throw new IllegalArgumentException("tile size " + w + "x" + h + " out of range");
		}
		if (jpeg.length > MAX_BYTES) {
			throw new IllegalArgumentException("tile of " + jpeg.length + " bytes is too big");
		}
		if (input.capacity() < jpeg.length) {
			input = MemoryUtil.memRealloc(input, jpeg.length);
		}
		input.clear();
		input.put(0, jpeg);
		input.limit(jpeg.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer pw = stack.mallocInt(1);
			IntBuffer ph = stack.mallocInt(1);
			IntBuffer pc = stack.mallocInt(1);
			if (!STBImage.stbi_info_from_memory(input, pw, ph, pc)) {
				throw new IllegalArgumentException("not a JPEG: " + STBImage.stbi_failure_reason());
			}
			if (pw.get(0) != w || ph.get(0) != h) {
				throw new IllegalArgumentException("tile declares " + pw.get(0) + "x" + ph.get(0) + ", expected " + w + "x" + h);
			}
			ByteBuffer img = STBImage.stbi_load_from_memory(input, pw, ph, pc, 4);
			if (img == null) {
				throw new IllegalArgumentException("not a JPEG: " + STBImage.stbi_failure_reason());
			}
			try {
				if (pw.get(0) != w || ph.get(0) != h) {
					throw new IllegalArgumentException("tile is " + pw.get(0) + "x" + ph.get(0) + ", expected " + w + "x" + h);
				}
				dst.put(dst.position(), img, 0, w * h * 4);
			} finally {
				STBImage.stbi_image_free(img);
			}
		}
	}

	@Override
	public void close() {
		callback.free();
		MemoryUtil.memFree(pixels);
		MemoryUtil.memFree(input);
	}
}
