package local.carter.office.stream;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server settings from {@code config/office.json}: {@code { "udpPort": 24455, "viewRange": 48, "maxKbpsPerStream": 2000 }}.
 * A missing file is created with the defaults; missing keys use the defaults; out-of-range values are clamped.
 * {@code udpPort} 0 turns UDP off (everything goes over the payload fallback).
 */
public record OfficeConfig(int udpPort, int viewRange, int maxKbpsPerStream) {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final OfficeConfig DEFAULTS = new OfficeConfig(24455, 48, 2000);
	public static final String FILE_NAME = "office.json";

	public OfficeConfig {
		udpPort = Math.max(0, Math.min(65535, udpPort));
		viewRange = Math.max(1, Math.min(512, viewRange));
		maxKbpsPerStream = Math.max(16, Math.min(1_000_000, maxKbpsPerStream));
	}

	/** Reads {@code file}, writing the defaults first if it does not exist. Never throws: problems log and use defaults. */
	public static OfficeConfig load(final Path file) {
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(file.getParent());
				Files.writeString(file, toJson(DEFAULTS), StandardCharsets.UTF_8);
				return DEFAULTS;
			}
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(r);
				if (!root.isJsonObject()) {
					throw new IllegalStateException("not a JSON object");
				}
				JsonObject o = root.getAsJsonObject();
				return new OfficeConfig(intOr(o, "udpPort", DEFAULTS.udpPort), intOr(o, "viewRange", DEFAULTS.viewRange),
						intOr(o, "maxKbpsPerStream", DEFAULTS.maxKbpsPerStream));
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("[office] Could not read {} ({}); using defaults {}", file, e.toString(), DEFAULTS);
			return DEFAULTS;
		}
	}

	private static int intOr(final JsonObject o, final String key, final int fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	static String toJson(final OfficeConfig c) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		JsonObject o = new JsonObject();
		o.addProperty("udpPort", c.udpPort);
		o.addProperty("viewRange", c.viewRange);
		o.addProperty("maxKbpsPerStream", c.maxKbpsPerStream);
		return gson.toJson(o) + "\n";
	}
}
