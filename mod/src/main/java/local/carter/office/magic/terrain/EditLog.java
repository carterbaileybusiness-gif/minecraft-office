package local.carter.office.magic.terrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The terrain edit log on disk: one compressed NBT file per terrain-changing cast at
 * {@code <world>/data/office/magic-edits/<player uuid>/<gameTime>-<castId>.nbt}, the newest {@link #KEEP} per player.
 * Server thread only.
 */
public final class EditLog {
	/** Casts kept per player (the oldest are deleted). */
	public static final int KEEP = 20;

	private static final Logger LOGGER = LoggerFactory.getLogger("office");
	private static final String EXT = ".nbt";
	private static final String TMP = ".tmp";

	/**
	 * One block change: what was there when the change was applied, and what the change left. {@code launched}: the block flew
	 * off as a falling block. Undo puts it back like any edit: debris lands only through {@code LaunchedDebris}, which logs the
	 * landing in the same cast (undone first, newest first) or discards the debris, so nothing is duplicated.
	 */
	public record LoggedEdit(BlockPos pos, BlockState before, BlockState after, boolean launched) {
		public static final Codec<LoggedEdit> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(LoggedEdit::pos),
				BlockState.CODEC.fieldOf("before").forGetter(LoggedEdit::before),
				BlockState.CODEC.fieldOf("after").forGetter(LoggedEdit::after),
				Codec.BOOL.optionalFieldOf("launched", false).forGetter(LoggedEdit::launched)).apply(i, LoggedEdit::new));

		public LoggedEdit(BlockPos pos, BlockState before, BlockState after) {
			this(pos, before, after, false);
		}
	}

	/** Every applied edit of one cast (all of its batches), in the order applied. */
	public record LoggedCast(UUID player, Identifier spell, ResourceKey<Level> dimension, long gameTime, List<LoggedEdit> edits) {
		public static final Codec<LoggedCast> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.STRING_CODEC.fieldOf("player").forGetter(LoggedCast::player),
				Identifier.CODEC.fieldOf("spell").forGetter(LoggedCast::spell),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(LoggedCast::dimension),
				Codec.LONG.fieldOf("game_time").forGetter(LoggedCast::gameTime),
				LoggedEdit.CODEC.listOf().fieldOf("edits").forGetter(LoggedCast::edits)).apply(i, LoggedCast::new));

		public LoggedCast {
			edits = List.copyOf(edits);
		}
	}

	/** Blocks put back, casts undone, and blocks left alone (changed since the cast, or a dimension that is gone). */
	public record UndoReport(int restored, int casts, int skipped) {
		public UndoReport plus(UndoReport o) {
			return new UndoReport(restored + o.restored, casts + o.casts, skipped + o.skipped);
		}
	}

	private record Entry(Path file, long gameTime, long castId) {}

	private static final Comparator<Entry> NEWEST_FIRST = Comparator.comparingLong(Entry::gameTime)
			.thenComparingLong(Entry::castId).reversed();

	private final Path dir;

	public EditLog(Path dir) {
		this.dir = dir;
	}

	/** The world's log directory, {@code <world>/data/office/magic-edits}. */
	public static EditLog forServer(MinecraftServer server) {
		return new EditLog(server.getWorldPath(LevelResource.DATA).resolve("office").resolve("magic-edits"));
	}

	public Path dir() {
		return dir;
	}

	/** Writes (or rewrites) the file of cast {@code castId}, then drops the player's oldest casts beyond {@link #KEEP}. */
	public void write(LoggedCast cast, long castId) {
		Path playerDir = dir.resolve(cast.player().toString());
		Path file = playerDir.resolve(cast.gameTime() + "-" + castId + EXT);
		try {
			Files.createDirectories(playerDir);
			Tag tag = LoggedCast.CODEC.encodeStart(NbtOps.INSTANCE, cast).getOrThrow();
			Path tmp = playerDir.resolve(file.getFileName() + TMP);
			NbtIo.writeCompressed((CompoundTag) tag, tmp);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Magic terrain: could not write the edit log {}", file, e);
			return;
		}
		List<Entry> entries = entries(cast.player());
		for (int i = KEEP; i < entries.size(); i++) delete(entries.get(i).file());
	}

	/** The player's logged casts, newest first (unreadable files are skipped). */
	public List<LoggedCast> casts(UUID player) {
		List<LoggedCast> out = new ArrayList<>();
		for (Entry e : entries(player)) {
			LoggedCast c = read(e.file());
			if (c != null) out.add(c);
		}
		return out;
	}

	/**
	 * Undoes the player's newest {@code n} casts, newest first: each edit (last applied first) goes back to {@code before} only
	 * if the block is still the edit's {@code after} (for a launched block: the air or fluid it left); the others are skipped, so
	 * a block a player put there since stays. A landing on its own launch cell is undone before the launch. Undone casts leave
	 * the log.
	 */
	public UndoReport undo(MinecraftServer server, UUID player, int n) {
		int restored = 0;
		int casts = 0;
		int skipped = 0;
		List<Entry> entries = entries(player);
		for (int i = 0; i < Math.min(n, entries.size()); i++) {
			Path file = entries.get(i).file();
			LoggedCast cast = read(file);
			delete(file);
			if (cast == null) continue;
			casts++;
			ServerLevel level = server.getLevel(cast.dimension());
			if (level == null) {
				skipped += cast.edits().size();
				continue;
			}
			List<LoggedEdit> edits = cast.edits();
			for (int j = edits.size() - 1; j >= 0; j--) {
				LoggedEdit e = edits.get(j);
				if (level.getBlockState(e.pos()).equals(e.after())) {
					level.setBlock(e.pos(), e.before(), Block.UPDATE_ALL);
					restored++;
				} else {
					skipped++;
				}
			}
		}
		return new UndoReport(restored, casts, skipped);
	}

	/** Deletes {@code *.tmp} files a crash left mid-write (server start). */
	public void deleteStaleTemp() {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.walk(dir, 2)) {
			files.filter(f -> f.getFileName().toString().endsWith(TMP)).forEach(EditLog::delete);
		} catch (IOException e) {
			LOGGER.warn("Magic terrain: could not clean {}", dir, e);
		}
	}

	private List<Entry> entries(UUID player) {
		Path playerDir = dir.resolve(player.toString());
		if (!Files.isDirectory(playerDir)) return List.of();
		List<Entry> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(playerDir)) {
			files.forEach(f -> {
				Entry e = parse(f);
				if (e != null) out.add(e);
			});
		} catch (IOException e) {
			LOGGER.error("Magic terrain: could not list the edit log {}", playerDir, e);
		}
		out.sort(NEWEST_FIRST);
		return out;
	}

	private static @Nullable Entry parse(Path file) {
		String name = file.getFileName().toString();
		if (!name.endsWith(EXT)) return null;
		String stem = name.substring(0, name.length() - EXT.length());
		int dash = stem.indexOf('-');
		if (dash <= 0) return null;
		try {
			return new Entry(file, Long.parseLong(stem.substring(0, dash)), Long.parseLong(stem.substring(dash + 1)));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static @Nullable LoggedCast read(Path file) {
		try {
			CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			return LoggedCast.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Magic terrain: skipping unreadable edit log {}", file, e);
			return null;
		}
	}

	private static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOGGER.warn("Magic terrain: could not delete {}", file, e);
		}
	}
}
