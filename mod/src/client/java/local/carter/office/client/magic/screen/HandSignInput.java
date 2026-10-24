package local.carter.office.client.magic.screen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import local.carter.office.magic.core.Dir;

/**
 * The hand-sign prompt's rules, free of Minecraft so they can be unit-tested. The arrow keys always type arrows, and so do
 * the keys bound to walking forward/back/left/right ({@link #bindings}; WASD by default, whatever the player rebound them
 * to otherwise); any other key is ignored. A key that is already down (auto-repeat, or held when the prompt opened, e.g. W
 * while walking) counts only after it is released and pressed again. The first wrong arrow, Esc or the time limit ends the
 * prompt with what was typed so far; the server fizzles anything that is not the full sequence and stays the only authority
 * on time. The limit runs on the client level's game time ({@link #advance}), so it follows the server's tick rate;
 * animation times ({@code nowMs}) are wall-clock.
 */
public final class HandSignInput {
	public enum State {
		ENTERING, COMPLETE, MISSED, TIMED_OUT, GAVE_UP;

		public boolean finished() {
			return this != ENTERING;
		}
	}

	/** Marks a movement direction with no keyboard key (unbound, or bound to a mouse button) in {@link #bindings}. */
	public static final int NO_KEY = -1;
	/** Arrows plus WASD: the mapping with default movement keys. */
	public static final Map<Integer, Dir> DEFAULT = bindings(InputConstants.KEY_W, InputConstants.KEY_S, InputConstants.KEY_A, InputConstants.KEY_D);

	private final Dir[] expected;
	private final Map<Integer, Dir> keys;
	private final double startTick;
	private final int limitTicks;
	private double lastTick;
	private double endedTick = Double.NaN;
	private final List<Dir> typed = new ArrayList<>();
	private final long[] typedAt;
	private final Set<Integer> held = new HashSet<>();
	private State state = State.ENTERING;
	private long endedAt = -1;

	/**
	 * @param keys       key code → arrow ({@link #bindings})
	 * @param startTick  client game time when the prompt opened (with the partial tick)
	 * @param limitTicks the challenge's limit
	 * @param heldAtOpen keys already down when the prompt opened: ignored until released
	 */
	public HandSignInput(Dir[] expected, Map<Integer, Dir> keys, double startTick, int limitTicks, Collection<Integer> heldAtOpen) {
		this.expected = expected.clone();
		this.keys = Map.copyOf(keys);
		this.startTick = startTick;
		this.lastTick = startTick;
		this.limitTicks = limitTicks;
		this.typedAt = new long[expected.length];
		this.held.addAll(heldAtOpen);
	}

	/**
	 * The keys that type arrows: the movement keys bound to forward/back/left/right ({@link #NO_KEY} for none), then the
	 * arrow keys, which always work and win if a movement key is bound to an arrow key of another direction. SDL3 physical
	 * key codes ({@code InputConstants.KEY_*}, the same as {@code KeyEvent.key()}).
	 */
	public static Map<Integer, Dir> bindings(int forward, int back, int left, int right) {
		Map<Integer, Dir> m = new LinkedHashMap<>();
		if (forward != NO_KEY) m.put(forward, Dir.UP);
		if (back != NO_KEY) m.put(back, Dir.DOWN);
		if (left != NO_KEY) m.put(left, Dir.LEFT);
		if (right != NO_KEY) m.put(right, Dir.RIGHT);
		m.put(InputConstants.KEY_UP, Dir.UP);
		m.put(InputConstants.KEY_DOWN, Dir.DOWN);
		m.put(InputConstants.KEY_LEFT, Dir.LEFT);
		m.put(InputConstants.KEY_RIGHT, Dir.RIGHT);
		return Map.copyOf(m);
	}

	/** With default movement keys: ↑/W → UP, ↓/S → DOWN, ←/A → LEFT, →/D → RIGHT; anything else null. */
	public static @Nullable Dir dirFor(int keyCode) {
		return DEFAULT.get(keyCode);
	}

	/** This prompt's mapping: the arrow for {@code keyCode}, or null. */
	public @Nullable Dir dir(int keyCode) {
		return keys.get(keyCode);
	}

	/** Every key that types an arrow here (to check which are held when the prompt opens). */
	public Set<Integer> keys() {
		return keys.keySet();
	}

	/** A key went down at {@code nowMs}. Returns the state after it (unchanged for repeats, other keys and after the end). */
	public State keyDown(int keyCode, long nowMs) {
		if (state.finished()) return state;
		Dir d = keys.get(keyCode);
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

	/**
	 * Moves the timer to client game time {@code gameTick} (never backwards: a server time correction can step the client
	 * clock back) and ends the prompt once the limit has passed.
	 */
	public State advance(double gameTick, long nowMs) {
		lastTick = Math.max(lastTick, gameTick);
		if (!state.finished() && lastTick - startTick >= limitTicks) end(State.TIMED_OUT, nowMs);
		return state;
	}

	private void end(State s, long nowMs) {
		state = s;
		endedAt = nowMs;
		endedTick = lastTick;
	}

	public State state() {
		return state;
	}

	/** When the prompt ended (-1 while entering). */
	public long endedAt() {
		return endedAt;
	}

	/** Time left as a fraction 1..0 at the last {@link #advance} (frozen when the prompt ends). */
	public float remaining() {
		double at = state.finished() ? endedTick : lastTick;
		return (float) Math.clamp(1.0 - (at - startTick) / limitTicks, 0.0, 1.0);
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
