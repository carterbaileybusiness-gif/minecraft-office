package local.carter.office.core;

import java.util.List;

/**
 * Result of feeding one captured frame to {@link TileCodec}: the grid shape plus the tiles that changed
 * (every tile on the first frame or after a size change, in which case {@code sizeChanged} is true and the
 * sender should emit a SIZE packet before the tiles).
 */
public record TileFrame(int width, int height, int cols, int rows, boolean sizeChanged, List<TileCodec.Tile> tiles) {
    public TileFrame {
        tiles = List.copyOf(tiles);
    }
}
