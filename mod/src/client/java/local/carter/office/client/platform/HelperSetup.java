package local.carter.office.client.platform;

import java.nio.file.Path;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The helper set-up decisions of {@link MacPlatform}, without Minecraft or I/O (client thread only). A "session" is one
 * stay in a world; answers that arrive after the player left are still recorded (an installed helper stays installed)
 * but never act on a world the player has left.
 */
public final class HelperSetup {
	public enum State { UNKNOWN, ASKING, INSTALLING, DECLINED, FAILED, READY }

	/** What MacPlatform should do next. */
	public enum Action {
		/** Nothing to do (already set up and running, or waiting for an answer). */
		NONE,
		/** Connect to (and if needed launch) {@link #app()}. */
		START,
		/** Read the release manifest, then ask the player. */
		ASK,
		/** {@code office.helperPath} points nowhere: tell the player. */
		BAD_OVERRIDE,
		/** Tell the player the step failed (offer "Try again"). */
		REPORT_FAILURE
	}

	private State state = State.UNKNOWN;
	private int session;
	private @Nullable Path app;
	/** Session in which an own hologram asked for the helper while a download was running. */
	private int wantedDuringInstall = -1;

	public State state() {
		return state;
	}

	public int session() {
		return session;
	}

	public @Nullable Path app() {
		return app;
	}

	/** An own hologram needs the helper. */
	public Action ensure(final Optional<Path> installed, final boolean usesOverride) {
		switch (state) {
			case READY:
				return Action.START;
			case UNKNOWN:
				if (installed.isPresent()) {
					app = installed.get();
					state = State.READY;
					return Action.START;
				}
				if (usesOverride) {
					state = State.FAILED;
					return Action.BAD_OVERRIDE;
				}
				state = State.ASKING;
				return Action.ASK;
			case INSTALLING:
				wantedDuringInstall = session;
				return Action.NONE;
			default:
				return Action.NONE;
		}
	}

	/** The manifest was read (show the confirm screen: true) or could not be (false: report). */
	public boolean manifestRead(final int askedIn, final boolean ok) {
		if (askedIn != session || state != State.ASKING) {
			return false;
		}
		if (!ok) {
			state = State.FAILED;
		}
		return ok;
	}

	/** The player answered the confirm screen; true = start the download. */
	public boolean answered(final int askedIn, final boolean yes) {
		if (askedIn != session) {
			return false;
		}
		if (!yes) {
			state = State.DECLINED;
			return false;
		}
		state = State.INSTALLING;
		wantedDuringInstall = session;
		return true;
	}

	/**
	 * The download/unpack finished ({@code installed} non-null) or failed. The outcome is always recorded; the helper is
	 * only started if a hologram in the <em>current</em> world wants it, and a failure is only reported to the player who
	 * is still in the world that asked.
	 */
	public Action installFinished(final int startedIn, final @Nullable Path installed) {
		boolean wanted = wantedDuringInstall == session;
		wantedDuringInstall = -1;
		if (installed != null) {
			app = installed;
			state = State.READY;
			return wanted ? Action.START : Action.NONE;
		}
		if (startedIn == session) {
			state = State.FAILED;
			return Action.REPORT_FAILURE;
		}
		state = State.UNKNOWN; // left meanwhile: ask again next time
		return Action.NONE;
	}

	/** {@code /officescreens setup} after "Not now" or a failure: start over (asks again). */
	public void retry() {
		if (state == State.DECLINED || state == State.FAILED) {
			state = State.UNKNOWN;
		}
	}

	/** The player left the world: "Not now" and failures are forgotten; a running download keeps going. */
	public void leftWorld() {
		session++;
		if (state == State.ASKING || state == State.DECLINED || state == State.FAILED) {
			state = State.UNKNOWN;
		}
	}
}
