package local.carter.office.client.render;

import net.minecraft.resources.Identifier;

/**
 * A hologram's live screen image, registered with the texture manager under {@link #id()}.
 * Minimal placeholder from Task 6 (the renderer only needs these three accessors); Task 7 fills in uploading.
 */
public class ScreenTexture {
	private final Identifier id;
	private final int width;
	private final int height;

	public ScreenTexture(final Identifier id, final int width, final int height) {
		this.id = id;
		this.width = width;
		this.height = height;
	}

	public Identifier id() {
		return id;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}
}
