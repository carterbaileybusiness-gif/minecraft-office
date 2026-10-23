package local.carter.office.client.selftest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Removes what identifies the person from self-test text (report, status lines, log lines): the game folder, the temp
 * folder (where natives are extracted), the home folder, any {@code C:\Users\name}, {@code /Users/name} or
 * {@code /home/name} path (any case, either slash, 8.3 short names like {@code JOHNSM~1}), and the login name as a
 * word. Pure; {@link #current} is configured from this JVM's properties and the game folder.
 */
public final class PrivateText {
	public static final String HOME = "~";
	public static final String USER = "<user>";
	public static final String GAME_DIR = "<game folder>";
	public static final String TEMP = "<temp>";
	/** Login names shorter than this aren't scrubbed as words (too likely to hit ordinary words). */
	static final int MIN_USER_CHARS = 3;

	private static final String NAME = "[^\\\\/\\s\"'<>|:;,()\\[\\]]+";
	private static final Pattern WINDOWS_USERS = Pattern.compile(
			"(?i)(?<![a-z])[a-z]:[\\\\/]+(?:users|documents and settings|docume~\\d+)[\\\\/]+" + NAME);
	private static final Pattern UNC_USERS = Pattern.compile("(?i)[\\\\/]{2}[^\\\\/\\s]+[\\\\/]+[a-z]\\$[\\\\/]+users[\\\\/]+" + NAME);
	private static final Pattern MAC_USERS = Pattern.compile("(?i)/users/" + NAME);
	private static final Pattern LINUX_HOME = Pattern.compile("(?i)/home/" + NAME);

	private static volatile PrivateText current = fromSystem(null);

	/** Folder → replacement, longest first (the game folder usually lives inside the home folder). */
	private final List<Rule> folders = new ArrayList<>();
	private final List<Pattern> userWords = new ArrayList<>();

	private record Rule(Pattern pattern, String with) {}

	public PrivateText(final @Nullable String home, final @Nullable String userName, final @Nullable String gameDir, final @Nullable String tempDir) {
		List<String[]> dirs = new ArrayList<>();
		add(dirs, gameDir, GAME_DIR);
		add(dirs, tempDir, TEMP);
		add(dirs, home, HOME);
		dirs.sort(Comparator.comparingInt((String[] d) -> d[0].length()).reversed());
		for (String[] d : dirs) {
			folders.add(new Rule(folderPattern(d[0]), d[1]));
		}
		if (userName != null && userName.trim().length() >= MIN_USER_CHARS) {
			String u = userName.trim();
			userWords.add(Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(u) + "(?![\\p{L}\\p{N}_])"));
			String alnum = u.replaceAll("[^A-Za-z0-9]", "");
			if (alnum.length() >= MIN_USER_CHARS) {
				// 8.3 short form of a long login name: "John Smith" -> JOHNSM~1
				String six = alnum.length() > 6 ? alnum.substring(0, 6) : alnum;
				userWords.add(Pattern.compile("(?i)(?<![a-z0-9])" + Pattern.quote(six) + "~\\d+"));
			}
		}
	}

	/** This JVM's home, login name and temp folder, plus the game folder once {@link #setGameDir} ran. */
	public static PrivateText current() {
		return current;
	}

	public static void setGameDir(final @Nullable Path gameDir) {
		current = fromSystem(gameDir == null ? null : gameDir.toAbsolutePath().normalize().toString());
	}

	private static PrivateText fromSystem(final @Nullable String gameDir) {
		return new PrivateText(System.getProperty("user.home"), System.getProperty("user.name"), gameDir, System.getProperty("java.io.tmpdir"));
	}

	public String scrub(final @Nullable String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		String out = s;
		for (Rule r : folders) {
			out = r.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(r.with()));
		}
		out = WINDOWS_USERS.matcher(out).replaceAll(HOME);
		out = UNC_USERS.matcher(out).replaceAll(HOME);
		out = MAC_USERS.matcher(out).replaceAll(HOME);
		out = LINUX_HOME.matcher(out).replaceAll(HOME);
		for (Pattern p : userWords) {
			out = p.matcher(out).replaceAll(Matcher.quoteReplacement(USER));
		}
		return out;
	}

	private static void add(final List<String[]> dirs, final @Nullable String dir, final String with) {
		if (dir == null) {
			return;
		}
		String d = dir.trim();
		while (d.length() > 1 && (d.endsWith("/") || d.endsWith("\\"))) {
			d = d.substring(0, d.length() - 1);
		}
		if (d.length() > 3) { // never "/", "C:\" or "~"
			dirs.add(new String[] {d, with});
		}
	}

	/** The folder in any case, with either slash (or doubled slashes) between its parts, not followed by more name. */
	static Pattern folderPattern(final String dir) {
		StringBuilder re = new StringBuilder("(?iu)");
		StringBuilder lit = new StringBuilder();
		for (int i = 0; i < dir.length(); i++) {
			char c = dir.charAt(i);
			if (c == '/' || c == '\\') {
				if (!lit.isEmpty()) {
					re.append(Pattern.quote(lit.toString()));
					lit.setLength(0);
				}
				re.append("[\\\\/]+");
			} else {
				lit.append(c);
			}
		}
		if (!lit.isEmpty()) {
			re.append(Pattern.quote(lit.toString()));
		}
		re.append("(?![^\\\\/\\s\"'<>|:;,()\\[\\]])");
		return Pattern.compile(re.toString());
	}
}
