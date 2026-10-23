package local.carter.office.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

/**
 * The GPU half of a {@link ScreenTexture}: an RGBA8 texture with a mip chain, registered with the texture manager under
 * the screen's id so stock render types ({@code RenderTypes.text(id)}) can draw it. Its sampler is clamp-to-edge,
 * linear, with mipmaps (trilinear: {@code GL_LINEAR_MIPMAP_LINEAR} / Vulkan equivalent), and render types use the
 * texture's own sampler ({@code RenderSetup.prepareTextures}).
 *
 * <p>Uploads use {@code CommandEncoder.writeToTexture(texture, buffer, mipLevel, layer, x, y, w, h)}, which copies
 * synchronously (GL: {@code glTexSubImage2D}; Vulkan: into transient staging memory), so the caller may reuse the
 * buffer straight after. Render thread only (except {@link #runOnRenderThread}).
 *
 * <p>Ids are stable per hologram, so successive instances can share one id: the texture-manager slot belongs to the
 * instance that allocated last, and freeing an older instance never touches a newer one's texture.
 */
final class ScreenGpuTexture extends AbstractTexture implements ScreenTexture.Gpu {
	/** Which instance currently holds each id's texture-manager slot (render thread). */
	private static final Map<Identifier, ScreenGpuTexture> SLOT_OWNERS = new HashMap<>();

	private final Identifier id;

	ScreenGpuTexture(final Identifier id) {
		this.id = id;
		this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
				FilterMode.LINEAR, true);
	}

	@Override
	public void allocate(final int width, final int height, final int levels) {
		RenderSystem.assertOnRenderThread();
		releaseTextures();
		GpuDevice device = RenderSystem.getDevice();
		this.texture = device.createTexture(() -> "Office screen " + id, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, width, height, 1, levels);
		this.textureView = device.createTextureView(this.texture);
		if (SLOT_OWNERS.get(id) != this) {
			// replaces (and closes) an older instance with the same id, if any
			Minecraft.getInstance().getTextureManager().register(id, this);
			SLOT_OWNERS.put(id, this);
		}
	}

	@Override
	public void write(final int level, final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), rgba, level, 0, x, y, w, h);
	}

	@Override
	public void free() {
		RenderSystem.assertOnRenderThread();
		releaseTextures();
		if (SLOT_OWNERS.get(id) == this) {
			SLOT_OWNERS.remove(id);
			Minecraft.getInstance().getTextureManager().release(id); // closes this again: a no-op now
		}
	}

	@Override
	public boolean canFreeNow() {
		return RenderSystem.isOnRenderThread();
	}

	@Override
	public void runOnRenderThread(final Runnable task) {
		Minecraft.getInstance().execute(task);
	}
}
