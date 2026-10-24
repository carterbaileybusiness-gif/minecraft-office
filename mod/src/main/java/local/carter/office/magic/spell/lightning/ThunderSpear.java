package local.carter.office.magic.spell.lightning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.ChargeMath;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.ActiveEffects;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.LingeringEffect;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;

import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.tree.Knob;
import local.carter.office.magic.tree.SpellRank;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import net.minecraft.world.phys.Vec3;

/**
 * Thunder Spear (Lightning, slot 3, medium): a spear of lightning hurled from the eye along the look ({@link #spec}: speed
 * {@link #SPEED}, gravity {@link #GRAVITY}, radius {@link #RADIUS}, at most {@link #MAX_TICKS} ticks), stopping at the first
 * creature or block. At the impact:
 * <ul>
 * <li>every creature within {@link #blast} (2.5 at a tap, 5 at full charge) that the blast reaches (nothing solid between; the
 * one it struck always) takes {@code 10 × P}; every creature a control effect may take (never a boss, even the one struck) is
 * slowed (Slowness II, {@link #SLOW_TICKS} ticks);</li>
 * <li>minor permanent change, one MEDIUM batch whose origin is the block struck (for a creature, the ground under it if within
 * {@link #GROUND_DEPTH}, else none): a bowl of radius {@link #bowl} (2 → 4) is blasted out, and sand or red sand within
 * {@link #glass} (3 → 6, the medium cap) of the origin fuses to glass.</li>
 * </ul>
 * A spear that runs out of time ends in the air with nothing.
 *
 * <p>Ranks widen the blast (the size knob, up to {@link #MAX_BLAST}; the crater keeps its charge size). Forks: III
 * {@link #JAVELIN} ({@link #JAVELIN_SPEED}× as fast, no drop) or {@link #MAGNET} (after the blast, the impact drags creatures
 * within {@link #MAGNET_REACH} past the blast in toward it: a control effect, never a boss); V {@link #RETURN} (after the
 * strike the spear flies back to the caster at {@link #SPEED} a tick, striking each creature within {@link #RETURN_RADIUS} of its
 * path once for {@link #RETURN_SHARE} of the hit; its own caster slot, skipped when the caster's effects are full) or
 * {@link #CHAIN} (arcs on to {@link #CHAIN_HOPS} more creatures within {@link #CHAIN_RANGE} at {@link #CHAIN_SHARE}).
 *
 * <p>Fx: the cast's default event (origin = eye, dir = look); the client rebuilds the flight with {@link #spec} and
 * {@code Projectiles.step}, clipping it as the server does, and draws its own strike from the sky (no vanilla {@code LightningBolt}:
 * those boom for everyone in view distance). No data.
 */
public final class ThunderSpear implements Spell {
	public static final double SPEED = 3.0;
	public static final double GRAVITY = 0.01;
	public static final double RADIUS = 0.3;
	public static final int MAX_TICKS = 30;
	public static final float DAMAGE = 10;
	public static final double BLAST = 2.5;
	public static final int SLOW_TICKS = 40;
	/** Slowness II. */
	public static final int SLOW_AMPLIFIER = 1;
	public static final double BOWL = 2;
	public static final double BOWL_DEPTH = 0.6;
	public static final double GLASS = 3;
	/** How far below a struck creature's feet the crater's ground may be. */
	public static final int GROUND_DEPTH = 2;
	public static final String JAVELIN = "javelin";
	public static final String MAGNET = "magnet";
	public static final String RETURN = "return";
	public static final String CHAIN = "chain";
	/** Widest a ranked blast reaches (the medium radius cap). */
	public static final double MAX_BLAST = 6;
	public static final double JAVELIN_SPEED = 1.6;
	/** Magnet: how far past the blast it reaches, and how hard it drags (blocks a tick per block of distance, at most 1.5). */
	public static final double MAGNET_REACH = 4;
	public static final double MAGNET_PULL = 0.3;
	/** Returning Spear: its share of the hit, the reach round its path, and its longest flight home. */
	public static final float RETURN_SHARE = 0.5F;
	public static final double RETURN_RADIUS = 1.0;
	public static final int RETURN_TICKS = 40;
	public static final int CHAIN_HOPS = 3;
	public static final double CHAIN_RANGE = 8;
	public static final float CHAIN_SHARE = 0.6F;

	/** The blast's reach at {@code charge} and {@code rank} (the size knob), at most {@link #MAX_BLAST}. */
	public static double blast(float charge, SpellRank rank) {
		return Math.min(MAX_BLAST, blast(charge) * rank.mul(Knob.SIZE));
	}

	/** The spear's flight at {@code rank} (Javelin: faster, no drop; shared by the server and the client fx). */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir, SpellRank rank) {
		if (!rank.is(JAVELIN)) return spec(origin, dir);
		Vec3 d = dir.lengthSqr() < 1.0E-12 ? new Vec3(0, 0, 1) : dir.normalize();
		return new ProjectileSpec(origin, d.scale(SPEED * JAVELIN_SPEED), 0, RADIUS, MAX_TICKS, false);
	}

	/** Where the Returning Spear is {@code t} ticks after turning at {@code from}, flying at {@link #SPEED} toward {@code home}. */
	public static Vec3 homeward(Vec3 from, Vec3 home, double t) {
		Vec3 d = home.subtract(from);
		double len = d.length();
		double go = Math.min(len, SPEED * t);
		return len < 1.0E-6 ? home : from.add(d.scale(go / len));
	}

	/** The blast's reach at {@code charge}: 2.5, 5 at full charge. */
	public static double blast(float charge) {
		return BLAST * ChargeMath.size(charge);
	}

	/** The crater's radius at {@code charge}: 2, 4 at full charge. */
	public static double bowl(float charge) {
		return BOWL * ChargeMath.size(charge);
	}

	/** How far sand fuses to glass at {@code charge}: 3, 6 (the medium radius cap) at full charge. */
	public static double glass(float charge) {
		return GLASS * ChargeMath.size(charge);
	}

	@Override
	public Identifier id() {
		return SpellIds.THUNDER_SPEAR;
	}

	@Override
	public @Nullable Element element() {
		return Element.LIGHTNING;
	}

	@Override
	public int slot() {
		return Slots.MEDIUM_B;
	}

	/** The spear's flight from {@code origin} along {@code dir} (shared by the server and the client fx). */
	public static ProjectileSpec spec(Vec3 origin, Vec3 dir) {
		Vec3 d = dir.lengthSqr() < 1.0E-12 ? new Vec3(0, 0, 1) : dir.normalize();
		return new ProjectileSpec(origin, d.scale(SPEED), GRAVITY, RADIUS, MAX_TICKS, false);
	}

	@Override
	public CastOutcome cast(CastContext ctx) {
		ProjectileSpec spec = spec(ctx.eye(), ctx.look(), ctx.rank());
		Vec3 dir = spec.velocity().normalize();
		if (!Projectiles.launch(ctx, spec, hit -> impact(ctx, dir, hit))) return CastOutcome.fizzle(CastService.BUSY_KEY);
		return CastOutcome.cast(ctx.fx());
	}

	private static void impact(CastContext ctx, Vec3 dir, ProjectileHit hit) {
		ServerLevel level = ctx.level();
		Vec3 at = hit.pos();
		LivingEntity direct = hit.entity();
		BlockHitResult block = hit.block();
		// The crater's origin, read before the blast (a creature it kills still leaves its crater).
		BlockPos origin = block != null ? block.getBlockPos() : direct != null ? groundUnder(level, direct) : null;

		Vec3 from = at.subtract(dir.scale(0.3));
		float damage = DAMAGE * ctx.power();
		double blast = blast(ctx.charge(), ctx.rank());
		List<LivingEntity> blasted = new ArrayList<>();
		for (LivingEntity e : Targets.radius(ctx, at, blast)) {
			if (e != direct && !Arcs.reaches(ctx, from, e.getBoundingBox())) continue;
			Hurt.damage(ctx, e, damage);
			blasted.add(e);
			if (e.isAlive() && TargetFilter.controllable(ctx.caster(), e)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, SLOW_AMPLIFIER), ctx.caster());
			}
		}
		// Magnet: the impact drags what is near in toward it (a control effect, within the cast's budget).
		if (ctx.rank().is(MAGNET)) {
			for (LivingEntity e : Targets.radius(ctx, at, blast + MAGNET_REACH)) {
				if (!TargetFilter.controllable(ctx.caster(), e) || !Arcs.reaches(ctx, from, e.getBoundingBox()) || !Hurt.admit(ctx, e)) continue;
				Vec3 in = at.subtract(e.position());
				double d = Math.sqrt(in.x * in.x + in.z * in.z);
				if (d > 1) Hurt.knock(e, in, Math.min(1.5, d * MAGNET_PULL), 0.25);
			}
		}
		// Returning Spear: it flies home, striking what it passes.
		if (ctx.rank().is(RETURN)) ActiveEffects.add(ctx, new Homeward(ctx, at, damage * RETURN_SHARE));
		// Chain Spear: the strike leaps on from what it hit (or the nearest it blasted).
		LivingEntity from0 = direct != null ? direct : blasted.isEmpty() ? null : blasted.getFirst();
		if (ctx.rank().is(CHAIN) && from0 != null) {
			List<LivingEntity> chain = Arcs.chain(ctx, from0, CHAIN_HOPS, CHAIN_RANGE);
			for (int i = 1; i < chain.size(); i++) Hurt.damage(ctx, chain.get(i), damage * CHAIN_SHARE);
		}

		if (origin != null) crater(ctx, origin);
	}

	/** The Returning Spear: from the impact to the caster's middle, striking each creature near its path once. */
	static final class Homeward implements LingeringEffect {
		private final CastContext ctx;
		private final Vec3 from;
		private final float damage;
		private final Set<Integer> struck = new HashSet<>();

		Homeward(CastContext ctx, Vec3 from, float damage) {
			this.ctx = ctx;
			this.from = from;
			this.damage = damage;
		}

		@Override
		public boolean tick(ServerLevel level, int age) {
			ServerPlayer p = ctx.caster();
			if (p.isRemoved() || p.level() != level) return false;
			Vec3 home = p.getBoundingBox().getCenter();
			Vec3 a = homeward(from, home, age - 1), b = homeward(from, home, age);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(a, b).inflate(RETURN_RADIUS),
					e -> TargetFilter.affects(p, e) && !struck.contains(e.getId()))) {
				AABB box = e.getBoundingBox().inflate(RETURN_RADIUS);
				if (!box.contains(a) && box.clip(a, b).isEmpty()) continue;
				struck.add(e.getId());
				Hurt.damage(ctx, e, damage);
			}
			return b.distanceToSqr(home) > 0.25 && age < RETURN_TICKS;
		}

		@Override
		public String toString() {
			return "ThunderSpear.Homeward[" + ctx.caster().getScoreboardName() + "]";
		}
	}

	/** The bowl and the glass, in one MEDIUM batch round {@code origin}. */

	private static void crater(CastContext ctx, BlockPos origin) {
		ServerLevel level = ctx.level();
		List<Cell> bowl = Shapes.bowl(bowl(ctx.charge()), BOWL_DEPTH);
		Set<Cell> blasted = new HashSet<>(bowl);
		try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.MEDIUM)) {
			for (Cell c : bowl) b.remove(origin.offset(c.x(), c.y(), c.z()));
			for (Cell c : Shapes.sphere(glass(ctx.charge()))) {
				if (blasted.contains(c)) continue;
				BlockPos pos = origin.offset(c.x(), c.y(), c.z());
				if (sand(level.getBlockState(pos))) b.set(pos, Blocks.GLASS.defaultBlockState());
			}
		}
	}

	static boolean sand(BlockState s) {
		return s.is(Blocks.SAND) || s.is(Blocks.RED_SAND);
	}

	/** The first non-air block under {@code e}'s feet, at most {@link #GROUND_DEPTH} down; null if none. */
	private static @Nullable BlockPos groundUnder(ServerLevel level, LivingEntity e) {
		BlockPos.MutableBlockPos m = BlockPos.containing(e.getX(), e.getY() - 0.2, e.getZ()).mutable();
		for (int i = 0; i <= GROUND_DEPTH; i++, m.move(0, -1, 0)) {
			if (m.getY() < level.getMinY()) return null;
			if (!level.getBlockState(m).isAir()) return m.immutable();
		}
		return null;
	}
}
