package local.carter.office.client.render;

import java.nio.ByteBuffer;

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
 * buffer straight after. Render thread only.
 */
final class ScreenGpuTexture extends AbstractTexture implements ScreenTexture.Gpu {
	private final Identifier id;
	private boolean registered;

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
		if (!registered) {
			Minecraft.getInstance().getTextureManager().register(id, this);
			registered = true;
		}
	}

	@Override
	public void write(final int level, final int x, final int y, final int w, final int h, final ByteBuffer rgba) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), rgba, level, 0, x, y, w, h);
	}

	@Override
	public void free() {
		if (registered) {
			registered = false;
			Minecraft.getInstance().getTextureManager().release(id); // closes this -> releaseTextures()
		} else {
			releaseTextures();
		}
	}

	@Override
	public boolean canFreeNow() {
		return RenderSystem.isOnRenderThread();
	}
}
