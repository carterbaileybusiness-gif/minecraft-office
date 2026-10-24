package local.carter.office.magic.spell.lightning;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import local.carter.office.magic.cast.CastService;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.core.Shapes.Cell;
import local.carter.office.magic.core.Slots;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.Hurt;
import local.carter.office.magic.spell.ProjectileHit;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.Projectiles;
import local.carter.office.magic.spell.Spell;
import local.carter.office.magic.spell.SpellIds;
import local.carter.office.magic.spell.Targets;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditTier;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Thunder Spear (Lightning, slot 3, medium): a spear of lightning hurled from the eye along the look ({@link #spec}: speed
 * {@link #SPEED}, gravity {@link #GRAVITY}, radius {@link #RADIUS}, at most {@link #MAX_TICKS} ticks), stopping at the first
 * creature or block. At the impact:
 * <ul>
 * <li>every creature within {@link #BLAST} that the blast reaches (nothing solid between; the one it struck always) takes
 * {@code 10 × P}; the one it struck, and hostile mobs, are slowed (Slowness II, {@link #SLOW_TICKS} ticks);</li>
 * <li>minor permanent change, one MEDIUM batch whose origin is the block struck (for a creature, the ground under it if within
 * {@link #GROUND_DEPTH}, else none): a bowl of radius {@link #BOWL} is blasted out, and sand or red sand within {@link #GLASS} of
 * the origin fuses to glass.</li>
 * </ul>
 * A spear that runs out of time ends in the air with nothing.
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
		ProjectileSpec spec = spec(ctx.eye(), ctx.look());
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
		for (LivingEntity e : Targets.radius(ctx, at, BLAST)) {
			if (e != direct && !Arcs.reaches(ctx, from, e.getBoundingBox())) continue;
			Hurt.damage(ctx, e, damage);
			if (e.isAlive() && (e == direct || e instanceof Enemy)) {
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SLOW_TICKS, SLOW_AMPLIFIER), ctx.caster());
			}
		}

		if (origin != null) crater(ctx, origin);
	}

	/** The bowl and the glass, in one MEDIUM batch round {@code origin}. */
	private static void crater(CastContext ctx, BlockPos origin) {
		ServerLevel level = ctx.level();
		List<Cell> bowl = Shapes.bowl(BOWL, BOWL_DEPTH);
		Set<Cell> blasted = new HashSet<>(bowl);
		try (EditBatch b = ctx.kit().terrain().begin(ctx, origin, EditTier.MEDIUM)) {
			for (Cell c : bowl) b.remove(origin.offset(c.x(), c.y(), c.z()));
			for (Cell c : Shapes.sphere(GLASS)) {
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
