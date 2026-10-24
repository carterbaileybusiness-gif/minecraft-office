package local.carter.office.magic.core;

import java.util.Optional;

/** The six magic elements. Colours live here only; tools/make_magic_textures.py parses the COLOURS block. */
public enum Element {
	// COLOURS
	FIRE("fire", 0xFFFF6A1A),
	WATER("water", 0xFF2F8CFF),
	WIND("wind", 0xFF9CF2C8),
	LIGHTNING("lightning", 0xFFFFE94A),
	EARTH("earth", 0xFFB0814A),
	SHADOW("shadow", 0xFF8A3CFF);
	/** Recall (non-elemental) gold. */
	public static final int RECALL_ARGB = 0xFFFFD27A;
	// END COLOURS

	private final String key;
	private final int argb;

	Element(String key, int argb) {
		this.key = key;
		this.argb = argb;
	}

	public String key() { return key; }

	public int argb() { return argb; }

	public static Optional<Element> byKey(String key) {
		for (Element e : values()) if (e.key.equals(key)) return Optional.of(e);
		return Optional.empty();
	}
}
