package local.carter.office.magic.spell.lightning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla lightning bolts that are only a picture ({@code LightningBolt.setVisualOnly(true)}): the bolt from the sky, the flash
 * and the thunder every nearby client draws and hears, with no effect on the world.
 *
 * <p>Checked against 26.3's {@code LightningBolt} (bytecode): with {@code visualOnly} set, {@code spawnFire} returns at once (no
 * fire, on any difficulty) and the {@code thunderHit} pass over nearby entities is skipped on every flash (no damage, no
 * villager → witch, pig → zombified piglin, creeper charging or mooshroom turning; {@code getHitEntities()} stays empty). Two things
 * still run whatever the flag: {@code powerLightningRod} and {@code clearCopperOnLightningStrike}, both on the block at the bolt's
 * strike position (just under its feet). So a bolt is set {@link #LIFT} above the ground, which makes its strike position the
 * cell the ground's surface is in, and none is made where that cell is a lightning rod or copper ({@link #conducts}): a spell's
 * picture never powers a rod or scrapes a copper roof, inside a claim or out.
 */
final class VisualBolts {
	/** How far above the ground a bolt is set (its strike position is the cell it stands in, not the block under it). */
	static final double LIFT = 0.02;

	private VisualBolts() {}

	/** A visual-only bolt onto the ground at {@code ground}. False (none made) where the strike would touch a rod or copper. */
	static boolean strike(ServerLevel level, Vec3 ground) {
		Vec3 at = new Vec3(ground.x, ground.y + LIFT, ground.z);
		BlockPos cell = BlockPos.containing(at.x, at.y - 1.0E-6, at.z);
		if (!level.isLoaded(cell) || conducts(level.getBlockState(cell))) return false;
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt == null) return false;
		bolt.setVisualOnly(true);
		bolt.snapTo(at);
		return level.addFreshEntity(bolt);
	}

	/** Whether a bolt striking {@code s} would change it even when visual-only: a lightning rod, or any copper (waxed or not). */
	static boolean conducts(BlockState s) {
		Block b = s.getBlock();
		return b instanceof LightningRodBlock || b instanceof WeatheringCopper || HoneycombItem.WAX_OFF_BY_BLOCK.get().containsKey(b);
	}
}
