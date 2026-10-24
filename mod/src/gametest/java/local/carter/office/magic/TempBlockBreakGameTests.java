package local.carter.office.magic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.terrain.TempBlockData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Temporary blocks are never a source of items ({@code TempBlockGuard}): each break path on a temporary block placed through the
 * installed kit's {@link TempBlockData}, then the same action on a real block as the positive control. Breaking one drops nothing
 * and puts the original block back at once.
 *
 * <p>The enderman tests rely on {@code data/minecraft/tags/block/enderman_holdable.json} in the test resources adding packed mud
 * (no temporary-safe block is holdable in vanilla), so the guard itself is what keeps the enderman's hands empty.
 */
public class TempBlockBreakGameTests {
	private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
	private static final BlockState PACKED_MUD = Blocks.PACKED_MUD.defaultBlockState();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final int TICKS = 400;

	private static TempBlockData temp(GameTestHelper h) {
		h.assertTrue(MagicServer.kit().temp() instanceof TempBlockData, "temp block data installed, got " + MagicServer.kit().temp());
		return (TempBlockData) MagicServer.kit().temp();
	}

	private static BlockPos abs(GameTestHelper h, int x, int y, int z) {
		return h.absolutePos(new BlockPos(x, y, z));
	}

	/** Places a temporary {@code state} at absolute {@code pos} through the core API, asserting it took. */
	private static void placeTemp(GameTestHelper h, TempBlockData t, BlockPos pos, BlockState state) {
		h.assertTrue(t.place(h.getLevel(), pos, state, TICKS), "temporary " + state + " at " + pos);
		h.assertTrue(t.isTemp(h.getLevel(), pos), "live temporary block at " + pos);
	}

	private static List<ItemEntity> items(GameTestHelper h) {
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, h.getBounds());
	}

	private static int count(GameTestHelper h, net.minecraft.world.item.Item item) {
		int n = 0;
		for (ItemEntity e : items(h)) if (e.getItem().is(item)) n += e.getItem().getCount();
		return n;
	}

	private static void assertNoDrops(GameTestHelper h, String what) {
		List<ItemEntity> items = items(h);
		h.assertTrue(items.isEmpty(), what + ": no item entities, got " + items.stream().map(e -> e.getItem().toString()).toList());
		h.assertTrue(h.getLevel().getEntitiesOfClass(ExperienceOrb.class, h.getBounds()).isEmpty(), what + ": no XP");
	}

	private static ServerPlayer miner(GameTestHelper h, ItemStack tool) {
		ServerPlayer p = MagicTestKit.otherPlayer(h, new BlockPos(16, 4, 10));
		p.setItemInHand(InteractionHand.MAIN_HAND, tool);
		return p;
	}

	private static ItemStack silkPick(GameTestHelper h) {
		ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
		Holder<Enchantment> silk = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
		pick.enchant(silk, 1);
		return pick;
	}

	// ---- Players ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void playerMiningTempBlockDropsNothingAndRestores(GameTestHelper h) {
		TempBlockData t = temp(h);
		ServerLevel level = h.getLevel();
		BlockPos overAir = abs(h, 16, 6, 16);
		BlockPos overGrass = abs(h, 17, 4, 16);
		h.setBlock(new BlockPos(17, 3, 16), Blocks.DIRT); // short grass needs dirt below
		h.setBlock(new BlockPos(17, 4, 16), Blocks.SHORT_GRASS);
		placeTemp(h, t, overAir, COBBLE);
		placeTemp(h, t, overGrass, COBBLE);
		int before = t.active();
		ServerPlayer p = miner(h, new ItemStack(Items.DIAMOND_PICKAXE));
		h.assertFalse(p.gameMode.destroyBlock(overAir), "mining a temporary block is cancelled");
		h.assertFalse(p.gameMode.destroyBlock(overGrass), "mining a temporary block is cancelled");
		assertNoDrops(h, "survival mining");
		h.assertValueEqual(level.getBlockState(overAir), AIR, "original air back");
		h.assertValueEqual(level.getBlockState(overGrass).getBlock(), Blocks.SHORT_GRASS, "original short grass back");
		h.assertValueEqual(t.active(), before - 2, "entries resolved");
		h.assertFalse(t.isTemp(level, overAir), "no live entry left");
		h.assertTrue(p.getMainHandItem().getDamageValue() == 0, "no tool wear");
		h.assertTrue(p.getInventory().countItem(Items.COBBLESTONE) == 0, "nothing in the inventory");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void playerMiningRealBlockDrops(GameTestHelper h) {
		BlockPos real = abs(h, 16, 6, 16);
		h.getLevel().setBlock(real, COBBLE, Block.UPDATE_ALL);
		ServerPlayer p = miner(h, new ItemStack(Items.DIAMOND_PICKAXE));
		h.assertTrue(p.gameMode.destroyBlock(real), "mining a real block");
		h.assertValueEqual(count(h, Items.COBBLESTONE), 1, "real cobblestone drops");
		h.assertValueEqual(h.getLevel().getBlockState(real), AIR, "mined");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void silkTouchOnTempBlockDropsNothing(GameTestHelper h) {
		TempBlockData t = temp(h);
		BlockPos glass = abs(h, 16, 6, 16);
		BlockPos ice = abs(h, 17, 6, 16);
		placeTemp(h, t, glass, GLASS);
		placeTemp(h, t, ice, Blocks.PACKED_ICE.defaultBlockState());
		ServerPlayer p = miner(h, silkPick(h));
		h.assertFalse(p.gameMode.destroyBlock(glass), "silk touch on temporary glass is cancelled");
		h.assertFalse(p.gameMode.destroyBlock(ice), "silk touch on temporary packed ice is cancelled");
		assertNoDrops(h, "silk touch");
		h.assertValueEqual(h.getLevel().getBlockState(glass), AIR, "original back");
		h.assertValueEqual(h.getLevel().getBlockState(ice), AIR, "original back");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void silkTouchOnRealBlockDrops(GameTestHelper h) {
		BlockPos glass = abs(h, 16, 6, 16);
		h.getLevel().setBlock(glass, GLASS, Block.UPDATE_ALL);
		ServerPlayer p = miner(h, silkPick(h));
		h.assertTrue(p.gameMode.destroyBlock(glass), "silk touch on real glass");
		h.assertValueEqual(count(h, Items.GLASS), 1, "real glass drops with silk touch");
		h.succeed();
	}

	// ---- Explosions ----

	private static List<BlockPos> blastCells(GameTestHelper h) {
		BlockPos c = abs(h, 16, 12, 16);
		return List.of(c.below(), c.east(), c.west(), c.north(), c.south());
	}

	private static void blast(GameTestHelper h) {
		BlockPos c = abs(h, 16, 12, 16);
		h.getLevel().explode(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 4f, Level.ExplosionInteraction.TNT);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void explosionOnTempBlocksDropsNothing(GameTestHelper h) {
		TempBlockData t = temp(h);
		for (BlockPos pos : blastCells(h)) placeTemp(h, t, pos, COBBLE);
		blast(h);
		assertNoDrops(h, "explosion");
		for (BlockPos pos : blastCells(h)) {
			h.assertValueEqual(h.getLevel().getBlockState(pos), AIR, "original back at " + pos);
			h.assertFalse(t.isTemp(h.getLevel(), pos), "entry resolved at " + pos);
		}
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void explosionOnRealBlocksDrops(GameTestHelper h) {
		for (BlockPos pos : blastCells(h)) h.getLevel().setBlock(pos, COBBLE, Block.UPDATE_ALL);
		blast(h);
		for (BlockPos pos : blastCells(h)) h.assertValueEqual(h.getLevel().getBlockState(pos), AIR, "blown at " + pos);
		h.assertTrue(count(h, Items.COBBLESTONE) > 0, "real cobblestone drops (TNT: no drop decay)");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 40)
	public void windBurstLeavesTempBlocksIntact(GameTestHelper h) {
		TempBlockData t = temp(h);
		ServerLevel level = h.getLevel();
		BlockPos glass = abs(h, 18, 6, 16);
		BlockPos ice = abs(h, 16, 6, 20);
		placeTemp(h, t, glass, GLASS);
		placeTemp(h, t, ice, Blocks.PACKED_ICE.defaultBlockState());
		// A real wind charge flying into the glass, and a wind burst (TRIGGER_BLOCK) right next to the ice.
		WindCharge charge = new WindCharge(level, glass.getX() - 2.5, glass.getY() + 0.5, glass.getZ() + 0.5, new Vec3(0.6, 0, 0));
		charge.shoot(1, 0, 0, 1f, 0f);
		level.addFreshEntity(charge);
		level.explode(null, ice.getX() + 0.5, ice.getY() + 0.5, ice.getZ() - 0.5, 1.2f, Level.ExplosionInteraction.TRIGGER);
		h.assertValueEqual(level.getBlockState(ice), Blocks.PACKED_ICE.defaultBlockState(), "temporary packed ice survives the burst");
		h.assertTrue(t.isTemp(level, ice), "packed ice still a live entry");
		h.runAfterDelay(20, () -> {
			h.assertTrue(charge.isRemoved(), "the wind charge burst");
			h.assertValueEqual(level.getBlockState(glass), GLASS, "temporary glass survives the wind charge");
			h.assertTrue(t.isTemp(level, glass), "glass still a live entry");
			assertNoDrops(h, "wind burst");
			t.breakTemp(level, glass, null, false);
			t.breakTemp(level, ice, null, false);
			h.succeed();
		});
	}

	// ---- Level.destroyBlock (wither, mods, spells) ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void destroyBlockOnTempBlockDropsNothing(GameTestHelper h) {
		TempBlockData t = temp(h);
		BlockPos pos = abs(h, 16, 6, 16);
		placeTemp(h, t, pos, COBBLE);
		h.assertTrue(h.getLevel().destroyBlock(pos, true), "destroyBlock reports a change");
		assertNoDrops(h, "destroyBlock");
		h.assertValueEqual(h.getLevel().getBlockState(pos), AIR, "original back");
		h.assertFalse(t.isTemp(h.getLevel(), pos), "entry resolved");
		BlockPos real = abs(h, 17, 6, 16);
		h.getLevel().setBlock(real, COBBLE, Block.UPDATE_ALL);
		h.assertTrue(h.getLevel().destroyBlock(real, true), "real block destroyed");
		h.assertValueEqual(count(h, Items.COBBLESTONE), 1, "real cobblestone drops");
		h.succeed();
	}

	// ---- Fluids ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void fluidsCannotBreakTempBlocks(GameTestHelper h) {
		// Every state the temp tag allows is solid to fluids: nothing water or lava washes away or flows into.
		int n = 0;
		for (Holder<Block> b : BuiltInRegistries.BLOCK.getTagOrEmpty(TempBlockData.SAFE)) {
			BlockState s = b.value().defaultBlockState();
			if (!TempBlockData.isSafe(s)) continue;
			n++;
			h.assertFalse(s.canBeReplaced(Fluids.FLOWING_WATER) || s.canBeReplaced(Fluids.FLOWING_LAVA), "fluid replaces " + s);
		}
		h.assertTrue(n > 0, "temp-safe states");
		h.assertFalse(TempBlockData.isSafe(Blocks.SHORT_GRASS.defaultBlockState()), "washable states are never temporary");
		// And in the world: water flowing against a temporary block leaves it alone.
		TempBlockData t = temp(h);
		BlockPos wall = abs(h, 16, 4, 16);
		placeTemp(h, t, wall, GLASS);
		h.getLevel().setBlock(wall.west(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
		h.runAfterDelay(30, () -> {
			h.assertValueEqual(h.getLevel().getBlockState(wall), GLASS, "temporary glass still there");
			h.assertTrue(t.isTemp(h.getLevel(), wall), "still a live entry");
			assertNoDrops(h, "water");
			t.breakTemp(h.getLevel(), wall, null, false);
			h.getLevel().setBlock(wall.west(), AIR, Block.UPDATE_ALL);
			h.succeed();
		});
	}

	// ---- Pistons ----

	private record PistonRig(BlockPos piston, BlockPos front, BlockPos beyond, BlockPos power, Direction dir) {}

	private static PistonRig piston(GameTestHelper h) {
		Direction dir = Direction.EAST;
		BlockPos piston = abs(h, 14, 4, 16);
		h.getLevel().setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, dir), Block.UPDATE_ALL);
		return new PistonRig(piston, piston.relative(dir), piston.relative(dir, 2), piston.relative(dir.getOpposite()), dir);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void pistonCannotPushTempBlock(GameTestHelper h) {
		TempBlockData t = temp(h);
		PistonRig rig = piston(h);
		placeTemp(h, t, rig.front(), COBBLE);
		h.getLevel().setBlock(rig.power(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		h.runAfterDelay(6, () -> {
			ServerLevel level = h.getLevel();
			h.assertValueEqual(level.getBlockState(rig.front()), COBBLE, "temporary block stays put");
			h.assertTrue(t.isTemp(level, rig.front()), "still the live temporary block");
			h.assertValueEqual(level.getBlockState(rig.beyond()), AIR, "no real block pushed out");
			h.assertFalse(level.getBlockState(rig.piston()).getValue(PistonBaseBlock.EXTENDED), "piston could not extend");
			assertNoDrops(h, "piston");
			// Expiry still restores it normally.
			t.breakTemp(level, rig.front(), null, false);
			h.assertValueEqual(level.getBlockState(rig.front()), AIR, "original back");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void pistonPushesRealBlock(GameTestHelper h) {
		PistonRig rig = piston(h);
		h.getLevel().setBlock(rig.front(), COBBLE, Block.UPDATE_ALL);
		h.getLevel().setBlock(rig.power(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		h.runAfterDelay(6, () -> {
			ServerLevel level = h.getLevel();
			h.assertValueEqual(level.getBlockState(rig.beyond()), COBBLE, "real block pushed");
			h.assertTrue(level.getBlockState(rig.piston()).getValue(PistonBaseBlock.EXTENDED), "piston extended");
			h.succeed();
		});
	}

	// ---- Endermen and silverfish ----

	private static Goal goal(GameTestHelper h, Mob mob, String simpleName) {
		for (WrappedGoal w : mob.getGoalSelector().getAvailableGoals()) {
			if (w.getGoal().getClass().getSimpleName().equals(simpleName)) return w.getGoal();
		}
		h.assertTrue(false, "no " + simpleName + " on " + mob);
		throw new IllegalStateException();
	}

	/** The 24 cells around an enderman standing at {@code c}: the only blocks its take-block goal can reach. */
	private static List<BlockPos> ring(BlockPos c) {
		List<BlockPos> out = new ArrayList<>();
		for (int dy = 0; dy < 3; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dz != 0) out.add(c.offset(dx, dy, dz));
				}
			}
		}
		return out;
	}

	/**
	 * A mob with no AI of its own but its goals kept ({@code spawnWithNoFreeWill} removes them): the test runs a goal by hand.
	 */
	private static <T extends Mob> T stillMob(GameTestHelper h, net.minecraft.world.entity.EntityType<T> type, BlockPos rel) {
		T mob = h.spawn(type, rel);
		mob.setNoAi(true);
		return mob;
	}

	/** An enderman whose take-block goal the test ticks by hand (each tick picks a random nearby block). */
	private static Enderman endermanIn(GameTestHelper h, BlockPos rel) {
		return stillMob(h, EntityTypes.ENDERMAN, rel);
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void endermanCannotTakeTempBlock(GameTestHelper h) {
		TempBlockData t = temp(h);
		h.assertTrue(PACKED_MUD.is(net.minecraft.tags.BlockTags.ENDERMAN_HOLDABLE), "test tag makes packed mud holdable");
		BlockPos rel = new BlockPos(16, 4, 16);
		List<BlockPos> ring = ring(h.absolutePos(rel));
		for (BlockPos pos : ring) placeTemp(h, t, pos, PACKED_MUD);
		Enderman e = endermanIn(h, rel);
		Goal take = goal(h, e, "EndermanTakeBlockGoal");
		for (int i = 0; i < 400; i++) take.tick();
		h.assertTrue(e.getCarriedBlock() == null, "enderman's hands empty, got " + e.getCarriedBlock());
		for (BlockPos pos : ring) h.assertTrue(t.isTemp(h.getLevel(), pos), "temporary packed mud still at " + pos);
		for (BlockPos pos : ring) t.breakTemp(h.getLevel(), pos, null, false);
		e.discard();
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void endermanTakesRealBlock(GameTestHelper h) {
		BlockPos rel = new BlockPos(16, 4, 16);
		List<BlockPos> ring = ring(h.absolutePos(rel));
		for (BlockPos pos : ring) h.getLevel().setBlock(pos, PACKED_MUD, Block.UPDATE_ALL);
		Enderman e = endermanIn(h, rel);
		Goal take = goal(h, e, "EndermanTakeBlockGoal");
		for (int i = 0; i < 400 && e.getCarriedBlock() == null; i++) take.tick();
		h.assertValueEqual(e.getCarriedBlock(), PACKED_MUD, "enderman picked up real packed mud");
		e.discard();
		h.succeed();
	}

	/** Makes the silverfish's merge goal infest the block east of it ({@code start} with a merge chosen). */
	private static void mergeEast(GameTestHelper h, Silverfish s) throws ReflectiveOperationException {
		Goal merge = goal(h, s, "SilverfishMergeWithStoneGoal");
		Field dir = merge.getClass().getDeclaredField("selectedDirection");
		Field doMerge = merge.getClass().getDeclaredField("doMerge");
		dir.setAccessible(true);
		doMerge.setAccessible(true);
		dir.set(merge, Direction.EAST);
		doMerge.setBoolean(merge, true);
		merge.start();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void silverfishCannotInfestTempBlock(GameTestHelper h) throws ReflectiveOperationException {
		TempBlockData t = temp(h);
		BlockPos rel = new BlockPos(16, 4, 16);
		BlockPos east = h.absolutePos(rel).east();
		placeTemp(h, t, east, STONE);
		Silverfish s = stillMob(h, EntityTypes.SILVERFISH, rel);
		mergeEast(h, s);
		h.assertValueEqual(h.getLevel().getBlockState(east), STONE, "temporary stone not infested");
		h.assertFalse(s.isRemoved(), "silverfish still out");
		t.breakTemp(h.getLevel(), east, null, false);
		s.discard();
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void silverfishInfestsRealBlock(GameTestHelper h) throws ReflectiveOperationException {
		BlockPos rel = new BlockPos(16, 4, 16);
		BlockPos east = h.absolutePos(rel).east();
		h.getLevel().setBlock(east, STONE, Block.UPDATE_ALL);
		Silverfish s = stillMob(h, EntityTypes.SILVERFISH, rel);
		mergeEast(h, s);
		h.assertValueEqual(h.getLevel().getBlockState(east).getBlock(), Blocks.INFESTED_STONE, "real stone infested");
		h.succeed();
	}
}
