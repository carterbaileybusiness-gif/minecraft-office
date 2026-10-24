package local.carter.office.client.magic.fx.earth;

import java.util.Optional;

import local.carter.office.client.magic.fx.FxKit;
import local.carter.office.client.magic.fx.GlowMote;
import local.carter.office.client.magic.fx.SpellFx;
import local.carter.office.magic.net.FxEvent;
import local.carter.office.magic.spell.ProjectileSpec;
import local.carter.office.magic.spell.TargetFilter;
import local.carter.office.magic.spell.earth.StoneBullet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Stone Bullet: a small earth circle snaps open before the caster, a chunk of rock is torn up into the hand and flung. The
 * boulder (a real cobblestone model with two chips circling it) tumbles along the server's own arc ({@link StoneBullet#path}),
 * shedding crumbs and brown dust, until it smashes on the first entity its sweep meets or on the wall the server named
 * ({@code target}): a burst of the struck block's crumbs, dust, a ring of light, and rock chips that bounce and settle.
 * Run out of range, it crumbles in the air.
 */
public final class StoneBulletFx implements SpellFx {
	/** Ticks the boulder takes to swing from the hand onto the arc. */
	private static final float HAND_TICKS = 2.5F;
	/** No trail closer than this to the caster's eye, so their view stays clear. */
	private static final double CLEAR = 1.6;
	/** In the caster's own first-person view: ticks before the strike over which the rock swings from low-right onto the arc. */
	private static final float OWN_JOIN = 1F;
	/** Longest life of an impact chip. */
	private static final int CHIP_LIFE = 24;
	/** Block crumbs for the whole throw, and the share of them kept for the smash (paid at the cast, so the trail can't spend them). */
	private static final int CRUMB_CAP = 110;
	private static final int IMPACT_CRUMBS = 10;

	@Override
	public void play(FxEvent e, ClientLevel level, FxKit kit) {
		Vec3 dir = EarthFx.unit(e.dir());
		Vec3 origin = e.origin();
		float c = Mth.clamp(e.charge(), 0F, 1F);
		float[] data = e.data();
		double speed = data.length > 0 && data[0] > 0 ? data[0] : StoneBullet.SPEED;
		double gravity = data.length > 1 ? data[1] : StoneBullet.GRAVITY;
		double radius = data.length > 2 ? data[2] : StoneBullet.RADIUS;
		ProjectileSpec spec = new ProjectileSpec(origin, dir.scale(speed), gravity, radius, StoneBullet.MAX_TICKS, false);
		int flight = Math.max(1, e.duration());
		// The server names the wall the arc meets, or where its range ends: a stone that runs out crumbles in the air.
		boolean spent = flight >= StoneBullet.MAX_TICKS && e.target().distanceTo(StoneBullet.path(spec, StoneBullet.MAX_TICKS)) < 0.05;

		// The cast: a quick circle facing the caster, rock torn up from below into the hand.
		Minecraft mc = Minecraft.getInstance();
		boolean own = mc.player != null && mc.player.getId() == e.casterId() && mc.options.getCameraType().isFirstPerson();
		Vec3 right = EarthFx.unit(dir.cross(new Vec3(0, 1, 0)));
		// In the caster's own first-person view the rock rides low and to the right, well clear of the crosshair, until it is away.
		Vec3 hand = own ? right.scale(0.9).add(0, -0.7, 0) : right.scale(0.38).add(0, -0.32, 0).add(dir.scale(0.55));
		Vec3 muzzle = origin.add(dir.scale(1.6)).add(0, -0.15, 0);
		EarthFx.Crumbs crumbs = new EarthFx.Crumbs(kit, CRUMB_CAP);
		kit.circle(muzzle, dir.reverse(), EarthFx.ARGB, 0.42F * (1F + c), 2 + Math.round(2 * c), 9 + Math.round(4 * c));
		// The rock tears loose where it is drawn: at the hand (low and right in the caster's own view, off the crosshair).
		Vec3 tear = own ? StoneBullet.path(spec, 1).add(hand) : muzzle;
		crumbs.burst(tear, EarthFx.COBBLE, 8 + Math.round(6 * c), 0.12);
		// The smash's crumbs, paid now: a long trail can never leave the impact without them.
		int kept = crumbs.reserve(struckCrumbs(c) + IMPACT_CRUMBS);
		kit.burst(tear, EarthFx.DUST, 5, 0.06);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.55F, 0.55F + 0.1F * (1 - c), false);
		level.playLocalSound(muzzle.x, muzzle.y, muzzle.z, SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 0.6F, 0.75F, false);

		// As big as the server's stone (StoneBullet.radius): twice a tap's at full charge.
		float size = 0.5F * (1F + c);
		// The wall is somewhere along the last tick's step: stop the rock on it, not inside it.
		Vec3 last = StoneBullet.path(spec, flight - 1);
		double lastStep = Math.max(1.0E-6, last.distanceTo(StoneBullet.path(spec, flight)));
		float[] end = {(float) (flight - 1 + Math.min(1, last.distanceTo(e.target()) / lastStep))};

		// One cancellable timer flies the rock (sweeping for entities and for walls risen since the cast) and keeps its rocks
		// and chips on the leash.
		boolean[] done = {false};
		RockLedger.Leash leash = new RockLedger.Leash(EarthStones.now());
		EarthFx.drive(kit, leash, flight + 2 + CHIP_LIFE, t -> {
			if (done[0] || t >= flight) return;
			Vec3 from = StoneBullet.path(spec, t);
			Vec3 to = t + 1 >= flight ? e.target() : StoneBullet.path(spec, t + 1);
			Optional<Vec3> hit = sweep(level, e.casterId(), from, to, radius);
			// A wall the server did not know about at the cast (an Earth Wall rising in the way) stops it here too.
			BlockHitResult block = level.clip(new ClipContext(from, hit.orElse(to), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					CollisionContext.empty()));
			boolean wall = block.getType() != HitResult.Type.MISS;
			Vec3 head = wall ? block.getLocation() : hit.orElse(to);
			boolean entity = hit.isPresent() && !wall;
			if (entity || wall) {
				double seg = Math.max(1.0E-6, from.distanceTo(to));
				end[0] = (float) (t + Math.min(1, from.distanceTo(head) / seg));
			}
			// The trail: crumbs and grit falling off the rock, past the clear zone (following the drawn rock, so in the caster's
			// own view it stays low and right with it).
			if (head.distanceTo(origin) > CLEAR) {
				Vec3 tail = from.distanceTo(origin) > CLEAR ? from : origin.add(dir.scale(CLEAR));
				Vec3 drawnTail = tail.add(offset(hand, own, (float) StoneBullet.timeAt(spec, tail), end[0]));
				Vec3 drawnHead = head.add(offset(hand, own, t + 1, end[0]));
				crumbs.trail(drawnTail, drawnHead, EarthFx.COBBLE, 2);
				kit.trail(drawnTail, drawnHead, EarthFx.GRIT, 2);
				kit.motes(drawnHead.subtract(StoneBullet.heading(spec, t).scale(0.4)), EarthFx.CLAY, 1, 0.08, Vec3.ZERO, 0.01, GlowMote.Drift.FALL,
						0.16F + 0.06F * c, 6);
			}
			if (entity || wall || t + 1 >= flight) {
				done[0] = true;
				Vec3 heading = StoneBullet.heading(spec, end[0]);
				if (!entity && !wall && spent) crumble(kit, leash, kept, level, head, heading);
				else impact(kit, leash, kept, level, head, heading, c, entity);
			}
		});

		// A craggy boulder: a cobblestone core with lumps of stone grown onto it, all tumbling as one rock.
		RandomSource r = kit.random();
		BlockState core = r.nextBoolean() ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
		BlockState[] parts = {core, Blocks.COBBLESTONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
		float[][] lumps = {{0, 0, 0, 1F}, {0.3F, 0.2F, -0.12F, 0.72F}, {-0.26F, -0.18F, 0.2F, 0.66F}, {0.06F, -0.3F, -0.28F, 0.5F}};
		float spinYaw = 29F + 9F * r.nextFloat();
		float spinPitch = 41F + 9F * r.nextFloat();
		float showFrom = own ? 0.8F : 0.4F;
		for (int i = 0; i < lumps.length; i++) {
			if (!kit.budget().ghostBlock()) break;
			float[] l = lumps[i];
			Vector3f local = new Vector3f(l[0], l[1], l[2]).mul(size);
			EarthStones.add(e.casterId(), leash, parts[i], flight + 1, (t, out) -> {
				if (t > end[0] || t < showFrom) return false;
				float grow = Mth.clamp(t / 1.2F, 0.35F, 1F);
				float yaw = t * spinYaw;
				float pitch = t * spinPitch;
				Vector3f o = new Quaternionf().rotationY(yaw * Mth.DEG_TO_RAD).rotateX(pitch * Mth.DEG_TO_RAD).transform(new Vector3f(local)).mul(grow);
				out.at(position(spec, hand, own, t, end[0]).add(o.x, o.y, o.z));
				out.yaw = yaw;
				out.pitch = pitch;
				out.scale = size * l[3] * grow;
				return true;
			});
		}
	}

	/**
	 * Where the boulder is drawn at {@code t}: on the arc, swung in from the hand ({@code hand} = offset from the arc). Others see
	 * it join the arc over the first {@link #HAND_TICKS}. In the caster's own first-person view it keeps the whole offset (low
	 * and right; the offset is wider than the rock, so at any distance the rock is never over the crosshair) and only swings onto
	 * the arc over the last {@link #OWN_JOIN} ticks before it strikes ({@code end}).
	 */
	private static Vec3 position(ProjectileSpec spec, Vec3 hand, boolean own, float t, float end) {
		return StoneBullet.path(spec, t).add(offset(hand, own, t, end));
	}

	/** How far from the arc the rock is drawn at {@code t} (see {@link #position}). */
	private static Vec3 offset(Vec3 hand, boolean own, float t, float end) {
		float k = own ? Mth.clamp((end - t) / OWN_JOIN, 0F, 1F) : Mth.clamp(1F - t / HAND_TICKS, 0F, 1F);
		return k <= 0 ? Vec3.ZERO : hand.scale(own ? k : k * k);
	}

	/** The smash: the struck block's crumbs, dust (pillars of it on the ground), a ring of light, bouncing chips, a heavy crack. */
	/** Crumbs of the struck block at the smash. */
	private static int struckCrumbs(float c) {
		return 22 + Math.round(14 * c);
	}

	private static void impact(FxKit kit, RockLedger.Leash leash, int kept, ClientLevel level, Vec3 at, Vec3 heading, float c,
			boolean entity) {
		Vec3 back = at.subtract(heading.scale(0.2));
		BlockPos struck = BlockPos.containing(at.add(heading.scale(0.15)));
		BlockState s = level.getBlockState(struck);
		BlockState debris = entity || s.isAir() ? EarthFx.COBBLE : s;
		int ofBlock = Math.min(kept, struckCrumbs(c));
		EarthFx.spray(level, EarthFx.crumbs(debris), back, ofBlock, 0.06, 0.24 + 0.08 * c, false);
		EarthFx.spray(level, EarthFx.crumbs(EarthFx.COBBLE), back, Math.min(kept - ofBlock, IMPACT_CRUMBS), 0.06, 0.18, false);
		kit.burst(back, EarthFx.DUST, 10 + Math.round(6 * c), 0.09);
		kit.burst(back, ParticleTypes.POOF, 3 + Math.round(3 * c), 0.05);
		boolean ground = !entity && heading.y < -0.25;
		if (ground) {
			kit.ring(back, 0.5, new BlockParticleOption(ParticleTypes.DUST_PILLAR, debris), 8 + Math.round(6 * c));
		}
		kit.circle(back, heading.reverse(), EarthFx.ARGB, 0.45F + 0.3F * c, 1, 7);
		kit.motes(back, EarthFx.CLAY, 6, 0.2, heading.scale(-0.05), 0.1, GlowMote.Drift.FALL, 0.18F, 10);
		RandomSource r = kit.random();
		int n = 5 + Math.round(3 * c);
		for (int i = 0; i < n; i++) {
			Vec3 v = heading.scale(-0.12 - 0.08 * r.nextDouble()).add(EarthFx.sphere(r).scale(0.2 + 0.1 * c)).add(0, 0.18, 0);
			EarthFx.chip(kit, leash, level, i % 2 == 0 ? EarthFx.COBBLE : debris, back, v, 0.16F + 0.1F * r.nextFloat(), CHIP_LIFE - r.nextInt(8));
		}
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.STONE_BREAK, SoundSource.PLAYERS, 1.0F, 0.6F + 0.1F * r.nextFloat(), false);
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, entity ? 0.75F : 0.5F, 1.25F - 0.2F * c, false);
		if (c >= 0.5F) kit.shake(0.18F * c, 6);
	}

	/** Out of range: the rock breaks apart and its pieces rain down. */
	private static void crumble(FxKit kit, RockLedger.Leash leash, int kept, ClientLevel level, Vec3 at, Vec3 heading) {
		EarthFx.spray(level, EarthFx.crumbs(EarthFx.COBBLE), at, Math.min(kept, 14), 0.06, 0.12, false);
		kit.burst(at, EarthFx.DUST, 6, 0.05);
		RandomSource r = kit.random();
		for (int i = 0; i < 4; i++) {
			EarthFx.chip(kit, leash, level, EarthFx.COBBLE, at, heading.scale(0.25).add(EarthFx.sphere(r).scale(0.08)), 0.2F, CHIP_LIFE);
		}
		level.playLocalSound(at.x, at.y, at.z, SoundEvents.GRAVEL_BREAK, SoundSource.PLAYERS, 0.6F, 0.8F, false);
	}

	/** Where the stone from {@code from} to {@code to} first meets an entity the spell could hit (the client's own sweep). */
	private static Optional<Vec3> sweep(ClientLevel level, int casterId, Vec3 from, Vec3 to, double radius) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity en : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(radius + 1),
				en -> en.getId() != casterId && TargetFilter.affects(null, en))) {
			AABB box = en.getBoundingBox().inflate(radius);
			Optional<Vec3> at = box.contains(from) ? Optional.of(from) : box.clip(from, to);
			if (at.isPresent() && from.distanceToSqr(at.get()) < bestD) {
				bestD = from.distanceToSqr(at.get());
				best = at.get();
			}
		}
		return Optional.ofNullable(best);
	}
}
