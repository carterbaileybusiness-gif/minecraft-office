package local.carter.office.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Trivial server gametest proving the runGametest wiring. */
public class OfficeGameTest {
	@GameTest
	public void wiringWorks(GameTestHelper context) {
		context.setBlock(0, 1, 0, Blocks.STONE);
		context.assertBlockPresent(Blocks.STONE, 0, 1, 0);
		context.succeed();
	}
}
