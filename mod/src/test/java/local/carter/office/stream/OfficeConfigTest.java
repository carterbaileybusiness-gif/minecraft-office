package local.carter.office.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeConfigTest {
	@TempDir
	Path dir;

	@Test
	void missingFileIsCreatedWithTheDefaults() throws Exception {
		Path f = dir.resolve("config").resolve("office.json");
		assertEquals(new OfficeConfig(24455, 48, 2000), OfficeConfig.load(f));
		String json = Files.readString(f);
		assertTrue(json.contains("\"udpPort\": 24455") && json.contains("\"viewRange\": 48")
				&& json.contains("\"maxKbpsPerStream\": 2000"), json);
		assertEquals(OfficeConfig.DEFAULTS, OfficeConfig.load(f), "round-trips");
	}

	@Test
	void partialAndOutOfRangeValues() throws Exception {
		Path f = dir.resolve("office.json");
		Files.writeString(f, "{ \"udpPort\": 99999, \"viewRange\": 64 }");
		assertEquals(new OfficeConfig(65535, 64, 2000), OfficeConfig.load(f));
	}

	@Test
	void garbageFallsBackToDefaultsWithoutOverwriting() throws Exception {
		Path f = dir.resolve("office.json");
		Files.writeString(f, "{ not json");
		assertEquals(OfficeConfig.DEFAULTS, OfficeConfig.load(f));
		assertEquals("{ not json", Files.readString(f), "user's file left alone");
	}
}
