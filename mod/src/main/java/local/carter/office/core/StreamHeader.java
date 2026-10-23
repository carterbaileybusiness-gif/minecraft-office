package local.carter.office.core;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Wire header of every screen-stream datagram, big-endian:
 * {@code magic u16 0x4F46 | ver u8 = 1 | type u8 | streamId i32 | frameSeq i32 | tx u16 | ty u16 | part u8 | parts u8}.
 * Client-to-server packets append an 8-byte token after the payload (outside this header).
 */
public record StreamHeader(int type, int streamId, int frameSeq, int tx, int ty, int part, int parts) {
    public static final int MAGIC = 0x4F46;
    public static final int VERSION = 1;
    /** Sum of the fields above: 2 + 1 + 1 + 4 + 4 + 2 + 2 + 1 + 1. */
    public static final int HEADER_BYTES = 18;

    public static final int TYPE_HELLO = 1;
    public static final int TYPE_HELLO_ACK = 2;
    public static final int TYPE_TILE = 3;
    public static final int TYPE_CLEAR = 4;
    public static final int TYPE_SIZE = 5;

    public StreamHeader {
        if (type < 0 || type > 0xFF) throw new IllegalArgumentException("type " + type);
        if (tx < 0 || tx > 0xFFFF) throw new IllegalArgumentException("tx " + tx);
        if (ty < 0 || ty > 0xFFFF) throw new IllegalArgumentException("ty " + ty);
        if (part < 0 || part > 0xFF) throw new IllegalArgumentException("part " + part);
        if (parts < 0 || parts > 0xFF) throw new IllegalArgumentException("parts " + parts);
    }

    public byte[] encode() {
        byte[] out = new byte[HEADER_BYTES];
        writeTo(ByteBuffer.wrap(out));
        return out;
    }

    /** Writes the header at the buffer's current position (big-endian), advancing it by {@link #HEADER_BYTES}. */
    public void writeTo(ByteBuffer b) {
        b.putShort((short) MAGIC)
                .put((byte) VERSION)
                .put((byte) type)
                .putInt(streamId)
                .putInt(frameSeq)
                .putShort((short) tx)
                .putShort((short) ty)
                .put((byte) part)
                .put((byte) parts);
    }

    /** Parses the first {@link #HEADER_BYTES} bytes; empty when too short, wrong magic, or wrong version. */
    public static Optional<StreamHeader> decode(byte[] datagram) {
        if (datagram == null || datagram.length < HEADER_BYTES) return Optional.empty();
        ByteBuffer b = ByteBuffer.wrap(datagram);
        if ((b.getShort() & 0xFFFF) != MAGIC) return Optional.empty();
        if ((b.get() & 0xFF) != VERSION) return Optional.empty();
        int type = b.get() & 0xFF;
        int streamId = b.getInt();
        int frameSeq = b.getInt();
        int tx = b.getShort() & 0xFFFF;
        int ty = b.getShort() & 0xFFFF;
        int part = b.get() & 0xFF;
        int parts = b.get() & 0xFF;
        return Optional.of(new StreamHeader(type, streamId, frameSeq, tx, ty, part, parts));
    }
}
