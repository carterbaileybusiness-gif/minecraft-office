package local.carter.office.client.magic.fx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntConsumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.SpellIds;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Everything a {@link SpellFx} draws with, made fresh for each fx event ({@link FxClient}): circles, particles, motes, bolts,
 * ghost blocks, lingering client timing, camera shake, hit-stop and FOV kicks. Every call draws from the event's {@link FxBudget}
 * (400 particles, 1500 for an ultimate, 128 ghost blocks, water cells included) and draws only what it grants, so no spell can flood a client.
 * Vanilla particles follow the player's Particles setting; on Minimal the kit's own motes are skipped too (circles stay).
 *
 * <p>Lasting things (circles that last, {@link #simulate} timers, ghost blocks) are registered under the caster, so
 * {@link #cancel(int)} (an {@code FxCancel}) stops them all.
 */
public final class FxKit {
	private static final Logger LOGGER = LoggerFactory.getLogger("office");

	private final FxEvent event;
	private final ClientLevel level;
	private final FxBudget budget;
	private final RandomSource random;
	private int circles;
	/** This event's ghost water, one body (made on the first {@link #ghostFluid}). */
	private GhostBlocks.@Nullable FluidBody water;

	/** Most circles one event may draw. */
	public static final int MAX_CIRCLES = 6;
	/** Longest {@link #simulate} timer, in ticks, and most timers running at once across every event. */
	/** Long enough for the longest lasting visual: a ranked Earth Wall stands 870 ticks. */
	public static final int MAX_SIMULATE_TICKS = 900;
	public static final int MAX_SIMULATIONS = 64;

	public FxKit(FxEvent event, ClientLevel level) {
		this.event = event;
		this.level = level;
		this.budget = new FxBudget(isUltimate(event.spell()));
		this.random = RandomSource.create(event.seed());
	}

	/** Whether {@code spell} is an element's ultimate (slot 4). */
	public static boolean isUltimate(Identifier spell) {
		for (Element e : Element.values()) {
			if (SpellIds.at(e, Slots.ULTIMATE).equals(spell)) return true;
		}
		return false;
	}

	public FxEvent event() {
		return event;
	}

	public ClientLevel level() {
		return level;
	}

	public FxBudget budget() {
		return budget;
	}

	/** The event's random (seeded from {@code event.seed()}, so every client draws the same spread). */
	public RandomSource random() {
		return random;
	}

	// ---- circles ---------------------------------------------------------------------------------------------------------

	/**
	 * A summoning circle at {@code centre} facing {@code normal} (it reads correctly from that side), {@code radius} to its outer
	 * ring, with {@code rings} rings (1..5) appearing one by one, for {@code lifeTicks}. The colour's alpha scales its brightness
	 * (00 = full); an element's colour gives that element's glyph at the heart. Costs one particle.
	 */
	public void circle(Vec3 centre, Vec3 normal, int argb, float radius, int rings, int lifeTicks) {
		// A circle is thousands of quads: at most MAX_CIRCLES per event, whatever the particle budget says.
		if (circles >= MAX_CIRCLES || budget.particles(1) == 0) return;
		circles++;
		CircleParticle.Fixed c = new CircleParticle.Fixed(level, centre, normal, argb, radius, rings, lifeTicks, event.casterId());
		Minecraft.getInstance().particleEngine.add(c);
		lasting(event.casterId(), c::fadeOut, c::isAlive);
	}

	// ---- particles -------------------------------------------------------------------------------------------------------

	/** {@code count} particles evenly along {@code from} → {@code to} (a little jitter), standing still. */
	public void trail(Vec3 from, Vec3 to, ParticleOptions p, int count) {
		int n = vanilla(count);
		for (int i = 0; i < n; i++) {
			double t = n == 1 ? 0.5 : i / (double) (n - 1);
			Vec3 at = from.lerp(to, t);
			level.addParticle(p, at.x + jitter(0.06), at.y + jitter(0.06), at.z + jitter(0.06), 0, 0, 0);
		}
	}

	/** {@code count} particles bursting from {@code at} in every direction at up to {@code speed} blocks a tick. */
	public void burst(Vec3 at, ParticleOptions p, int count, double speed) {
		int n = vanilla(count);
		for (int i = 0; i < n; i++) {
			Vec3 d = sphere().scale(speed * (0.35 + 0.65 * random.nextDouble()));
			level.addParticle(p, at.x, at.y, at.z, d.x, d.y, d.z);
		}
	}

	/** {@code count} particles round a flat ring of {@code radius} about {@code centre}, drifting gently outward. */
	public void ring(Vec3 centre, double radius, ParticleOptions p, int count) {
		int n = vanilla(count);
		double turn = random.nextDouble() * Mth.TWO_PI;
		for (int i = 0; i < n; i++) {
			double a = turn + Mth.TWO_PI * i / Math.max(1, n);
			double cx = Math.cos(a);
			double cz = Math.sin(a);
			level.addParticle(p, centre.x + cx * radius, centre.y, centre.z + cz * radius, cx * 0.03, 0.005, cz * 0.03);
		}
	}

	/**
	 * Glowing motes (additive light, the kit's own particle) in {@code rgb}: {@code count} of them about {@code at}, spread
	 * {@code spread}, each pushed by {@code velocity} plus a random {@code speed}, moving by {@code drift}. Skipped on Minimal particles.
	 */
	public void motes(Vec3 at, int rgb, int count, double spread, Vec3 velocity, double speed, GlowMote.Drift drift, float size, int life) {
		ParticleStatus status = Minecraft.getInstance().options.particles().get();
		if (status == ParticleStatus.MINIMAL) return;
		int n = budget.particles(status == ParticleStatus.DECREASED ? (count + 1) / 2 : count);
		Minecraft mc = Minecraft.getInstance();
		for (int i = 0; i < n; i++) {
			Vec3 p = at.add(jitter(spread), jitter(spread), jitter(spread));
			Vec3 v = velocity.add(sphere().scale(speed * random.nextDouble()));
			mc.particleEngine.add(new GlowMote(level, p, v, rgb, size * (0.7F + 0.6F * random.nextFloat()), life + random.nextInt(Math.max(1, life / 3)),
					drift, random.nextInt(3) == 0));
		}
	}

	/** A jagged lightning bolt {@code from} → {@code to} with a white core and forks, struck from {@code seed} (the same on every client). */
	public void bolt(Vec3 from, Vec3 to, long seed, int argb) {
		if (budget.particles(1) == 0) return;
		Minecraft.getInstance().particleEngine.add(new BoltParticle(level, from, to, seed, argb, 7));
	}

	// ---- ghost blocks ----------------------------------------------------------------------------------------------------

	/** A client-only block model at {@code pos} + {@code offset} for {@code lifeTicks}, moving by {@code motion}; no world change. */
	public void ghostBlock(BlockPos pos, BlockState state, Vec3 offset, int lifeTicks, GhostBlocks.Motion motion) {
		if (!budget.ghostBlock()) return;
		GhostBlocks.add(pos, state, offset, lifeTicks, motion, event.casterId());
	}

	/**
	 * A client-only cell of water at {@code pos}, {@code height} (0..1) of a block deep, for {@code lifeTicks}, moving by
	 * {@code motion}; no world change. Vanilla's water look (still sprite on top, flowing sprite down the sides, animated), the
	 * biome's water tint, see-through like real water. Every cell of this event is one body of water: faces its cells share are not
	 * drawn, so a wave reads as water, not glass cubes (place cells side by side, or stacked, to make one body). Another event's
	 * water never merges with it. A cell this event already has at {@code pos} is replaced. Counts as one ghost block.
	 */
	public void ghostFluid(BlockPos pos, float height, int lifeTicks, GhostBlocks.Motion motion) {
		if (!(height > 0) || !budget.ghostBlock()) return;
		if (water == null) water = new GhostBlocks.FluidBody();
		GhostBlocks.addFluid(pos, Math.min(1F, height), lifeTicks, motion, event.casterId(), water);
	}

	// ---- timing ----------------------------------------------------------------------------------------------------------

	/**
	 * Client timing for lingering visuals: {@code onTick} runs with the tick number (0, every, 2 × every, … below
	 * {@code durationTicks}) at the end of client ticks, the first one right now. Lanes feed it the same pure geometry helper the
	 * server uses, so the picture follows the effect. Stops early on {@link #cancel(int)} or leaving the world; a throwing callback
	 * is logged and stopped.
	 */
	public void simulate(int everyTicks, int durationTicks, IntConsumer onTick) {
		// At most MAX_SIMULATE_TICKS long, and none past MAX_SIMULATIONS running (that visual is skipped).
		if (SIMS.size() >= MAX_SIMULATIONS) return;
		Sim sim = new Sim(event.casterId(), Math.max(1, everyTicks), Math.min(durationTicks, MAX_SIMULATE_TICKS), onTick);
		if (sim.step()) SIMS.add(sim);
	}

	/** Stops every lasting visual of {@code casterId}'s casts: circles fade out, timers stop, ghost blocks go. */
	public static void cancel(int casterId) {
		SIMS.removeIf(s -> s.casterId == casterId);
		GhostBlocks.cancel(casterId);
		for (Iterator<Lasting> it = LASTING.iterator(); it.hasNext(); ) {
			Lasting l = it.next();
			if (l.casterId == casterId) {
				l.stop.run();
				it.remove();
			}
		}
	}

	// ---- camera ----------------------------------------------------------------------------------------------------------

	/** Shakes this player's camera, by {@code intensity} (0..3) over {@code ticks}: full within 6 blocks of the event, none past 40. */
	public void shake(float intensity, int ticks) {
		Shake.shake(intensity * nearness(6, 40), ticks);
	}

	/** Holds the struck entities (and the caster) still on screen for {@code ticks} (real time, at most 5), for players within 32 blocks. */
	public void hitStop(int ticks) {
		if (nearness(32, 33) <= 0) return;
		int[] struck = event.entities();
		int[] ids = Arrays.copyOf(struck, struck.length + 1);
		ids[struck.length] = event.casterId();
		HitStop.hold(Math.min(ticks, 5) * 50, ids);
	}

	/** Widens this player's view by {@code amount} (0.1 = 10 %) for a moment: full within 4 blocks of the event, none past 16. */
	public void fovKick(float amount) {
		Shake.kick(amount * nearness(4, 16));
	}

	/** 1 within {@code full} blocks of the camera, 0 beyond {@code none}, linear between. */
	private float nearness(double full, double none) {
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		double d = camera.position().distanceTo(event.origin());
		return (float) Mth.clamp((none - d) / (none - full), 0, 1);
	}

	// ---- helpers ---------------------------------------------------------------------------------------------------------

	private int vanilla(int count) {
		return budget.particles(Math.max(0, count));
	}

	private double jitter(double r) {
		return (random.nextDouble() * 2 - 1) * r;
	}

	/** A random unit vector. */
	private Vec3 sphere() {
		double z = random.nextDouble() * 2 - 1;
		double a = random.nextDouble() * Mth.TWO_PI;
		double r = Math.sqrt(1 - z * z);
		return new Vec3(r * Math.cos(a), z, r * Math.sin(a));
	}

	// ---- lasting things (static: they outlive the event's kit) -----------------------------------------------------------

	private record Lasting(int casterId, Runnable stop, java.util.function.BooleanSupplier alive) {}

	private static final List<Lasting> LASTING = new ArrayList<>();
	private static final List<Sim> SIMS = new ArrayList<>();
	private static ClientLevel seen;

	/** Registers something of {@code casterId}'s that {@link #cancel} must stop ({@code stop}), until {@code alive} says it ended. */
	static void lasting(int casterId, Runnable stop, java.util.function.BooleanSupplier alive) {
		LASTING.add(new Lasting(casterId, stop, alive));
	}

	private static final class Sim {
		final int casterId;
		final int every;
		final int duration;
		final IntConsumer onTick;
		int age;

		Sim(int casterId, int every, int duration, IntConsumer onTick) {
			this.casterId = casterId;
			this.every = every;
			this.duration = duration;
			this.onTick = onTick;
		}

		/** Runs this tick's step (if one is due) and advances; false once done. */
		boolean step() {
			if (age >= duration) return false;
			if (age % every == 0) {
				try {
					onTick.accept(age);
				} catch (RuntimeException ex) {
					LOGGER.warn("[office] magic fx timer failed; stopping it", ex);
					return false;
				}
			}
			age++;
			return age < duration;
		}
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(FxKit::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level != seen) {
			seen = mc.level;
			SIMS.clear();
			LASTING.clear();
			return;
		}
		if (mc.isPaused()) return;
		LASTING.removeIf(l -> !l.alive.getAsBoolean());
		// A step may start new timers (and they run from the next tick).
		List<Sim> now = new ArrayList<>(SIMS);
		for (Sim s : now) {
			if (!s.step()) SIMS.remove(s);
		}
	}

	/** Timers running (the game tests read it). */
	public static int simulations() {
		return SIMS.size();
	}
}
