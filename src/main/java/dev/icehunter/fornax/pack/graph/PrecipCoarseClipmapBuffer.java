package dev.icehunter.fornax.pack.graph;

/**
 * Vulkan-free ABI for the engine-owned, coarse nearby-precipitation field.
 *
 * <p>Each word describes the representative block column in one four-by-four block cell. Its low
 * byte is the caller's precipitation value, bit eight records whether the word was sampled, and
 * its upper sixteen bits hold an eight-bit tile index for each axis. The validity bit makes an
 * all-zero reset word unambiguously unknown even in the tag-zero region. The tag rejects the usual
 * toroidal aliases, but repeats after {@link #TAG_PERIOD_CELLS} cells on either axis; it is not an
 * unbounded world-cell identity.
 *
 * <p><strong>Discontinuous-recenter invariant:</strong> an uploader must clear the backing buffer
 * and schedule a complete refill of the current window before any consumer dispatch after a
 * discontinuous recenter, including a teleport. No encoded word may survive that reset, because a
 * tag match alone cannot distinguish a sample from one tag period away.
 */
public final class PrecipCoarseClipmapBuffer {
    /** Engine-buffer target name that packs may declare without a size. */
    public static final String TARGET = "precipCoarseClipmap";

    /**
     * Authored from the owner's observed roughly 100-block weather boundary: four-block cells retain
     * useful boundary detail across the accepted 512-block nearby-weather window.
     */
    public static final int CELL_STRIDE = 4;

    /** The accepted 512-block nearby-weather window contains 128 cells on each axis. */
    public static final int GRID = 128;
    public static final int COLUMNS = GRID * GRID;
    public static final long BYTE_SIZE = (long) COLUMNS * Integer.BYTES;

    /**
     * The accepted nearby-weather design moves coverage in 16-block steps, avoiding per-block
     * window churn while keeping the observed boundary inside the field.
     */
    public static final int ANCHOR_SNAP_BLOCKS = 16;

    /** Tag identity retains eight tile-index bits per axis in the upper sixteen-bit ABI field. */
    private static final int TAG_AXIS_BITS = 8;
    private static final int TAG_AXIS_MASK = (1 << TAG_AXIS_BITS) - 1;
    private static final int GRID_SHIFT = Integer.numberOfTrailingZeros(GRID);
    private static final int VALUE_MASK = 0xFF;
    /** Bit eight means this word was sampled; zero is always unknown after a reset. */
    public static final int VALID_MASK = 0x00000100;
    private static final int TAG_MASK = 0xFFFF0000;

    /** Number of cells after which the retained tag bits repeat on either world axis. */
    public static final int TAG_PERIOD_CELLS = GRID * (TAG_AXIS_MASK + 1);
    /** Block-coordinate equivalent of {@link #TAG_PERIOD_CELLS}. */
    public static final int TAG_PERIOD_BLOCKS = TAG_PERIOD_CELLS * CELL_STRIDE;

    private PrecipCoarseClipmapBuffer() {}

    /** Allocates the fixed raw-data buffer only while an enabled graph pass declares this target. */
    public static void ensureAllocated(TargetRegistry registry) {
        registry.ensureBufferSize(TARGET, BYTE_SIZE);
    }

    /** Releases the buffer when no enabled graph pass consumes this engine data lane. */
    public static void free(TargetRegistry registry) {
        registry.releaseBuffer(TARGET);
    }

    /** Maps a block coordinate to its enclosing coarse cell with floor division across the origin. */
    public static int cellForBlock(int block) {
        return Math.floorDiv(block, CELL_STRIDE);
    }

    /** Returns the centre-side block column that represents a coarse cell. */
    public static int representativeBlock(int cell) {
        return cell * CELL_STRIDE + CELL_STRIDE / 2;
    }

    /** Returns the toroidal storage slot for a world cell. */
    public static int slotForCell(int cellX, int cellZ) {
        return (cellZ & (GRID - 1)) * GRID + (cellX & (GRID - 1));
    }

    /** Returns the bounded upper-sixteen-bit tag for a world cell. */
    public static int tagForCell(int cellX, int cellZ) {
        return (((cellX >> GRID_SHIFT) & TAG_AXIS_MASK) << 16)
                | (((cellZ >> GRID_SHIFT) & TAG_AXIS_MASK) << 24);
    }

    /** Packs a precipitation value with the bounded tag of the cell it describes. */
    public static int encodeCell(int cellX, int cellZ, int value) {
        return (value & VALUE_MASK) | VALID_MASK | tagForCell(cellX, cellZ);
    }

    /**
     * Whether an encoded word's bounded tag matches this cell.
     *
     * <p>The match is reliable only between discontinuous-recenter resets; see this class's reset
     * invariant.
     */
    public static boolean describesCell(int encoded, int cellX, int cellZ) {
        return (encoded & VALID_MASK) != 0 && (encoded & TAG_MASK) == tagForCell(cellX, cellZ);
    }

    /** Returns the lowest coarse-cell coordinate in the player-centred window. */
    public static int windowBaseCell(int anchorBlock) {
        return Math.floorDiv(anchorBlock, ANCHOR_SNAP_BLOCKS)
                * (ANCHOR_SNAP_BLOCKS / CELL_STRIDE) - GRID / 2;
    }
}
