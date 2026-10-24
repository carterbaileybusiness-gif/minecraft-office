package local.carter.office.client.magic.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The Skill tree's coach hint is shown once per player: the players (by UUID) who have seen it, one per line in
 * {@code config/office-skill-tree-hints.txt} on this computer. A file that can't be read or written only means the hint may show
 * again.
 */
public final class TreeHints {
	private static @Nullable Set<UUID> seen;

	private TreeHints() {}

	static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("office-skill-tree-hints.txt");
	}

	/** True the first time it is asked for {@code player} (then never again: it is remembered on disk). */
	public static synchronized boolean firstTime(UUID player) {
		Set<UUID> s = load();
		if (!s.add(player)) return false;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), String.join("\n", s.stream().map(UUID::toString).toList()) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("office").warn("[office] Could not remember the skill tree hint was shown", e);
		}
		return true;
	}

	private static Set<UUID> load() {
		if (seen != null) return seen;
		Set<UUID> s = new HashSet<>();
		try {
			if (Files.exists(file())) {
				for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
					try {
						if (!line.isBlank()) s.add(UUID.fromString(line.trim()));
					} catch (IllegalArgumentException bad) {
						// skip a damaged line
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("office").warn("[office] Could not read the skill tree hints", e);
		}
		seen = s;
		return s;
	}
}
