package local.carter.office.magic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import local.carter.office.magic.terrain.EditLog;
import local.carter.office.magic.terrain.EditLog.LoggedCast;
import local.carter.office.magic.terrain.EditLog.LoggedEdit;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

class EditLogTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap(); // block states need the block registry
	}

	private static LoggedCast cast(UUID player, long gameTime) {
		return new LoggedCast(player, Identifier.fromNamespaceAndPath("office", "meteor"), Level.NETHER, gameTime, List.of(
				new LoggedEdit(new BlockPos(1, 64, -2), Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState()),
				new LoggedEdit(new BlockPos(-30_000, -60, 7), Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.MAGMA_BLOCK.defaultBlockState()),
				new LoggedEdit(new BlockPos(5, 300, 5), Blocks.AIR.defaultBlockState(),
						Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP))));
	}

	@Test
	void loggedCastRoundTripsThroughNbt() {
		LoggedCast c = cast(UUID.randomUUID(), 123_456L);
		Tag tag = LoggedCast.CODEC.encodeStart(NbtOps.INSTANCE, c).getOrThrow();
		LoggedCast back = LoggedCast.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		assertEquals(c, back);
		assertEquals(3, back.edits().size());
		assertEquals(SlabType.TOP, back.edits().get(2).after().getValue(SlabBlock.TYPE));
	}

	@Test
	void filesRoundTripNewestFirstAndKeepTwenty(@TempDir Path dir) throws Exception {
		EditLog log = new EditLog(dir);
		UUID player = UUID.randomUUID();
		for (int i = 0; i < 25; i++) log.write(cast(player, 1000 + i), i + 1);
		Path playerDir = dir.resolve(player.toString());
		try (var files = Files.list(playerDir)) {
			assertEquals(EditLog.KEEP, files.count());
		}
		List<LoggedCast> casts = log.casts(player);
		assertEquals(EditLog.KEEP, casts.size());
		assertEquals(1024, casts.get(0).gameTime(), "newest first");
		assertEquals(1005, casts.get(EditLog.KEEP - 1).gameTime(), "the oldest 5 were dropped");
		assertEquals(cast(player, 1024), casts.get(0));
		assertTrue(Files.exists(playerDir.resolve("1024-25.nbt")));
		assertTrue(log.casts(UUID.randomUUID()).isEmpty());
	}
}
