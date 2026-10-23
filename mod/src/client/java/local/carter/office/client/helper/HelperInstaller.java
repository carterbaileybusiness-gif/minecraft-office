package local.carter.office.client.helper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Installs and launches OfficeHelper.app (macOS).
 *
 * <p>The release's {@code manifest.json} (Task 15a: {@code helper = {name, sha256, size, url}}) is read from the
 * release base URL; the zip is downloaded over HTTPS only, its size and SHA-256 are checked <em>before</em> anything is
 * unpacked, then {@code /usr/bin/ditto -x -k} unpacks it (keeping the code signature intact) into
 * {@code ~/Library/Application Support/Minecraft Office/OfficeHelper.app}. A marker file records the mod version the
 * installed helper belongs to, so a later launch of the same mod version needs no network.
 *
 * <p>Callers must have the player's consent before {@link #ensureInstalled} downloads anything (the in-game confirm
 * screen). Dev override: {@code -Doffice.helperPath=<path to OfficeHelper.app>} uses a local build and never downloads.
 */
public final class HelperInstaller {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	public static final String APP_NAME = "OfficeHelper.app";
	public static final String HELPER_PATH_PROPERTY = "office.helperPath";
	public static final String VERSION_MARKER = "OfficeHelper.version";
	/** Refuse absurd manifests (the helper zip is a few MB). */
	public static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;
	private static final long MAX_MANIFEST_BYTES = 256 * 1024;

	/** One release asset from the manifest. */
	public record Asset(String name, String sha256, long size, URI url) {}

	/** Why an install was refused or failed; the message is shown to the player. */
	public static final class InstallException extends IOException {
		public InstallException(final String message) {
			super(message);
		}
	}

	/** Downloads one HTTPS resource (at most {@code maxBytes}). */
	@FunctionalInterface
	public interface Fetcher {
		byte[] fetch(URI uri) throws IOException;
	}

	@FunctionalInterface
	public interface Launcher {
		void launch(Path app) throws IOException;
	}

	private final Path supportDir;
	private final String version;
	private final URI releaseBase;
	private final @Nullable String helperPathOverride;
	private final Fetcher fetcher;
	private final Launcher launcher;

	public HelperInstaller(final Path supportDir, final String version, final URI releaseBase, final @Nullable String helperPathOverride,
			final Fetcher fetcher, final Launcher launcher) {
		this.supportDir = supportDir;
		this.version = version;
		this.releaseBase = releaseBase;
		this.helperPathOverride = helperPathOverride == null || helperPathOverride.isBlank() ? null : helperPathOverride;
		this.fetcher = fetcher;
		this.launcher = launcher;
	}

	/** The real installer: Application Support folder, HTTPS fetcher, {@code open -g} launcher. */
	public static HelperInstaller create(final String modVersion, final String releaseBase) {
		return new HelperInstaller(defaultSupportDir(), modVersion, URI.create(releaseBase), System.getProperty(HELPER_PATH_PROPERTY),
				HelperInstaller::httpsFetch, HelperInstaller::launch);
	}

	/** {@code ~/Library/Application Support/Minecraft Office} (the helper's own folder: socket, token, log). */
	public static Path defaultSupportDir() {
		return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Minecraft Office");
	}

	public Path supportDir() {
		return supportDir;
	}

	/** True when the dev override {@code office.helperPath} is set. */
	public boolean usesOverride() {
		return helperPathOverride != null;
	}

	/** The helper to use without downloading anything: the dev override, or an installed helper of this mod version. */
	public Optional<Path> installedApp() {
		if (helperPathOverride != null) {
			Path p = Path.of(helperPathOverride).toAbsolutePath();
			return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
		}
		Path app = supportDir.resolve(APP_NAME);
		try {
			if (Files.isDirectory(app) && version.equals(Files.readString(supportDir.resolve(VERSION_MARKER)).trim())) {
				return Optional.of(app);
			}
		} catch (IOException e) {
			// no marker: not installed (or an older helper)
		}
		return Optional.empty();
	}

	/** Reads the release manifest's {@code helper} entry (small JSON; no app is downloaded). */
	public CompletableFuture<Asset> fetchHelperInfo() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return readManifest();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		});
	}

	/**
	 * The helper app, installing it first if needed (download, verify, unpack), then launched with {@code open -g}.
	 * Only call after the player agreed to the download.
	 */
	public CompletableFuture<Path> ensureInstalled() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Path app = installedApp().orElse(null);
				if (app == null) {
					if (helperPathOverride != null) {
						throw new InstallException("office.helperPath does not point to an app: " + helperPathOverride);
					}
					app = install(readManifest());
				}
				launcher.launch(app);
				return app;
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		});
	}

	private Asset readManifest() throws IOException {
		URI manifestUri = requireHttps(releaseBase.resolve("manifest.json"));
		byte[] bytes = fetcher.fetch(manifestUri);
		if (bytes.length > MAX_MANIFEST_BYTES) {
			throw new InstallException("release manifest is too large");
		}
		return parseHelper(new String(bytes, StandardCharsets.UTF_8));
	}

	/** The {@code helper} entry of a Task 15a manifest. */
	static Asset parseHelper(final String json) throws InstallException {
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			JsonElement h = root.get("helper");
			if (h == null || !h.isJsonObject()) {
				throw new InstallException("this release has no Mac helper yet");
			}
			JsonObject o = h.getAsJsonObject();
			String name = o.get("name").getAsString();
			String sha = o.get("sha256").getAsString().toLowerCase(Locale.ROOT);
			long size = o.get("size").getAsLong();
			URI url = URI.create(o.get("url").getAsString());
			if (name.isEmpty() || name.contains("/") || !name.endsWith(".zip")) {
				throw new InstallException("bad helper name in the release manifest");
			}
			return new Asset(name, sha, size, url);
		} catch (InstallException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new InstallException("unreadable release manifest: " + e.getMessage());
		}
	}

	/** Downloads, verifies (size + SHA-256) and unpacks {@code asset}; returns the installed app. */
	Path install(final Asset asset) throws IOException {
		requireHttps(asset.url());
		if (asset.size() <= 0 || asset.size() > MAX_ZIP_BYTES) {
			throw new InstallException("bad helper size in the release manifest");
		}
		if (!asset.sha256().matches("[0-9a-f]{64}")) {
			throw new InstallException("bad helper checksum in the release manifest");
		}
		ensureSupportDir();
		LOGGER.info("Office helper: downloading {} ({} bytes)", asset.url(), asset.size());
		byte[] zip = fetcher.fetch(asset.url());
		if (zip.length != asset.size()) {
			throw new InstallException("download size mismatch (" + zip.length + " bytes, expected " + asset.size() + ")");
		}
		String actual = sha256(zip);
		if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), asset.sha256().getBytes(StandardCharsets.US_ASCII))) {
			throw new InstallException("download checksum mismatch; not installing it");
		}
		Path staging = Files.createTempDirectory(supportDir, "install-");
		Path zipFile = staging.resolve(asset.name());
		try {
			Files.write(zipFile, zip);
			Path out = Files.createDirectory(staging.resolve("out"));
			run(new ProcessBuilder("/usr/bin/ditto", "-x", "-k", zipFile.toString(), out.toString()), "unpack");
			Path unpacked = out.resolve(APP_NAME);
			if (!Files.isDirectory(unpacked) || Files.isSymbolicLink(unpacked)) {
				throw new InstallException("the helper zip has no " + APP_NAME);
			}
			Path app = supportDir.resolve(APP_NAME);
			deleteTree(app);
			try {
				Files.move(unpacked, app, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(unpacked, app);
			}
			Files.writeString(supportDir.resolve(VERSION_MARKER), version + "\n");
			LOGGER.info("Office helper {} installed at {}", version, app);
			return app;
		} finally {
			deleteTree(staging);
		}
	}

	private void ensureSupportDir() throws IOException {
		Files.createDirectories(supportDir);
		Files.setPosixFilePermissions(supportDir, PosixFilePermissions.fromString("rwx------"));
	}

	/** Starts the helper in the background without stealing focus: {@code /usr/bin/open -g <app>}. */
	public static void launch(final Path app) throws IOException {
		run(new ProcessBuilder("/usr/bin/open", "-g", app.toString()), "launch");
	}

	private static void run(final ProcessBuilder pb, final String what) throws IOException {
		Process p = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		try {
			if (!p.waitFor(60, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				throw new InstallException("helper " + what + " timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new InstallException("helper " + what + " interrupted");
		}
		if (p.exitValue() != 0) {
			throw new InstallException("helper " + what + " failed (exit " + p.exitValue() + ")");
		}
	}

	static URI requireHttps(final URI uri) throws InstallException {
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new InstallException("refusing to download over " + uri.getScheme() + ": " + uri);
		}
		return uri;
	}

	/** HTTPS GET with redirects (never to plain HTTP), capped at {@link #MAX_ZIP_BYTES}. */
	static byte[] httpsFetch(final URI uri) throws IOException {
		requireHttps(uri);
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL) // NORMAL never follows HTTPS -> HTTP
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent", "MinecraftOffice").GET().build();
		try {
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				requireHttps(response.uri());
				if (response.statusCode() != 200) {
					throw new IOException("HTTP " + response.statusCode() + " for " + uri);
				}
				byte[] bytes = in.readNBytes((int) MAX_ZIP_BYTES + 1);
				if (bytes.length > MAX_ZIP_BYTES) {
					throw new InstallException("download too large: " + uri);
				}
				return bytes;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("download interrupted", e);
		}
	}

	static String sha256(final byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** "≈2.2 MB" (one decimal under 10 MB, at least 0.1). */
	public static String sizeLabel(final long bytes) {
		double mb = Math.max(0.1, bytes / (1024.0 * 1024.0));
		return mb < 10 ? String.format(Locale.ROOT, "≈%.1f MB", mb) : String.format(Locale.ROOT, "≈%d MB", Math.round(mb));
	}

	private static void deleteTree(final Path root) throws IOException {
		if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}
}
