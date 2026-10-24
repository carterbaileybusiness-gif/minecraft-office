package local.carter.office.magic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import local.carter.office.hologram.OfficeBlocks;
import local.carter.office.magic.cast.CastService.Result;
import local.carter.office.magic.command.MagicCommand;
import local.carter.office.magic.core.Element;
import local.carter.office.magic.core.Shapes;
import local.carter.office.magic.data.MagicAttachments;
import local.carter.office.magic.data.MagicData;
import local.carter.office.magic.net.MagicServer;
import local.carter.office.magic.spell.CastContext;
import local.carter.office.magic.spell.CastOutcome;
import local.carter.office.magic.spell.SpellCost;
import local.carter.office.magic.spell.SpellKit;
import local.carter.office.magic.terrain.ClaimCheck;
import local.carter.office.magic.terrain.EditBatch;
import local.carter.office.magic.terrain.EditLog;
import local.carter.office.magic.terrain.EditLog.LoggedCast;
import local.carter.office.magic.terrain.EditLog.UndoReport;
import local.carter.office.magic.terrain.EditTier;
import local.carter.office.magic.terrain.FallbackClaims;
import local.carter.office.magic.terrain.OpacClaims;
import local.carter.office.magic.terrain.NoTerrain;
import local.carter.office.magic.terrain.TempBlockData;
import local.carter.office.magic.terrain.WorldTerrainEdits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Server GameTests for terrain edits (Task 5): rules, radius per batch, budget per cast, the applier, the fallback claim rule,
 * temporary blocks, the edit log and undo. Concurrent tests share the fallback rule's inputs (every hologram emitter, every
 * online player's home and respawn point), so the tests run in {@link MagicTestKit#TERRAIN_ENV}, and each test that creates
 * one of those inputs runs alone in its own environment.
 */
public class TerrainGameTests {
	private static final String ENV_EMITTER = "office-test:magic_terrain_emitter";
	private static final String ENV_EMITTER_FAR = "office-test:magic_terrain_emitter_far";
	private static final String ENV_HOME = "office-test:magic_terrain_home";
	private static final String ENV_DIMENSION = "office-test:magic_terrain_dimension";

	/** Cast ids for hand-made contexts, far above {@code CastService}'s counter. */
	private static final AtomicLong CAST_IDS = new AtomicLong(1L << 40);
	private static final MagicTestKit.Probe SPELL = MagicTestKit.probe("terrain_probe", Element.EARTH, 1);
	private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private static MinecraftServer server(GameTestHelper h) {
		return h.getLevel().getServer();
	}

	/** A cast context for {@code p} with a fresh cast id (hand-made contexts reach the world kit without a spell). */
	private static CastContext ctx(GameTestHelper h, ServerPlayer p, SpellKit kit) {
		return new CastContext(p, h.getLevel(), SPELL, 0f, 1f, 2, 0L, CAST_IDS.incrementAndGet(), kit);
	}

	/** A world-edit service of this test only (its own applier, ticked by the test), with {@code claims}. */
	private static WorldTerrainEdits local(GameTestHelper h, ClaimCheck claims) {
		MinecraftServer s = server(h);
		return new WorldTerrainEdits(s, claims, new TempBlockData(), EditLog.forServer(s));
	}

	private static SpellKit kitOf(WorldTerrainEdits edits) {
		return new SpellKit(edits, NoTerrain.INSTANCE);
	}

	private static BlockPos abs(GameTestHelper h, int x, int y, int z) {
		return h.absolutePos(new BlockPos(x, y, z));
	}

	/** The installed world kit (asserted to be the real one). */
	private static WorldTerrainEdits world(GameTestHelper h) {
		h.assertTrue(MagicServer.kit().terrain() instanceof WorldTerrainEdits, "world terrain kit installed, got " + MagicServer.kit().terrain());
		h.assertTrue(MagicServer.kit().temp() instanceof TempBlockData, "temp block data installed");
		return (WorldTerrainEdits) MagicServer.kit().terrain();
	}

	/** An EARTH level-2 caster with a free medium probe {@code office-test:<path>} whose body edits terrain through the installed kit. */
	private static ServerPlayer terraformer(GameTestHelper h, String path, Consumer<EditBatchCtx> body) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		MagicTestKit.override(h, MagicTestKit.probe(path, Element.EARTH, 1).withCost(new SpellCost(0, 0, 0, 0)).withBody(ctx -> {
			body.accept(new EditBatchCtx(ctx));
			return CastOutcome.cast(ctx.fx());
		}));
		return p;
	}

	private record EditBatchCtx(CastContext ctx) {
		EditBatch begin(BlockPos origin, EditTier tier) {
			return ctx.kit().terrain().begin(ctx, origin, tier);
		}
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("office-test", path);
	}

	// ---- Rules ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_EMITTER)
	public void refusesBlockEntitiesBedrockObsidian(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		MagicTestKit.chest(h, new BlockPos(14, 4, 8));
		h.setBlock(new BlockPos(15, 4, 8), Blocks.BEDROCK);
		h.setBlock(new BlockPos(16, 4, 8), Blocks.OBSIDIAN);
		MagicTestKit.emitter(h, new BlockPos(17, 4, 8), p);
		long before = MagicTestKit.blockHash(h);
		// No claim rule here: what is refused is refused for being a block entity or immune, not for the emitter's 32 blocks.
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 16, 4, 6), EditTier.MEDIUM)) {
			h.assertFalse(b.remove(abs(h, 14, 4, 8)), "chest removed");
			h.assertFalse(b.set(abs(h, 15, 4, 8), GLASS), "bedrock replaced");
			h.assertFalse(b.launch(abs(h, 16, 4, 8), new Vec3(0, 1, 0)), "obsidian launched");
			h.assertFalse(b.remove(abs(h, 17, 4, 8)), "emitter removed");
			h.assertValueEqual(b.refused(), 4, "refused");
			h.assertValueEqual(b.applied(), 0, "applied");
		}
		for (int i = 0; i < 4; i++) edits.tick();
		h.assertValueEqual(MagicTestKit.blockHash(h), before, "arena blocks unchanged");
		h.assertBlockPresent(Blocks.CHEST, new BlockPos(14, 4, 8));
		h.assertBlockPresent(OfficeBlocks.HOLOGRAM_EMITTER, new BlockPos(17, 4, 8));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void refusesBeyondRadius(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 10, 8, 16), EditTier.MEDIUM)) {
			h.assertTrue(b.set(abs(h, 16, 8, 16), GLASS), "6 from the origin");
			h.assertFalse(b.set(abs(h, 17, 8, 16), GLASS), "7 from the origin");
			h.assertFalse(b.set(abs(h, 10, 8, 23), GLASS), "7 from the origin along z");
			h.assertValueEqual(b.refused(), 2, "refused");
		}
		edits.tick();
		h.assertBlockPresent(Blocks.GLASS, new BlockPos(16, 8, 16));
		h.assertBlockPresent(Blocks.AIR, new BlockPos(17, 8, 16));
		h.assertBlockPresent(Blocks.AIR, new BlockPos(10, 8, 23));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void segmentedBatchesReachFurther(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		CastContext ctx = ctx(h, p, kitOf(edits));
		try (EditBatch first = edits.begin(ctx, abs(h, 6, 6, 10), EditTier.MEDIUM);
				EditBatch second = edits.begin(ctx, abs(h, 16, 6, 10), EditTier.MEDIUM)) {
			h.assertTrue(first.set(abs(h, 6, 6, 15), GLASS), "first segment, 5 from its origin");
			h.assertTrue(second.set(abs(h, 16, 6, 15), GLASS), "second segment, 5 from its origin (15 from the first)");
		}
		edits.tick();
		edits.tick();
		h.assertBlockPresent(Blocks.GLASS, new BlockPos(6, 6, 15));
		h.assertBlockPresent(Blocks.GLASS, new BlockPos(16, 6, 15));
		List<LoggedCast> casts = edits.log().casts(p.getUUID());
		h.assertValueEqual(casts.size(), 1, "one log entry for the cast");
		h.assertValueEqual(casts.get(0).edits().size(), 2, "edits in the entry");
		h.assertValueEqual(casts.get(0).player(), p.getUUID(), "player");
		h.assertValueEqual(casts.get(0).spell(), SPELL.id(), "spell");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void budgetIsPerCast(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		CastContext ctx = ctx(h, p, kitOf(edits));
		List<Shapes.Cell> cells = Shapes.sphere(EditTier.MEDIUM.radius()).subList(0, 200);
		int applied = 0;
		int refused = 0;
		for (BlockPos origin : List.of(abs(h, 8, 11, 16), abs(h, 23, 11, 16))) {
			try (EditBatch b = edits.begin(ctx, origin, EditTier.MEDIUM)) {
				for (Shapes.Cell c : cells) b.set(origin.offset(c.x(), c.y(), c.z()), GLASS);
				applied += b.applied();
				refused += b.refused();
			}
		}
		h.assertValueEqual(applied, EditTier.MEDIUM.budget(), "accepted over both batches");
		h.assertValueEqual(refused, 400 - EditTier.MEDIUM.budget(), "refused over both batches");
		for (int i = 0; i < 3; i++) edits.tick();
		h.assertValueEqual(MagicTestKit.count(h, Blocks.GLASS), EditTier.MEDIUM.budget(), "glass placed");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void applierCapsPerTick(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 7);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 16, 12, 16), EditTier.ULTIMATE)) {
			for (BlockPos rel : BlockPos.betweenClosed(new BlockPos(11, 7, 11), new BlockPos(20, 16, 20))) b.set(h.absolutePos(rel), GLASS);
			h.assertValueEqual(b.applied(), 1000, "queued");
		}
		h.assertValueEqual(MagicTestKit.count(h, Blocks.GLASS), 0, "nothing before the applier runs");
		edits.tick();
		int afterOne = MagicTestKit.count(h, Blocks.GLASS);
		h.assertTrue(afterOne > 0 && afterOne <= WorldTerrainEdits.PER_TICK, "after one tick: " + afterOne);
		h.assertValueEqual(edits.queued(), 1000 - afterOne, "still queued");
		for (int i = 0; i < 3; i++) edits.tick();
		h.assertValueEqual(MagicTestKit.count(h, Blocks.GLASS), 1000, "after four ticks");
		h.assertValueEqual(edits.queued(), 0, "queue drained");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void launchThrowsFallingBlocksWithoutDrops(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		BlockPos origin = abs(h, 16, 3, 16);
		int accepted = 0;
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), origin, EditTier.MEDIUM)) {
			for (Shapes.Cell c : Shapes.sphere(4)) {
				if (c.y() <= 0 && b.launch(origin.offset(c.x(), c.y(), c.z()), new Vec3(0, 0.6, 0))) accepted++;
			}
			h.assertFalse(b.launch(abs(h, 16, 10, 16), Vec3.ZERO), "air is not launched");
		}
		h.assertValueEqual(accepted, WorldTerrainEdits.MAX_LAUNCH, "launches per cast");
		edits.tick();
		AABB box = new AABB(h.absoluteVec(Vec3.ZERO), h.absoluteVec(new Vec3(MagicTestKit.SIZE_X, MagicTestKit.SIZE_Y, MagicTestKit.SIZE_Z)));
		List<FallingBlockEntity> falling = h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, box);
		h.assertValueEqual(falling.size(), WorldTerrainEdits.MAX_LAUNCH, "falling blocks");
		for (FallingBlockEntity f : falling) {
			h.assertFalse(f.dropItem, "falling block drops an item");
			h.assertTrue(f.getDeltaMovement().y > 0.5, "launched upward: " + f.getDeltaMovement());
		}
		h.assertValueEqual(edits.log().casts(p.getUUID()).get(0).edits().size(), WorldTerrainEdits.MAX_LAUNCH, "launches logged");
		h.assertTrue(edits.log().casts(p.getUUID()).get(0).edits().stream().allMatch(EditLog.LoggedEdit::launched), "flagged as launched");
		for (FallingBlockEntity f : falling) f.discard();
		int stone = MagicTestKit.count(h, Blocks.STONE);
		long stoneLaunched = edits.log().casts(p.getUUID()).get(0).edits().stream().filter(e -> e.before().is(Blocks.STONE)).count();
		// The debris never landed (discarded): undo puts every launched block back.
		h.assertValueEqual(edits.undo(p.getUUID(), 1), new UndoReport(WorldTerrainEdits.MAX_LAUNCH, 1, 0), "undo report");
		h.assertValueEqual(MagicTestKit.count(h, Blocks.STONE), stone + (int) stoneLaunched, "stone after undo");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void placeTemporaryMediumOnlyOverAirOrReplaceable(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 7);
		TempBlockData temp = new TempBlockData();
		MinecraftServer s = server(h);
		WorldTerrainEdits edits = new WorldTerrainEdits(s, ClaimCheck.NONE, temp, EditLog.forServer(s));
		SpellKit kit = new SpellKit(edits, temp);
		h.setBlock(new BlockPos(17, 3, 16), Blocks.DIRT);
		h.setBlock(new BlockPos(17, 4, 16), Blocks.SHORT_GRASS);
		BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
		try (EditBatch b = edits.begin(ctx(h, p, kit), abs(h, 16, 4, 16), EditTier.MEDIUM)) {
			h.assertTrue(b.placeTemporary(abs(h, 16, 5, 16), ice, 100), "over air");
			h.assertTrue(b.placeTemporary(abs(h, 17, 4, 16), ice, 100), "over short grass");
			h.assertFalse(b.placeTemporary(abs(h, 16, 3, 16), ice, 100), "over stone");
		}
		try (EditBatch b = edits.begin(ctx(h, p, kit), abs(h, 16, 4, 16), EditTier.ULTIMATE)) {
			h.assertFalse(b.placeTemporary(abs(h, 15, 5, 16), ice, 100), "ultimate batch");
		}
		edits.tick();
		h.assertBlockPresent(Blocks.PACKED_ICE, new BlockPos(16, 5, 16));
		h.assertBlockPresent(Blocks.PACKED_ICE, new BlockPos(17, 4, 16));
		h.assertBlockPresent(Blocks.AIR, new BlockPos(15, 5, 16));
		h.assertValueEqual(temp.active(), 2, "temp entries");
		h.assertTrue(edits.log().casts(p.getUUID()).isEmpty(), "temporary blocks are not in the edit log");
		temp.restoreAll(s);
		h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 5, 16));
		h.assertBlockPresent(Blocks.SHORT_GRASS, new BlockPos(17, 4, 16));
		h.succeed();
	}

	// ---- Fallback claim rule ----

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_EMITTER_FAR, maxTicks = 100)
	public void fallbackProtectsNearEmitter(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		MagicTestKit.emitter(h, new BlockPos(2, 4, 2), p);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		WorldTerrainEdits world = world(h);
		CastContext ctx = ctx(h, p, MagicServer.kit());
		try (EditBatch near = world.begin(ctx, abs(h, 2, 4, 22), EditTier.MEDIUM)) {
			h.assertFalse(near.set(abs(h, 2, 4, 22), GLASS), "20 from an emitter");
			h.assertFalse(near.set(abs(h, 3, 5, 22), GLASS), "about 20 from an emitter");
			h.assertValueEqual(near.refused(), 2, "refused near the emitter");
		}
		try (EditBatch far = world.begin(ctx, abs(h, 30, 10, 30), EditTier.MEDIUM)) {
			h.assertTrue(far.set(abs(h, 30, 10, 30), GLASS), "40 from the emitter");
			h.assertTrue(far.set(abs(h, 29, 10, 30), GLASS), "39 from the emitter");
		}
		h.succeedWhen(() -> {
			h.assertBlockPresent(Blocks.GLASS, new BlockPos(30, 10, 30));
			h.assertBlockPresent(Blocks.GLASS, new BlockPos(29, 10, 30));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(2, 4, 22));
			h.assertBlockPresent(Blocks.AIR, new BlockPos(3, 5, 22));
			h.assertValueEqual(rec.messageKeys(), List.of("magic.office.protected"), "one protected message per cast");
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_HOME, maxTicks = 100)
	public void fallbackProtectsOtherPlayersHome(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(28, 4, 28));
		GlobalPos home = GlobalPos.of(h.getLevel().dimension(), abs(h, 16, 4, 12));
		BlockPos target = abs(h, 16, 4, 22); // 10 from the home
		WorldTerrainEdits world = world(h);

		MagicAttachments.set(other, MagicData.EMPTY.withHome(Optional.of(home)));
		try (EditBatch b = world.begin(ctx(h, p, MagicServer.kit()), target, EditTier.MEDIUM)) {
			h.assertFalse(b.set(target, GLASS), "10 from another player's home");
		}
		MagicAttachments.set(other, MagicData.EMPTY);
		other.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(h.getLevel().dimension(), home.pos(), 0, 0), true), false);
		try (EditBatch b = world.begin(ctx(h, p, MagicServer.kit()), target, EditTier.MEDIUM)) {
			h.assertFalse(b.set(target, GLASS), "10 from another player's respawn point");
		}
		other.setRespawnPosition(null, false);
		MagicAttachments.set(p, MagicAttachments.get(p).withHome(Optional.of(home)));
		try (EditBatch b = world.begin(ctx(h, p, MagicServer.kit()), target, EditTier.MEDIUM)) {
			h.assertTrue(b.set(target, GLASS), "10 from the caster's own home");
		}
		h.succeedWhen(() -> h.assertBlockPresent(Blocks.GLASS, new BlockPos(16, 4, 22)));
	}

	// ---- Edit log and undo ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void undoRestoresLastCast(GameTestHelper h) {
		List<BlockPos> first = List.of(abs(h, 10, 4, 10), abs(h, 11, 4, 10), abs(h, 12, 4, 10));
		List<BlockPos> second = List.of(abs(h, 10, 4, 20), abs(h, 11, 4, 20), abs(h, 12, 4, 20));
		ServerPlayer p = terraformer(h, "terrain_undo_first", e -> {
			try (EditBatch b = e.begin(first.get(1), EditTier.MEDIUM)) {
				for (BlockPos pos : first) b.set(pos, GLASS);
			}
		});
		MagicTestKit.override(h, MagicTestKit.probe("terrain_undo_second", Element.EARTH, 1).withCost(new SpellCost(0, 0, 0, 0)).withBody(ctx -> {
			try (EditBatch b = ctx.kit().terrain().begin(ctx, second.get(1), EditTier.MEDIUM)) {
				for (BlockPos pos : second) b.set(pos, Blocks.OAK_PLANKS.defaultBlockState());
			}
			return CastOutcome.cast(ctx.fx());
		}));
		world(h);
		h.assertValueEqual(MagicTestKit.cast(p, id("terrain_undo_first"), 0f), Result.CAST, "first cast");
		h.assertValueEqual(MagicTestKit.cast(p, id("terrain_undo_second"), 0f), Result.CAST, "second cast");
		h.startSequence()
				.thenWaitUntil(() -> h.assertValueEqual(world(h).log().casts(p.getUUID()).size(), 2, "logged casts"))
				.thenExecute(() -> {
					UndoReport r = MagicCommand.undo(server(h), p.getUUID(), 1);
					h.assertValueEqual(r, new UndoReport(3, 1, 0), "undo report");
					for (BlockPos pos : second) h.assertValueEqual(h.getLevel().getBlockState(pos), AIR, "second cast undone at " + pos);
					for (BlockPos pos : first) h.assertValueEqual(h.getLevel().getBlockState(pos), GLASS, "first cast kept at " + pos);
					h.assertValueEqual(world(h).log().casts(p.getUUID()).size(), 1, "logged casts after undo");
				})
				.thenSucceed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 100)
	public void undoSkipsBlocksChangedSince(GameTestHelper h) {
		BlockPos centre = new BlockPos(16, 3, 16);
		List<Shapes.Cell> bowl = Shapes.bowl(3, 0.5);
		ServerPlayer p = terraformer(h, "terrain_crater", e -> {
			BlockPos o = h.absolutePos(centre);
			try (EditBatch b = e.begin(o, EditTier.MEDIUM)) {
				for (Shapes.Cell c : bowl) b.remove(o.offset(c.x(), c.y(), c.z()));
			}
		});
		world(h);
		int stone = MagicTestKit.count(h, Blocks.STONE);
		List<BlockPos> planks = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Shapes.Cell c = bowl.get(i * 5);
			planks.add(centre.offset(c.x(), c.y(), c.z()));
		}
		h.assertValueEqual(MagicTestKit.cast(p, id("terrain_crater"), 0f), Result.CAST, "crater cast");
		h.startSequence()
				.thenWaitUntil(() -> {
					h.assertValueEqual(MagicTestKit.count(h, Blocks.STONE), stone - bowl.size(), "crater dug");
					h.assertValueEqual(world(h).log().casts(p.getUUID()).size(), 1, "logged casts");
				})
				.thenExecute(() -> {
					for (BlockPos rel : planks) h.setBlock(rel, Blocks.OAK_PLANKS);
					UndoReport r = MagicCommand.undo(server(h), p.getUUID(), 1);
					h.assertValueEqual(r, new UndoReport(bowl.size() - 3, 1, 3), "undo report");
					h.assertValueEqual(MagicTestKit.count(h, Blocks.STONE), stone - 3, "stone restored");
					for (BlockPos rel : planks) h.assertBlockPresent(Blocks.OAK_PLANKS, rel);
				})
				.thenSucceed();
	}

	// ---- Temporary blocks ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void tempBlocksRestoreWhenExpired(GameTestHelper h) {
		TempBlockData temp = new TempBlockData();
		MinecraftServer s = server(h);
		BlockPos pit = new BlockPos(16, 3, 16);
		BlockPos replaced = new BlockPos(10, 4, 10);
		h.setBlock(pit, Blocks.AIR);
		h.assertFalse(temp.place(h.getLevel(), h.absolutePos(pit), Blocks.WATER.defaultBlockState(), 20), "temp water (never a liquid)");
		h.assertTrue(temp.place(h.getLevel(), h.absolutePos(pit), Blocks.PACKED_ICE.defaultBlockState(), 20), "temp packed ice");
		h.assertTrue(temp.place(h.getLevel(), h.absolutePos(replaced), GLASS, 20), "temp glass");
		h.assertFalse(temp.place(h.getLevel(), h.absolutePos(new BlockPos(10, 3, 10)), GLASS, 20), "over stone");
		h.setBlock(replaced, Blocks.DIRT);
		h.onEachTick(() -> temp.tick(s));
		h.runAfterDelay(10, () -> {
			h.assertBlockPresent(Blocks.PACKED_ICE, pit);
			h.assertValueEqual(temp.active(), 2, "active");
		});
		h.runAfterDelay(21, () -> {
			h.assertBlockPresent(Blocks.AIR, pit);
			h.assertBlockPresent(Blocks.DIRT, replaced);
			h.assertValueEqual(temp.active(), 0, "active after expiry");
			h.succeed();
		});
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void stoppingRestoresTempBlocks(GameTestHelper h) {
		TempBlockData temp = new TempBlockData();
		h.setBlock(new BlockPos(12, 3, 12), Blocks.DIRT); // short grass needs dirt below
		h.setBlock(new BlockPos(12, 4, 12), Blocks.SHORT_GRASS);
		List<BlockPos> cells = List.of(new BlockPos(12, 4, 12), new BlockPos(13, 4, 12), new BlockPos(14, 6, 12), new BlockPos(15, 9, 12));
		List<BlockState> originals = cells.stream().map(h::getBlockState).toList();
		for (BlockPos rel : cells) h.assertTrue(temp.place(h.getLevel(), h.absolutePos(rel), Blocks.PACKED_ICE.defaultBlockState(), 10_000), "temp at " + rel);
		for (BlockPos rel : cells) h.assertBlockPresent(Blocks.PACKED_ICE, rel);
		temp.restoreAll(server(h));
		for (int i = 0; i < cells.size(); i++) h.assertValueEqual(h.getBlockState(cells.get(i)), originals.get(i), "restored at " + cells.get(i));
		h.assertValueEqual(temp.active(), 0, "active");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 60)
	public void tempBlocksRestoredAfterReload(GameTestHelper h) {
		TempBlockData temp = new TempBlockData();
		BlockPos rel = new BlockPos(20, 5, 20);
		h.assertTrue(temp.place(h.getLevel(), h.absolutePos(rel), GLASS, 10), "temp glass");
		Tag saved = TempBlockData.TYPE.codec().encodeStart(NbtOps.INSTANCE, temp).getOrThrow();
		temp = null; // dropped: only the loaded copy below ever ticks
		TempBlockData loaded = TempBlockData.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
		h.assertValueEqual(loaded.active(), 1, "loaded entries");
		MinecraftServer s = server(h);
		h.runAfterDelay(5, () -> {
			loaded.tick(s);
			h.assertBlockPresent(Blocks.GLASS, rel);
		});
		h.runAfterDelay(12, () -> {
			loaded.tick(s);
			h.assertBlockPresent(Blocks.AIR, rel);
			h.assertValueEqual(loaded.active(), 0, "active");
			h.succeed();
		});
	}

	// ---- Fix round 1 ----

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void refusesTallBlockHalves(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		BlockState lower = Blocks.TALL_GRASS.defaultBlockState();
		BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		h.setBlock(new BlockPos(10, 3, 10), Blocks.DIRT);
		h.setBlock(new BlockPos(10, 4, 10), lower);
		h.setBlock(new BlockPos(10, 5, 10), upper);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 10, 5, 12), EditTier.MEDIUM)) {
			h.assertFalse(b.remove(abs(h, 10, 4, 10)), "lower half removed");
			h.assertFalse(b.launch(abs(h, 10, 5, 10), new Vec3(0, 1, 0)), "upper half launched");
			h.assertFalse(b.set(abs(h, 12, 4, 10), lower), "half a tall plant set");
			h.assertTrue(b.set(abs(h, 12, 5, 10), GLASS), "plain block next to it");
			h.assertValueEqual(b.refused(), 3, "refused");
		}
		edits.tick();
		h.assertValueEqual(h.getBlockState(new BlockPos(10, 4, 10)), lower, "lower half");
		h.assertValueEqual(h.getBlockState(new BlockPos(10, 5, 10)), upper, "upper half");
		h.assertBlockPresent(Blocks.AIR, new BlockPos(12, 4, 10));
		h.assertBlockPresent(Blocks.GLASS, new BlockPos(12, 5, 10));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void refusesSpawnProtection(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		MagicTestKit.Recorder rec = MagicTestKit.recorder(h);
		h.assertFalse(WorldTerrainEdits.SpawnProtection.VANILLA.test(p, h.getLevel(), abs(h, 16, 6, 10)),
				"GameTest servers have no spawn protection");
		MinecraftServer s = server(h);
		int edge = abs(h, 0, 0, 10).getZ();
		// Spawn protection reaching up to z = 10 of this arena (a GameTest server has none of its own).
		WorldTerrainEdits edits = new WorldTerrainEdits(s, ClaimCheck.NONE, new TempBlockData(), EditLog.forServer(s),
				(caster, level, pos) -> pos.getZ() <= edge);
		try (EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 16, 6, 12), EditTier.MEDIUM)) {
			h.assertFalse(b.set(abs(h, 16, 6, 10), GLASS), "inside spawn protection");
			h.assertFalse(b.remove(abs(h, 16, 3, 9)), "inside spawn protection, below");
			h.assertTrue(b.set(abs(h, 16, 6, 14), GLASS), "outside spawn protection");
			h.assertValueEqual(b.refused(), 2, "refused");
		}
		edits.tick();
		h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 6, 10));
		h.assertBlockPresent(Blocks.STONE, new BlockPos(16, 3, 9));
		h.assertBlockPresent(Blocks.GLASS, new BlockPos(16, 6, 14));
		h.assertValueEqual(rec.messageKeys(), List.of("magic.office.protected"), "one protected message per cast");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = ENV_DIMENSION, maxTicks = 100)
	public void claimsStayInTheirDimension(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		ServerPlayer other = MagicTestKit.otherPlayer(h, new BlockPos(28, 4, 28));
		MinecraftServer s = server(h);
		ServerLevel nether = s.getLevel(Level.NETHER);
		h.assertTrue(nether != null, "the Nether exists");
		BlockPos target = abs(h, 16, 4, 22);
		// Fallback: another player's home in the Nether, at the same coordinates as the overworld target.
		MagicAttachments.set(other, MagicData.EMPTY.withHome(Optional.of(GlobalPos.of(Level.NETHER, target))));
		FallbackClaims fallback = new FallbackClaims(s);
		h.assertFalse(fallback.forBatch(p, h.getLevel(), target, 6).test(target), "fallback: overworld under a Nether home");
		h.assertTrue(fallback.forBatch(p, nether, target, 6).test(target), "fallback: the Nether at the home");
		// OPAC: the lookup is asked for the batch's own dimension.
		UUID owner = other.getUUID();
		OpacClaims opac = new OpacClaims((level, cx, cz) -> level.dimension().equals(Level.NETHER) && cx == target.getX() >> 4
				&& cz == target.getZ() >> 4 ? owner : null);
		h.assertFalse(opac.forBatch(p, h.getLevel(), target, 6).test(target), "OPAC: overworld chunk under a Nether claim");
		h.assertTrue(opac.forBatch(p, nether, target, 6).test(target), "OPAC: the claimed Nether chunk");
		// And the installed kit lets the overworld edit through.
		try (EditBatch b = world(h).begin(ctx(h, p, MagicServer.kit()), target, EditTier.MEDIUM)) {
			h.assertTrue(b.set(target, GLASS), "overworld edit under a Nether home");
		}
		h.succeedWhen(() -> h.assertBlockPresent(Blocks.GLASS, new BlockPos(16, 4, 22)));
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void tempBlocksMustBeInertAndRefresh(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.WATER, 7);
		TempBlockData temp = new TempBlockData();
		MinecraftServer s = server(h);
		WorldTerrainEdits edits = new WorldTerrainEdits(s, ClaimCheck.NONE, temp, EditLog.forServer(s));
		SpellKit kit = new SpellKit(edits, temp);
		BlockPos cell = abs(h, 16, 6, 16);
		BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
		List<BlockState> unsafe = List.of(Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(), Blocks.ICE.defaultBlockState(),
				Blocks.SAND.defaultBlockState(), Blocks.CHEST.defaultBlockState(), Blocks.TALL_GRASS.defaultBlockState(),
				Blocks.OAK_DOOR.defaultBlockState(), Blocks.GLOWSTONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
		try (EditBatch b = edits.begin(ctx(h, p, kit), cell, EditTier.MEDIUM)) {
			for (BlockState bad : unsafe) h.assertFalse(b.placeTemporary(cell, bad, 100), "temporary " + bad);
			h.assertValueEqual(b.refused(), unsafe.size(), "refused");
			h.assertTrue(b.placeTemporary(cell, ice, 20), "temporary packed ice");
		}
		for (BlockState bad : unsafe) h.assertFalse(temp.place(h.getLevel(), abs(h, 17, 6, 16), bad, 20), "direct temporary " + bad);
		edits.tick();
		h.assertBlockPresent(Blocks.PACKED_ICE, new BlockPos(16, 6, 16));
		long firstExpire = temp.entries().get(0).expireGameTime();
		// The same state again through a batch refreshes the timer (the original stays air).
		try (EditBatch b = edits.begin(ctx(h, p, kit), cell, EditTier.MEDIUM)) {
			h.assertTrue(b.placeTemporary(cell, ice, 200), "refresh");
			h.assertFalse(b.placeTemporary(cell, GLASS, 200), "a different block over a live temporary one");
		}
		edits.tick();
		h.assertValueEqual(temp.active(), 1, "one entry");
		TempBlockData.Entry e = temp.entries().get(0);
		h.assertTrue(e.expireGameTime() >= firstExpire + 150, "timer refreshed: " + firstExpire + " -> " + e.expireGameTime());
		h.assertValueEqual(e.original(), AIR, "original kept");
		temp.restoreAll(s);
		h.assertBlockPresent(Blocks.AIR, new BlockPos(16, 6, 16));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void tempBlocksRestoredOnChunkUnload(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		TempBlockData temp = new TempBlockData();
		BlockPos a = abs(h, 2, 6, 2);
		BlockPos b = abs(h, 29, 6, 29);
		h.assertTrue(ChunkPos.pack(a) != ChunkPos.pack(b), "two chunks");
		BlockState ice = Blocks.PACKED_ICE.defaultBlockState();
		h.assertTrue(temp.place(level, a, ice, 10_000), "temp a");
		h.assertTrue(temp.place(level, a.above(), ice, 10_000), "temp a, above");
		h.assertTrue(temp.place(level, b, ice, 10_000), "temp b");
		// What CHUNK_UNLOAD does before the chunk is saved: every entry of that chunk back, due or not.
		temp.onChunkUnload(level, level.getChunkAt(a));
		h.assertValueEqual(level.getBlockState(a), AIR, "a restored");
		h.assertValueEqual(level.getBlockState(a.above()), AIR, "a above restored");
		h.assertValueEqual(level.getBlockState(b), ice, "other chunk untouched");
		h.assertValueEqual(temp.active(), 1, "entries left");
		temp.restoreAll(server(h));
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV, maxTicks = 200)
	public void expiredTempBlockInUnloadedChunkRestoredOnLoad(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		MinecraftServer s = server(h);
		BlockPos far = new BlockPos(h.absolutePos(BlockPos.ZERO).getX() + 8192, 200, h.absolutePos(BlockPos.ZERO).getZ());
		h.assertFalse(level.isLoaded(far), "far chunk not loaded");
		// As a crash leaves it: the saved data holds an expired entry for a chunk saved with the temporary block in it.
		TempBlockData.Entry entry = new TempBlockData.Entry(level.dimension(), far, AIR, GLASS, level.getGameTime() - 1);
		CompoundTag tag = new CompoundTag();
		tag.put("entries", TempBlockData.Entry.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.of(entry)).getOrThrow());
		TempBlockData temp = TempBlockData.TYPE.codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
		temp.tick(s);
		h.assertValueEqual(temp.active(), 1, "kept while its chunk is unloaded");
		h.assertValueEqual(temp.waiting(), 1, "waiting for the chunk");
		h.assertFalse(level.isLoaded(far), "tick never loads the chunk");
		temp.tick(s);
		LevelChunk chunk = level.getChunk(far.getX() >> 4, far.getZ() >> 4);
		chunk.setBlockState(far, GLASS, Block.UPDATE_SKIP_ALL_SIDEEFFECTS); // the chunk as saved, with the temporary block
		temp.onChunkLoad(level, chunk); // what CHUNK_LOAD does
		h.assertValueEqual(chunk.getBlockState(far), AIR, "restored on load");
		h.assertValueEqual(temp.active(), 0, "entries");
		h.assertValueEqual(temp.waiting(), 0, "waiting");
		h.succeed();
	}

	@GameTest(structure = MagicTestKit.ARENA, environment = MagicTestKit.TERRAIN_ENV)
	public void undoRevokesRunningCast(GameTestHelper h) {
		ServerPlayer p = MagicTestKit.caster(h, Element.EARTH, 2);
		WorldTerrainEdits edits = local(h, ClaimCheck.NONE);
		EditBatch b = edits.begin(ctx(h, p, kitOf(edits)), abs(h, 16, 6, 16), EditTier.MEDIUM);
		for (int x = 14; x <= 16; x++) h.assertTrue(b.set(abs(h, x, 6, 16), GLASS), "set " + x);
		for (int i = 0; i < WorldTerrainEdits.FLUSH_TICKS; i++) edits.tick();
		List<LoggedCast> logged = edits.log().casts(p.getUUID());
		h.assertValueEqual(logged.size(), 1, "periodic flush of a running cast");
		h.assertValueEqual(logged.get(0).edits().size(), 3, "edits flushed");
		h.assertTrue(b.set(abs(h, 17, 6, 16), GLASS), "queued, not yet applied");
		h.assertValueEqual(edits.undo(p.getUUID(), 1), new UndoReport(3, 1, 0), "undo report");
		h.assertFalse(b.set(abs(h, 18, 6, 16), GLASS), "the undone cast's batch refuses");
		b.close();
		for (int i = 0; i < 3; i++) edits.tick();
		for (int x = 14; x <= 18; x++) h.assertBlockPresent(Blocks.AIR, new BlockPos(x, 6, 16));
		h.assertTrue(edits.log().casts(p.getUUID()).isEmpty(), "nothing logged after the undo");
		h.succeed();
	}
}
