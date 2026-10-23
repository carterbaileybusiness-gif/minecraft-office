package local.carter.office.client.selftest;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import local.carter.office.client.platform.Platform;

/**
 * The self-test's plain-text report: facts about this computer, then one result line per step. Safe to paste
 * anywhere: it never holds screen pixels (steps only record frame sizes), window titles are replaced by
 * {@link #HIDDEN_TITLE} wherever they appear (only app names stay), personal paths and the login name are removed by
 * {@link PrivateText}, and IP addresses (the server's) in step results become {@link #HIDDEN_ADDRESS}. Thread-safe.
 */
public final class SelfTestReport {
	public static final String HIDDEN_TITLE = "<window title>";
	public static final String HIDDEN_ADDRESS = "<address>";
	/**
	 * Titles shorter than this aren't scrubbed (too likely to hit ordinary words); neither are titles that are part of an
	 * app name ("Finder" in "Finder", "Chrome" in "Google Chrome"), which would hide the app names the report keeps.
	 */
	static final int MIN_TITLE_CHARS = 4;
	private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?![\\d.])");
	/** Three or more colons: never a clock time ("12:34:56"). */
	private static final Pattern IPV6 = Pattern.compile("(?<![\\w:])\\[?(?:[0-9a-fA-F]{0,4}:){3,7}[0-9a-fA-F]{0,4}\\]?(?![\\w:])");

	public enum Status {
		PASS("✓", "OK"),
		FAIL("✗", "FAILED"),
		SKIP("–", "SKIPPED"),
		/** Not run yet (only seen in the live checklist). */
		PENDING("·", "waiting"),
		RUNNING("…", "running");

		private final String symbol;
		private final String word;

		Status(final String symbol, final String word) {
			this.symbol = symbol;
			this.word = word;
		}

		public String symbol() {
			return symbol;
		}

		public String word() {
			return word;
		}
	}

	/** One step's result; {@code millis} < 0 when the step didn't run. */
	public record Line(String id, String name, Status status, String detail, long millis) {}

	private final Map<String, String> info = new LinkedHashMap<>();
	private final Map<String, Line> lines = new LinkedHashMap<>();
	private final Set<String> titles = new HashSet<>();
	private final Set<String> appNames = new HashSet<>();
	private final PrivateText privacy;

	public SelfTestReport() {
		this(PrivateText.current());
	}

	SelfTestReport(final PrivateText privacy) {
		this.privacy = privacy;
	}

	/** A fact about this computer ("OS" → "Mac OS X 26.0 (aarch64)"); values are scrubbed. */
	public synchronized void info(final String key, final String value) {
		info.put(key, value == null ? "unknown" : value);
	}

	public synchronized @Nullable String infoValue(final String key) {
		return info.get(key);
	}

	/** Windows the player could pick: their titles are scrubbed from everything in the report, app names are kept. */
	public synchronized void hideWindowTitles(final Collection<Platform.WindowInfo> windows) {
		for (Platform.WindowInfo w : windows) {
			if (w.app() != null && !w.app().isBlank()) {
				appNames.add(w.app().trim().toLowerCase(Locale.ROOT));
			}
		}
		for (Platform.WindowInfo w : windows) {
			String t = w.title() == null ? "" : w.title().trim();
			String lower = t.toLowerCase(Locale.ROOT);
			if (t.length() >= MIN_TITLE_CHARS && appNames.stream().noneMatch(app -> app.contains(lower))) {
				titles.add(t);
			}
		}
	}

	/** Records (or replaces) a step's result. */
	public synchronized void result(final Line line) {
		lines.put(line.id(), line);
	}

	public synchronized List<Line> lines() {
		return List.copyOf(lines.values());
	}

	/** The report as pasted (scrubbed), stamped with {@code time}. */
	public synchronized String render(final ZonedDateTime time) {
		StringBuilder sb = new StringBuilder();
		sb.append("Minecraft Office screen self-test\n");
		sb.append("Created: ").append(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT))).append('\n');
		sb.append('\n');
		int keyWidth = info.keySet().stream().mapToInt(String::length).max().orElse(0);
		for (Map.Entry<String, String> e : info.entrySet()) {
			sb.append(pad(e.getKey() + ":", keyWidth + 2)).append(oneLine(scrub(e.getValue()))).append('\n');
		}
		sb.append('\n').append("Results:\n");
		int ok = 0;
		int failed = 0;
		int skipped = 0;
		for (Line l : lines.values()) {
			switch (l.status()) {
				case PASS -> ok++;
				case FAIL -> failed++;
				default -> skipped++;
			}
			sb.append(l.status().symbol()).append(' ').append(pad(l.status().word(), 8)).append(oneLine(scrubDetail(l.name())));
			String detail = oneLine(scrubDetail(l.detail()));
			if (!detail.isEmpty()) {
				sb.append(": ").append(detail);
			}
			if (l.millis() >= 0) {
				sb.append(" (").append(l.millis()).append(" ms)");
			}
			sb.append('\n');
		}
		sb.append('\n').append("Summary: ").append(ok).append(" OK, ").append(failed).append(" failed, ").append(skipped).append(" skipped\n");
		sb.append("No screen pictures, window titles or server address are included.\n");
		return sb.toString();
	}

	/** Removes window titles, personal paths and the login name from {@code s}. */
	public synchronized String scrub(final String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		String out = s;
		List<String> sorted = new ArrayList<>(titles);
		sorted.sort(Comparator.comparingInt(String::length).reversed()); // longest first: a title containing another
		for (String t : sorted) {
			out = replaceIgnoreCase(out, t, HIDDEN_TITLE);
		}
		return privacy.scrub(out);
	}

	/** {@link #scrub} plus IP addresses (step details may quote a socket error; info lines hold versions like 25.0.4.1). */
	synchronized String scrubDetail(final String s) {
		String out = scrub(s);
		out = IPV4.matcher(out).replaceAll(HIDDEN_ADDRESS);
		return IPV6.matcher(out).replaceAll(HIDDEN_ADDRESS);
	}

	private static String replaceIgnoreCase(final String s, final String find, final String with) {
		String lower = s.toLowerCase(Locale.ROOT);
		String f = find.toLowerCase(Locale.ROOT);
		if (lower.length() != s.length() || f.length() != find.length()) {
			return s.replace(find, with); // case folding changed lengths (rare scripts): exact match only
		}
		StringBuilder sb = new StringBuilder();
		int from = 0;
		int at;
		while ((at = lower.indexOf(f, from)) >= 0) {
			sb.append(s, from, at).append(with);
			from = at + f.length();
		}
		return sb.append(s, from, s.length()).toString();
	}

	private static String oneLine(final String s) {
		return s.replace("\r\n", "; ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
	}

	private static String pad(final String s, final int width) {
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < width) {
			sb.append(' ');
		}
		return sb.toString();
	}
}
