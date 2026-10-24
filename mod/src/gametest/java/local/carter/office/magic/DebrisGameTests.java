package local.carter.office.magic;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellKit;
import local.carter.office.magic.terrain.ClaimCheck;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditLog;
import local.carter.office.magic.terrain.EditLog.LoggedEdit;
import local.carter.office.magic.terrain.EditLog.UndoReport;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.LaunchedDebris;
import local.carter.office.magic.terrain.NoTerrain;
import local.carter.office.magic.terrain.TempBlockData;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Where spell debris ({@code EditBatch.launch}) may land ({@link LaunchedDebris}, {@code FallingBlockEntityMixin}). Every test
 * drops stone hovering at {@link #HOVER_Y} straight down (launched with no velocity) onto the arena floor at y = 4, one column
 * per case, with an open column (or a plain falling block) as the positive control.
 */
public class DebrisGameTests {
	private static final int HOVER_Y = 10;
	private static final int GROUND_Y = MagicTestKit.FLOOR_TOP + 1;
	/** Cast ids for hand-made contexts, far above {@code CastService}'s counter and TerrainGameTests' ids. */
	private static final AtomicLong CAST_IDS = new AtomicLong(1L << 41);
	private static final MagicTestKit.Probe SPELL = MagicTestKit.probe("debris_probe", Element.EARTH, 1);

	private static CastContext ctx(GameTestHelper h, ServerPlayer p, SpellKit kit) {
		return new CastContext(p, h.getLevel(), SPELL, 0f, 1f, 2, 0L, CAST_IDS.incrementAndGet(), kit);
	}

	/** A world-edit service of this test only (ticked by the test), with {@code claims}. */
	private static WorldTerrainEdits local(GameTestHelper h, ClaimCheck claims) {
		MinecraftServer s = h.getLevel().getServer();
		return new WorldTerrainEdits(s, claims, new TempBlockData(), EditLog.forServer(s));
	}

	private static BlockPos hover(int x, int z) {
		return new BlockPos(x, HOVER_Y, z);
	}

	private static BlockPos ground(int x, int z) {
		return new BlockPos(x, GROUND_Y, z);
	}

	/** Puts stone at {@link #hover} of each column and launches it in one batch of {@code edits} (applied at once). */
	private static void drop(GameTestHelper h, ServerPlayer p, WorldTerrainEdits edits, int z, int... xs) {
		for (int x : xs) h.setBlock(hover(x, z), Blocks.STONE);
		try (EditBatch b = edits.begin(ctx(h, p, new SpellKit(edits, NoTerrain.INSTANCE)), h.absolutePos(hover(16, z)), EditTier.ULTIMATE)) {
			for (int x : xs) h.assertTrue(b.launch(h.absolutePos(hover(x, z)), Vec3.ZERO), "launch at x " + x);
		}
		edits.tick();
		for (int x : xs) h.assertBlockPresent(Blocks.AIR, hover(x, z));
	}

	private static AABB arena(GameTestHelper h) {
		return new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(MagicTestKit.SIZE_X, MagicTestKit.SIZE_Y, MagicTestKit.SIZE_Z)));
	}

	private static List<FallingBlockEntity> falling(GameTestHelper h) {
		return h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, arena(h));
	}

	/** Items in the column at {@code x, z} (rel), from the floor to above the hover height. */
	private static List<ItemEntity> itemsAt(GameTestHelper h, int x, int z) {
		AABB column = new AABB(h.absoluteVec(new Vec3(x - 1.5, GROUND_Y - 1, z - 1.5)), h.absoluteVec(new Vec3(x + 2.5, HOVER_Y + 3, z + 2.5)));
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, column);
	}

	private static boolean landedAt(List<LoggedEdit> edits, BlockPos abs) {
		return edits.stream().anyMatch(e -> !e.launched() && e.pos().equals(abs) && e.after().is(Blocks.STONE));
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void debrisNeverLandsInAnotherPlayersClaim(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		ServerPlayer owner = MagicTestKit.otherPlayer(h, new BlockPos(28, GROUND_Y, 28));
		BlockPos claimedGround = h.absolutePos(ground(12, 16));
		int claimTop = claimedGround.getY() + 3;
		// Another player's claim: the column at x 12 up to 3 blocks above the floor (the hovering stone itself is outside it).
		ClaimCheck claims = (caster, level, origin, radius) -> pos -> !caster.getUUID().equals(owner.getUUID())
				&& pos.getX() == claimedGround.getX() && pos.getZ() == claimedGround.getZ() && pos.getY() <= claimTop;
		WorldTerrainEdits edits = local(h, claims);
		drop(h, p, edits, 16, 12, 20);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(falling(h).isEmpty(), "debris still falling"))
				.thenIdle(2)
				.thenExecute(() -> {
					edits.tick(); // writes the cast's log
					for (int y = GROUND_Y; y < HOVER_Y; y++) h.assertBlockPresent(Blocks.AIR, new BlockPos(12, y, 16));
					h.assertTrue(itemsAt(h, 12, 16).isEmpty(), "items in the claim: " + itemsAt(h, 12, 16));
					// Positive control: the open column gets its block.
					h.assertBlockPresent(Blocks.STONE, ground(20, 16));
					h.assertTrue(itemsAt(h, 20, 16).isEmpty(), "items in the open column");
					List<LoggedEdit> logged = edits.log().casts(p.getUUID()).get(0).edits();
					h.assertTrue(landedAt(logged, h.absolutePos(ground(20, 16))), "open landing logged: " + logged);
					h.assertFalse(logged.stream().anyMatch(e -> e.pos().equals(claimedGround)), "refused landing logged");
				})
				.thenSucceed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void debrisNeverLandsOnAPlayerOrProtectedEntity(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		ServerPlayer friend = MagicTestKit.otherPlayer(h, ground(12, 16));
		float health = friend.getHealth();
		ArmorStand stand = h.spawn(EntityTypes.ARMOR_STAND, ground(8, 16));
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		drop(h, p, edits, 16, 12, 8, 20);
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(falling(h).isEmpty(), "debris still falling"))
				.thenIdle(20) // a stone in the friend's cells would have hurt them by now
				.thenExecute(() -> {
					h.assertBlockPresent(Blocks.AIR, ground(12, 16));
					h.assertBlockPresent(Blocks.AIR, ground(12, 16).above());
					h.assertFalse(friend.isInWall(), "friend in a wall");
					h.assertValueEqual(friend.getHealth(), health, "friend's health");
					h.assertBlockPresent(Blocks.AIR, ground(8, 16));
					h.assertTrue(stand.isAlive(), "armor stand still there");
					h.assertTrue(itemsAt(h, 12, 16).isEmpty() && itemsAt(h, 8, 16).isEmpty(), "items where debris was refused");
					// Positive control.
					h.assertBlockPresent(Blocks.STONE, ground(20, 16));
				})
				.thenSucceed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 140)
	public void landedDebrisIsRemovedByUndo(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		BlockPos landing = ground(16, 16);
		h.setBlock(landing.below(), Blocks.DIRT);
		h.setBlock(landing, Blocks.SHORT_GRASS); // replaceable: the debris takes its cell, undo brings it back
		h.setBlock(hover(16, 16), Blocks.STONE);
		BlockPos from = h.absolutePos(hover(16, 16));
		MagicTestKit.override(h, MagicTestKit.probe("debris_undo", Element.EARTH, 1).withCost(new SpellCost(0, 0, 0, 0)).withBody(ctx -> {
			try (EditBatch b = ctx.kit().terrain().begin(ctx, from, EditTier.MEDIUM)) {
				h.assertTrue(b.launch(from, Vec3.ZERO), "launch");
			}
			return CastOutcome.cast(ctx.fx());
		}));
		h.assertTrue(MagicServer.kit().terrain() instanceof WorldTerrainEdits, "world terrain kit installed");
		WorldTerrainEdits world = (WorldTerrainEdits) MagicServer.kit().terrain();
		h.assertValueEqual(MagicTestKit.cast(p, Identifier.fromNamespaceAndPath("office-test", "debris_undo"), 0f),
				Result.CAST, "cast");
		h.startSequence()
				.thenWaitUntil(() -> h.assertBlockPresent(Blocks.STONE, landing))
				.thenWaitUntil(() -> {
					List<EditLog.LoggedCast> casts = world.log().casts(p.getUUID());
					h.assertTrue(!casts.isEmpty() && landedAt(casts.get(0).edits(), h.absolutePos(landing)), "landing logged");
				})
				.thenExecute(() -> {
					h.assertTrue(itemsAt(h, 16, 16).isEmpty(), "items");
					UndoReport r = MagicCommand.undo(h.getLevel().getServer(), p.getUUID(), 1);
					// The landing is restored; the launch cell stays empty (launched blocks are never put back).
					h.assertValueEqual(r, new UndoReport(1, 1, 1), "undo report");
					h.assertBlockPresent(Blocks.SHORT_GRASS, landing);
					h.assertBlockPresent(Blocks.AIR, hover(16, 16));
				})
				.thenSucceed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void debrisBreakingOnATorchDropsNothing(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		for (int x : new int[] {8, 12, 20}) h.setBlock(ground(x, 16), Blocks.TORCH);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		drop(h, p, edits, 16, 8, 12);
		// Debris whose dropItem was switched back on (an NBT edit, a mod) still drops nothing.
		for (FallingBlockEntity f : falling(h)) {
			h.assertTrue(LaunchedDebris.isDebris(f), "launched debris is tagged");
			if (f.blockPosition().getX() == h.absolutePos(hover(12, 16)).getX()) f.dropItem = true;
		}
		// Positive control: a plain falling block breaking on a torch drops its item.
		h.setBlock(hover(20, 16), Blocks.STONE);
		FallingBlockEntity plain = FallingBlockEntity.fall(h.getLevel(), h.absolutePos(hover(20, 16)), Blocks.STONE.defaultBlockState());
		h.assertTrue(plain.dropItem && !LaunchedDebris.isDebris(plain), "plain falling block");
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(falling(h).isEmpty(), "debris still falling"))
				.thenIdle(2)
				.thenExecute(() -> {
					for (int x : new int[] {8, 12, 20}) h.assertBlockPresent(Blocks.TORCH, ground(x, 16));
					h.assertTrue(itemsAt(h, 8, 16).isEmpty(), "items from debris: " + itemsAt(h, 8, 16));
					h.assertTrue(itemsAt(h, 12, 16).isEmpty(), "items from debris with dropItem on: " + itemsAt(h, 12, 16));
					h.assertTrue(itemsAt(h, 20, 16).stream().anyMatch(i -> i.getItem().is(Items.STONE)), "the plain falling block's item");
					for (ItemEntity i : itemsAt(h, 20, 16)) i.discard();
				})
				.thenSucceed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void untrackedDebrisNeverLands(GameTestHelper h) {
		// Tagged debris without a flight record (saved with the world before a restart) vanishes: no block, no item.
		h.setBlock(hover(12, 16), Blocks.STONE);
		FallingBlockEntity orphan = FallingBlockEntity.fall(h.getLevel(), h.absolutePos(hover(12, 16)), Blocks.STONE.defaultBlockState());
		orphan.addTag(LaunchedDebris.TAG);
		// Positive control: an untagged falling block lands.
		h.setBlock(hover(20, 16), Blocks.STONE);
		FallingBlockEntity.fall(h.getLevel(), h.absolutePos(hover(20, 16)), Blocks.STONE.defaultBlockState());
		h.startSequence()
				.thenWaitUntil(() -> h.assertTrue(falling(h).isEmpty(), "still falling"))
				.thenIdle(2)
				.thenExecute(() -> {
					for (int y = GROUND_Y; y <= HOVER_Y; y++) h.assertBlockPresent(Blocks.AIR, new BlockPos(12, y, 16));
					h.assertTrue(itemsAt(h, 12, 16).isEmpty(), "items: " + itemsAt(h, 12, 16));
					h.assertBlockPresent(Blocks.STONE, ground(20, 16));
				})
				.thenSucceed();
	}
}
