package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.magic.core.Dir;

/**
 * The hand-sign prompt's rules, free of Minecraft so they can be unit-tested. Arrow keys and WASD (physical keys) type
 * arrows; any other key is ignored. A key that is already down (auto-repeat, or held when the prompt opened, e.g. W while
 * walking) counts only after it is released and pressed again. The first wrong arrow, Esc or the time limit ends the prompt
 * with what was typed so far; the server fizzles anything that is not the full sequence.
 */
public final class HandSignInput {
	public enum State {
		ENTERING, COMPLETE, MISSED, TIMED_OUT, GAVE_UP;

		public boolean finished() {
			return this != ENTERING;
		}
	}

	/** Every key {@link #dirFor} maps, for checking which are already held when the prompt opens. */
	public static final int[] KEYS = {
		InputConstants.KEY_UP, InputConstants.KEY_W, InputConstants.KEY_DOWN, InputConstants.KEY_S,
		InputConstants.KEY_LEFT, InputConstants.KEY_A, InputConstants.KEY_RIGHT, InputConstants.KEY_D,
	};

	private final Dir[] expected;
	private final long startMs;
	private final long limitMs;
	private final List<Dir> typed = new ArrayList<>();
	private final long[] typedAt;
	private final Set<Integer> held = new HashSet<>();
	private State state = State.ENTERING;
	private long endedAt = -1;

	/**
	 * @param heldAtOpen keys already down when the prompt opened: ignored until released
	 */
	public HandSignInput(Dir[] expected, long startMs, long limitMs, Collection<Integer> heldAtOpen) {
		this.expected = expected.clone();
		this.startMs = startMs;
		this.limitMs = limitMs;
		this.typedAt = new long[expected.length];
		this.held.addAll(heldAtOpen);
	}

	/** ↑/W → UP, ↓/S → DOWN, ←/A → LEFT, →/D → RIGHT (SDL3 physical key codes); anything else null. */
	public static @Nullable Dir dirFor(int keyCode) {
		return switch (keyCode) {
			case InputConstants.KEY_UP, InputConstants.KEY_W -> Dir.UP;
			case InputConstants.KEY_DOWN, InputConstants.KEY_S -> Dir.DOWN;
			case InputConstants.KEY_LEFT, InputConstants.KEY_A -> Dir.LEFT;
			case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> Dir.RIGHT;
			default -> null;
		};
	}

	/** A key went down at {@code nowMs}. Returns the state after it (unchanged for repeats, other keys and after the end). */
	public State keyDown(int keyCode, long nowMs) {
		if (state.finished()) return state;
		Dir d = dirFor(keyCode);
		if (d == null || !held.add(keyCode)) return state;
		int i = typed.size();
		typed.add(d);
		typedAt[i] = nowMs;
		if (d != expected[i]) {
			end(State.MISSED, nowMs);
		} else if (typed.size() == expected.length) {
			end(State.COMPLETE, nowMs);
		}
		return state;
	}

	public void keyUp(int keyCode) {
		held.remove(keyCode);
	}

	/** Esc: give up with what was typed (the server fizzles it). */
	public State escape(long nowMs) {
		if (!state.finished()) end(State.GAVE_UP, nowMs);
		return state;
	}

	/** Ends the prompt once the limit has passed. */
	public State tick(long nowMs) {
		if (!state.finished() && nowMs - startMs >= limitMs) end(State.TIMED_OUT, nowMs);
		return state;
	}

	private void end(State s, long nowMs) {
		state = s;
		endedAt = nowMs;
	}

	public State state() {
		return state;
	}

	/** When the prompt ended (-1 while entering). */
	public long endedAt() {
		return endedAt;
	}

	/** Time left as a fraction 1..0 (frozen when the prompt ends). */
	public float remaining(long nowMs) {
		long at = state.finished() ? endedAt : nowMs;
		return Math.clamp(1f - (float) (at - startMs) / limitMs, 0f, 1f);
	}

	/** Arrows typed right so far. */
	public int correct() {
		return state == State.MISSED ? typed.size() - 1 : typed.size();
	}

	/** Index of the wrong arrow, or -1. */
	public int missedAt() {
		return state == State.MISSED ? typed.size() - 1 : -1;
	}

	/** When arrow {@code i} was typed, or -1. */
	public long typedAt(int i) {
		return i < typed.size() ? typedAt[i] : -1;
	}

	/** What is sent to the server: everything typed, the wrong arrow included. */
	public Dir[] answer() {
		return typed.toArray(Dir[]::new);
	}

	public Dir[] expected() {
		return expected.clone();
	}

	public int length() {
		return expected.length;
	}
}
