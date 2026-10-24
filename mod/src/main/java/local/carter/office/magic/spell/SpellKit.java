package local.carter.office.magic.spell;

import local.carter.office.magic.terrain.NoTerrain;
import local.carter.office.magic.terrain.TempBlocks;
import local.carter.office.magic.terrain.TerrainEdits;

/** World services a spell may use, reached through {@link CastContext#kit()}. Task 5 installs the real one on server start. */
public record SpellKit(TerrainEdits terrain, TempBlocks temp) {
	/** Refuses every block change. */
	public static final SpellKit NONE = new SpellKit(NoTerrain.INSTANCE, NoTerrain.INSTANCE);
}
