package local.carter.office.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;

/**
 * "⌨ working" after a player's name (name tag above their head and the tab list) while they are zoomed into one of
 * their screens. Done with a vanilla scoreboard team suffix, which every client renders in both places: the player is
 * moved to the {@link #TEAM} team while working and back to the team they were on before (if any) afterwards.
 *
 * <p>The team is not meant to persist: it is emptied when the server starts (a crash mid-session would otherwise leave
 * someone "working" forever) and the player is taken out of it when they disconnect. Server thread only.
 */
public final class WorkingTag {
	public static final String TEAM = "office_working";
	public static final String SUFFIX = " ⌨ working";

	/** Player UUID -> the team (by name) they were on before working, or "" for none. */
	private static final Map<UUID, String> PREVIOUS = new ConcurrentHashMap<>();

	private WorkingTag() {}

	public static void set(final ServerPlayer player, final boolean working) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		ServerScoreboard board = server.getScoreboard();
		String name = player.getScoreboardName();
		if (working) {
			PlayerTeam team = team(board);
			PlayerTeam current = board.getPlayersTeam(name);
			if (current == team) {
				return;
			}
			PREVIOUS.put(player.getUUID(), current == null ? "" : current.getName());
			board.addPlayerToTeam(name, team);
		} else {
			String previous = PREVIOUS.remove(player.getUUID());
			PlayerTeam team = board.getPlayerTeam(TEAM);
			if (team == null || board.getPlayersTeam(name) != team) {
				return;
			}
			PlayerTeam back = previous == null || previous.isEmpty() ? null : board.getPlayerTeam(previous);
			if (back != null) {
				board.addPlayerToTeam(name, back); // moves them out of the working team
			} else {
				board.removePlayerFromTeam(name, team);
			}
		}
	}

	/** Server start: nobody is working yet. */
	public static void reset(final MinecraftServer server) {
		PREVIOUS.clear();
		ServerScoreboard board = server.getScoreboard();
		PlayerTeam team = board.getPlayerTeam(TEAM);
		if (team != null) {
			board.removePlayerTeam(team);
		}
	}

	public static boolean isTagged(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		PlayerTeam team = server == null ? null : server.getScoreboard().getPlayerTeam(TEAM);
		return team != null && team.getPlayers().contains(player.getScoreboardName());
	}

	private static PlayerTeam team(final ServerScoreboard board) {
		@Nullable PlayerTeam team = board.getPlayerTeam(TEAM);
		if (team == null) {
			team = board.addPlayerTeam(TEAM);
			team.setPlayerSuffix(Component.literal(SUFFIX).withStyle(ChatFormatting.GRAY)); // broadcasts the change
		}
		return team;
	}
}
