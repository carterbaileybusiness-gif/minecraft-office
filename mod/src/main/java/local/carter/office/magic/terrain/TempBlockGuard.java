package local.carter.office.magic.terrain;

import java.util.Iterator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import local.carter.office.OfficeMod;
import local.carter.office.magic.net.MagicServer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/**
 * Temporary blocks are never a source of items (no dupes from Earth Wall and friends). Every way a block breaks or moves ends here:
 * <ul>
 * <li><b>Players</b> (survival, silk touch, any tool; creative too): {@link PlayerBlockBreakEvents#BEFORE}, in a phase after the
 * default one so claim and protection mods decide first. The break is cancelled and the original block comes back at once:
 * no items, no XP, no tool wear.</li>
 * <li><b>Explosions</b> (creepers, TNT, beds, spells): {@code ServerExplosionMixin} takes temporary blocks out of the blast before
 * any block is hit and restores their originals: no drops, and no fire on those cells. Wind bursts ({@code TRIGGER_BLOCK}:
 * wind charges, the breeze, the mace) break nothing in vanilla and leave temporary blocks intact.</li>
 * <li><b>{@code Level.destroyBlock}</b> (the wither, mods, spells that shatter a block): {@code LevelMixin}, same result.</li>
 * <li><b>Pistons</b>: {@code PistonBaseBlockMixin} makes a temporary block immovable, like obsidian, so it never becomes a moved
 * real block. Clients don't know which blocks are temporary and may animate one moving, so its cell and the one it would move
 * into are sent to them again.</li>
 * <li><b>Endermen</b> never pick one up and <b>silverfish</b> never infest one ({@code EndermanTakeBlockGoalMixin},
 * {@code SilverfishMergeWithStoneGoalMixin}).</li>
 * <li><b>Fluids</b> can't break one: {@link TempBlockData#isSafe} keeps out everything water or lava can wash away or flow into.</li>
 * </ul>
 * Breaking a temporary block always restores the original block right away (the entry is done, so the timed restore has
 * nothing left to do). Only the installed server kit's {@link TempBlockData} is watched. Server thread only.
 */
public final class TempBlockGuard {
	/** Runs after {@link Event#DEFAULT_PHASE}: a protection mod that refuses the break wins, and the block stays as it is. */
	public static final Identifier PHASE = OfficeMod.id("temp_blocks");

	private TempBlockGuard() {}

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
		PlayerBlockBreakEvents.BEFORE.register(PHASE, (level, player, pos, state, blockEntity) -> !breakTemp(level, pos, player, true));
	}

	private static @Nullable TempBlockData data(LevelAccessor level) {
		if (!(level instanceof ServerLevel)) return null;
		return MagicServer.kit().temp() instanceof TempBlockData t && t.active() > 0 ? t : null;
	}

	/** Whether {@code pos} holds a live temporary block (always false on a client). */
	public static boolean isTemp(LevelAccessor level, BlockPos pos) {
		TempBlockData t = data(level);
		return t != null && t.isTemp((Level) level, pos);
	}

	/** {@link TempBlockData#breakTemp}: no drops, original back at once. False, changing nothing, for anything else. */
	public static boolean breakTemp(Level level, BlockPos pos, @Nullable Entity breaker, boolean effects) {
		TempBlockData t = data(level);
		return t != null && t.breakTemp((ServerLevel) level, pos, breaker, effects);
	}

	/**
	 * An explosion is about to hit {@code targets}: temporary blocks leave the list and get their original back. A
	 * {@code TRIGGER_BLOCK} burst (wind charge, breeze, mace) breaks nothing in vanilla, so it leaves them as they are.
	 */
	public static void spareFromExplosion(ServerLevel level, Explosion.BlockInteraction interaction, List<BlockPos> targets) {
		if (interaction == Explosion.BlockInteraction.TRIGGER_BLOCK) return;
		TempBlockData t = data(level);
		if (t == null) return;
		for (Iterator<BlockPos> it = targets.iterator(); it.hasNext();) {
			BlockPos pos = it.next();
			if (t.breakTemp(level, pos, null, false)) it.remove();
		}
	}

	/**
	 * Whether a piston must treat {@code pos} as immovable (a live temporary block). When it is, the cell and the one it would be
	 * pushed into are sent to clients again, which can't tell and may have started moving it.
	 */
	public static boolean immovable(Level level, BlockPos pos, Direction direction) {
		if (!isTemp(level, pos)) return false;
		ServerLevel server = (ServerLevel) level;
		server.getChunkSource().blockChanged(pos);
		BlockPos into = pos.relative(direction);
		if (server.isLoaded(into)) server.getChunkSource().blockChanged(into);
		return true;
	}
}
