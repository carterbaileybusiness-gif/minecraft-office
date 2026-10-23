package local.carter.office.client.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import local.carter.office.client.helper.HelperInstaller.Asset;
import local.carter.office.client.helper.HelperInstaller.InstallException;

class HelperInstallerTest {
	private static final String VERSION = "0.1.0";
	private static final String BASE = "https://github.com/carterbaileybusiness-gif/minecraft-office/releases/download/v0.1.0/";
	private static final String ZIP_URL = BASE + "OfficeHelper-0.1.0.zip";

	private Path dir;
	private final Map<String, byte[]> served = new HashMap<>();
	private final List<URI> fetched = new ArrayList<>();
	private final List<Path> launched = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		dir = Files.createTempDirectory("office-install").resolve("Minecraft Office");
	}

	@AfterEach
	void tearDown() throws IOException {
		Path root = dir.getParent();
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private final List<Long> fetchLimits = new ArrayList<>();
	private final List<Path> verified = new ArrayList<>();
	/** Stands in for codesign (the test bundles are unsigned); a test can make it refuse. */
	private boolean signatureOk = true;

	private HelperInstaller installer(final String base, final String helperPathOverride) {
		return new HelperInstaller(dir, VERSION, URI.create(base), helperPathOverride, (uri, max) -> {
			fetched.add(uri);
			fetchLimits.add(max);
			byte[] b = served.get(uri.toString());
			if (b == null) {
				throw new IOException("404 " + uri);
			}
			return b;
		}, launched::add, app -> {
			verified.add(app);
			if (!signatureOk) {
				throw new InstallException("the downloaded helper's signature does not verify; not installing it");
			}
		});
	}

	private void assertNothingInstalled() throws IOException {
		assertFalse(Files.exists(dir.resolve(HelperInstaller.APP_NAME)), "nothing installed");
		assertTrue(launched.isEmpty(), "nothing launched");
		try (Stream<Path> s = Files.list(dir)) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith("install-")), "staging removed");
		}
	}

	private static byte[] zipOf(final String... namesAndContents) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (int i = 0; i < namesAndContents.length; i += 2) {
				zip.putNextEntry(new ZipEntry(namesAndContents[i]));
				zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private void serveZip(final byte[] zip) throws Exception {
		served.put(ZIP_URL, zip);
		serveManifest(ZIP_URL, sha256(zip), zip.length);
	}

	// macOS helper installer only: ensureSupportDir sets POSIX rwx------ (throws on Windows); never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void zipSlipIsRefusedBeforeUnpacking() throws Exception {
		Path outside = dir.getParent().resolve("evil.txt");
		for (String bad : new String[] {"OfficeHelper.app/../../evil.txt", "../evil.txt", "/tmp/evil.txt", "Other.app/x"}) {
			served.clear();
			serveZip(zipOf("OfficeHelper.app/Contents/Info.plist", "<plist/>", bad, "pwned"));
			Throwable t = failure(installer(BASE, null).ensureInstalled());
			assertInstanceOf(InstallException.class, t, bad);
			assertNothingInstalled();
			assertFalse(Files.exists(outside), "nothing written outside: " + bad);
		}
		assertTrue(verified.isEmpty(), "never got as far as the signature check");
	}

	@Test
	void symlinkOutOfTheBundleIsRefused() throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/ditto")), "needs macOS ditto");
		// Build a bundle with a link to /etc and zip it the way release-helper.sh does (ditto keeps links).
		Path src = Files.createDirectories(dir.getParent().resolve("src/OfficeHelper.app/Contents"));
		Files.writeString(src.resolve("Info.plist"), "<plist/>");
		Files.createSymbolicLink(src.resolve("Resources"), Path.of("/etc"));
		Path zipFile = dir.getParent().resolve("evil.zip");
		Process p = new ProcessBuilder("/usr/bin/ditto", "-c", "-k", "--keepParent", src.getParent().toString(), zipFile.toString())
				.redirectErrorStream(true).start();
		assertEquals(0, p.waitFor());
		serveZip(Files.readAllBytes(zipFile));

		Throwable t = failure(installer(BASE, null).ensureInstalled());
		assertInstanceOf(InstallException.class, t);
		assertTrue(t.getMessage().contains("links outside"), t.getMessage());
		assertNothingInstalled();

		// The post-unpack check catches the same thing on its own.
		Path unpacked = Files.createDirectories(dir.getParent().resolve("check/OfficeHelper.app/Contents"));
		Files.createSymbolicLink(unpacked.resolve("Frameworks"), Path.of("../../../outside"));
		assertThrows(InstallException.class, () -> HelperInstaller.checkLinksStayInside(unpacked.getParent()));
		Files.delete(unpacked.resolve("Frameworks"));
		Files.createSymbolicLink(unpacked.resolve("Current"), Path.of("Resources")); // inside: fine
		HelperInstaller.checkLinksStayInside(unpacked.getParent());
	}

	@Test
	void badSignatureRefusesTheInstall() throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/ditto")), "needs macOS ditto");
		serveZip(fakeAppZip());
		signatureOk = false;
		Throwable t = failure(installer(BASE, null).ensureInstalled());
		assertInstanceOf(InstallException.class, t);
		assertTrue(t.getMessage().contains("signature"), t.getMessage());
		assertEquals(1, verified.size(), "checked the staged app");
		assertNothingInstalled();
	}

	@Test
	void realCodesignRejectsAnUnsignedBundle() throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/codesign")), "needs macOS codesign");
		Path app = Files.createDirectories(dir.getParent().resolve("unsigned/OfficeHelper.app/Contents"));
		Files.writeString(app.resolve("Info.plist"), "<plist/>");
		assertThrows(InstallException.class, () -> HelperInstaller.codesignVerify(app.getParent()));
	}

	@Test
	void downloadIsCappedAtTheManifestSizeAndTheBaseGetsASlash() throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/ditto")), "needs macOS ditto");
		byte[] zip = fakeAppZip();
		serveZip(zip);
		installer(BASE.substring(0, BASE.length() - 1), null).ensureInstalled().get(20, TimeUnit.SECONDS);
		assertEquals(List.of(URI.create(BASE + "manifest.json"), URI.create(ZIP_URL)), fetched, "base without '/' still finds manifest.json");
		assertEquals(zip.length + 1L, fetchLimits.get(1), "zip buffer capped at size + 1");
	}

	private void serveManifest(final String url, final String sha256, final long size) {
		String json = "{\"schema\":1,\"version\":\"0.1.0\",\"tag\":\"v0.1.0\","
				+ "\"mod\":{\"name\":\"office-0.1.0.jar\",\"sha256\":\"ab\",\"size\":1,\"url\":\"" + BASE + "office-0.1.0.jar\"},"
				+ "\"helper\":{\"name\":\"OfficeHelper-0.1.0.zip\",\"sha256\":\"" + sha256 + "\",\"size\":" + size + ",\"url\":\"" + url + "\"}}";
		served.put(BASE + "manifest.json", json.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] fakeAppZip() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("OfficeHelper.app/"));
			zip.putNextEntry(new ZipEntry("OfficeHelper.app/Contents/"));
			zip.putNextEntry(new ZipEntry("OfficeHelper.app/Contents/Info.plist"));
			zip.write("<plist/>".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("OfficeHelper.app/Contents/MacOS/"));
			zip.putNextEntry(new ZipEntry("OfficeHelper.app/Contents/MacOS/OfficeHelper"));
			zip.write("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	private static String sha256(final byte[] b) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
	}

	private static Throwable failure(final java.util.concurrent.CompletableFuture<?> f) throws Exception {
		ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(10, TimeUnit.SECONDS));
		return e.getCause();
	}

	@Test
	void readsTheHelperEntryOfTheReleaseManifest() throws Exception {
		serveManifest(ZIP_URL, "c0ffee", 2_345_678);
		Asset a = installer(BASE, null).fetchHelperInfo().get(10, TimeUnit.SECONDS);
		assertEquals(new Asset("OfficeHelper-0.1.0.zip", "c0ffee", 2_345_678, URI.create(ZIP_URL)), a);
		assertEquals(List.of(URI.create(BASE + "manifest.json")), fetched);
	}

	// macOS helper installer only: ensureSupportDir sets POSIX rwx------ (throws on Windows); never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void checksumMismatchRefusesTheInstall() throws Exception {
		byte[] zip = fakeAppZip();
		served.put(ZIP_URL, zip);
		serveManifest(ZIP_URL, sha256("something else".getBytes(StandardCharsets.UTF_8)), zip.length);

		Throwable t = failure(installer(BASE, null).ensureInstalled());
		assertInstanceOf(InstallException.class, t);
		assertTrue(t.getMessage().contains("checksum"), t.getMessage());
		assertFalse(Files.exists(dir.resolve(HelperInstaller.APP_NAME)), "nothing installed");
		assertTrue(launched.isEmpty(), "nothing launched");
		try (Stream<Path> s = Files.list(dir)) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".zip") || p.getFileName().toString().startsWith("install-")),
					"download removed");
		}
	}

	// macOS helper installer only: ensureSupportDir sets POSIX rwx------ (throws on Windows); never reached on Windows.
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void sizeMismatchRefusesTheInstall() throws Exception {
		byte[] zip = fakeAppZip();
		served.put(ZIP_URL, zip);
		serveManifest(ZIP_URL, sha256(zip), zip.length + 1);
		Throwable t = failure(installer(BASE, null).ensureInstalled());
		assertInstanceOf(InstallException.class, t);
		assertFalse(Files.exists(dir.resolve(HelperInstaller.APP_NAME)));
	}

	@Test
	void plainHttpIsNeverUsed() throws Exception {
		Throwable t = failure(installer("http://example.com/releases/", null).fetchHelperInfo());
		assertInstanceOf(InstallException.class, t);
		assertTrue(fetched.isEmpty(), "nothing fetched over http");

		byte[] zip = fakeAppZip();
		served.put("http://example.com/OfficeHelper-0.1.0.zip", zip);
		serveManifest("http://example.com/OfficeHelper-0.1.0.zip", sha256(zip), zip.length);
		t = failure(installer(BASE, null).ensureInstalled());
		assertInstanceOf(InstallException.class, t);
		assertEquals(List.of(URI.create(BASE + "manifest.json")), fetched, "the http zip URL was not fetched");
	}

	@Test
	void installsAVerifiedZipLaunchesItAndReusesIt() throws Exception {
		assumeTrue(Files.isExecutable(Path.of("/usr/bin/ditto")), "needs macOS ditto");
		byte[] zip = fakeAppZip();
		served.put(ZIP_URL, zip);
		serveManifest(ZIP_URL, sha256(zip), zip.length);

		Path app = installer(BASE, null).ensureInstalled().get(20, TimeUnit.SECONDS);
		assertEquals(dir.resolve(HelperInstaller.APP_NAME), app);
		assertTrue(Files.isRegularFile(app.resolve("Contents/Info.plist")));
		assertEquals(List.of(app), launched);
		assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));

		fetched.clear();
		HelperInstaller again = installer(BASE, null);
		assertEquals(Optional.of(app), again.installedApp());
		assertEquals(app, again.ensureInstalled().get(10, TimeUnit.SECONDS));
		assertTrue(fetched.isEmpty(), "an installed helper of this version is not downloaded again");

		// A different mod version needs a fresh helper.
		HelperInstaller newer = new HelperInstaller(dir, "0.2.0", URI.create(BASE), null, (uri, max) -> new byte[0], p -> {});
		assertEquals(Optional.empty(), newer.installedApp());
	}

	@Test
	void helperPathOverrideSkipsTheDownload() throws Exception {
		Path local = Files.createDirectories(dir.getParent().resolve("build/OfficeHelper.app"));
		HelperInstaller i = installer(BASE, local.toString());
		assertEquals(Optional.of(local), i.installedApp());
		assertEquals(local, i.ensureInstalled().get(10, TimeUnit.SECONDS));
		assertTrue(fetched.isEmpty());
		assertEquals(List.of(local), launched);
	}

	@Test
	void sizeLabelIsRoughMegabytes() {
		assertEquals("≈2.2 MB", HelperInstaller.sizeLabel(2_345_678));
		assertEquals("≈0.1 MB", HelperInstaller.sizeLabel(1_000));
		assertEquals("≈12 MB", HelperInstaller.sizeLabel(12_400_000));
	}
}
