package local.carter.office.client.magic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import local.carter.office.client.magic.fx.ShaderCompat;
import local.carter.office.client.magic.fx.ShaderCompat.Path;

class ShaderCompatTest {
	@Test
	void aShaderPackInUseMeansThePlainPath() {
		assertEquals(Path.PLAIN, ShaderCompat.path(true));
		assertEquals(Path.ADDITIVE, ShaderCompat.path(false));
	}

	@Test
	void additiveLightIsPremultipliedWithFullAlpha() {
		assertEquals(0xFF804020, ShaderCompat.encode(Path.ADDITIVE, 0.5F, 0xFF8040));
		assertEquals(0xFFFF8040, ShaderCompat.encode(Path.ADDITIVE, 1F, 0xFF8040));
		assertEquals(0xFF000000, ShaderCompat.encode(Path.ADDITIVE, 0F, 0xFF8040));
		assertEquals(0xFFFF8040, ShaderCompat.encode(Path.ADDITIVE, 2F, 0xFF8040), "clamped to 1");
	}

	@Test
	void plainLightKeepsTheColourAndPutsTheIntensityInAlpha() {
		assertEquals(0x80FF8040, ShaderCompat.encode(Path.PLAIN, 0.5F, 0xFF8040));
		assertEquals(0xFFFF8040, ShaderCompat.encode(Path.PLAIN, 1F, 0x12FF8040), "the input's alpha is ignored");
		assertEquals(0x00FF8040, ShaderCompat.encode(Path.PLAIN, -1F, 0xFF8040), "clamped to 0");
	}

	@Test
	void withoutIrisThereIsNoShaderPackAndNothingThrows() {
		assertDoesNotThrow(ShaderCompat::shaderPackInUse);
		assertFalse(ShaderCompat.shaderPackInUse());
		assertDoesNotThrow(ShaderCompat::refresh);
		assertEquals(Path.ADDITIVE, ShaderCompat.current());
	}
}
